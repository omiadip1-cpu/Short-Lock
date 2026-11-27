package com.shortslock.app

/** Decisions the lock makes, kept free of Android classes so they can be unit-tested. */
object LockPolicy {
    enum class Action { NONE, RETURN }

    const val YOUTUBE = "com.google.android.youtube"
    private const val SYSTEMUI = "com.android.systemui"

    /** Calls and alarms must stay usable during a lock. Names cover stock, Google, Xiaomi, Vivo and Samsung. */
    val CALLS_AND_ALARMS = setOf(
        "com.android.incallui", "com.android.server.telecom", "com.android.phone", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.incallui",
        "com.android.deskclock", "com.google.android.deskclock", "com.android.BBKClock",
        "com.sec.android.app.clockpackage",
    )

    /** Lock timer choices: 1 to 4 hours. */
    val DURATIONS_MIN = listOf(60, 120, 180, 240)
    const val DEFAULT_DURATION_MIN = 60

    /** What to do when [pkg] is the app in front while locked. Everything not allowed is sent back to Shorts. */
    fun onForeground(pkg: String?, imePkg: String?, keyguardLocked: Boolean): Action = when {
        pkg == null || keyguardLocked -> Action.NONE // mid-transition, or the phone's own lock screen
        pkg == YOUTUBE -> Action.NONE
        // Shade, volume panel, and the power menu (needed for reboot / Safe Mode recovery) all live here.
        // We must not close the power menu, so the shade is blocked by the status-bar strip instead.
        pkg == SYSTEMUI -> Action.NONE
        pkg in CALLS_AND_ALARMS || pkg == imePkg -> Action.NONE
        else -> Action.RETURN // home, recents, Settings, installer, any other app, even Shorts Lock itself
    }

    /** Saved value if it is one of the choices, otherwise the default (also covers old 15/30 min settings). */
    fun validDuration(saved: Int?) = if (saved in DURATIONS_MIN) saved!! else DEFAULT_DURATION_MIN

    fun nextDuration(current: Int) = DURATIONS_MIN[(DURATIONS_MIN.indexOf(current) + 1) % DURATIONS_MIN.size]

    fun label(min: Int) = if (min < 60) "$min min" else "${min / 60} h"

    /** Restore after a crash only: the lock must not have expired and the phone must not have rebooted. */
    fun shouldRestore(lockUntil: Long, now: Long, savedBoot: Int, currentBoot: Int) =
        lockUntil > now && savedBoot == currentBoot
}
