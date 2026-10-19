package com.kyoto.osmandtriprpc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory, thread-safe log ring buffer. Intentionally dependency-free: this is a debug aid,
 * not a logging framework. Lines survive until the process dies or the user clears them.
 */
object AppLog {

    private const val MAX_LINES = 800

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun info(tag: String, message: String) = add("I", tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) = add("W", tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) = add("E", tag, message, error)

    private fun add(level: String, tag: String, message: String, error: Throwable?) {
        val suffix = error?.let {
            val detail = it.message
            val name = it.javaClass.simpleName
            if (detail.isNullOrBlank()) " [$name]" else " [$name: $detail]"
        } ?: ""
        val line = truncate("${stamp.get()!!.format(Date())} $level/$tag: $message$suffix", 500)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listeners.forEach { listener -> runCatching { listener(line) } }
    }

    /** Truncates without splitting a surrogate pair (keeps shared text valid UTF-16). */
    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Test hook: drop every registered listener. */
    fun removeAllListeners() {
        listeners.clear()
    }
}
