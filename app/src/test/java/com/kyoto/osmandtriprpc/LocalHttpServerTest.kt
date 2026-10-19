package com.kyoto.osmandtriprpc

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalHttpServer only uses java.net/java.io, so it can be exercised on the JVM
 * without an Android runtime. Tests cover request routing and response framing.
 */
class LocalHttpServerTest {

    private var server: LocalHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private data class Response(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun startServer(body: String): Int {
        val port = freeLoopbackPort()
        val started = LocalHttpServer(port) { body }
        val error = started.start()
        assertNull("server failed to start: $error", error)
        server = started
        return port
    }

    private fun send(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun parse(raw: String): Response {
        val separator = raw.indexOf("\r\n\r\n")
        assertTrue("missing header/body separator in: $raw", separator >= 0)
        val lines = raw.substring(0, separator).split("\r\n")
        val headers = lines.drop(1).associate { line ->
            val colon = line.indexOf(':')
            assertTrue("malformed header line: $line", colon > 0)
            line.substring(0, colon).trim().lowercase(Locale.US) to line.substring(colon + 1).trim()
        }
        return Response(lines.first(), headers, raw.substring(separator + 4))
    }

    @Test
    fun servesStatusWithCompleteFraming() {
        // Multibyte body: Content-Length must be the UTF-8 byte count, not the char count.
        val body = "{\"state\":\"café 😀\"}"
        val port = startServer(body)

        val response = parse(send(port, "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n"))

        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("application/json; charset=utf-8", response.headers["content-type"])
        assertEquals("no-store", response.headers["cache-control"])
        assertEquals("close", response.headers["connection"])
        assertEquals(body, response.body)
        assertEquals(
            body.toByteArray(StandardCharsets.UTF_8).size.toString(),
            response.headers["content-length"],
        )
    }

    @Test
    fun rootPathServesTheSameBody() {
        val port = startServer("{\"enabled\":false}")
        val response = parse(send(port, "GET / HTTP/1.1\r\n\r\n"))

        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("{\"enabled\":false}", response.body)
    }

    @Test
    fun queryStringIsIgnored() {
        val port = startServer("{\"ok\":true}")
        val response = parse(send(port, "GET /status?foo=bar&baz=1 HTTP/1.1\r\n\r\n"))

        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("{\"ok\":true}", response.body)
    }

    @Test
    fun unknownPathReturns404Json() {
        val port = startServer("{\"ok\":true}")
        val response = parse(send(port, "GET /nope HTTP/1.1\r\n\r\n"))

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine)
        assertEquals("{\"error\":\"not found\"}", response.body)
        assertEquals(
            "{\"error\":\"not found\"}".toByteArray(StandardCharsets.UTF_8).size.toString(),
            response.headers["content-length"],
        )
    }

    @Test
    fun handlesSequentialConnections() {
        val port = startServer("{\"seq\":1}")
        repeat(3) {
            val response = parse(send(port, "GET /status HTTP/1.1\r\n\r\n"))
            assertEquals("HTTP/1.1 200 OK", response.statusLine)
            assertEquals("{\"seq\":1}", response.body)
        }
    }

    @Test
    fun secondStartIsANoOp() {
        val body = "{\"enabled\":false}"
        val port = startServer(body)

        assertNull(server!!.start())
        assertEquals(body, parse(send(port, "GET /status HTTP/1.1\r\n\r\n")).body)
    }

    @Test
    fun startReportsAnUnusablePort() {
        val invalid = LocalHttpServer(65_536) { "{}" }
        val error = invalid.start()

        assertNotNull(error)
        assertTrue("unexpected error: $error", error!!.startsWith("Port 65536 unavailable:"))
    }

    @Test
    fun rejectsNonGetMethods() {
        val port = startServer("{\"ok\":true}")
        val response = parse(send(port, "POST /status HTTP/1.1\r\nContent-Length: 0\r\n\r\n"))

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine)
        assertEquals("{\"error\":\"method not allowed\"}", response.body)
    }

    @Test
    fun returnsJson500WhenTheBodyProviderThrows() {
        val port = freeLoopbackPort()
        val started = LocalHttpServer(port) { throw IllegalStateException("boom") }
        assertNull(started.start())
        server = started

        val response = parse(send(port, "GET /status HTTP/1.1\r\n\r\n"))

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine)
        assertEquals("{\"error\":\"status unavailable\"}", response.body)
    }

    @Test
    fun countsServedRequests() {
        val port = startServer("{\"n\":1}")
        assertEquals(0, server!!.requestCount)

        repeat(3) { parse(send(port, "GET /status HTTP/1.1\r\n\r\n")) }

        assertEquals(3, server!!.requestCount)
    }

    @Test
    fun stopStopsAcceptingConnections() {
        val port = startServer("{}")
        server!!.stop()
        server = null

        assertThrows(Exception::class.java) {
            Socket("127.0.0.1", port).use { it.soTimeout = 1_000 }
        }
    }
}
