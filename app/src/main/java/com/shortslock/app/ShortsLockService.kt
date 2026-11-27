package com.shortslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/** Owns the floating bubble. Later phases add Shorts detection, shields and the lock itself. */
class ShortsLockService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        if (PrefsStore(this).get(KEY_ACTIVE) == "1") showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // Phase 4
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    fun showBubble() {
        if (bubble != null) return
        val size = dp(BUBBLE_DP)
        val view = TextView(this).apply {
            text = "S"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99202020.toInt()) }
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
        bubbleLp = lp
    }

    fun hideBubble() {
        hideMenu()
        bubble?.let { wm.removeView(it) }
        bubble = null
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
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xEE202020.toInt()) }
            addView(Button(context).apply {
                text = "START"
                setOnClickListener { toast("START arrives in Phase 4"); hideMenu() }
            })
            addView(Button(context).apply {
                text = "Hide bubble"
                setOnClickListener {
                    PrefsStore(context).put(KEY_ACTIVE to "0")
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
        private const val BUBBLE_DP = 52
        private const val MENU_W_DP = 160
        private const val MENU_H_DP = 120

        /** Set while the service is connected; the app uses it to show status and to show the bubble. */
        @Volatile var instance: ShortsLockService? = null
    }
}
