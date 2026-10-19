package com.kyoto.osmandtriprpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStatsTest {

    @Test
    fun formatsTheRequestedStatusLine() {
        assertEquals(
            "15KM/H - 50KM - 01:45:23",
            TripStats.formatStatus(speedKmh = 15.4, distanceMeters = 50_321.0, durationMs = 6_323_000L),
        )
    }

    @Test
    fun formatsZeroValues() {
        assertEquals("0KM/H - 0KM - 00:00:00", TripStats.formatStatus(0.0, 0.0, 0L))
    }

    @Test
    fun roundsSpeedAndDistance() {
        assertEquals("15KM/H - 1KM - 00:00:00", TripStats.formatStatus(14.6, 500.0, 0L))
        assertEquals("14KM/H - 0KM - 00:00:00", TripStats.formatStatus(14.4, 499.0, 0L))
    }

    @Test
    fun durationUsesHoursMinutesSeconds() {
        assertEquals("00:00:00", TripStats.formatDuration(0L))
        assertEquals("00:00:59", TripStats.formatDuration(59_999L))
        assertEquals("00:01:00", TripStats.formatDuration(60_000L))
        assertEquals("01:45:23", TripStats.formatDuration(6_323_000L))
        assertEquals("100:00:00", TripStats.formatDuration(360_000_000L))
    }

    @Test
    fun negativeDurationClampsToZero() {
        assertEquals("00:00:00", TripStats.formatDuration(-5_000L))
    }

    @Test
    fun haversineMatchesOneDegreeOfLatitude() {
        val meters = TripStats.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, meters, 200.0)
    }

    @Test
    fun haversineIsZeroForSamePoint() {
        assertEquals(0.0, TripStats.haversineMeters(51.5, -0.12, 51.5, -0.12), 0.0001)
    }

    @Test
    fun speedFromPointSpeedConvertsMetersPerSecond() {
        assertEquals(36.0, TripStats.speedKmhFromPointSpeed(10f)!!, 0.0001)
    }

    @Test
    fun unusablePointSpeedIsNull() {
        assertNull(TripStats.speedKmhFromPointSpeed(Float.NaN))
        assertNull(TripStats.speedKmhFromPointSpeed(-1f))
    }

    @Test
    fun speedFromPositionsUsesDistanceOverTime() {
        val tenMetersInDegrees = 10.0 / 111_195.0
        val speed = TripStats.speedKmhFromPositions(0.0, 0.0, 0L, tenMetersInDegrees, 0.0, 1_000L)
        assertEquals(36.0, speed!!, 0.2)
    }

    @Test
    fun speedFromPositionsRejectsNonPositiveDeltaTime() {
        assertNull(TripStats.speedKmhFromPositions(0.0, 0.0, 5_000L, 0.0, 0.0, 5_000L))
        assertNull(TripStats.speedKmhFromPositions(0.0, 0.0, 5_000L, 0.0, 0.0, 4_000L))
    }

    @Test
    fun pickSpeedPrefersRecordedPointSpeed() {
        val points = listOf(
            PointSample(0.0, 0.0, 0L, 1f),
            PointSample(0.0, 0.0, 1_000L, 10f),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(points), 0.0001)
    }

    @Test
    fun pickSpeedFallsBackToPositionDelta() {
        val tenMetersInDegrees = 10.0 / 111_195.0
        val points = listOf(
            PointSample(0.0, 0.0, 0L, 0f),
            PointSample(tenMetersInDegrees, 0.0, 1_000L, 0f),
        )
        assertEquals(36.0, TripStats.pickSpeedKmh(points), 0.2)
    }

    @Test
    fun pickSpeedWithNoPointsIsZero() {
        assertEquals(0.0, TripStats.pickSpeedKmh(emptyList()), 0.0)
    }
}
