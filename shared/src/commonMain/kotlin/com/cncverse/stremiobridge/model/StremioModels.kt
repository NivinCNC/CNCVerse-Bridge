@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.cncverse.stremiobridge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ────────────────────────────────────────────────────────────────────────────
//  Stremio Addon Protocol data classes
//  Spec: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class StremioManifest(
    @SerialName("id")          val id: String,
    @SerialName("version")     val version: String,
    @SerialName("name")        val name: String,
    @SerialName("description") val description: String,
    @SerialName("logo")        val logo: String? = null,
    @SerialName("types")       val types: List<String>,
    @SerialName("resources")   val resources: List<String>,
    @SerialName("catalogs")    val catalogs: List<StremioCatalogDef>,
    @SerialName("behaviorHints") val behaviorHints: BehaviorHints = BehaviorHints(),
    /** Ids Stremio sends for meta/stream/subtitles: the bridge's own "cnc:" items plus other catalogs' ids. */
    @SerialName("idPrefixes")  val idPrefixes: List<String> = com.cncverse.stremiobridge.server.ExternalIds.MANIFEST_PREFIXES,
)

@Serializable
data class BehaviorHints(
    @SerialName("configurable")      val configurable: Boolean = false,
    @SerialName("adult")             val adult: Boolean = false,
)

@Serializable
data class StremioCatalogDef(
    @SerialName("type") val type: String,
    @SerialName("id")   val id: String,
    @SerialName("name") val name: String,
    @SerialName("extra") val extra: List<ExtraEntry> = emptyList(),
)

@Serializable
data class ExtraEntry(
    @SerialName("name")       val name: String,
    @SerialName("isRequired") val isRequired: Boolean = false,
    @SerialName("options")    val options: List<String>? = null,
)

// ─── Catalog Response ────────────────────────────────────────────────────────

@Serializable
data class StremioCatalogResponse(
    @SerialName("metas") val metas: List<StremioMeta>,
)

@Serializable
data class StremioMeta(
    /** Format: "cnc:{base64(pluginInternalName::dataUrl)}" */
    @SerialName("id")          val id: String,
    @SerialName("type")        val type: String,
    @SerialName("name")        val name: String,
    @SerialName("poster")      val poster: String? = null,
    @SerialName("posterShape") val posterShape: String = "poster",
    @SerialName("background")  val background: String? = null,
    @SerialName("logo")        val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    @SerialName("year")        val year: Int? = null,
    @SerialName("imdbRating")  val imdbRating: String? = null,
    @SerialName("runtime")     val runtime: String? = null,
    /** Stremio apps read cast photos from here (Cinemeta does the same); plain `cast` is names only. */
    @SerialName("app_extras")  val appExtras: AppExtras? = null,
    @SerialName("genres")      val genres: List<String>? = null,
    @SerialName("cast")        val cast: List<String>? = null,
    @SerialName("links")       val links: List<MetaLink>? = null,
    @SerialName("videos")      val videos: List<StremioVideo>? = null,
    @SerialName("behaviorHints") val behaviorHints: MetaBehaviorHints? = null,
)

@Serializable
data class AppExtras(@SerialName("cast") val cast: List<AppCast>? = null)

@Serializable
data class AppCast(
    @SerialName("name")      val name: String,
    @SerialName("character") val character: String? = null,
    @SerialName("photo")     val photo: String? = null,
)

@Serializable
data class MetaBehaviorHints(
    @SerialName("defaultVideoId") val defaultVideoId: String? = null,
)

@Serializable
data class StremioVideo(
    @SerialName("id")       val id: String,
    @SerialName("title")    val title: String,
    @SerialName("released") val released: String? = null,
    @SerialName("season")   val season: Int? = null,
    @SerialName("episode")  val episode: Int? = null,
    @SerialName("thumbnail")val thumbnail: String? = null,
    @SerialName("overview") val overview: String? = null
)

@Serializable
data class MetaLink(
    @SerialName("name")     val name: String,
    @SerialName("category") val category: String,
    @SerialName("url")      val url: String,
)

