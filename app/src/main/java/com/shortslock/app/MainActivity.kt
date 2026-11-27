package com.shortslock.app

import android.app.Activity
import android.os.Bundle
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
