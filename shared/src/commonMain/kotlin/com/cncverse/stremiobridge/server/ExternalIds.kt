package com.cncverse.stremiobridge.server

/**
 * A Stremio id from another catalog: IMDb "tt123[:S:E]", "tmdb:123[:S:E]", "tvdb:123[:S:E]",
 * "tvmaze:123[:S:E]", or an anime id "kitsu:|mal:|anilist:|anidb:123[:EP]" / "[:S:E]".
 * Anime ids number episodes absolutely, so a bare episode is season 1.
 */
data class ExternalId(val scheme: String, val key: String, val season: Int?, val episode: Int?)

object ExternalIds {
    /** Prefixes the manifest declares for meta/stream/subtitles ("cnc:" is the bridge's own items). */
    val MANIFEST_PREFIXES = listOf("cnc:", "tt", "tmdb:", "tvdb:", "mal:", "kitsu:", "tvmaze:", "anilist:", "anidb:")

    private val IMDB = Regex("tt[0-9]+")
    private val NUM = Regex("[0-9]+")
    private val SERIES_SCHEMES = setOf("tmdb", "tvdb", "tvmaze")
    val ANIME_SCHEMES = setOf("kitsu", "mal", "anilist", "anidb")

    fun parse(id: String): ExternalId? {
        val p = id.trim().split(":")
        val head = p.firstOrNull()?.lowercase() ?: return null
        fun n(i: Int) = p.getOrNull(i)?.toIntOrNull()
        return when {
            head.matches(IMDB) -> ExternalId("imdb", head, n(1), n(2))
            head.matches(NUM) -> ExternalId("tmdb", head, n(1), n(2)) // bare TMDB number (older installs)
            head in SERIES_SCHEMES && p.getOrNull(1)?.matches(NUM) == true -> ExternalId(head, p[1], n(2), n(3))
            head in ANIME_SCHEMES && p.getOrNull(1)?.matches(NUM) == true -> when {
                p.size >= 4 -> ExternalId(head, p[1], n(2), n(3))
                p.size == 3 -> ExternalId(head, p[1], n(2)?.let { 1 }, n(2))
                else -> ExternalId(head, p[1], null, null)
            }
            else -> null
        }
    }
}
