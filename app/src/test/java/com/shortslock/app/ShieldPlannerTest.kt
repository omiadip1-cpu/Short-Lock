package com.shortslock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldPlannerTest {
    private val w = 1000
    private val h = 2000
    private fun plan(vararg b: Box, pad: Int = 0, max: Int = 40) = ShieldPlanner.plan(b.toList(), w, h, pad, max)

    @Test fun keepsEdgeButtonsAndInflates() {
        val like = Box(880, 900, 980, 1000)
        assertEquals(listOf(like.inflate(5)), plan(like, pad = 5))
    }

    @Test fun dropsHugeNodesSuchAsThePlayer() {
        assertTrue(plan(Box(0, 0, 1000, 1500)).isEmpty())
    }

    @Test fun dropsNodesInTheFreeMiddle() {
        assertTrue(plan(Box(400, 700, 600, 900)).isEmpty())
    }

    @Test fun dropsTinyNodes() {
        assertTrue(plan(Box(900, 900, 905, 905)).isEmpty())
    }

    @Test fun removesBoxesInsideAnotherBox() {
        val outer = Box(850, 800, 1000, 1400)
        val inner = Box(880, 900, 980, 1000)
        assertEquals(listOf(outer), plan(outer, inner, inner))
    }

    @Test fun capsCountKeepingLargest() {
        val boxes = (0 until 10).map { Box(900, it * 150, 900 + 20 + it, it * 150 + 40) }.toTypedArray()
        val out = plan(*boxes, max = 3)
        assertEquals(3, out.size)
        assertEquals(boxes.last(), out.first())
    }
}
