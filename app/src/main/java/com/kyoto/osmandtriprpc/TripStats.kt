package com.kyoto.osmandtriprpc

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One recorded GPS sample as exposed by OsmAnd's AIDL API.
 */
data class PointSample(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val speedMps: Float,
)

/**
 * Pure trip-stat maths and formatting. No Android dependencies so it is unit-testable.
 */
object TripStats {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Speed values below this are treated as "not recorded" and the position delta is used instead. */
    private const val MIN_TRUSTED_POINT_SPEED_KMH = 0.5

    /**
     * Formats the presence line: "15KM/H - 50KM - 01:45:23".
     * Distance is shown in metres below 1 km, then in km with at most one decimal.
     * Speed is shown with at most one decimal, and whole numbers drop the ".0".
     */
    fun formatStatus(speedKmh: Double, distanceMeters: Double, durationMs: Long): String {
        return "${formatSpeed(speedKmh)} - ${formatDistance(distanceMeters)} - ${formatDuration(durationMs)}"
    }

    fun formatSpeed(kmh: Double): String {
        val tenths = tenths(kmh)
        return if (tenths % 10L == 0L) "${tenths / 10}KM/H" else "${tenths / 10}.${tenths % 10}KM/H"
    }

    fun formatDistance(meters: Double): String {
        val roundedMeters = roundNonNegative(meters)
        if (roundedMeters < 1000L) return "${roundedMeters}M"
        // 100 m per tenth of a kilometre, so the result never has more than one decimal.
        val tenths = Math.round(roundedMeters / 100.0)
        return if (tenths % 10L == 0L) "${tenths / 10}KM" else "${tenths / 10}.${tenths % 10}KM"
    }

    /** HH:MM:SS; hours are not capped (100h -> "100:00:00"). */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = if (durationMs <= 0L) 0L else durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /** km/h from a GPX point speed in m/s, or null when the value is unusable. */
    fun speedKmhFromPointSpeed(speedMps: Float): Double? {
        if (!speedMps.isFinite() || speedMps < 0f) return null
        return speedMps * 3.6
    }

    /** km/h from two fixes; null when timestamps are unusable. */
    fun speedKmhFromPositions(
        lat1: Double,
        lon1: Double,
        time1Ms: Long,
        lat2: Double,
        lon2: Double,
        time2Ms: Long,
    ): Double? {
        val deltaMs = time2Ms - time1Ms
        if (deltaMs <= 0L) return null
        val meters = haversineMeters(lat1, lon1, lat2, lon2)
        if (!meters.isFinite()) return null
        return meters / (deltaMs / 1000.0) * 3.6
    }

    /**
     * Best available speed for the most recent sample: the point's own speed when OsmAnd
     * recorded one, otherwise the delta between the last two fixes.
     */
    fun pickSpeedKmh(points: List<PointSample>): Double {
        if (points.isEmpty()) return 0.0
        val last = points[points.size - 1]
        speedKmhFromPointSpeed(last.speedMps)
            ?.takeIf { it >= MIN_TRUSTED_POINT_SPEED_KMH }
            ?.let { return it }
        if (points.size >= 2) {
            val previous = points[points.size - 2]
            speedKmhFromPositions(
                previous.latitude,
                previous.longitude,
                previous.timeMs,
                last.latitude,
                last.longitude,
                last.timeMs,
            )?.let { return it }
        }
        return 0.0
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    private fun roundNonNegative(value: Double): Long {
        if (!value.isFinite() || value <= 0.0) return 0L
        return Math.round(value)
    }

    /** Tenths of the unit, so formatting is integer-only and locale-independent. */
    private fun tenths(value: Double): Long {
        if (!value.isFinite() || value <= 0.0) return 0L
        return Math.round(value * 10.0)
    }
}
