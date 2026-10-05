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
    val cast = (resp.get("getActors") as? List<*>)?.mapNotNull { a ->
        a?.get("getActor")?.str("getName")
    }?.distinct()?.take(40)?.takeIf { it.isNotEmpty() }
    return copy(
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
