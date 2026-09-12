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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

private val serverJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val httpClient by lazy {
    HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(serverJson)
        }
        try {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        } catch (e: Throwable) {}
    }
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
    private var activePort: Int = 8080
    val isRunning: Boolean get() = engine != null

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

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                true
            }
        } catch (e: Throwable) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                    true
                }
            } catch (e2: Throwable) {
                false
            }
        }
    }

    private suspend fun findAvailablePort(defaultPort: Int): Int {
        // Retry default port a few times in case an old server instance is actively shutting down
        for (attempt in 1..3) {
            if (isPortAvailable(defaultPort)) return defaultPort
            if (attempt < 3) delay(300)
        }

        // If default port is occupied by another process, scan fallback ports
        for (port in (defaultPort + 1)..(defaultPort + 50)) {
            if (isPortAvailable(port)) {
                ServerState.info("Port $defaultPort is in use, falling back to port $port")
                return port
            }
        }

        // As a last resort, ask OS for an ephemeral port
        try {
            ServerSocket(0).use { socket ->
                val randomPort = socket.localPort
                ServerState.info("Default ports in use, using dynamic port $randomPort")
                return randomPort
            }
        } catch (e: Throwable) {
            // ignore
        }

        throw BindException("No available ports found between $defaultPort and ${defaultPort + 50}")
    }

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort
        if (cacheDir != null) {
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
        }
        val targetPort = findAvailablePort(port)
        activePort = targetPort
        engine = embeddedServer(CIO, port = targetPort, host = "0.0.0.0") {
            setupPlugins()
            setupRoutes()
        }.start(wait = false)
        ServerState.serverPort = targetPort
        ServerState.info("Stremio server started on port $targetPort")

        if (ServerState.isStremioMode.value) {
            com.cncverse.stremiobridge.tunnel.CloudflaredManager.startTunnel(targetPort)
        }

        return targetPort
    }

    fun stop() {
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        engine?.stop(0, 500)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    /**
     * Toggles a plugin's enabled state and persists it. Returns the new enabled state.
     */
    fun togglePluginDisabled(internalName: String): Boolean {
        val enabled = if (disabledPlugins.contains(internalName)) {
            disabledPlugins.remove(internalName)
            true
        } else {
            disabledPlugins.add(internalName)
            false
        }
        saveDisabledPlugins()
        return enabled
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
        setupWebAdminRoutes()
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

            // ── Subtitles ────────────────────────────────────────────────────
            get("/subtitles/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                val subtitles = streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }
                
                call.respond(StremioSubtitleResponse(subtitles))
            }
        }
    }

    // 📺 Manifest builder 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺

    private suspend fun buildManifest(): StremioManifest {
        val activeApis = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
        val types = listOf("movie","series", "other", "tv")

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
            resources   = listOf("catalog", "meta", "stream", "subtitles"),
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
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (internalName, dataUrl) = decoded
            val api = loadedApis.find { it.internalName == internalName } ?: return emptyList()
            if (disabledPlugins.contains(api.internalName)) return emptyList()
            return try {
                api.loadLinks(dataUrl)
            } catch (e: Throwable) {
                ServerState.warn("Stream error for $internalName: ${e.message}")
                emptyList()
            }
        }

        // Handle generic Stremio requests with TMDB/IMDB IDs
        val baseId = id.substringBefore(":")
        val mediaType = if (type == "series") "tv" else "movie"
        val tmdbId = if (baseId.startsWith("tmdb:")) baseId.removePrefix("tmdb:") else baseId
        
        ServerState.info("Generic request: id=$id, type=$type, baseId=$baseId, tmdbId=$tmdbId")
        
        return try {
            val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
            val isImdbId = tmdbId.startsWith("tt")
            val tmdbUrl = if (isImdbId) {
                "https://api.themoviedb.org/3/find/$tmdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
            } else {
                "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"
            }
            
            ServerState.info("Fetching TMDB: $tmdbUrl")
            val responseText = httpClient.get(tmdbUrl).bodyAsText()
            val jsonObject = serverJson.parseToJsonElement(responseText).jsonObject
            
            val mediaObj = if (isImdbId) {
                val movieResults = jsonObject["movie_results"] as? kotlinx.serialization.json.JsonArray
                val tvResults = jsonObject["tv_results"] as? kotlinx.serialization.json.JsonArray
                (movieResults?.firstOrNull() ?: tvResults?.firstOrNull())?.jsonObject
            } else {
                jsonObject
            }
            
            if (mediaObj == null) {
                ServerState.warn("TMDB resolve failed: no media found for $tmdbId")
                return emptyList()
            }

            val title = mediaObj["title"]?.jsonPrimitive?.content 
                ?: mediaObj["name"]?.jsonPrimitive?.content
                
            if (title == null) {
                ServerState.warn("TMDB resolve failed: no title found")
                return emptyList()
            }
                
            val year = mediaObj["release_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull() 
                ?: mediaObj["first_air_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()

            ServerState.info("TMDB resolve success: title='$title', year=$year")

            val allStreams = mutableListOf<StremioStream>()
            val activePlugins = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
            ServerState.info("Searching across ${activePlugins.size} plugins...")
            
            coroutineScope {
                activePlugins.map { api ->
                    async {
                        try {
                            ServerState.info("[${api.name}] Searching for '$title'")
                            val searchResults = api.search(title)
                            ServerState.info("[${api.name}] Found ${searchResults.size} results")
                            
                            val bestMatch = searchResults.find { it.name.equals(title, ignoreCase = true) && (year == null || it.year == null || it.year == year) } 
                                ?: searchResults.firstOrNull { it.name.contains(title, ignoreCase = true) }
                                ?: searchResults.firstOrNull()

                            if (bestMatch != null) {
                                ServerState.info("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
                                val mediaInfo = api.load(bestMatch.url)
                                if (mediaInfo != null) {
                                    var dataUrlToLoad = mediaInfo.dataUrl
                                    if (type == "series" && id.contains(":")) {
                                        val parts = id.split(":")
                                        val season = parts.getOrNull(1)?.toIntOrNull()
                                        val episode = parts.getOrNull(2)?.toIntOrNull()
                                        if (season != null && episode != null) {
                                            val ep = mediaInfo.episodes?.find { it.season == season && it.episode == episode }
                                            if (ep != null) {
                                                dataUrlToLoad = ep.dataUrl
                                                ServerState.info("[${api.name}] Found episode S${season}E${episode}")
                                            } else {
                                                ServerState.warn("[${api.name}] Episode S${season}E${episode} not found in mediaInfo")
                                                return@async emptyList<StremioStream>()
                                            }
                                        }
                                    }
                                    ServerState.info("[${api.name}] Loading links for $dataUrlToLoad")
                                    val links = api.loadLinks(dataUrlToLoad)
                                    ServerState.info("[${api.name}] Found ${links.size} streams")
                                    links.map { stream ->
                                        val newName = "${bestMatch.name}" + (if (!stream.name.isNullOrBlank()) "\n${stream.name}" else "")
                                        stream.copy(name = newName)
                                    }
                                } else {
                                    ServerState.warn("[${api.name}] MediaInfo load failed for ${bestMatch.url}")
                                    emptyList()
                                }
                            } else {
                                ServerState.info("[${api.name}] No matching search result")
                                emptyList()
                            }
                        } catch (e: Exception) {
                            ServerState.warn("Search/load error in ${api.name}: ${e.message}")
                            emptyList<StremioStream>()
                        }
                    }
                }.awaitAll().forEach { allStreams.addAll(it) }
            }
            ServerState.info("Returning total ${allStreams.size} streams")
            allStreams
        } catch (e: Exception) {
            ServerState.warn("TMDB resolve error for $id: ${e.stackTraceToString()}")
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
<title>CNCVerse Bridge</title>
<style>
  body{background:#0f0f1a;color:#e0e0f0;font-family:sans-serif;padding:2rem}
  h1{color:#a78bfa}
  table{border-collapse:collapse;width:100%;margin-top:1rem}
  th,td{padding:.5rem 1rem;border:1px solid #2a2a4a;text-align:left}
  th{background:#1a1a2e}
  .btn{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;background:#7c3aed;color:#fff;border-radius:.5rem;text-decoration:none;font-weight:bold}
</style></head><body>
<h1>&#127916; CNCVerse Bridge</h1>
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

/**
 * Platform hook for the web admin panel routes. Mounted inside the main addon
 * server engine when enabled (desktop with CNC_WEB_ADMIN=1, always on in the
 * headless server app); a no-op on Android.
 */
expect fun Application.setupWebAdminRoutes()

