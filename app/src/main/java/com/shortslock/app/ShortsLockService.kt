package com.shortslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/** Owns the floating bubble and Shorts detection. Later phases add shields and the lock itself. */
class ShortsLockService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var detector: ShortsDetector? = null
    private var evalScheduled = false
    private var shieldBoxes: List<Box> = emptyList()
    private val shieldViews = ArrayList<View>()
    private var tint = false // test aid: paints the shields red so you can see where they are

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        if (PrefsStore(this).get(KEY_ACTIVE) == "1") showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (detector == null || event == null || event.packageName == packageName) return
        // Coalesce bursts of events (video progress fires constantly) into one check per EVAL_MS.
        if (evalScheduled) return
        evalScheduled = true
        handler.postDelayed({ evalScheduled = false; evaluate() }, EVAL_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    // ---- detection ----

    private class Scan(val ids: Set<String>, val clickable: List<Box>)

    private fun scan(root: AccessibilityNodeInfo, withBoxes: Boolean): Scan {
        val ids = HashSet<String>()
        val boxes = ArrayList<Box>()
        val r = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_NODES) {
            val n = stack.removeLast()
            if (!n.isVisibleToUser) continue // skips pre-loaded neighbouring Shorts pages
            n.viewIdResourceName?.takeIf { it.startsWith(YT_ID_PREFIX) }?.let { ids += it }
            if (withBoxes && (n.isClickable || n.isLongClickable)) {
                n.getBoundsInScreen(r)
                boxes += Box(r.left, r.top, r.right, r.bottom)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return Scan(ids, boxes)
    }

    private fun startWatching() {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != YOUTUBE) { toast("Open YouTube Shorts first"); return }
        val s = scan(root, withBoxes = true)
        if (s.ids.size < MIN_IDS) { toast("Could not read the YouTube screen (${s.ids.size} elements)"); return }
        val fp = Fingerprint(s.ids)
        shieldBoxes = ShieldPlanner.plan(s.clickable, screenW, screenH, dp(SHIELD_PAD_DP))
        PrefsStore(this).put(
            KEY_SNAPSHOT to fp.serialize(),
            KEY_SHIELDS to shieldBoxes.joinToString("\n") { "${it.l},${it.t},${it.r},${it.b}" },
            KEY_STATE to "ON (at START, ${shieldBoxes.size} shields)",
        )
        detector = ShortsDetector(fp)
        paint(true)
        showShields()
        toast("Locked: ${s.ids.size} elements, ${shieldBoxes.size} shields")
    }

    private fun stopWatching() {
        detector = null
        hideShields()
        PrefsStore(this).put(KEY_STATE to "stopped")
        paint(null)
    }

    // ---- shields ----

    private fun showShields() {
        hideShields()
        hideMenu()
        for (b in shieldBoxes) {
            val v = View(this).apply {
                setBackgroundColor(if (tint) 0x44FF0000 else Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true } // swallow every touch on a blocked button
            }
            val lp = WindowManager.LayoutParams(
                b.w, b.h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = b.l
                y = b.t
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            wm.addView(v, lp)
            shieldViews += v
        }
        raiseBubble()
    }

    private fun hideShields() {
        shieldViews.forEach { wm.removeView(it) }
        shieldViews.clear()
    }

    /** Shields are added after the bubble, so re-add the bubble to keep it on top and tappable. */
    private fun raiseBubble() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        wm.removeViewImmediate(v)
        wm.addView(v, lp)
    }

    private fun evaluate() {
        val d = detector ?: return
        val root = rootInActiveWindow
        val yt = root?.packageName?.toString() == YOUTUBE
        val before = d.onShorts
        val on = d.update(yt, if (root != null && yt) scan(root, withBoxes = false).ids else emptySet())
        if (on != before) {
            paint(on)
            if (on) showShields() else hideShields() // never leave shields over another app
            PrefsStore(this).put(KEY_STATE to (if (on) "ON" else "LEFT") + " (score %.2f)".format(d.lastScore))
        }
    }

    private fun paint(on: Boolean?) {
        bubbleBg?.setColor(
            when (on) {
                null -> 0x99202020.toInt()
                true -> 0x9900A050.toInt()
                false -> 0x99E08000.toInt()
            }
        )
    }

    // ---- bubble ----

    fun showBubble() {
        if (bubble != null) return
        val size = dp(BUBBLE_DP)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99202020.toInt()) }
        val view = TextView(this).apply {
            text = "S"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = screenW - size; y = screenH / 3 }
        view.setOnTouchListener(dragOrTap(lp, size))
        wm.addView(view, lp)
        bubble = view
        bubbleBg = bg
        bubbleLp = lp
        paint(detector?.onShorts)
    }

    fun hideBubble() {
        hideMenu()
        hideShields()
        bubble?.let { wm.removeView(it) }
        bubble = null
        bubbleBg = null
        bubbleLp = null
    }

    private fun dragOrTap(lp: WindowManager.LayoutParams, size: Int) = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        val slop = ViewConfiguration.get(this@ShortsLockService).scaledTouchSlop
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > slop) { moved = true; hideMenu() }
                    if (moved) {
                        lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        lp.x = BubbleMath.snapX(lp.x, size, screenW)
                        wm.updateViewLayout(v, lp)
                    } else {
                        v.performClick()
                        if (menu == null) showMenu() else hideMenu()
                    }
                }
            }
            return true
        }
    }

    private fun showMenu() {
        val b = bubbleLp ?: return
        val w = dp(MENU_W_DP)
        val h = dp(MENU_H_DP)
        val d = detector
        val status = when {
            d == null -> "Not recording"
            d.onShorts -> "Shorts: ON (score %.2f)".format(d.lastScore)
            else -> "Shorts: LEFT (score %.2f)".format(d.lastScore)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xEE202020.toInt()) }
            addView(TextView(context).apply {
                text = "$status\nShields: ${shieldViews.size}"
                setTextColor(Color.WHITE)
            })
            addView(Button(context).apply {
                text = "START (lock)"
                setOnClickListener { hideMenu(); startWatching() }
            })
            addView(Button(context).apply {
                text = if (tint) "Hide shield tint (test)" else "Show shield tint (test)"
                setOnClickListener {
                    hideMenu()
                    tint = !tint
                    if (shieldViews.isNotEmpty()) showShields()
                }
            })
            addView(Button(context).apply {
                text = "Stop lock (test, no PIN yet)"
                setOnClickListener { hideMenu(); stopWatching() }
            })
            addView(Button(context).apply {
                text = "Hide bubble"
                setOnClickListener {
                    PrefsStore(context).put(KEY_ACTIVE to "0")
                    stopWatching()
                    hideBubble()
                }
            })
        }
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = BubbleMath.clamp(b.x + dp(BUBBLE_DP) / 2 - w / 2, 0, screenW - w)
            y = BubbleMath.clamp(b.y + dp(BUBBLE_DP), 0, screenH - h)
        }
        wm.addView(panel, lp)
        menu = panel
    }

    private fun hideMenu() {
        menu?.let { wm.removeView(it) }
        menu = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_STATE = "dbg_state"
        const val KEY_SHIELDS = "dbg_shields"
        private const val SHIELD_PAD_DP = 4
        const val YOUTUBE = "com.google.android.youtube"
        private const val YT_ID_PREFIX = "$YOUTUBE:id/"
        private const val BUBBLE_DP = 52
        private const val MENU_W_DP = 190
        private const val MENU_H_DP = 300
        private const val EVAL_MS = 300L
        private const val MAX_NODES = 3000
        private const val MIN_IDS = 5

        /** Set while the service is connected; the app uses it to show status and to show the bubble. */
        @Volatile var instance: ShortsLockService? = null
    }
}
