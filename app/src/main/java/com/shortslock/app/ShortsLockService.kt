package com.shortslock.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
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
import com.shortslock.app.LockPolicy.Action
import kotlin.math.hypot

/** Owns the bubble, Shorts detection, the touch shields, the return-to-Shorts guard, the PIN exit and the timer. */
class ShortsLockService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PrefsStore(this) }
    private val pins by lazy { PinManager(store, KeystoreSigner()) }

    private var bubble: View? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var pinView: View? = null
    private var pinLp: WindowManager.LayoutParams? = null
    private var strip: View? = null
    private var navBlock: View? = null

    private var detector: ShortsDetector? = null
    private var lastOn: Boolean? = null
    private var evalScheduled = false
    private var shieldBoxes: List<Box> = emptyList()
    private val shieldViews = ArrayList<View>()
    private var tint = false // test aid: paints the shields red so you can see where they are
    /** Stored in prefs so the app screen and the bubble menu share one setting. */
    private var durationMin: Int
        get() = LockPolicy.validDuration(store.get(KEY_DURATION)?.toIntOrNull())
        set(v) { store.put(KEY_DURATION to v.toString()) }
    private var lockUntil = 0L
    private var lastReturn = 0L

    val isLocked get() = detector != null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    /** Re-checks once a second while locked, so the guard and the timer work even when no events arrive. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isLocked) return
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        if (store.get(KEY_ACTIVE) == "1") showBubble()
        restoreAfterCrash()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLocked || event == null || event.packageName == packageName) return
        // Coalesce bursts of events (video progress fires constantly) into one check per EVAL_MS.
        if (evalScheduled) return
        evalScheduled = true
        handler.postDelayed({ evalScheduled = false; evaluate() }, EVAL_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // The saved lock state is kept on purpose: if the service comes back before expiry (a crash), the lock resumes.
        handler.removeCallbacksAndMessages(null)
        detector = null
        hideStrip()
        hidePin()
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    // ---- lock lifecycle ----

    private fun startLock() {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != YOUTUBE) { toast("Open YouTube Shorts first"); return }
        val s = scan(root, withBoxes = true)
        if (s.ids.size < MIN_IDS) { toast("Could not read the YouTube screen (${s.ids.size} elements)"); return }
        val fp = Fingerprint(s.ids)
        shieldBoxes = ShieldPlanner.plan(s.clickable, screenW, screenH, dp(SHIELD_PAD_DP))
        store.put(
            KEY_SNAPSHOT to fp.serialize(),
            KEY_SHIELDS to shieldBoxes.joinToString("\n") { "${it.l},${it.t},${it.r},${it.b}" },
        )
        beginLock(fp, System.currentTimeMillis() + durationMin * 60_000L)
        toast("Locked for ${LockPolicy.label(durationMin)} (${shieldBoxes.size} shields)")
    }

    private fun beginLock(fp: Fingerprint, until: Long) {
        detector = ShortsDetector(fp)
        lastOn = null
        lockUntil = until
        store.put(KEY_LOCK_UNTIL to until.toString(), KEY_BOOT to bootCount().toString(), KEY_STATE to "locked")
        showStrip()
        paint(true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun endLock(message: String) {
        detector = null
        lockUntil = 0
        handler.removeCallbacks(tick)
        hideShields()
        hideStrip()
        hidePin()
        store.put(KEY_LOCK_UNTIL to null, KEY_BOOT to null, KEY_STATE to "unlocked")
        paint(null)
        toast(message)
    }

    private fun restoreAfterCrash() {
        val until = store.get(KEY_LOCK_UNTIL)?.toLongOrNull() ?: return
        val boot = store.get(KEY_BOOT)?.toIntOrNull() ?: -1
        val snap = store.get(KEY_SNAPSHOT)
        if (snap == null || !LockPolicy.shouldRestore(until, System.currentTimeMillis(), boot, bootCount())) {
            store.put(KEY_LOCK_UNTIL to null, KEY_BOOT to null)
            return
        }
        shieldBoxes = store.get(KEY_SHIELDS).orEmpty().lines().mapNotNull { line ->
            line.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }?.let { Box(it[0], it[1], it[2], it[3]) }
        }
        store.put(KEY_ACTIVE to "1")
        showBubble()
        beginLock(Fingerprint.parse(snap), until)
    }

    private fun bootCount() = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)

    // ---- detection and guard ----

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

    private fun evaluate() {
        val d = detector ?: return
        if (System.currentTimeMillis() >= lockUntil) { endLock("Time is up. Lock ended."); return }
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val yt = pkg == YOUTUBE
        val on = d.update(yt, if (root != null && yt) scan(root, withBoxes = false).ids else emptySet())

        // Shields exist only while Shorts is on screen, so they never sit over another app for long.
        if (on && shieldViews.isEmpty()) showShields() else if (!on && shieldViews.isNotEmpty()) hideShields()
        if (on != lastOn) {
            lastOn = on
            paint(on)
            store.put(KEY_STATE to (if (on) "locked, on Shorts" else "locked, left Shorts") + " (score %.2f)".format(d.lastScore))
        }

        when (LockPolicy.onForeground(pkg, imePackage(), keyguardLocked())) {
            Action.RETURN -> returnToShorts(RETURN_COOLDOWN_MS)
            Action.NONE -> if (yt && !on) returnToShorts(RETURN_IN_YOUTUBE_COOLDOWN_MS)
        }
    }

    private fun returnToShorts(cooldown: Long) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastReturn < cooldown) return
        lastReturn = t
        val shorts = Intent(Intent.ACTION_VIEW, Uri.parse(SHORTS_URL)).setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(shorts)
        } catch (e: ActivityNotFoundException) {
            packageManager.getLaunchIntentForPackage(YOUTUBE)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun imePackage() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun keyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun paint(on: Boolean?) {
        (bubble as? TextView)?.text = if (on == null) "▶" else "🔒"
        bubbleBg?.setColor(
            when (on) {
                null -> 0x99202020.toInt()
                true -> 0x9900A050.toInt()
                false -> 0x99E08000.toInt()
            }
        )
    }

    // ---- overlays: shared ----

    private fun overlayParams(w: Int, h: Int, fullScreenCoords: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (fullScreenCoords) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (fullScreenCoords) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** The system may already have removed our overlays (e.g. while the service is being stopped). */
    private fun removeSafely(v: View) {
        try { wm.removeView(v) } catch (e: IllegalArgumentException) { }
    }

    private fun blocker(color: Int) = View(this).apply {
        setBackgroundColor(color)
        setOnTouchListener { _, _ -> true } // swallow every touch
    }

    /** Overlays added later sit on top, so re-add the bubble and the PIN screen after new shields. */
    private fun raiseTop() {
        listOf(bubble to bubbleLp, pinView to pinLp).forEach { (v, lp) ->
            if (v != null && lp != null && v.isAttachedToWindow) { wm.removeViewImmediate(v); wm.addView(v, lp) }
        }
    }

    // ---- shields and status-bar strip ----

    private fun showShields() {
        hideShields()
        hideMenu()
        for (b in shieldBoxes) {
            val v = blocker(if (tint) 0x44FF0000 else Color.TRANSPARENT)
            wm.addView(v, overlayParams(b.w, b.h, true).apply { x = b.l; y = b.t })
            shieldViews += v
        }
        if (shieldViews.isNotEmpty()) raiseTop() // with zero shields this runs every tick; re-adding would make the PIN screen drop taps
    }

    private fun hideShields() {
        shieldViews.forEach { removeSafely(it) }
        shieldViews.clear()
    }

    /** Blocks pulling down the notification shade while locked. */
    private fun showStrip() {
        if (strip != null) return
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = maxOf(if (id > 0) resources.getDimensionPixelSize(id) else 0, dp(24))
        val v = blocker(if (tint) 0x440000FF else Color.TRANSPARENT)
        wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, h, true))
        strip = v
        showNavBlock()
        raiseTop()
    }

    /**
     * Covers the 3-button navigation bar (Back, Home, Recents) so taps on it do nothing while locked.
     * An accessibility overlay sits above the navigation bar. It cannot stop swipe gestures (gesture navigation),
     * so for full blocking the phone must use 3-button navigation.
     */
    private fun showNavBlock() {
        if (navBlock != null) return
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = maxOf(if (id > 0) resources.getDimensionPixelSize(id) else 0, dp(48))
        val v = blocker(if (tint) 0x4400FF00 else Color.TRANSPARENT)
        wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, navH, true).apply { y = realHeight() - navH })
        navBlock = v
    }

    private fun realHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels

    private fun hideStrip() {
        strip?.let { removeSafely(it) }
        strip = null
        navBlock?.let { removeSafely(it) }
        navBlock = null
    }

    // ---- PIN screen ----

    private fun showPin() {
        if (pinView != null) return
        hideMenu()
        val entered = StringBuilder()
        val dots = TextView(this).apply { textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val msg = TextView(this).apply { setTextColor(0xFFFF8080.toInt()); gravity = Gravity.CENTER; minHeight = dp(40) }
        fun refresh() { dots.text = "●".repeat(entered.length) + "○".repeat(4 - entered.length) }
        refresh()

        fun press(k: String) {
            when (k) {
                "Cancel" -> { hidePin(); return }
                "⌫" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                else -> if (entered.length < 4) entered.append(k)
            }
            refresh()
            if (entered.length < 4) return
            val r = pins.verify(entered.toString())
            entered.clear()
            refresh()
            when (r) {
                PinManager.Result.Ok -> endLock("Unlocked")
                is PinManager.Result.Wrong -> msg.text = "Wrong PIN. ${r.attemptsLeft} tries before a wait"
                is PinManager.Result.LockedOut -> msg.text = "Too many tries. Wait ${(r.millis + 999) / 1000} s"
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(0xF0101010.toInt())
            isClickable = true // swallow touches between the keys
            addView(TextView(context).apply { text = "Parent PIN"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
            addView(dots)
            addView(msg)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Cancel", "0", "⌫")).forEach { row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { k ->
                        addView(Button(context).apply {
                            text = k
                            textSize = if (k.length == 1) 24f else 16f
                            setOnClickListener { press(k) }
                        }, LinearLayout.LayoutParams(0, dp(72), 1f))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true)
        wm.addView(panel, lp)
        pinView = panel
        pinLp = lp
    }

    private fun hidePin() {
        pinView?.let { removeSafely(it) }
        pinView = null
        pinLp = null
    }

    // ---- bubble and menu ----

    fun showBubble() {
        if (bubble != null) return
        val size = dp(BUBBLE_DP)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99202020.toInt()) }
        val view = TextView(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
        }
        val lp = overlayParams(size, size, false).apply { x = screenW - size; y = screenH / 3 }
        view.setOnTouchListener(dragOrTap(lp, size))
        wm.addView(view, lp)
        bubble = view
        bubbleBg = bg
        bubbleLp = lp
        paint(if (isLocked) lastOn ?: true else null)
    }

    fun hideBubble() {
        hideMenu()
        hideShields()
        bubble?.let { removeSafely(it) }
        bubble = null
        bubbleBg = null
        bubbleLp = null
    }

    private fun dragOrTap(lp: WindowManager.LayoutParams, size: Int) = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var longPressed = false
        val slop = ViewConfiguration.get(this@ShortsLockService).scaledTouchSlop
        val longPress = Runnable { longPressed = true; if (!isLocked) showMenu() }
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; longPressed = false
                    hideMenu()
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLocked) return true // no dragging while locked
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > slop) { moved = true; handler.removeCallbacks(longPress); hideMenu() }
                    if (moved) {
                        lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (longPressed) return true // menu already open
                    if (moved) {
                        lp.x = BubbleMath.snapX(lp.x, size, screenW)
                        wm.updateViewLayout(v, lp)
                    } else {
                        v.performClick()
                        if (isLocked) showPin() else startLock() // locked: PIN only. Unlocked: one tap starts the lock
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun showMenu() {
        val b = bubbleLp ?: return
        val w = dp(MENU_W_DP)
        val h = dp(MENU_H_DP)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xEE202020.toInt()) }
            addView(TextView(context).apply { text = "Tap the bubble on Shorts to lock.\nLong-press for this menu."; setTextColor(Color.WHITE) })
            addView(Button(context).apply {
                text = "Time: ${LockPolicy.label(durationMin)}"
                setOnClickListener {
                    durationMin = LockPolicy.nextDuration(durationMin)
                    text = "Time: ${LockPolicy.label(durationMin)}"
                }
            })
            addView(Button(context).apply {
                text = "START"
                setOnClickListener { hideMenu(); startLock() }
            })
            addView(Button(context).apply {
                text = if (tint) "Shield tint: on (test)" else "Shield tint: off (test)"
                setOnClickListener { tint = !tint; text = if (tint) "Shield tint: on (test)" else "Shield tint: off (test)" }
            })
            addView(Button(context).apply {
                text = "Hide bubble"
                setOnClickListener { store.put(KEY_ACTIVE to "0"); hideBubble() }
            })
        }
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT, false).apply {
            x = BubbleMath.clamp(b.x + dp(BUBBLE_DP) / 2 - w / 2, 0, screenW - w)
            y = BubbleMath.clamp(b.y + dp(BUBBLE_DP), 0, screenH - h)
        }
        wm.addView(panel, lp)
        menu = panel
    }

    private fun hideMenu() {
        menu?.let { removeSafely(it) }
        menu = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_STATE = "dbg_state"
        const val KEY_SHIELDS = "dbg_shields"
        private const val KEY_LOCK_UNTIL = "lock_until"
        const val KEY_DURATION = "duration"
        private const val KEY_BOOT = "lock_boot"
        const val YOUTUBE = LockPolicy.YOUTUBE
        private const val YT_ID_PREFIX = "$YOUTUBE:id/"
        private const val SHORTS_URL = "https://www.youtube.com/shorts"
        private const val SHIELD_PAD_DP = 4
        private const val BUBBLE_DP = 52
        private const val MENU_W_DP = 200
        private const val MENU_H_DP = 300
        private const val EVAL_MS = 300L
        private const val TICK_MS = 1000L
        private const val RETURN_COOLDOWN_MS = 1000L
        private const val RETURN_IN_YOUTUBE_COOLDOWN_MS = 3000L
        private const val MAX_NODES = 3000
        private const val MIN_IDS = 5

        /** Set while the service is connected; the app uses it to show status and to show the bubble. */
        @Volatile var instance: ShortsLockService? = null
    }
}
