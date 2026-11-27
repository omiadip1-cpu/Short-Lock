package com.shortslock.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var pm: PinManager
    private lateinit var root: LinearLayout
    private var pendingCode: String? = null // recovery code awaiting "I saved it"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pm = PinManager(PrefsStore(this), KeystoreSigner())
        pendingCode = savedInstanceState?.getString("code")
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("code", pendingCode)
    }

    private fun render() {
        root.removeAllViews()
        val code = pendingCode
        when {
            code != null -> recoveryScreen(code)
            !pm.isSet() -> setupScreen()
            else -> checkScreen()
        }
    }

    private fun setupScreen() {
        text("Create parent PIN", 22f)
        val a = pinField("4-digit PIN")
        val b = pinField("Repeat PIN")
        val msg = text("")
        button("Set PIN") {
            val pin = a.text.toString()
            when {
                pin.length != 4 -> msg.text = "PIN must be 4 digits"
                pin != b.text.toString() -> msg.text = "PINs do not match"
                else -> { pendingCode = pm.setPin(pin); render() }
            }
        }
    }

    private fun recoveryScreen(code: String) {
        text("Recovery code", 22f)
        text(code, 28f)
        text("Write this down and keep it safe. It is shown only once and resets your PIN if you forget it.")
        button("I saved it") { pendingCode = null; render() }
    }

    private fun checkScreen() {
        text("PIN is set", 22f)
        val f = pinField("Enter PIN")
        val msg = text("")
        button("Check PIN") {
            msg.text = when (val r = pm.verify(f.text.toString())) {
                PinManager.Result.Ok -> "Correct"
                is PinManager.Result.Wrong -> "Wrong PIN. ${r.attemptsLeft} tries before lockout"
                is PinManager.Result.LockedOut -> "Locked out. Try again in ${(r.millis + 999) / 1000} s"
            }
            f.text.clear()
        }
        button("Forgot PIN? Use recovery code") { resetScreen() }
        activationSection()
    }

    private fun activationSection() {
        val on = ShortsLockService.instance != null
        text("Accessibility service: ${if (on) "ON" else "OFF"}", 18f)
        if (!on) text(
            "Tap Activate, then switch on Shorts Lock in Accessibility. If the switch is greyed out: " +
                "Settings > Apps > Shorts Lock > menu (three dots) > Allow restricted settings, then try again."
        )
        val store = PrefsStore(this)
        button("Activate (show bubble)") {
            store.put(ShortsLockService.KEY_ACTIVE to "1")
            val s = ShortsLockService.instance
            if (s != null) s.showBubble() else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Deactivate (hide bubble)") {
            store.put(ShortsLockService.KEY_ACTIVE to "0")
            ShortsLockService.instance?.hideBubble()
        }
        debugSection(store)
    }

    /** Phase 4 diagnostics: what the last START recorded and the last detection change. */
    private fun debugSection(store: PrefsStore) {
        val ids = store.get(ShortsLockService.KEY_SNAPSHOT)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        text("Detection debug", 18f)
        text("Last state: ${store.get(ShortsLockService.KEY_STATE) ?: "none"}")
        text("Recorded elements: ${ids.size}")
        if (ids.isNotEmpty()) text(ids.take(40).joinToString("\n") { it.substringAfter(":id/") }, 11f)
        val shields = store.get(ShortsLockService.KEY_SHIELDS)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        text("Shields (left,top,right,bottom px): ${shields.size}")
        if (shields.isNotEmpty()) text(shields.joinToString("\n"), 11f)
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun resetScreen() {
        root.removeAllViews()
        text("Reset PIN", 22f)
        val c = EditText(this).apply {
            hint = "Recovery code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(c)
        val p = pinField("New 4-digit PIN")
        val msg = text("")
        button("Reset PIN") {
            val pin = p.text.toString()
            when {
                pin.length != 4 -> msg.text = "PIN must be 4 digits"
                pm.resetWithRecovery(c.text.toString(), pin) -> render()
                else -> msg.text = "Wrong recovery code"
            }
        }
        button("Cancel") { render() }
    }

    private fun text(s: String, size: Float = 16f) =
        TextView(this).apply { text = s; textSize = size; setPadding(0, 16, 0, 16); root.addView(this) }

    private fun pinField(h: String) = EditText(this).apply {
        hint = h
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        root.addView(this)
    }

    private fun button(label: String, onClick: (View) -> Unit) =
        Button(this).apply { text = label; setOnClickListener(onClick); root.addView(this) }
}
