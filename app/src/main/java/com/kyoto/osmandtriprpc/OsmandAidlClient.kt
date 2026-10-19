package com.kyoto.osmandtriprpc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxPoint
import net.osmand.aidlapi.gpx.AGpxRecordingInfo
import net.osmand.aidlapi.gpx.GpxPointsParams

/**
 * Thin wrapper around OsmAnd's official AIDL plugin API (OsmAndAidlServiceV2).
 *
 * The app is auto-registered by OsmAnd as a "connected app" the first time it binds, which is
 * what makes it show up in OsmAnd's Plugins screen. Method access is gated by AidlPermissionGroup,
 * granted through OsmAnd's own "Allow ... to use OsmAnd?" dialog.
 */
class OsmandAidlClient(private val context: Context) {

    companion object {
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        const val PERMISSION_REQUEST_ACTION = "net.osmand.aidl.REQUEST_PERMISSIONS"
        const val EXTRA_GROUPS = "groups"
        const val EXTRA_GRANTED_GROUPS = "granted_groups"

        /** Packages to try, most specific first (Play Store vs F-Droid builds). */
        val PREFERRED_PACKAGES = listOf("net.osmand.plus", "net.osmand")

        private const val REBIND_DELAY_MS = 3_000L
        private const val NOT_FOUND_MESSAGE = "OsmAnd with the current AIDL API was not found (update OsmAnd)"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var api: IOsmAndAidlInterface? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var wantBound = false

    @Volatile
    private var bindInFlight = false

    @Volatile
    private var rebindPosted = false

    @Volatile
    private var lastBindLog: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindInFlight = false
            lastBindLog = null
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            if (api != null) {
                lastError = null
                AppLog.info("Aidl", "connected to ${name?.packageName ?: "OsmAnd"}")
                // Binding alone does not register us in OsmAnd. The connected-app entry is
                // created on the first AIDL method call (OsmAnd's getOrCreateConnectedApp), so
                // probe once; before permissions are granted this call is denied, which is fine.
                probeRegistration()
            } else {
                lastError = "OsmAnd returned an empty service handle"
                AppLog.error("Aidl", lastError!!)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The binding stays registered and reconnects automatically when OsmAnd comes back,
            // so re-binding here is redundant (bindService would just return true again).
            api = null
            lastError = "OsmAnd disconnected"
            AppLog.warn("Aidl", "OsmAnd disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            api = null
            bindInFlight = false
            lastBindLog = null
            runCatching { context.unbindService(this) }
            lastError = "OsmAnd connection died"
            AppLog.warn("Aidl", "OsmAnd connection died, rebinding in 3 s")
            scheduleRebind()
        }
    }

    /**
     * Finds the installed OsmAnd package that actually exposes the AIDL service.
     */
    fun resolvePackage(): String? {
        val pm = context.packageManager
        val packagesWithService = try {
            pm.queryIntentServices(Intent(SERVICE_ACTION), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        return PREFERRED_PACKAGES.firstOrNull { it in packagesWithService }
    }

    /**
     * Debug helper: installed OsmAnd packages and their versions, for the log export.
     */
    fun describeInstalledOsmAnd(): String {
        val pm = context.packageManager
        return PREFERRED_PACKAGES.mapNotNull { pkg ->
            runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                "$pkg ${info.versionName ?: "?"}"
            }.getOrNull()
        }.joinToString(", ").ifEmpty { "none installed" }
    }

    @Synchronized
    fun bindIfNeeded() {
        wantBound = true
        if (api != null || bindInFlight) return

        val packageName = resolvePackage()
        if (packageName == null) {
            // No installed OsmAnd advertises the AIDL V2 service; retrying forever cannot help.
            if (lastError != NOT_FOUND_MESSAGE) AppLog.warn("Aidl", NOT_FOUND_MESSAGE)
            lastError = NOT_FOUND_MESSAGE
            return
        }

        if (lastBindLog != "binding:$packageName") {
            lastBindLog = "binding:$packageName"
            AppLog.info("Aidl", "binding to $packageName")
        }
        val intent = Intent(SERVICE_ACTION).setPackage(packageName)
        var flags = Context.BIND_AUTO_CREATE
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
        }

        bindInFlight = true
        val bound = try {
            context.bindService(intent, connection, flags)
        } catch (e: Exception) {
            lastError = "Could not bind to OsmAnd: ${e.message}"
            false
        }
        if (!bound) {
            bindInFlight = false
            if (lastError == null) lastError = "Could not bind to OsmAnd"
            val message = lastError!!
            if (lastBindLog != message) {
                lastBindLog = message
                AppLog.warn("Aidl", message)
            }
            scheduleRebind()
        }
    }

    @Synchronized
    fun unbind() {
        if (wantBound) AppLog.info("Aidl", "unbound from OsmAnd")
        wantBound = false
        rebindPosted = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { context.unbindService(connection) }
        bindInFlight = false
        api = null
    }

    fun markError(message: String) {
        lastError = message
    }

    /**
     * Triggers OsmAnd's connected-app registration so the app appears in OsmAnd's Plugins list
     * even before permissions are granted (the first AIDL method call creates the entry).
     */
    private fun probeRegistration() {
        val binder = api ?: return
        runCatching { binder.getGpxRecordingInfo() }
            .onSuccess { info ->
                AppLog.info(
                    "Aidl",
                    if (info == null) {
                        "registration probe returned null (OsmAnd < 5.5 or access not granted)"
                    } else {
                        "registration probe ok (trip API available)"
                    }
                )
            }
            .onFailure {
                AppLog.info("Aidl", "registration probe failed, expected before grant: ${it.message}")
            }
    }

    /** Live recording stats straight from OsmAnd's SavingTrackHelper. */
    fun getRecordingInfo(): AGpxRecordingInfo? = api?.getGpxRecordingInfo()

    /**
     * Recent points of the in-progress recording (an empty file name means "current recording").
     */
    fun getRecentPoints(offset: Int, limit: Int): List<AGpxPoint> {
        val result = api?.getGpxPoints(GpxPointsParams("", offset, limit)) ?: return emptyList()
        return result.points ?: emptyList()
    }

    @Synchronized
    private fun scheduleRebind() {
        if (!wantBound || rebindPosted) return
        rebindPosted = true
        mainHandler.postDelayed({
            rebindPosted = false
            if (wantBound && api == null) bindIfNeeded()
        }, REBIND_DELAY_MS)
    }
}
