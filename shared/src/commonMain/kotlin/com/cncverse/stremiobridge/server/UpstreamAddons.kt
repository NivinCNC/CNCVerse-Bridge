package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStreamResponse
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Other Stremio add-ons whose streams are merged into this bridge's results.
 * Meant for a pencarimovie-style Telegram server: it already serves its files with
 * range support, so its stream URLs are passed through untouched (no media-server proxying).
 */
@Serializable
data class UpstreamAddon(
    val name: String,
    /** Base URL, with or without a trailing /manifest.json. */
    val url: String,
    val enabled: Boolean = true,
)

object UpstreamAddons {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private val list = CopyOnWriteArrayList<UpstreamAddon>()
    @Volatile private var file: File? = null

    fun init(cacheDir: String) {
        file = File(cacheDir, "upstream_addons.json")
        list.clear()
        runCatching {
            val f = file ?: return
            if (f.exists()) list.addAll(json.decodeFromString<List<UpstreamAddon>>(f.readText()))
        }.onFailure { ServerState.warn("[Upstream] could not read saved add-ons: ${it.message}") }
    }

    fun all(): List<UpstreamAddon> = list.toList()
    fun active(): List<UpstreamAddon> = list.filter { it.enabled }

    private fun save() {
        runCatching { file?.writeText(json.encodeToString(list.toList())) }
    }

    fun base(url: String): String =
        url.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
            .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")

    fun add(name: String, url: String) {
        val b = base(url)
        if (b.isBlank()) return
        list.removeAll { base(it.url).equals(b, true) }
        list.add(UpstreamAddon(name.trim().ifBlank { runCatching { java.net.URI(b).host }.getOrNull() ?: "Upstream" }, b))
        save()
    }

    fun remove(url: String) {
        list.removeAll { base(it.url).equals(base(url), true) }
        save()
    }

    fun setEnabled(url: String, on: Boolean) {
        val i = list.indexOfFirst { base(it.url).equals(base(url), true) }
        if (i >= 0) { list[i] = list[i].copy(enabled = on); save() }
    }

    /** Fetches the manifest to check the URL is a working add-on; returns its name or an error text. */
    suspend fun test(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder().url(base(url) + "/manifest.json").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val o = json.parseToJsonElement(r.body?.string().orEmpty())
                (o as? kotlinx.serialization.json.JsonObject)?.get("name")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "Add-on"
            }
        }
    }

    suspend fun fetch(addon: UpstreamAddon, type: String, id: String): List<StremioStream> = withContext(Dispatchers.IO) {
        val enc = id.replace(":", "%3A")
        val req = okhttp3.Request.Builder().url("${base(addon.url)}/stream/$type/$enc.json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                if (r.code != 404) ServerState.warn("[Upstream] ${addon.name}: HTTP ${r.code}")
                return@use emptyList()
            }
            val body = r.body?.string().orEmpty()
            val streams = json.decodeFromString<StremioStreamResponse>(body).streams
            streams.filter { it.url != null || it.infoHash != null || it.externalUrl != null }.map { s ->
                val label = s.name ?: addon.name
                s.copy(
                    name = if (s.name.isNullOrBlank()) addon.name else s.name,
                    info = StreamInfo(
                        addonName = addon.name,
                        linkName = listOfNotNull(s.name, s.title).joinToString(" ").ifBlank { label },
                        linkType = "UPSTREAM",
                        providerName = addon.name,
                    ),
                )
            }
        }
    }
}
