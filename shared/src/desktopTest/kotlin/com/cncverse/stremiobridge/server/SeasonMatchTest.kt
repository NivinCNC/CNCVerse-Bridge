package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonMatchTest {
    private fun r(name: String) = SearchResult(name = name, url = "u:$name", posterUrl = null, type = "Anime", year = null)

    // What ReAnime / AniPM list for "The Apothecary Diaries"
    private val reanime = listOf(
        "The Apothecary Diaries", "The Apothecary Diaries Season 2", "The Apothecary Diaries Season 3 Part 2",
        "The Apothecary Diaries Season 3", "Maomao no Hitorigoto 3rd Season",
    ).map { r(it) }
    private val anipm = listOf("Kusuriya no Hitorigoto 3rd Season", "The Apothecary Diaries Season 2", "The Apothecary Diaries").map { r(it) }

    @Test
    fun picksTheSeasonEntry() {
        val s = StremioServer
        // The plain season wins over its "Part 2", whatever the order
        assertEquals("The Apothecary Diaries Season 3", s.seasonMatch(reanime, listOf("The Apothecary Diaries"), 3)?.name)
        assertEquals("The Apothecary Diaries Season 3 Part 2", s.seasonMatch(reanime, listOf("The Apothecary Diaries"), 3, part = 2)?.name)
        // "3rd Season" under the romaji name (from the alternative titles)
        assertNull(s.seasonMatch(anipm, listOf("The Apothecary Diaries"), 3))
        assertEquals("Kusuriya no Hitorigoto 3rd Season", s.seasonMatch(anipm, listOf("The Apothecary Diaries", "Kusuriya no Hitorigoto"), 3)?.name)
        // Season 1 never matches Season 10, and only a Part 2 is never taken as the season itself
        assertNull(s.seasonMatch(listOf(r("Show Season 10")), listOf("Show"), 1))
        assertNull(s.seasonMatch(listOf(r("Show Season 3 Part 2")), listOf("Show"), 3))
    }
}
