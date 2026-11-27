package com.shortslock.app

object BubbleMath {
    /** Snaps the bubble to the nearest vertical screen edge. */
    fun snapX(x: Int, size: Int, screenW: Int) = if (x + size / 2 < screenW / 2) 0 else screenW - size

    /** Clamp that never throws when the range is inverted (tiny screens). */
    fun clamp(v: Int, min: Int, max: Int) = v.coerceIn(min, maxOf(min, max))
}
