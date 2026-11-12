package com.cncverse.stremiobridge.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvTypeMappingTest {
    @Test
    fun mapsCloudStreamTypesToStremioTypes() {
        assertEquals("movie", cs3TvTypeToStremio("Movie"))
        assertEquals("series", cs3TvTypeToStremio("TvSeries"))
        assertEquals("channel", cs3TvTypeToStremio("Live"))
        assertEquals("tv", cs3TvTypeToStremio("tv"))
        assertEquals("other", cs3TvTypeToStremio("Unknown"))
    }
}
