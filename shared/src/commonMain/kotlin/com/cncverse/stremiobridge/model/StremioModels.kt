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
    @SerialName("genres")      val genres: List<String>? = null,
    @SerialName("cast")        val cast: List<String>? = null,
    @SerialName("links")       val links: List<MetaLink>? = null,
    @SerialName("videos")      val videos: List<StremioVideo>? = null,
    @SerialName("behaviorHints") val behaviorHints: MetaBehaviorHints? = null,
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
    @SerialName("clearkey") val clearkey: String? = null,
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
