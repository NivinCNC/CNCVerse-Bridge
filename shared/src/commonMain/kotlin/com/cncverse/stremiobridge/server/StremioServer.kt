package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

private val serverJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Manages the Ktor-based embedded HTTP server exposing the Stremio addon protocol.
 *
 * Routes:
 *  GET /manifest.json
 *  GET /catalog/{type}/{id}.json[?search=query]
 *  GET /meta/{type}/{id}.json
 *  GET /stream/{type}/{id}.json
 *  GET /                         (status HTML page)
 */
object StremioServer {

    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Holds live references to loaded [MainApiWrapper] instances.
     * Populated by the platform-specific [PluginLoader] after loading.
     */
    val loadedApis: MutableList<MainApiWrapper> = mutableListOf()

    val disabledPlugins: MutableSet<String> = mutableSetOf()
    private var disabledPluginsFile: File? = null

    private fun loadDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                disabledPlugins.clear()
                disabledPlugins.addAll(serverJson.decodeFromString<Set<String>>(json))
            } catch (e: Exception) {
                ServerState.warn("Failed to load disabled plugins: ${e.message}")
            }
        }
    }

    private fun saveDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        try {
            val json = serverJson.encodeToString(disabledPlugins)
            file.writeText(json)
        } catch (e: Exception) {
            ServerState.warn("Failed to save disabled plugins: ${e.message}")
        }
    }

    fun start(port: Int, cacheDir: String? = null) {
        if (engine != null) return
        if (cacheDir != null) {
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
        }
        engine = embeddedServer(CIO, port = port) {
            setupPlugins()
            setupRoutes()
        }.start(wait = false)
        ServerState.info("Stremio server started on port $port")
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    private fun Application.setupPlugins() {
        install(ContentNegotiation) { json(serverJson) }
        
        // Stremio Web (and sometimes Desktop) can be very strict or send 'Origin: null'. 
        // Manually appending these headers ensures maximum compatibility across all Stremio clients.
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
            call.response.header("Access-Control-Allow-Origin", "*")
            call.response.header("Access-Control-Allow-Headers", "*")
            
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                return@intercept // End pipeline for OPTIONS
            }
        }
    }

    private fun Application.setupRoutes() {
        setupMpdProxyRoutes()
        routing {
            // 📺 Status Page 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺
            get("/") {
                call.respondText(buildStatusHtml(), ContentType.Text.Html)
            }

            get("/api/toggle-plugin") {
                val id = call.request.queryParameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (disabledPlugins.contains(id)) {
                    disabledPlugins.remove(id)
                } else {
                    disabledPlugins.add(id)
                }
                saveDisabledPlugins()
                call.respondRedirect("/")
            }

            // 📺 Manifest 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺─────────────────────────────────────────────────────
            get("/manifest.json") {
                call.respond(buildManifest())
            }

            // 📺 Catalog 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺
            get("/catalog/{path...}") {
                val pathSegments = call.parameters.getAll("path") ?: emptyList()
                if (pathSegments.size < 2) return@get call.respond(HttpStatusCode.BadRequest)
                
                val type = pathSegments[0]
                
                if (pathSegments.size == 2) {
                    val idWithExt = pathSegments[1]
                    if (!idWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    
                    val id = idWithExt.removeSuffix(".json")
                    val search = call.request.queryParameters["search"]
                    val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, null) }
                    call.respond(StremioCatalogResponse(metas))
                } else if (pathSegments.size == 3) {
                    val id = pathSegments[1]
                    val extraWithExt = pathSegments[2]
                    if (!extraWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    
                    val extraStr = extraWithExt.removeSuffix(".json")
                    val parsedExtra = io.ktor.http.parseQueryString(extraStr)
                    
                    val search = parsedExtra["search"] ?: call.request.queryParameters["search"]
                    val skip = (parsedExtra["skip"] ?: call.request.queryParameters["skip"])?.toIntOrNull() ?: 0
                    val genre = parsedExtra["genre"]

                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, genre) }
                    call.respond(StremioCatalogResponse(metas))
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }

            // ── Meta ─────────────────────────────────────────────────────────
            get("/meta/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val meta = withContext(Dispatchers.IO) { buildMeta(type, id) }
                if (meta != null) {
                    call.respond(StremioMetaResponse(meta))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // ── Stream ───────────────────────────────────────────────────────
            get("/stream/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                call.respond(StremioStreamResponse(streams))
            }
        }
    }

    // 📺 Manifest builder 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺

    private suspend fun buildManifest(): StremioManifest {
        val activeApis = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
        val types = listOf("other", "tv")

        val catalogs = activeApis.flatMap { api ->
            api.supportedTypes
                .map { cs3TvTypeToStremio(it) }
                .distinct()
                .flatMap { stremioType ->
                    val extra = mutableListOf<ExtraEntry>()
                    val sections = api.getMainPageSections()
                    if (sections.isNotEmpty() && (sections.size > 1 || sections.first().isNotBlank())) {
                        extra.add(ExtraEntry(name = "genre", options = sections))
                    }
                    extra.add(ExtraEntry("search"))
                    extra.add(ExtraEntry("skip"))

                    listOf(
                        StremioCatalogDef(
                            type = stremioType,
                            id   = "cnc_${api.internalName}_$stremioType",
                            name = "${api.name} ($stremioType)",
                            extra = extra
                        )
                    )
                }
        }.distinctBy { it.id }
            .ifEmpty {
                listOf(StremioCatalogDef("movie", "cnc_all_movie", "CNCVerse (Movie)"))
            }

        return StremioManifest(
            id          = "com.cncverse.stremiobridge",
            version     = "1.0.0",
            name        = "CNCVerse Bridge",
            description = "CS3 plugin bridge for Stremio — powered by CNCVerse extensions",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png",
            types       = types,
            resources   = listOf("catalog", "meta", "stream"),
            catalogs    = catalogs,
        )
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)
        
        val api = loadedApis.find { rest.startsWith(it.internalName) }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (disabledPlugins.contains(api.internalName)) return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                val results = api.search(search)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    // ── Meta builder ──────────────────────────────────────────────────────────

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (internalName, dataUrl) = StremioIds.decode(id) ?: return null
        val api = loadedApis.find { it.internalName == internalName } ?: return null
        if (disabledPlugins.contains(api.internalName)) return null
        return try {
            api.load(dataUrl)?.toStremiMeta(api.internalName, type)
        } catch (e: Throwable) {
            ServerState.warn("Meta error for $internalName: ${e.message}")
            null
        }
    }

    // ── Stream builder ────────────────────────────────────────────────────────

    private suspend fun buildStreams(type: String, id: String): List<StremioStream> {
        val (internalName, dataUrl) = StremioIds.decode(id) ?: return emptyList()
        val api = loadedApis.find { it.internalName == internalName } ?: return emptyList()
        if (disabledPlugins.contains(api.internalName)) return emptyList()
        return try {
            api.loadLinks(dataUrl)
        } catch (e: Throwable) {
            ServerState.warn("Stream error for $internalName: ${e.message}")
            emptyList()
        }
    }

    // ── HTML status page ──────────────────────────────────────────────────────

    private fun buildStatusHtml(): String {
        val pluginRows = loadedApis.joinToString("") { api ->
            val isEnabled = !disabledPlugins.contains(api.internalName)
            val statusHtml = if (isEnabled) "<td style=\"color:#4ade80\">&#9679; Enabled</td>" else "<td style=\"color:#f87171\">&#9679; Disabled</td>"
            val actionText = if (isEnabled) "Disable" else "Enable"
            """<tr>
               <td>${api.name}</td>
               <td>${api.internalName}</td>
               <td>${api.supportedTypes.joinToString(", ")}</td>
               $statusHtml
               <td><a class="btn" style="padding:0.25rem 0.75rem;margin:0;font-size:0.9rem;" href="/api/toggle-plugin?id=${api.internalName}">$actionText</a></td>
             </tr>"""
        }
        return """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
<title>CNCVerse Stremio Bridge</title>
<style>
  body{background:#0f0f1a;color:#e0e0f0;font-family:sans-serif;padding:2rem}
  h1{color:#a78bfa}
  table{border-collapse:collapse;width:100%;margin-top:1rem}
  th,td{padding:.5rem 1rem;border:1px solid #2a2a4a;text-align:left}
  th{background:#1a1a2e}
  .btn{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;background:#7c3aed;color:#fff;border-radius:.5rem;text-decoration:none;font-weight:bold}
</style></head><body>
<h1>&#127916; CNCVerse Stremio Bridge</h1>
<p>Loaded plugins: <strong>${loadedApis.size}</strong></p>
<a class="btn" href="stremio://localhost:${ServerState.serverPort}/manifest.json">&#9654; Add to Stremio</a>
<table><thead><tr><th>Name</th><th>Internal</th><th>Types</th><th>Status</th><th>Action</th></tr></thead>
<tbody>$pluginRows</tbody></table>
</body></html>"""
    }
}