// ─── Meta Response ───────────────────────────────────────────────────────────

@Serializable
data class StremioMetaResponse(
    @SerialName("meta") val meta: StremioMeta,
)

// ─── Stream Response ─────────────────────────────────────────────────────────

@Serializable
data class StremioStreamResponse(
    @SerialName("streams") val streams: List<StremioStream>,
)

@Serializable
data class StremioStream(
    @SerialName("title")         val title: String? = null,
    @SerialName("name")          val name: String? = null,
    @SerialName("url")           val url: String? = null,
    @SerialName("ytId")          val ytId: String? = null,
    @SerialName("infoHash")      val infoHash: String? = null,
    @SerialName("behaviorHints") val behaviorHints: StreamBehaviorHints? = null,
    @SerialName("clearkey")      val clearkey: String? = null,
    @SerialName("subtitles")     val subtitles: List<StremioSubtitle>? = null,
    /** Opens a web page instead of playing (the "support the project" entry). */
    @SerialName("externalUrl")   val externalUrl: String? = null,
    /** Server-side metadata for the stream formatter — never sent to Stremio. */
    @kotlinx.serialization.Transient val info: StreamInfo? = null,
)

/**
 * What we know about a stream beyond its display strings: the extension that
 * produced it, the raw CloudStream link fields and the resolved title. Feeds
 * the stream formatter's template variables.
 */
data class StreamInfo(
    /** Extension (addon) name, e.g. "Netflix". */
    val addonName: String? = null,
    /** CloudStream quality value (e.g. 1080), null when unknown. */
    val quality: Int? = null,
    /** CloudStream ExtractorLinkType name: M3U8, DASH, VIDEO, TORRENT, MAGNET. */
    val linkType: String? = null,
    /** Raw link name from the extension (often a release name). */
    val linkName: String? = null,
    /** Extractor/source name (ExtractorLink.source). */
    val source: String? = null,
    /** Title/year the stream was resolved for (TMDB for generic requests). */
    val metadataTitle: String? = null,
    val metadataYear: Int? = null,
    /** Display name of the extension (MainAPI.name, e.g. "4K HDHUB") - what the configure page lists. */
    val providerName: String? = null,
)

@Serializable
data class StremioSubtitle(
    @SerialName("id")   val id: String,
    @SerialName("lang") val lang: String,
    @SerialName("url")  val url: String,
)

@Serializable
data class StremioSubtitleResponse(
    @SerialName("subtitles") val subtitles: List<StremioSubtitle>,
)

@Serializable
data class StreamBehaviorHints(
    @SerialName("bingeGroup")     val bingeGroup: String? = null,
    @SerialName("notWebReady")    val notWebReady: Boolean? = null,
    @SerialName("proxyHeaders")   val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(
    @SerialName("request") val request: Map<String, String>? = null,
)

// ─── ID helpers ──────────────────────────────────────────────────────────────

object StremioIds {
    /** Encodes "pluginInternalName::dataUrl" → base64url → "cnc:{b64}" */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun encode(pluginInternalName: String, dataUrl: String): String {
        val raw = "$pluginInternalName::$dataUrl"
        return "cnc:${kotlin.io.encoding.Base64.UrlSafe.encode(raw.encodeToByteArray())}"
    }

    /** Decodes "cnc:{b64}" → Pair(pluginInternalName, dataUrl), or null if invalid */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun decode(id: String): Pair<String, String>? {
        if (!id.startsWith("cnc:")) return null
        return try {
            val raw = kotlin.io.encoding.Base64.UrlSafe.decode(id.removePrefix("cnc:")).decodeToString()
            val parts = raw.split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1]) else null
        } catch (_: Exception) {
            null
        }
    }
}

/** Maps CS3 TvType names → Stremio type strings */
fun cs3TvTypeToStremio(tvType: String): String = when (tvType.lowercase()) {
    "live", "tv"                               -> "tv"
    else                                       -> "other"
}
