package com.kyoto.osmandtriprpc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON encoding edge cases: null vs empty error, every escape form, and hostile
 * strings that must still produce syntactically valid JSON.
 */
class JsonStatusEdgeCasesTest {

    @Test
    fun encodesErrorWithEveryEscapeForm() {
        val status = TripStatus(
            enabled = false,
            recording = false,
            speedKmh = 0,
            distanceKm = 0,
            elapsedSeconds = 0,
            state = "line1\nline2",
            error = "quote\" backslash\\ tab\t cr\r nul\u0000",
            updatedAt = 7L,
        )
        assertEquals(
            "{\"enabled\":false,\"recording\":false,\"speedKmh\":0,\"distanceKm\":0," +
                "\"elapsedSeconds\":0,\"state\":\"line1\\nline2\"," +
                "\"error\":\"quote\\\" backslash\\\\ tab\\t cr\\r nul\\u0000\",\"updatedAt\":7}",
            JsonStatus.toJson(status),
        )
    }

    @Test
    fun nullErrorIsNullLiteralAndEmptyErrorIsAString() {
        val base = TripStatus(true, true, 1, 2, 3, "x", null, 4L)
        assertTrue(JsonStatus.toJson(base).contains("\"error\":null"))

        val emptyError = base.copy(error = "")
        assertTrue(JsonStatus.toJson(emptyError).contains("\"error\":\"\""))
    }

    @Test
    fun emptyStateAndErrorAreEncodedAsEmptyStrings() {
        val status = TripStatus(false, false, 0, 0, 0, "", "", 0L)
        assertEquals(
            "{\"enabled\":false,\"recording\":false,\"speedKmh\":0,\"distanceKm\":0," +
                "\"elapsedSeconds\":0,\"state\":\"\",\"error\":\"\",\"updatedAt\":0}",
            JsonStatus.toJson(status),
        )
    }

    @Test
    fun escapesAllC0ControlCharacters() {
        for (code in 0x00..0x1f) {
            val escaped = JsonStatus.escape(code.toChar().toString())
            val expected = when (code) {
                0x09 -> "\\t"
                0x0a -> "\\n"
                0x0d -> "\\r"
                else -> String.format(Locale.US, "\\u%04x", code)
            }
            assertEquals(String.format(Locale.US, "control 0x%02x", code), expected, escaped)
            assertTrue("raw control char leaked for 0x$code", escaped.none { it < ' ' })
        }
    }

    @Test
    fun leavesPrintableUnicodeAloneButEscapesQuotes() {
        val input = "café – 東京 – 😀 – \"quoted\""
        assertEquals("café – 東京 – 😀 – \\\"quoted\\\"", JsonStatus.escape(input))
    }

    @Test
    fun hostileStatusFieldsStillProduceValidJson() {
        val status = TripStatus(
            enabled = true,
            recording = true,
            speedKmh = -3,
            distanceKm = 0,
            elapsedSeconds = 1,
            state = "line1\nline2\t\"quoted\"\\",
            error = "bad \u0000 \u001f \r\n \"x\" \\ 😀",
            updatedAt = 42L,
        )
        val json = JsonStatus.toJson(status)
        MiniJsonValidator.parse(json)
        assertTrue(json.contains("\"speedKmh\":-3"))
    }

    @Test
    fun validatorRejectsRawControlCharactersAndBadEscapes() {
        assertThrows(AssertionError::class.java) {
            MiniJsonValidator.parse("{\"state\":\"a\nb\"}")
        }
        assertThrows(AssertionError::class.java) {
            MiniJsonValidator.parse("{\"state\":\"a\\qb\"}")
        }
    }

    @Test
    fun validatorAcceptsKnownGoodJson() {
        MiniJsonValidator.parse("{\"a\":[1,-2.5,true,false,null,\"x\\u0041\"]}")
    }

    /**
     * Small strict JSON parser, only used to prove the hand-rolled encoder never emits
     * malformed JSON (no dependency on org.json, which is stubbed in JVM unit tests).
     */
    private object MiniJsonValidator {
        fun parse(json: String) {
            Parser(json).parseDocument()
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun parseDocument() {
                skipWhitespace()
                parseValue()
                skipWhitespace()
                if (i != s.length) fail("trailing characters")
            }

            private fun fail(message: String): Nothing =
                throw AssertionError("invalid JSON at index $i ($message): $s")

            private fun skipWhitespace() {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
            }

            private fun parseValue() {
                if (i >= s.length) fail("unexpected end")
                when (s[i]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> expectLiteral("true")
                    'f' -> expectLiteral("false")
                    'n' -> expectLiteral("null")
                    else -> parseNumber()
                }
            }

            private fun expectLiteral(literal: String) {
                if (!s.startsWith(literal, i)) fail("expected $literal")
                i += literal.length
            }

            private fun parseObject() {
                i++ // {
                skipWhitespace()
                if (i < s.length && s[i] == '}') {
                    i++
                    return
                }
                while (true) {
                    skipWhitespace()
                    if (i >= s.length || s[i] != '"') fail("expected object key")
                    parseString()
                    skipWhitespace()
                    if (i >= s.length || s[i] != ':') fail("expected ':'")
                    i++
                    skipWhitespace()
                    parseValue()
                    skipWhitespace()
                    if (i >= s.length) fail("unterminated object")
                    when (s[i]) {
                        ',' -> i++
                        '}' -> {
                            i++
                            return
                        }
                        else -> fail("expected ',' or '}'")
                    }
                }
            }

            private fun parseArray() {
                i++ // [
                skipWhitespace()
                if (i < s.length && s[i] == ']') {
                    i++
                    return
                }
                while (true) {
                    skipWhitespace()
                    parseValue()
                    skipWhitespace()
                    if (i >= s.length) fail("unterminated array")
                    when (s[i]) {
                        ',' -> i++
                        ']' -> {
                            i++
                            return
                        }
                        else -> fail("expected ',' or ']'")
                    }
                }
            }

            private fun parseString() {
                if (i >= s.length || s[i] != '"') fail("expected string")
                i++
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '"' -> {
                            i++
                            return
                        }
                        c == '\\' -> {
                            i++
                            if (i >= s.length) fail("unterminated escape")
                            when (val escaped = s[i]) {
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                                'u' -> {
                                    if (i + 4 >= s.length) fail("short unicode escape")
                                    for (offset in 1..4) {
                                        val hex = s[i + offset]
                                        val isHex = hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F'
                                        if (!isHex) fail("bad unicode escape")
                                    }
                                    i += 5
                                }
                                else -> fail("bad escape \\$escaped")
                            }
                        }
                        c < ' ' -> fail("raw control character")
                        else -> i++
                    }
                }
                fail("unterminated string")
            }

            private fun parseNumber() {
                val start = i
                if (i < s.length && s[i] == '-') i++
                if (i >= s.length || s[i] !in '0'..'9') fail("expected number")
                while (i < s.length && s[i] in '0'..'9') i++
                if (i < s.length && s[i] == '.') {
                    i++
                    if (i >= s.length || s[i] !in '0'..'9') fail("expected fraction digits")
                    while (i < s.length && s[i] in '0'..'9') i++
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    i++
                    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                    if (i >= s.length || s[i] !in '0'..'9') fail("expected exponent digits")
                    while (i < s.length && s[i] in '0'..'9') i++
                }
                if (i == start) fail("expected number")
            }
        }
    }
}