// ── MainApiWrapper ─────────────────────────────────────────────────────────────

/**
 * Platform-agnostic wrapper around a loaded CS3 MainAPI instance.
 * Implemented by each platform's PluginLoader actual.
 */
interface MainApiWrapper {
    val name: String
    val internalName: String
    val supportedTypes: List<String>
    suspend fun getMainPageSections(): List<String>

    suspend fun search(query: String): List<SearchResult>
    suspend fun getMainPage(page: Int, type: String, sectionName: String? = null): List<SearchResult>
    suspend fun load(url: String): MediaInfo?
    suspend fun loadLinks(dataUrl: String): List<StremioStream>
}

data class SearchResult(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val year: Int?,
    /** True when the HomePageList had isHorizontalImages = true */
    val isHorizontal: Boolean = false,
    /** The HomePageList section name — forwarded as genres in Stremio */
    val sectionName: String? = null,
)

data class MediaInfoEpisode(
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val dataUrl: String,
    val posterUrl: String?
)

data class MediaInfo(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val description: String?,
    val year: Int?,
    val dataUrl: String,
    val episodes: List<MediaInfoEpisode>? = null
)

fun SearchResult.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val encodedId = StremioIds.encode(pluginInternalName, url)
    val resolvedType = cs3TvTypeToStremio(type)
    return StremioMeta(
        id          = encodedId,
        type        = resolvedType,
        name        = name,
        poster      = posterUrl,
        background  = if (isHorizontal) posterUrl else null,
        posterShape = if (isHorizontal) "landscape" else "poster",
        genres      = null,
        year        = year,
        // For TV/live items, set defaultVideoId so Stremio can auto-play without extra navigation
        behaviorHints = if (resolvedType == "tv" || isHorizontal) {
            MetaBehaviorHints(defaultVideoId = encodedId)
        } else null,
    )
}

fun MediaInfo.toStremiMeta(pluginInternalName: String, stremioType: String) = StremioMeta(
    id          = StremioIds.encode(pluginInternalName, dataUrl),
    type        = cs3TvTypeToStremio(type),
    name        = name,
    poster      = posterUrl,
    description = description,
    year        = year,
    videos      = episodes?.map { ep ->
        StremioVideo(
            id       = StremioIds.encode(pluginInternalName, ep.dataUrl),
            title    = ep.name ?: "Episode ${ep.episode}",
            season   = ep.season ?: 1,
            episode  = ep.episode ?: 1,
            thumbnail= ep.posterUrl ?: posterUrl
        )
    }
)

expect fun Application.setupMpdProxyRoutes()

