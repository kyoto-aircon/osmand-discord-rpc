package com.kyoto.osmandtriprpc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Simple log viewer. No AndroidX, no RecyclerView: a TextView in a ScrollView is enough for
 * debugging on-device, and the log is small by construction (see [AppLog]).
 */
class LogActivity : Activity() {

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logEmpty: TextView
    private lateinit var osmAndStatus: String

    private val listener: (String) -> Unit = { line ->
        runOnUiThread { appendLine(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        logEmpty = findViewById(R.id.log_empty)

        findViewById<Button>(R.id.log_copy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("OsmAnd Trip RPC logs", exportText()))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.log_share).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "OsmAnd Trip RPC logs")
                .putExtra(Intent.EXTRA_TEXT, exportText())
            startActivity(Intent.createChooser(share, "Share logs"))
        }

        findViewById<Button>(R.id.log_clear).setOnClickListener {
            AppLog.clear()
            renderAll()
            AppLog.info("Log", "cleared by user")
        }

        renderAll()
        val client = OsmandAidlClient(this)
        osmAndStatus = client.resolvePackage() ?: "not in AIDL service list"
        osmAndStatus += " (installed: ${client.describeInstalledOsmAnd()})"
    }

    override fun onResume() {
        super.onResume()
        // Render first, then subscribe: a line arriving between snapshot() and addListener is
        // lost in the view (it is in the buffer), which beats rendering it twice.
        renderAll()
        AppLog.addListener(listener)
    }

    override fun onPause() {
        AppLog.removeListener(listener)
        super.onPause()
    }

    private fun renderAll() {
        val snapshot = AppLog.snapshot()
        logText.text = snapshot
        logEmpty.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
        scrollToBottom()
    }

    private fun appendLine(line: String) {
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        logEmpty.visibility = View.GONE
        scrollToBottom()
    }

    private fun scrollToBottom() {
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportText(): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        val header = buildString {
            appendLineCompat("OsmAnd Trip RPC $version")
            appendLineCompat("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLineCompat("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLineCompat("OsmAnd app: $osmAndStatus")
        }
        val body = AppLog.snapshot()
        return header + if (body.isEmpty()) "(no log lines)" else body
    }

    private fun StringBuilder.appendLineCompat(line: String) {
        append(line).append('\n')
    }
}
