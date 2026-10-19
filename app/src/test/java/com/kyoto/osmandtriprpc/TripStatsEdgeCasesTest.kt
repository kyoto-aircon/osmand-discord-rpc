package com.kyoto.osmandtriprpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases for the pure trip maths: non-finite input, rounding boundaries and the
 * point-speed/position-delta fallback used by the service.
 */
class TripStatsEdgeCasesTest {

    @Test
    fun formatStatusClampsNonFiniteAndNegativeValues() {
        assertEquals("0KM/H - 0KM - 00:00:00", TripStats.formatStatus(Double.NaN, 0.0, 0L))
        assertEquals(
            "0KM/H - 0KM - 00:00:00",
            TripStats.formatStatus(Double.POSITIVE_INFINITY, Double.NaN, 0L),
        )
        assertEquals(
            "0KM/H - 0KM - 00:00:00",
            TripStats.formatStatus(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0L),
        )
        assertEquals("0KM/H - 0KM - 00:00:00", TripStats.formatStatus(-12.0, -500.0, -1L))
    }

    @Test
    fun formatStatusRoundsHalfUpAtBoundaries() {
        assertEquals("1KM/H - 1KM - 00:00:00", TripStats.formatStatus(0.5, 500.0, 0L))
        assertEquals("0KM/H - 0KM - 00:00:00", TripStats.formatStatus(0.499, 499.0, 0L))
        assertEquals("2KM/H - 2KM - 00:00:00", TripStats.formatStatus(1.5, 1_500.0, 0L))
        assertEquals("50KM/H - 50KM - 00:00:00", TripStats.formatStatus(49.5, 49_501.0, 0L))
    }

    @Test
    fun durationTruncatesSubSecondAndRollsOver() {
        assertEquals("00:00:59", TripStats.formatDuration(59_999L))
        assertEquals("00:59:59", TripStats.formatDuration(3_599_999L))
        assertEquals("01:00:00", TripStats.formatDuration(3_600_000L))
        assertEquals("23:59:59", TripStats.formatDuration(86_399_999L))
        assertEquals("24:00:00", TripStats.formatDuration(86_400_000L))
        assertEquals("00:00:00", TripStats.formatDuration(Long.MIN_VALUE))
    }

    @Test
    fun pointSpeedRejectsAllNonFiniteVariantsAndAcceptsZero() {
        assertNull(TripStats.speedKmhFromPointSpeed(Float.POSITIVE_INFINITY))
        assertNull(TripStats.speedKmhFromPointSpeed(Float.NEGATIVE_INFINITY))
        assertEquals(0.0, TripStats.speedKmhFromPointSpeed(0f)!!, 0.0)
        assertEquals(0.0, TripStats.speedKmhFromPointSpeed(-0.0f)!!, 0.0)
        assertEquals(1.8, TripStats.speedKmhFromPointSpeed(0.5f)!!, 1e-9)
    }

    @Test
    fun pickSpeedUsesSingleTrustedPointSpeed() {
        val points = listOf(PointSample(1.0, 2.0, 5_000L, 4f))
        assertEquals(14.4, TripStats.pickSpeedKmh(points), 1e-9)
    }

    @Test
    fun pickSpeedWithSingleZeroSpeedPointIsZero() {
        assertEquals(
            0.0,
            TripStats.pickSpeedKmh(listOf(PointSample(0.0, 0.0, 0L, 0f))),
            0.0,
        )
    }

    @Test
    fun pickSpeedFallsBackWhenRecordedSpeedIsBelowTrustThreshold() {
        // 0.1 m/s = 0.36 km/h, below the 0.5 km/h trust threshold.
        val tenMeters = 10.0 / 111_195.0
        val points = listOf(
            PointSample(0.0, 0.0, 10_000L, 0.1f),
            PointSample(tenMeters, 0.0, 11_000L, 0.1f),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(points), 0.2)
    }

    @Test
    fun pickSpeedAcceptsRecordedSpeedAtTrustThreshold() {
        // Exactly 0.5 km/h expressed in m/s and narrowed to Float.
        val points = listOf(PointSample(0.0, 0.0, 0L, (0.5 / 3.6).toFloat()))
        val speed = TripStats.pickSpeedKmh(points)
        assertTrue("expected >= 0.5 km/h, got $speed", speed >= 0.5)
        assertEquals(0.5, speed, 1e-6)
    }

    @Test
    fun pickSpeedFallsBackWhenRecordedSpeedIsUnusable() {
        val tenMeters = 10.0 / 111_195.0
        val nanSpeed = listOf(
            PointSample(0.0, 0.0, 0L, 0f),
            PointSample(tenMeters, 0.0, 1_000L, Float.NaN),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(nanSpeed), 0.2)

        val negativeSpeed = listOf(
            PointSample(0.0, 0.0, 0L, 0f),
            PointSample(tenMeters, 0.0, 1_000L, -2f),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(negativeSpeed), 0.2)
    }

    @Test
    fun pickSpeedFallbackOnlyUsesTheLastTwoFixes() {
        val tenMeters = 10.0 / 111_195.0
        val points = listOf(
            // A large jump into the second point would inflate a first-to-last calculation.
            PointSample(10.0, 10.0, 0L, 0f),
            PointSample(0.0, 0.0, 1_000L, 0f),
            PointSample(tenMeters, 0.0, 2_000L, 0f),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(points), 0.2)
    }

    @Test
    fun pickSpeedFallbackRejectsNonPositiveTimeDelta() {
        val sameTimestamp = listOf(
            PointSample(0.0, 0.0, 1_000L, 0f),
            PointSample(0.001, 0.0, 1_000L, 0f),
        )
        assertEquals(0.0, TripStats.pickSpeedKmh(sameTimestamp), 0.0)

        val clockWentBackwards = listOf(
            PointSample(0.0, 0.0, 2_000L, 0f),
            PointSample(0.001, 0.0, 1_000L, 0f),
        )
        assertEquals(0.0, TripStats.pickSpeedKmh(clockWentBackwards), 0.0)
    }
}
