package com.cncverse.stremiobridge.server

/**
 * Optional detail-page fields of a CloudStream LoadResponse / Episode, read by reflection
 * (extensions are loaded from their own jars, so the classes are not linked at compile time).
 * Every getter is optional: an extension that does not set a field just leaves it null.
 */
private fun Any.get(name: String): Any? =
    runCatching { javaClass.getMethod(name).invoke(this) }.getOrNull()

private fun Any.str(name: String): String? = (get(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun Any.int(name: String): Int? = (get(name) as? Number)?.toInt()

/** CloudStream's Score (new API: toDouble(max)) or the legacy Int rating (0..10000), as 0..10. */
private fun Any.rating10(): Double? {
    get("getScore")?.let { score ->
        val v = runCatching {
            score.javaClass.getMethod("toDouble", Int::class.javaPrimitiveType).invoke(score, 10) as? Double
        }.getOrNull()
        if (v != null && v > 0.0) return v
    }
    val legacy = int("getRating") ?: return null
    return if (legacy > 0) legacy / 1000.0 else null
}

fun MediaInfo.withExtras(resp: Any): MediaInfo {
    val genres = (resp.get("getTags") as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?.distinct()?.take(30)?.takeIf { it.isNotEmpty() }
    val people = (resp.get("getActors") as? List<*>)?.mapNotNull { a ->
        val actor = a?.get("getActor") ?: return@mapNotNull null
        val name = actor.str("getName") ?: return@mapNotNull null
        CastPerson(name, character = a.str("getRoleString"), photo = actor.str("getImage"))
    }?.distinctBy { it.name }?.take(40)?.takeIf { it.isNotEmpty() }
    val cast = people?.map { it.name }
    return copy(
        castPeople = people,
        backgroundUrl = resp.str("getBackgroundPosterUrl"),
        logoUrl = resp.str("getLogoUrl"),
        genres = genres,
        rating = resp.rating10(),
        cast = cast,
        runtimeMinutes = resp.int("getDuration")?.takeIf { it > 0 },
        contentRating = resp.str("getContentRating"),
    )
}

fun MediaInfoEpisode.withExtras(ep: Any): MediaInfoEpisode = copy(
    description = ep.str("getDescription"),
    releasedMs = (ep.get("getDate") as? Number)?.toLong()?.takeIf { it > 0 },
)

/**
 * Anime extensions list every episode once per dub status ("Subbed", "Dubbed"), which showed
 * as duplicate episodes. Episodes with the same season/episode number become one episode whose
 * data carries all variants (VariantData); its streams come back labelled [Sub] / [Dub].
 * Lists with a single variant, and episodes without a number, are left as they were.
 */
fun mergeDubVariants(episodes: List<MediaInfoEpisode>): List<MediaInfoEpisode> {
    val variants = episodes.mapNotNull { VariantData.label(it.variant) }.distinct()
    if (variants.size < 2) return episodes
    fun plainName(e: MediaInfoEpisode): String? {
        val v = e.variant ?: return e.name
        val n = e.name ?: return null
        if (n == v) return null
        return n.removeSuffix(" ($v)").takeIf { it.isNotBlank() }
    }
    val out = ArrayList<MediaInfoEpisode>()
    val groups = LinkedHashMap<Pair<Int, Int>, MutableList<MediaInfoEpisode>>()
    for (e in episodes) {
        val num = e.episode
        if (num == null) { out += e; continue }
        groups.getOrPut((e.season ?: 1) to num) { ArrayList() } += e
    }
    // Sub before Dub, then anything else
    fun rank(e: MediaInfoEpisode) = when (VariantData.label(e.variant)) { "Sub" -> 0; "Dub" -> 1; else -> 2 }
    val merged = groups.values.map { group ->
        val sorted = group.sortedBy { rank(it) }
        val first = sorted.first()
        val parts = sorted.distinctBy { it.dataUrl }.map { (it.variant ?: "None") to it.dataUrl }
        first.copy(
            name = sorted.firstNotNullOfOrNull { plainName(it) },
            dataUrl = if (parts.size > 1) VariantData.encode(parts) else first.dataUrl,
            posterUrl = sorted.firstNotNullOfOrNull { it.posterUrl },
            description = sorted.firstNotNullOfOrNull { it.description },
            releasedMs = sorted.firstNotNullOfOrNull { it.releasedMs },
            variant = null,
        )
    }
    return (merged + out).sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
}
