package com.kyoto.osmandtriprpc

import org.junit.Assert.assertEquals
import org.junit.Test

class OsmAndPermissionsTest {

    @Test
    fun `all granted means nothing missing`() {
        assertEquals(emptyList<String>(), OsmAndPermissions.missing(listOf("recording", "tracks_view")))
    }

    @Test
    fun `missing one group is reported`() {
        assertEquals(listOf("tracks_view"), OsmAndPermissions.missing(listOf("recording")))
        assertEquals(listOf("recording"), OsmAndPermissions.missing(listOf("tracks_view")))
    }

    @Test
    fun `nothing granted reports both, in a stable order`() {
        assertEquals(listOf("recording", "tracks_view"), OsmAndPermissions.missing(emptyList()))
    }

    @Test
    fun `extra groups are ignored`() {
        assertEquals(emptyList<String>(), OsmAndPermissions.missing(listOf("recording", "tracks_view", "map")))
    }
}
