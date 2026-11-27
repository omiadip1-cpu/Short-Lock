package com.shortslock.app

/** The set of YouTube view ids visible on the Shorts screen at the moment the parent pressed START. */
class Fingerprint(val ids: Set<String>) {
    /** Share of the snapshot's ids that are visible now (0.0 to 1.0). */
    fun score(present: Set<String>): Double =
        if (ids.isEmpty()) 0.0 else ids.count { it in present }.toDouble() / ids.size

    fun serialize() = ids.sorted().joinToString("\n")

    companion object {
        const val THRESHOLD = 0.8
        fun parse(s: String) = Fingerprint(s.lines().filter { it.isNotBlank() }.toSet())
    }
}

/**
 * Says whether the screen is still the recorded Shorts screen. Starts "on".
 * It flips to "left" only after [missLimit] misses in a row, because YouTube briefly looks different while loading.
 */
class ShortsDetector(private val fp: Fingerprint, private val missLimit: Int = 3) {
    var onShorts = true
        private set
    var lastScore = 1.0
        private set
    private var misses = 0

    fun update(isYoutube: Boolean, present: Set<String>): Boolean {
        lastScore = if (isYoutube) fp.score(present) else 0.0
        if (lastScore >= Fingerprint.THRESHOLD) {
            misses = 0
            onShorts = true
        } else if (++misses >= missLimit) {
            onShorts = false
        }
        return onShorts
    }
}
