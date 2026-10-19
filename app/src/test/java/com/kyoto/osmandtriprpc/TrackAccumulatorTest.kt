package com.kyoto.osmandtriprpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAccumulatorTest {

    @Test
    fun `first fix anchors without distance`() {
        val acc = TrackAccumulator()
        val update = acc.addFix(48.0, 11.0, 1_000, 5.0, null)
        assertEquals(0.0, update!!.distanceMeters, 0.001)
    }

    @Test
    fun `accumulates distance between fixes`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, null)
        // ~111 m north (0.001 degrees latitude) in 10 s = 11.1 m/s ≈ 40 km/h
        val update = acc.addFix(48.001, 11.0, 10_000, 5.0, null)!!
        assertEquals(111.0, update.distanceMeters, 2.0)
        assertEquals(40.0, update.speedKmh, 1.0)
    }

    @Test
    fun `rejects inaccurate fixes`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, null)
        assertNull(acc.addFix(48.01, 11.0, 1_000, 500.0, null))
    }

    @Test
    fun `ignores movement below the jitter threshold`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, null)
        val update = acc.addFix(48.000001, 11.0, 1_000, 5.0, null)!!
        assertEquals(0.0, update.distanceMeters, 0.0001)
    }

    @Test
    fun `uses reported speed when present`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, 10f)
        val update = acc.addFix(48.0001, 11.0, 1_000, 5.0, 10f)!!
        assertEquals(36.0, update.speedKmh, 0.01)
    }

    @Test
    fun `falls back to position delta speed without reported speed`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, null)
        val update = acc.addFix(48.001, 11.0, 10_000, 5.0, null)!!
        assertTrue(update.speedKmh in 35.0..45.0)
    }

    @Test
    fun `stale fixes are ignored`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 10_000, 5.0, null)
        assertNull(acc.addFix(48.001, 11.0, 9_000, 5.0, null))
    }

    @Test
    fun `reset starts a new trip`() {
        val acc = TrackAccumulator()
        acc.addFix(48.0, 11.0, 0, 5.0, null)
        acc.addFix(48.001, 11.0, 10_000, 5.0, null)
        acc.reset()
        assertEquals(0.0, acc.addFix(49.0, 12.0, 20_000, 5.0, null)!!.distanceMeters, 0.001)
    }
}
