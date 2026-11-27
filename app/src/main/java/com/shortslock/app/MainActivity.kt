package com.shortslock.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
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
                else -> try {
                    pendingCode = pm.setPin(pin)
                    render()
                } catch (e: IllegalStateException) {
                    msg.text = "Could not save the PIN. Please try again."
                }
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

    /** Opens Shorts Lock's own on/off page (Android 11+), falling back to the general Accessibility list. */
    private fun openServiceSwitch() {
        val component = ComponentName(this, ShortsLockService::class.java).flattenToString()
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: SecurityException) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /** Gesture navigation can't be blocked by an overlay; 3-button navigation can. */
    private fun usesGestureNavigation(): Boolean =
        Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2 ||
            Settings.Global.getInt(contentResolver, "force_fsg_nav_bar", 0) == 1 // Xiaomi

    private fun activationSection() {
        val on = ShortsLockService.instance != null
        text("Accessibility service: ${if (on) "ON" else "OFF"}", 18f)
        if (!on) text(
            "Tap Activate. On the next screen turn on Shorts Lock and tap Allow.\n" +
                "Not there? Look under 'Downloaded apps' > Shorts Lock.\n" +
                "Switch greyed out? Tap 'Allow restricted settings' below, then tap Activate again."
        )
        if (usesGestureNavigation()) text(
            "Your phone uses swipe gestures. Android cannot block swipes, so Home/Back will only be undone, not blocked. " +
                "For full blocking switch to buttons: Settings > Display (or Additional settings) > System navigation > Buttons."
        )
        val store = PrefsStore(this)
        button("Activate (show bubble)") {
            store.put(ShortsLockService.KEY_ACTIVE to "1")
            val s = ShortsLockService.instance
            if (s != null) s.showBubble() else openServiceSwitch()
        }
        button("Deactivate (hide bubble)") {
            val s = ShortsLockService.instance
            if (s?.isLocked == true) return@button // unlock with the bubble PIN first
            store.put(ShortsLockService.KEY_ACTIVE to "0")
            s?.hideBubble()
        }
        setupSection()
        debugSection(store)
    }

    /** One-time phone setup so Xiaomi/Vivo do not kill the service or block it from reopening YouTube. */
    private fun setupSection() {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        text("Phone setup (do once)", 18f)
        text(
            when {
                listOf("xiaomi", "redmi", "poco").any { it in brand } ->
                    "Xiaomi / Redmi / POCO:\n" +
                        "1. App info > Autostart: ON\n" +
                        "2. App info > Battery saver: No restrictions\n" +
                        "3. App info > Other permissions: allow 'Display pop-up windows while running in the background'\n" +
                        "4. Recent apps: long-press Shorts Lock > lock icon\n" +
                        "5. If Accessibility is greyed out: App info > menu > Allow restricted settings"
                listOf("vivo", "iqoo").any { it in brand } ->
                    "Vivo / iQOO:\n" +
                        "1. Settings > Battery > Background power consumption > Shorts Lock: Allow\n" +
                        "2. i Manager > App manager > Autostart: Shorts Lock ON\n" +
                        "3. App info > Permissions: allow 'Background pop-ups' / 'Display pop-up windows'\n" +
                        "4. Recent apps: pull down on Shorts Lock to lock it\n" +
                        "5. If Accessibility is greyed out: App info > menu > Allow restricted settings"
                else ->
                    "1. Battery: set Shorts Lock to unrestricted\n" +
                        "2. If Accessibility is greyed out: App info > menu > Allow restricted settings"
            }
        )
        button("Allow restricted settings (opens App info, then tap the three dots)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Turn off battery optimisation") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
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
                else -> msg.text = "Wrong recovery code (or it could not be saved). Try again."
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
