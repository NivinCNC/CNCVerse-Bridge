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

    @Test
    fun germanSeasonNames() {
        val s = StremioServer
        val megakino = listOf("Tulsa King - 4 Staffel", "Tulsa King Staffel 3", "Tulsa King 2. Staffel").map { r(it) }
        assertEquals("Tulsa King - 4 Staffel", s.seasonMatch(megakino, listOf("Tulsa King"), 4)?.name)
        assertEquals("Tulsa King Staffel 3", s.seasonMatch(megakino, listOf("Tulsa King"), 3)?.name)
        assertEquals("Tulsa King 2. Staffel", s.seasonMatch(megakino, listOf("Tulsa King"), 2)?.name)
    }

    @Test
    fun picksTheEpisodeEntry() {
        val s = StremioServer
        // What FilmPalast lists for "VisionQuest": one entry per episode, newest first
        val filmpalast = listOf("VisionQuest S01E04", "VisionQuest S01E03", "VisionQuest S01E02", "VisionQuest S01E01").map { r(it) }
        assertEquals("VisionQuest S01E02", s.episodeEntryMatch(filmpalast, listOf("VisionQuest"), 1, 2)?.name)
        assertNull(s.episodeEntryMatch(filmpalast, listOf("VisionQuest"), 1, 5))
        assertNull(s.episodeEntryMatch(filmpalast, listOf("VisionQuest"), 2, 1))
        // S1E1 is never taken for S1E10, nor another show's episode
        assertNull(s.episodeEntryMatch(listOf(r("Show S01E10")), listOf("Show"), 1, 1))
        assertNull(s.episodeEntryMatch(listOf(r("Other Show S01E01")), listOf("Show"), 1, 1))
        assertEquals("Haus des Geldes Staffel 2 Folge 3",
            s.episodeEntryMatch(listOf(r("Haus des Geldes Staffel 2 Folge 3")), listOf("Haus des Geldes"), 2, 3)?.name)
        assertEquals("Dark 2x03", s.episodeEntryMatch(listOf(r("Dark 2x13"), r("Dark 2x03")), listOf("Dark"), 2, 3)?.name)
    }

    @Test
    fun umlautsAndAccentsMatchEitherSpelling() {
        val s = StremioServer
        // TMDB writes the umlauts, the site spells them out (or the other way round)
        assertEquals("Tuerkisch fuer Anfaenger Staffel 2",
            s.seasonMatch(listOf(r("Tuerkisch fuer Anfaenger Staffel 2")), listOf("Türkisch für Anfänger"), 2)?.name)
        assertEquals("Türkisch für Anfänger – Staffel 2 Folge 3",
            s.episodeEntryMatch(listOf(r("Türkisch für Anfänger – Staffel 2 Folge 3")), listOf("Tuerkisch fuer Anfaenger"), 2, 3)?.name)
        assertEquals("Die grosse Ueberraschung S01E01",
            s.episodeEntryMatch(listOf(r("Die grosse Ueberraschung S01E01")), listOf("Die große Überraschung!"), 1, 1)?.name)
        // Decomposed umlauts (a + combining diaeresis) and other accents
        assertEquals("Ma\u0308dchen Staffel 1", s.seasonMatch(listOf(r("Ma\u0308dchen Staffel 1")), listOf("Maedchen"), 1)?.name)
        assertEquals("Pokemon Season 2", s.seasonMatch(listOf(r("Pokemon Season 2")), listOf("Pokémon"), 2)?.name)
    }
}
