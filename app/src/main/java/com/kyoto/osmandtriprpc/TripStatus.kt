package com.kyoto.osmandtriprpc

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Snapshot of what the service currently knows about the OsmAnd trip recording.
 */
data class TripStatus(
    val enabled: Boolean,
    val recording: Boolean,
    val speedKmh: Int,
    val distanceKm: Int,
    val elapsedSeconds: Long,
    val state: String,
    val error: String?,
    val updatedAt: Long,
) {
    companion object {
        fun off(): TripStatus = TripStatus(
            enabled = false,
            recording = false,
            speedKmh = 0,
            distanceKm = 0,
            elapsedSeconds = 0,
            state = "OFF",
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/**
 * Process-wide latest status, observed by the UI and served over HTTP.
 */
object StatusRepository {
    @Volatile
    var current: TripStatus = TripStatus.off()
        private set

    private val listeners = CopyOnWriteArrayList<(TripStatus) -> Unit>()

    fun update(status: TripStatus) {
        current = status
        for (listener in listeners) {
            runCatching { listener(status) }
        }
    }

    fun addListener(listener: (TripStatus) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (TripStatus) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * JSON encoding for GET /status. Hand-rolled so the app has zero dependencies.
 */
object JsonStatus {
    fun toJson(status: TripStatus): String = buildString {
        append('{')
        append("\"enabled\":").append(status.enabled)
        append(",\"recording\":").append(status.recording)
        append(",\"speedKmh\":").append(status.speedKmh)
        append(",\"distanceKm\":").append(status.distanceKm)
        append(",\"elapsedSeconds\":").append(status.elapsedSeconds)
        append(",\"state\":\"").append(escape(status.state)).append('"')
        append(",\"error\":")
        if (status.error == null) {
            append("null")
        } else {
            append('"').append(escape(status.error)).append('"')
        }
        append(",\"updatedAt\":").append(status.updatedAt)
        append('}')
    }

    fun escape(value: String): String = buildString {
        for (c in value) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
    }
}
