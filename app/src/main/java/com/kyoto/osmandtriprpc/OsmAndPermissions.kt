package com.kyoto.osmandtriprpc

/**
 * The AIDL permission groups this app needs: `recording` for distance/duration/recording state,
 * `tracks_view` for the recent points used to derive speed.
 */
object OsmAndPermissions {
    val REQUIRED = listOf("recording", "tracks_view")

    fun missing(granted: Collection<String>): List<String> = REQUIRED.filterNot { it in granted }
}
