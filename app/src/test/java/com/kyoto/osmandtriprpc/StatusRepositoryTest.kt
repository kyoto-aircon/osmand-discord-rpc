package com.kyoto.osmandtriprpc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRepositoryTest {

    private val registered = mutableListOf<(TripStatus) -> Unit>()

    @After
    fun cleanUp() {
        registered.forEach { StatusRepository.removeListener(it) }
        StatusRepository.update(TripStatus.off())
    }

    private fun status(state: String): TripStatus =
        TripStatus(true, true, 0, 0, 0, state, null, 1L)

    private fun register(listener: (TripStatus) -> Unit) {
        registered += listener
        StatusRepository.addListener(listener)
    }

    @Test
    fun offSnapshotHasDocumentedDefaults() {
        val before = System.currentTimeMillis()
        val off = TripStatus.off()
        val after = System.currentTimeMillis()

        assertFalse(off.enabled)
        assertFalse(off.recording)
        assertEquals(0, off.speedKmh)
        assertEquals(0, off.distanceKm)
        assertEquals(0L, off.elapsedSeconds)
        assertEquals("OFF", off.state)
        assertNull(off.error)
        assertTrue("timestamp $before..$after", off.updatedAt in before..after)
    }

    @Test
    fun updatePublishesCurrentAndNotifiesListeners() {
        val received = mutableListOf<TripStatus>()
        register { received += it }

        val next = status("15KM/H - 50KM - 01:45:23")
        StatusRepository.update(next)

        assertSame(next, StatusRepository.current)
        assertEquals(listOf(next), received)
    }

    @Test
    fun throwingListenerDoesNotStopOtherListeners() {
        val received = mutableListOf<TripStatus>()
        register { throw IllegalStateException("listener boom") }
        register { received += it }

        StatusRepository.update(status("SURVIVED"))

        assertEquals(1, received.size)
        assertEquals("SURVIVED", received.single().state)
    }

    @Test
    fun sameListenerIsRegisteredOnlyOnce() {
        var calls = 0
        val listener: (TripStatus) -> Unit = { calls++ }
        StatusRepository.addListener(listener)
        StatusRepository.addListener(listener)
        registered += listener

        StatusRepository.update(status("ONCE"))

        assertEquals(1, calls)
    }

    @Test
    fun removeListenerStopsNotifications() {
        var calls = 0
        val listener: (TripStatus) -> Unit = { calls++ }
        StatusRepository.addListener(listener)
        StatusRepository.update(status("FIRST"))
        StatusRepository.removeListener(listener)
        StatusRepository.update(status("SECOND"))

        assertEquals(1, calls)
    }
}
