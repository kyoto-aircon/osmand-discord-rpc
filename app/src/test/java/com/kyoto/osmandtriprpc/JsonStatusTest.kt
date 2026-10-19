package com.kyoto.osmandtriprpc

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonStatusTest {

    @Test
    fun encodesExpectedStatusShape() {
        val status = TripStatus(
            enabled = true,
            recording = true,
            speedKmh = 15,
            distanceKm = 50,
            elapsedSeconds = 6323,
            state = "15KM/H - 50KM - 01:45:23",
            error = null,
            updatedAt = 1234L,
        )
        assertEquals(
            "{\"enabled\":true,\"recording\":true,\"speedKmh\":15,\"distanceKm\":50," +
                "\"elapsedSeconds\":6323,\"state\":\"15KM/H - 50KM - 01:45:23\"," +
                "\"error\":null,\"updatedAt\":1234}",
            JsonStatus.toJson(status),
        )
    }

    @Test
    fun includesErrorWhenPresent() {
        val status = TripStatus(false, false, 0, 0, 0, "WAITING", "no osmAnd", 1L)
        assertEquals(
            "{\"enabled\":false,\"recording\":false,\"speedKmh\":0,\"distanceKm\":0," +
                "\"elapsedSeconds\":0,\"state\":\"WAITING\",\"error\":\"no osmAnd\",\"updatedAt\":1}",
            JsonStatus.toJson(status),
        )
    }

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        assertEquals("a\\\"b\\\\c\\n", JsonStatus.escape("a\"b\\c\n"))
        assertEquals("tab\\there", JsonStatus.escape("tab\there"))
        assertEquals("\\u0001", JsonStatus.escape("\u0001"))
    }
}
