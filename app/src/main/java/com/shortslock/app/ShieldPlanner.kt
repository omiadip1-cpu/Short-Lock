package com.shortslock.app

/** Plain rectangle (screen pixels) so the planning logic can be unit-tested without Android classes. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val area get() = w.toLong() * h
    fun inflate(p: Int) = Box(l - p, t - p, r + p, b + p)
    fun contains(o: Box) = l <= o.l && t <= o.t && r >= o.r && b >= o.b
    fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
}

/**
 * Turns the clickable elements seen on the Shorts screen into the touch-blocking panels.
 * The middle of the screen is kept free so swiping and tap-to-pause still work.
 */
object ShieldPlanner {
    private const val MIN_SIDE = 12

    fun plan(boxes: List<Box>, screenW: Int, screenH: Int, pad: Int, maxShields: Int = 40): List<Box> {
        val keepFree = Box(screenW / 4, screenH * 3 / 10, screenW * 3 / 4, screenH * 6 / 10)
        val screenArea = screenW.toLong() * screenH
        val candidates = boxes
            .filter { it.w >= MIN_SIDE && it.h >= MIN_SIDE && it.area * 4 <= screenArea && !it.intersects(keepFree) }
            .map { it.inflate(pad) }
            .distinct()
        return candidates
            .filter { c -> candidates.none { o -> o != c && o.contains(c) } }
            .sortedByDescending { it.area }
            .take(maxShields)
    }
}
