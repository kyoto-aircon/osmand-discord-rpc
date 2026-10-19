package com.kyoto.osmandtriprpc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class AppLogTest {

    private val lineFormat = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} [IWE]/[^:]+: .*$")

    @Before
    fun reset() = AppLog.clear()

    @After
    fun cleanup() = AppLog.removeAllListeners()

    @Test
    fun `records lines in order with level and tag`() {
        AppLog.info("Test", "first")
        AppLog.warn("Test", "second")
        val lines = AppLog.snapshot().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("I/Test: first"))
        assertTrue(lines[1].contains("W/Test: second"))
        assertTrue(lines[0].matches(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*")))
    }

    @Test
    fun `includes throwable class and message`() {
        AppLog.error("Bind", "failed", IllegalStateException("nope"))
        val line = AppLog.snapshot()
        assertTrue(line.contains("E/Bind: failed"))
        assertTrue(line.contains("IllegalStateException: nope"))
    }

    @Test
    fun `error without a message still names the throwable class`() {
        AppLog.error("Bind", "failed", IllegalStateException())
        val line = AppLog.snapshot()
        assertTrue(line.contains("E/Bind: failed"))
        assertTrue("throwable class missing from: $line", line.contains("IllegalStateException"))
        assertTrue("suffix not closed: $line", line.endsWith("]"))
        assertFalse("unexpected multi-line entry: $line", line.contains('\n'))
    }

    @Test
    fun `warn without a throwable adds no suffix`() {
        AppLog.warn("Test", "plain")
        assertTrue(AppLog.snapshot().matches(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} W/Test: plain$")))
    }

    @Test
    fun `keeps only the most recent lines`() {
        repeat(850) { AppLog.info("Ring", "line $it") }
        val lines = AppLog.snapshot().lines()
        assertEquals(800, lines.size)
        assertTrue(lines.first().contains("line 50"))
        assertTrue(lines.last().contains("line 849"))
    }

    @Test
    fun `exactly the capacity fits without eviction`() {
        repeat(800) { AppLog.info("Ring", "line $it") }
        val lines = AppLog.snapshot().lines()
        assertEquals(800, lines.size)
        assertTrue(lines.first().endsWith("line 0"))
        assertTrue(lines.last().endsWith("line 799"))
    }

    @Test
    fun `one line over capacity evicts only the oldest`() {
        repeat(801) { AppLog.info("Ring", "line $it") }
        val lines = AppLog.snapshot().lines()
        assertEquals(800, lines.size)
        assertTrue("oldest line survived: ${lines.first()}", lines.first().endsWith("line 1"))
        assertTrue(lines.last().endsWith("line 800"))
    }

    @Test
    fun `repeated wrap-around keeps the newest window in order`() {
        val total = 3 * 800 + 17
        repeat(total) { AppLog.info("Ring", "line $it") }
        val lines = AppLog.snapshot().lines()
        assertEquals(800, lines.size)
        assertTrue(lines.first().endsWith("line ${total - 800}"))
        assertTrue(lines.last().endsWith("line ${total - 1}"))
    }

    @Test
    fun `truncates a long entry to 500 characters`() {
        AppLog.info("Long", "x".repeat(2_000))
        val line = AppLog.snapshot()
        assertEquals(500, line.length)
        assertTrue("timestamp or level lost: $line", line.matches(lineFormat))
    }

    @Test
    fun `truncation never leaves an unpaired surrogate`() {
        AppLog.info("Long", "\uD83D\uDE00".repeat(600))
        val line = AppLog.snapshot()
        var index = 0
        while (index < line.length) {
            val c = line[index]
            if (Character.isHighSurrogate(c)) {
                assertTrue("high surrogate at end", index + 1 < line.length)
                assertTrue("unpaired high surrogate", Character.isLowSurrogate(line[index + 1]))
                index += 2
            } else {
                assertFalse("unpaired low surrogate", Character.isLowSurrogate(c))
                index++
            }
        }
    }

    @Test
    fun `clear empties the buffer`() {
        AppLog.info("Test", "x")
        AppLog.clear()
        assertEquals("", AppLog.snapshot())
    }

    @Test
    fun `clear keeps listeners registered`() {
        val received = mutableListOf<String>()
        val listener: (String) -> Unit = { received.add(it) }
        AppLog.addListener(listener)
        try {
            AppLog.clear()
            AppLog.info("Test", "after clear")
        } finally {
            AppLog.removeListener(listener)
        }
        assertEquals(listOf("after clear"), received.map { it.substringAfter("I/Test: ") })
    }

    @Test
    fun `removeListener stops further delivery`() {
        val received = mutableListOf<String>()
        val listener: (String) -> Unit = { received.add(it) }
        AppLog.addListener(listener)
        try {
            AppLog.info("Test", "one")
            AppLog.removeListener(listener)
            AppLog.info("Test", "two")
        } finally {
            AppLog.removeListener(listener)
        }
        assertEquals(1, received.size)
        assertTrue(received[0].contains("one"))
    }

    @Test
    fun `listeners receive lines and survive a throwing listener`() {
        val received = mutableListOf<String>()
        val bad: (String) -> Unit = { throw IllegalStateException("listener bug") }
        AppLog.addListener(bad)
        AppLog.addListener { received.add(it) }
        try {
            AppLog.info("Test", "hello")
        } finally {
            AppLog.removeListener(bad)
        }
        assertEquals(1, received.size)
        assertTrue(received[0].contains("hello"))
        // A misbehaving listener must not stop the line from being recorded.
        assertTrue(AppLog.snapshot().contains("hello"))
    }

    @Test
    fun `concurrent writers and snapshot readers stay coherent`() {
        val writers = 8
        val perWriter = 300
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers + 1)
        try {
            val writes = (0 until writers).map { writer ->
                pool.submit {
                    start.await()
                    repeat(perWriter) { index -> AppLog.info("W$writer", "message $index") }
                }
            }
            val reads = pool.submit {
                start.await()
                repeat(200) {
                    AppLog.snapshot().lines().forEach { line ->
                        check(line.matches(lineFormat)) { "corrupt line: $line" }
                    }
                }
            }
            start.countDown()
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
            reads.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        val lines = AppLog.snapshot().lines()
        assertEquals(800, lines.size)
        assertTrue(
            "unexpected line shape: ${lines.firstOrNull { !it.matches(lineFormat) }}",
            lines.all { it.matches(lineFormat) },
        )
        assertTrue(lines.all { it.contains(" I/W") })
    }

    private fun String.lines(): List<String> = if (isEmpty()) emptyList() else split("\n")
}
