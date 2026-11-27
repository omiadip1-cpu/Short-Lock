package com.shortslock.app

import com.shortslock.app.LockPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    private fun act(pkg: String?, ime: String? = "com.google.android.inputmethod.latin", keyguard: Boolean = false) =
        LockPolicy.onForeground(pkg, ime, keyguard)

    @Test fun youtubeCallsAlarmsKeyboardAreAllowed() {
        for (p in listOf(LockPolicy.YOUTUBE, "com.android.incallui", "com.android.BBKClock", "com.google.android.inputmethod.latin")) {
            assertEquals(p, Action.NONE, act(p))
        }
    }

    @Test fun everythingElseIsSentBack() {
        for (p in listOf(
            "com.miui.home", "com.bbk.launcher2", "com.android.settings", "com.miui.securitycenter",
            "com.google.android.packageinstaller", "com.android.chrome", "com.shortslock.app",
        )) assertEquals(p, Action.RETURN, act(p))
    }

    @Test fun shadeIsClosedWithBack() = assertEquals(Action.BACK, act("com.android.systemui"))

    @Test fun lockScreenAndTransitionsDoNothing() {
        assertEquals(Action.NONE, act(null))
        assertEquals(Action.NONE, act("com.android.systemui", keyguard = true))
        assertEquals(Action.NONE, act("com.miui.home", keyguard = true))
    }

    @Test fun durationsCycleAndLabel() {
        assertEquals(120, LockPolicy.nextDuration(60))
        assertEquals(15, LockPolicy.nextDuration(240))
        assertEquals(15, LockPolicy.nextDuration(999))
        assertEquals("30 min", LockPolicy.label(30))
        assertEquals("2 h", LockPolicy.label(120))
    }

    @Test fun restoreOnlyAfterCrashNotRebootOrExpiry() {
        assertTrue(LockPolicy.shouldRestore(2000, 1000, 7, 7))
        assertFalse(LockPolicy.shouldRestore(2000, 1000, 7, 8)) // rebooted
        assertFalse(LockPolicy.shouldRestore(1000, 1000, 7, 7)) // expired
    }
}
