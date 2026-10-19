package com.kyoto.osmandtriprpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Polls OsmAnd's live recording through AIDL, publishes the status on 127.0.0.1:8090
 * and keeps an ongoing notification with the same string.
 */
class TripSharingService : Service() {

    companion object {
        const val ACTION_START = "com.kyoto.osmandtriprpc.action.START"
        const val ACTION_STOP = "com.kyoto.osmandtriprpc.action.STOP"
        const val PORT = 8090

        private const val CHANNEL_ID = "trip_rpc"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    @Volatile
    private var shuttingDown = false
    private lateinit var client: OsmandAidlClient
    private lateinit var server: LocalHttpServer
    private var executor: ScheduledExecutorService? = null
    private var pollStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotificationText: String? = null
    private var httpError: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        client = OsmandAidlClient(this)
        server = LocalHttpServer(PORT) { JsonStatus.toJson(StatusRepository.current) }
        httpError = server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(StatusRepository.current))
        isRunning = true

        if (!pollStarted) {
            pollStarted = true
            client.bindIfNeeded()
            val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "trip-rpc-poll")
            }
            executor = exec
            exec.scheduleWithFixedDelay({ runCatching { pollOnce() } }, 0, 1, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        isRunning = false
        pollStarted = false
        executor?.shutdownNow()
        executor = null
        server.stop()
        client.unbind()
        StatusRepository.update(TripStatus.off())
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollOnce() {
        if (shuttingDown) return
        val api = client.api
        if (api == null) {
            if (shuttingDown) return
            client.bindIfNeeded()
            val message = httpError ?: client.lastError ?: "Waiting for OsmAnd"
            publish(status(state = "WAITING", error = message))
            return
        }

        val info = try {
            api.getGpxRecordingInfo()
        } catch (e: SecurityException) {
            client.markError("OsmAnd access missing (trip recording)")
            publish(
                status(
                    state = "NO ACCESS",
                    error = "OsmAnd access missing: tap “Grant OsmAnd access” and enable “Trip recording”.",
                )
            )
            return
        } catch (e: Exception) {
            client.markError("OsmAnd call failed: ${e.message}")
            publish(status(state = "ERROR", error = client.lastError))
            return
        }

        if (info == null) {
            publish(status(state = "WAITING", error = "OsmAnd returned no recording info"))
            return
        }
        if (!info.isRecording) {
            publish(status(state = "NOT RECORDING"))
            return
        }

        val total = info.points
        var pointsError: String? = null
        val points = try {
            client.getRecentPoints(maxOf(0, total - 2), 2)
        } catch (e: SecurityException) {
            pointsError = "OsmAnd access missing: enable “Tracks: view” for speed."
            client.markError(pointsError)
            emptyList()
        } catch (e: Exception) {
            pointsError = "Could not read track points: ${e.message}"
            emptyList()
        }

        // Ordering of the AIDL page is not documented; timestamps make it deterministic.
        val samples = points
            .map { PointSample(it.latitude, it.longitude, it.time, it.speed) }
            .sortedBy { it.timeMs }
        val speed = TripStats.pickSpeedKmh(samples)
        val state = TripStats.formatStatus(speed, info.distance.toDouble(), info.duration)

        if (pointsError != null) client.markError(pointsError)

        publish(
            TripStatus(
                enabled = true,
                recording = true,
                speedKmh = Math.round(speed.coerceIn(0.0, Int.MAX_VALUE.toDouble())).toInt(),
                distanceKm = Math.round((info.distance / 1000.0).coerceIn(0.0, Int.MAX_VALUE.toDouble())).toInt(),
                elapsedSeconds = info.duration / 1000L,
                state = state,
                error = pointsError,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun status(
        state: String,
        enabled: Boolean = true,
        recording: Boolean = false,
        error: String? = null,
    ): TripStatus = TripStatus(
        enabled = enabled,
        recording = recording,
        speedKmh = 0,
        distanceKm = 0,
        elapsedSeconds = 0,
        state = state,
        error = error,
        updatedAt = System.currentTimeMillis(),
    )

    private fun publish(status: TripStatus) {
        StatusRepository.update(status)
        mainHandler.post { updateNotification(status) }
    }

    private fun updateNotification(status: TripStatus) {
        val text = status.error ?: status.state
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: TripStatus): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TripSharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status.error ?: status.state)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.notification_stop),
                    stopIntent,
                ).build()
            )

        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
    }
}
