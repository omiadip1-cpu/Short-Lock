package com.shortslock.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleMathTest {
    @Test fun snapsToNearestEdge() {
        assertEquals(0, BubbleMath.snapX(100, 100, 1000))
        assertEquals(900, BubbleMath.snapX(500, 100, 1000))
        assertEquals(900, BubbleMath.snapX(850, 100, 1000))
    }

    @Test fun clampNeverThrows() {
        assertEquals(5, BubbleMath.clamp(5, 0, 10))
        assertEquals(10, BubbleMath.clamp(50, 0, 10))
        assertEquals(0, BubbleMath.clamp(-3, 0, 10))
        assertEquals(0, BubbleMath.clamp(7, 0, -20))
    }
}
