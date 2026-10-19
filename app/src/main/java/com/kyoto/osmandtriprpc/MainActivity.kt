package com.kyoto.osmandtriprpc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_OSMAND_PERMISSIONS = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private val REQUIRED_AIDL_GROUPS = OsmAndPermissions.REQUIRED.toTypedArray()
    }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var grantButton: Button
    private lateinit var toggleButton: Button
    private lateinit var aidlClient: OsmandAidlClient
    private var pendingStart = false

    private val statusListener: (TripStatus) -> Unit = { status ->
        runOnUiThread { render(status) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        detailText = findViewById(R.id.detail_text)
        grantButton = findViewById(R.id.grant_button)
        toggleButton = findViewById(R.id.toggle_button)

        // Binding alone does not create the OsmAnd connected-app entry; the first AIDL call does
        // (see probeRegistration in OsmandAidlClient). Binding here warms that path so the app
        // can appear in OsmAnd's Plugins list before sharing starts.
        aidlClient = OsmandAidlClient(this)
        aidlClient.bindIfNeeded()

        grantButton.setOnClickListener { requestOsmandAccess() }
        toggleButton.setOnClickListener {
            if (TripSharingService.isRunning) stopSharing() else ensureNotificationPermissionThenStart()
        }
        findViewById<Button>(R.id.logs_button).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.open_osmand_button).setOnClickListener { openOsmAnd() }
        AppLog.info("Main", "activity opened")
    }

    override fun onResume() {
        super.onResume()
        StatusRepository.addListener(statusListener)
        render(StatusRepository.current)
    }

    override fun onPause() {
        StatusRepository.removeListener(statusListener)
        super.onPause()
    }

    override fun onDestroy() {
        aidlClient.unbind()
        super.onDestroy()
    }

    private fun render(status: TripStatus) {
        statusText.text = status.state
        detailText.text = when {
            status.error != null -> status.error
            !TripSharingService.isRunning -> "Not sharing."
            status.recording -> "Live from OsmAnd's trip recording."
            else -> "Waiting for a trip recording in OsmAnd."
        }
        toggleButton.text = if (TripSharingService.isRunning) "Stop sharing" else "Start sharing"
    }

    private fun requestOsmandAccess() {
        val client = OsmandAidlClient(this)
        val packageName = client.resolvePackage()
        if (packageName == null) {
            AppLog.warn("Main", "no OsmAnd AIDL V2 service; installed: ${client.describeInstalledOsmAnd()}")
            Toast.makeText(this, "OsmAnd with the current AIDL API was not found. Update OsmAnd first.", Toast.LENGTH_LONG).show()
            return
        }
        AppLog.info("Main", "requesting OsmAnd permission groups recording,tracks_view from $packageName")
        val intent = Intent(OsmandAidlClient.PERMISSION_REQUEST_ACTION)
            .setPackage(packageName)
            .putExtra(OsmandAidlClient.EXTRA_GROUPS, REQUIRED_AIDL_GROUPS)
        val matches = packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        AppLog.info("Main", "permission dialog candidates: ${matches.ifEmpty { "none" }}")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OSMAND_PERMISSIONS)
        } catch (e: Exception) {
            AppLog.error("Main", "OsmAnd permission dialog failed", e)
            showManualGrantHelp()
        }
    }

    /**
     * OsmAnd's permission dialog is missing on some builds. The same result is reachable from
     * OsmAnd itself once our connected-app entry exists: Plugins -> OsmAnd Trip RPC.
     */
    private fun showManualGrantHelp() {
        detailText.text = "Could not open OsmAnd's permission dialog. Open OsmAnd → ☰ menu → " +
            "Plugins → “OsmAnd Trip RPC”, enable it, and allow “Trip recording” (and “Tracks: view”). " +
            "Then come back and tap Start sharing."
        Toast.makeText(this, "Use OsmAnd → ☰ menu → Plugins → OsmAnd Trip RPC", Toast.LENGTH_LONG).show()
    }

    private fun openOsmAnd() {
        val launch = OsmandAidlClient(this).resolvePackage()
            ?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            Toast.makeText(this, "Could not open OsmAnd", Toast.LENGTH_SHORT).show()
            return
        }
        AppLog.info("Main", "opening OsmAnd")
        startActivity(launch)
    }

    @Deprecated("startActivityForResult is fine here; the app avoids AndroidX on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OSMAND_PERMISSIONS) return
        val granted = if (resultCode == RESULT_OK) {
            data?.getStringArrayExtra(OsmandAidlClient.EXTRA_GRANTED_GROUPS)?.toList().orEmpty()
        } else {
            emptyList()
        }
        val missing = OsmAndPermissions.missing(granted)
        AppLog.info("Main", "OsmAnd permission result code=$resultCode granted=${granted.joinToString(",").ifEmpty { "none" }}")
        if (missing.isNotEmpty()) {
            detailText.text = "Missing OsmAnd access: ${missing.joinToString(", ")}. " +
                "Tap “Grant OsmAnd access” and enable both toggles, or allow them in " +
                "OsmAnd → ☰ menu → Plugins → “OsmAnd Trip RPC”."
        } else {
            Toast.makeText(this, "OsmAnd access granted for ${granted.joinToString(", ")}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startSharing()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingStart) {
            pendingStart = false
            // Start even if denied: the foreground service still runs, the notification may not show.
            startSharing()
        }
    }

    private fun startSharing() {
        AppLog.info("Main", "start sharing requested")
        val intent = Intent(this, TripSharingService::class.java)
            .setAction(TripSharingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSharing() {
        AppLog.info("Main", "stop sharing requested")
        stopService(Intent(this, TripSharingService::class.java))
    }
}
