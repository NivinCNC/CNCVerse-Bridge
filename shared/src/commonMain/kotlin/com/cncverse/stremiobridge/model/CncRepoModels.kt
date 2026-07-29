package com.cncverse.stremiobridge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level CNC.json structure — mirrors CloudStream3's [Repository] exactly.
 */
@Serializable
data class CncRepository(
    @SerialName("name")        val name: String,
    @SerialName("iconUrl")     val iconUrl: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("manifestVersion") val manifestVersion: Int = 1,
    @SerialName("pluginLists") val pluginLists: List<String> = emptyList(),
)

/**
 * Individual plugin entry inside each pluginList URL — mirrors CloudStream3's [SitePlugin].
 *
 * Status values:
 *  0 = Down, 1 = OK, 2 = Slow, 3 = Beta-only
 */
@Serializable
data class SitePlugin(
    /** Direct URL to the .cs3 (DEX) file */
    @SerialName("url")           val url: String,
    @SerialName("status")        val status: Int = 1,
    @SerialName("version")       val version: Int = 1,
    @SerialName("apiVersion")    val apiVersion: Int = 1,
    @SerialName("name")          val name: String,
    @SerialName("internalName")  val internalName: String,
    @SerialName("authors")       val authors: List<String> = emptyList(),
    @SerialName("description")   val description: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    @SerialName("tvTypes")       val tvTypes: List<String>? = null,
    @SerialName("language")      val language: String? = null,
    @SerialName("iconUrl")       val iconUrl: String? = null,
    @SerialName("fileSize")      val fileSize: Long? = null,
    @SerialName("fileHash")      val fileHash: String? = null,
)

/**
 * Represents a plugin paired with its source repository data.
 */
data class PluginWrapper(
    val repository: CncRepository,
    val plugin: SitePlugin,
)

/** Status values matching CloudStream constants */
const val PROVIDER_STATUS_DOWN = 0
const val PROVIDER_STATUS_OK = 1
const val PROVIDER_STATUS_SLOW = 2
const val PROVIDER_STATUS_BETA_ONLY = 3

/** Convenience extension to convert a [SitePlugin] to a [LoadedPluginInfo] display model. */
fun SitePlugin.toLoadedPluginInfo(apiRegistered: Boolean = false, hasSettings: Boolean = false) =
    com.cncverse.stremiobridge.state.LoadedPluginInfo(
        internalName  = internalName,
        displayName   = name,
        iconUrl       = iconUrl,
        tvTypes       = tvTypes ?: emptyList(),
        language      = language,
        status        = status,
        hasSettings   = hasSettings,
        apiRegistered = apiRegistered,
    )
