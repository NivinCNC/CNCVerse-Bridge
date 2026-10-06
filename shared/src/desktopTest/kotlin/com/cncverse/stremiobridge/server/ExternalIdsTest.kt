package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalIdsTest {
    @Test
    fun `every supported id form parses`() {
        assertEquals(ExternalId("imdb", "tt0903747", 1, 3), ExternalIds.parse("tt0903747:1:3"))
        assertEquals(ExternalId("imdb", "tt9376612", null, null), ExternalIds.parse("tt9376612"))
        // The TMDB id is not the season (this used to look for season 1396)
        assertEquals(ExternalId("tmdb", "1396", 1, 3), ExternalIds.parse("tmdb:1396:1:3"))
        assertEquals(ExternalId("tvdb", "81189", 2, 5), ExternalIds.parse("tvdb:81189:2:5"))
        assertEquals(ExternalId("tvmaze", "169", null, null), ExternalIds.parse("tvmaze:169"))
        // Anime ids: a bare episode is absolute -> season 1
        assertEquals(ExternalId("kitsu", "7442", 1, 12), ExternalIds.parse("kitsu:7442:12"))
        assertEquals(ExternalId("mal", "16498", null, null), ExternalIds.parse("mal:16498"))
        assertEquals(ExternalId("anilist", "16498", 2, 4), ExternalIds.parse("anilist:16498:2:4"))
        assertEquals(ExternalId("anidb", "9541", 1, 1), ExternalIds.parse("anidb:9541:1"))
    }

    @Test
    fun `other add-ons' ids are rejected`() {
        assertNull(ExternalIds.parse("xtream:123"))
        assertNull(ExternalIds.parse("kitsu:abc"))
        assertNull(ExternalIds.parse("cnc:Zm9v"))
    }
}
