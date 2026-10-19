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
        private val REQUIRED_AIDL_GROUPS = arrayOf("recording", "tracks_view")
    }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var grantButton: Button
    private lateinit var toggleButton: Button
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

        grantButton.setOnClickListener { requestOsmandAccess() }
        toggleButton.setOnClickListener {
            if (TripSharingService.isRunning) stopSharing() else ensureNotificationPermissionThenStart()
        }
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
        val packageName = OsmandAidlClient(this).resolvePackage()
        if (packageName == null) {
            Toast.makeText(this, "OsmAnd with the current AIDL API was not found. Update OsmAnd first.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(OsmandAidlClient.PERMISSION_REQUEST_ACTION)
            .setPackage(packageName)
            .putExtra(OsmandAidlClient.EXTRA_GROUPS, REQUIRED_AIDL_GROUPS)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OSMAND_PERMISSIONS)
        } catch (e: Exception) {
            Toast.makeText(this, "OsmAnd did not accept the request: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        val missing = REQUIRED_AIDL_GROUPS.filterNot { it in granted }
        if (missing.isNotEmpty()) {
            detailText.text = "Missing OsmAnd access: ${missing.joinToString(", ")}. " +
                "Tap “Grant OsmAnd access” and enable both toggles."
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
        val intent = Intent(this, TripSharingService::class.java)
            .setAction(TripSharingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSharing() {
        stopService(Intent(this, TripSharingService::class.java))
    }
}
