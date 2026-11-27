package com.shortslock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsDetectorTest {
    private val shorts = (1..10).map { "id$it" }.toSet()
    private fun detector() = ShortsDetector(Fingerprint(shorts))

    @Test fun scoreIsShareOfSnapshotIdsPresent() {
        val fp = Fingerprint(shorts)
        assertEquals(1.0, fp.score(shorts + "extra"), 0.0)
        assertEquals(0.8, fp.score((1..8).map { "id$it" }.toSet()), 0.0)
        assertEquals(0.0, fp.score(emptySet()), 0.0)
        assertEquals(0.0, Fingerprint(emptySet()).score(shorts), 0.0)
    }

    @Test fun serializeRoundTrip() {
        assertEquals(shorts, Fingerprint.parse(Fingerprint(shorts).serialize()).ids)
    }

    @Test fun staysOnForTwoMissesAndFlipsOnThird() {
        val d = detector()
        assertTrue(d.update(true, shorts))
        assertTrue(d.update(true, setOf("home")))
        assertTrue(d.update(true, setOf("home")))
        assertFalse(d.update(true, setOf("home")))
    }

    @Test fun hitResetsMissesAndRecovers() {
        val d = detector()
        repeat(3) { d.update(true, emptySet()) }
        assertFalse(d.onShorts)
        assertTrue(d.update(true, shorts))
        d.update(true, emptySet()); d.update(true, emptySet())
        assertTrue(d.update(true, shorts))
        assertTrue(d.update(true, emptySet()))
    }

    @Test fun otherAppIsOffImmediately() {
        val d = detector()
        assertTrue(d.update(true, shorts))
        assertFalse(d.update(false, shorts))
        assertEquals(0.0, d.lastScore, 0.0)
    }

    @Test fun belowThresholdIsAMiss() {
        val d = detector()
        val seven = (1..7).map { "id$it" }.toSet()
        repeat(3) { d.update(true, seven) }
        assertFalse(d.onShorts)
    }
}
