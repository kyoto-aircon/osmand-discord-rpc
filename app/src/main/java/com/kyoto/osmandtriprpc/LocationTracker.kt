package com.kyoto.osmandtriprpc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Minimal GPS source for OsmAnd builds older than 5.5, where the AIDL trip-recording API
 * does not exist yet. Uses the platform LocationManager (no Play Services, works on GrapheneOS).
 */
class LocationTracker(
    private val context: Context,
    private val onFix: (Location) -> Unit,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { location -> onFix(location) }

    private var running = false

    /** Returns null on success, or an error message. */
    fun start(): String? {
        if (running) return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "Location permission missing: allow “Nearby devices”/location for this app"
        }
        return try {
            if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return "Phone GPS is disabled"
            }
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                listener,
                Looper.getMainLooper(),
            )
            running = true
            AppLog.info("Gps", "location updates started")
            null
        } catch (e: Exception) {
            AppLog.error("Gps", "could not start location updates", e)
            "GPS unavailable: ${e.message}"
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(listener) }
        AppLog.info("Gps", "location updates stopped")
    }
}
