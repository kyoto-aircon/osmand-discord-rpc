package com.kyoto.osmandtriprpc

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal loopback-only HTTP server. Serves GET /status and GET /.
 * Hand-rolled because Android has no com.sun.net.httpserver and we want zero dependencies.
 */
class LocalHttpServer(
    private val port: Int,
    private val bodyProvider: () -> String,
) {
    @Volatile
    private var running = false

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    private val requestCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** Total HTTP requests served since the server started (diagnostics). */
    val requestCount: Int
        get() = requestCounter.get()

    @Volatile
    private var lastRequestLogAt = 0L

    /** Returns null on success, or an error message. */
    fun start(): String? {
        if (running) return null
        return try {
            val socket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
            server = socket
            running = true
            workers = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "trip-rpc-http-worker").apply { isDaemon = true }
            }
            thread = Thread({ acceptLoop(socket) }, "trip-rpc-http").apply {
                isDaemon = true
                start()
            }
            null
        } catch (e: Exception) {
            runCatching { server?.close() }
            server = null
            "Port $port unavailable: ${e.message}"
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        thread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!running) return
                continue
            }
            runCatching { workers?.submit { runCatching { handle(client) } } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            if (requestLine.length > MAX_REQUEST_LINE) return

            var headerLines = 0
            while (headerLines < MAX_HEADER_LINES) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.length > MAX_HEADER_LINE) return
                headerLines++
            }
            if (headerLines >= MAX_HEADER_LINES) return

            val tokens = requestLine.split(' ')
            val method = tokens.getOrNull(0) ?: return
            val path = tokens.getOrNull(1)?.substringBefore('?') ?: "/"

            val response = when {
                method != "GET" -> Response(405, "Method Not Allowed", "{\"error\":\"method not allowed\"}")
                path != "/status" && path != "/" -> Response(404, "Not Found", "{\"error\":\"not found\"}")
                else -> try {
                    Response(200, "OK", bodyProvider())
                } catch (e: Exception) {
                    AppLog.error("HTTP", "status provider failed", e)
                    Response(500, "Internal Server Error", "{\"error\":\"status unavailable\"}")
                }
            }

            // The plugin polls every 2 s; log the first request and then one line per 10 s so the
            // ring buffer is not consumed by keep-alives, but always log anything non-200.
            var total = 0
            val shouldLog = synchronized(this) {
                total = requestCounter.incrementAndGet()
                val now = System.currentTimeMillis()
                if (response.status != 200 || now - lastRequestLogAt >= 10_000L || total == 1) {
                    lastRequestLogAt = now
                    true
                } else {
                    false
                }
            }
            if (shouldLog) {
                AppLog.info("HTTP", "$method $path -> ${response.status} (requests=$total)")
            }

            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Cache-Control: no-store\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    private data class Response(val status: Int, val reason: String, val body: String)

    private companion object {
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADER_LINE = 4 * 1024
        const val MAX_HEADER_LINES = 64
    }
}
