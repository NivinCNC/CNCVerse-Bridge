package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaExtrasTest {
    class Score(private val v: Double) { fun toDouble(max: Int) = v * max / 10.0 }
    class Actor(val name: String, val image: String? = null)
    class ActorData(private val a: Actor) { fun getActor() = a }

    /** Shaped like the Twitch extension's response: a plot, poster, background and tags. */
    class FakeLoad {
        fun getBackgroundPosterUrl() = "https://img.example/bg.jpg"
        fun getLogoUrl(): String? = null
        fun getTags() = listOf("Live", "en", "Rank: 3", "Live")
        fun getScore() = Score(7.456)
        fun getActors() = listOf(ActorData(Actor("Ada")), ActorData(Actor("Bob")))
        fun getDuration() = 45
        fun getContentRating() = "TV-14"
    }

    class BareLoad // an extension that sets nothing optional

    class FakeEpisode { fun getDescription() = "Pilot"; fun getDate() = 1_700_000_000_000L }

    private val base = MediaInfo("n", "u", null, "movie", null, null, "d")

    @Test
    fun `fields an extension sets are read`() {
        val m = base.withExtras(FakeLoad())
        assertEquals("https://img.example/bg.jpg", m.backgroundUrl)
        assertEquals(listOf("Live", "en", "Rank: 3"), m.genres)
        assertEquals(listOf("Ada", "Bob"), m.cast)
        assertEquals(45, m.runtimeMinutes)
        assertEquals("TV-14", m.contentRating)
        assertEquals(7.456, m.rating!!, 1e-9)
        assertNull(m.logoUrl)
    }

    @Test
    fun `an extension that sets nothing leaves everything null`() {
        val m = base.withExtras(BareLoad())
        assertNull(m.backgroundUrl); assertNull(m.genres); assertNull(m.cast); assertNull(m.rating); assertNull(m.runtimeMinutes)
    }

    @Test
    fun `episode description and date are read`() {
        val e = MediaInfoEpisode("e", 1, 1, "d", null).withExtras(FakeEpisode())
        assertEquals("Pilot", e.description)
        assertEquals(1_700_000_000_000L, e.releasedMs)
    }

    @Test
    fun `meta carries the new fields to Stremio`() {
        val meta = base.copy(backgroundUrl = "https://img.example/bg.jpg", genres = listOf("Live"), rating = 7.456,
            cast = listOf("Ada"), runtimeMinutes = 45, year = 2024).toStremiMeta("plug", "movie")
        assertEquals("https://img.example/bg.jpg", meta.background)
        assertEquals(listOf("Live"), meta.genres)
        assertEquals("7.5", meta.imdbRating)
        assertEquals("45 min", meta.runtime)
        assertEquals("2024", meta.releaseInfo)
        assertEquals(listOf("Ada"), meta.cast)
    }
}
