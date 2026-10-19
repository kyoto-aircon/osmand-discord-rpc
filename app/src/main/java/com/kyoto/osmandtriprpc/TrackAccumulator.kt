package com.kyoto.osmandtriprpc

/** Result of adding a location fix to the trip. */
data class TrackUpdate(val distanceMeters: Double, val speedKmh: Double)

/**
 * Accumulates distance and speed from raw location fixes with basic jitter rejection.
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
class TrackAccumulator {

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimeMs: Long = 0
    private var distanceMeters: Double = 0.0

    fun reset() {
        lastLat = null
        lastLon = null
        lastTimeMs = 0
        distanceMeters = 0.0
    }

    fun addFix(
        lat: Double,
        lon: Double,
        timeMs: Long,
        accuracyMeters: Double?,
        speedMps: Float?,
    ): TrackUpdate? {
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS) return null

        val prevLat = lastLat
        val prevLon = lastLon
        if (prevLat == null || prevLon == null) {
            lastLat = lat
            lastLon = lon
            lastTimeMs = timeMs
            return TrackUpdate(0.0, speedFrom(speedMps, 0.0, 0.0))
        }

        val dtSeconds = (timeMs - lastTimeMs) / 1000.0
        if (dtSeconds <= 0.0) return null

        val step = TripStats.haversineMeters(prevLat, prevLon, lat, lon)
        lastTimeMs = timeMs
        lastLat = lat
        lastLon = lon

        // GPS noise below a couple of metres is not real movement; keep the old anchor.
        if (step < MIN_STEP_METERS) {
            return TrackUpdate(distanceMeters, speedFrom(speedMps, 0.0, dtSeconds))
        }

        distanceMeters += step
        return TrackUpdate(distanceMeters, speedFrom(speedMps, step, dtSeconds))
    }

    private fun speedFrom(speedMps: Float?, stepMeters: Double, dtSeconds: Double): Double {
        if (speedMps != null && speedMps.isFinite() && speedMps >= 0f) {
            return speedMps * 3.6
        }
        if (dtSeconds <= 0.0) return 0.0
        return (stepMeters / dtSeconds) * 3.6
    }

    private companion object {
        const val MAX_ACCURACY_METERS = 50.0
        const val MIN_STEP_METERS = 2.0
    }
}
