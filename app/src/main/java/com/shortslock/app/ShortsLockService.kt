package com.shortslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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

    private fun collectIds(root: AccessibilityNodeInfo): Set<String> {
        val ids = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_NODES) {
            val n = stack.removeLast()
            if (!n.isVisibleToUser) continue // skips pre-loaded neighbouring Shorts pages
            n.viewIdResourceName?.takeIf { it.startsWith(YT_ID_PREFIX) }?.let { ids += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return ids
    }

    private fun startWatching() {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != YOUTUBE) { toast("Open YouTube Shorts first"); return }
        val ids = collectIds(root)
        if (ids.size < MIN_IDS) { toast("Could not read the YouTube screen (${ids.size} elements)"); return }
        val fp = Fingerprint(ids)
        PrefsStore(this).put(KEY_SNAPSHOT to fp.serialize(), KEY_STATE to "ON (at START)")
        detector = ShortsDetector(fp)
        paint(true)
        toast("Shorts recorded: ${ids.size} elements")
    }

    private fun stopWatching() {
        detector = null
        PrefsStore(this).put(KEY_STATE to "stopped")
        paint(null)
    }

    private fun evaluate() {
        val d = detector ?: return
        val root = rootInActiveWindow
        val yt = root?.packageName?.toString() == YOUTUBE
        val before = d.onShorts
        val on = d.update(yt, if (root != null && yt) collectIds(root) else emptySet())
        if (on != before) {
            paint(on)
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
            addView(TextView(context).apply { text = status; setTextColor(Color.WHITE) })
            addView(Button(context).apply {
                text = "START"
                setOnClickListener { hideMenu(); startWatching() }
            })
            addView(Button(context).apply {
                text = "Stop detection (test)"
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
        const val YOUTUBE = "com.google.android.youtube"
        private const val YT_ID_PREFIX = "$YOUTUBE:id/"
        private const val BUBBLE_DP = 52
        private const val MENU_W_DP = 190
        private const val MENU_H_DP = 230
        private const val EVAL_MS = 300L
        private const val MAX_NODES = 3000
        private const val MIN_IDS = 5

        /** Set while the service is connected; the app uses it to show status and to show the bubble. */
        @Volatile var instance: ShortsLockService? = null
    }
}
