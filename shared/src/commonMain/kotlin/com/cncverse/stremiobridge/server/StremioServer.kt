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

    /**
     * Per-profile disabled-extensions map. Key = profile UUID (cookie),
     * Value = set of internalNames that profile has disabled.
     * Stored in-memory only — survives server restart within a session.
     * Profiles are lightweight: globally-installed extensions stay installed;
     * only their manifest entry is omitted when the profile disables them.
     */
    val profileDisabledPlugins: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private var profilesFile: File? = null

    private fun loadProfiles() {
        val file = profilesFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                val map = serverJson.decodeFromString<Map<String, Set<String>>>(json)
                profileDisabledPlugins.clear()
                map.forEach { (k, v) -> profileDisabledPlugins[k] = v.toMutableSet() }
            } catch (e: Exception) {
                ServerState.warn("Failed to load profiles: ${e.message}")
            }
        }
    }

    private fun saveProfiles() {
        val file = profilesFile ?: return
        try {
            val map: Map<String, Set<String>> = profileDisabledPlugins
            file.writeText(serverJson.encodeToString(map))
        } catch (e: Exception) {
            ServerState.warn("Failed to save profiles: ${e.message}")
        }
    }

    fun getProfileDisabled(profileId: String): Set<String> =
        profileDisabledPlugins[profileId] ?: emptySet()

    fun toggleProfilePlugin(profileId: String, internalName: String): Boolean {
        val set = profileDisabledPlugins.getOrPut(profileId) { mutableSetOf() }
        val nowEnabled = if (set.contains(internalName)) {
            set.remove(internalName)
            true
        } else {
            set.add(internalName)
            false
        }
        saveProfiles()
        return nowEnabled
    }

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
            profilesFile = File(cacheDir, "profiles.json")
            loadProfiles()
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

            // Track the public base URL from the Host header so proxy/admin URLs
            // reflect the domain the client is connecting through (not the LAN IP).
            val reqHost = call.request.host()
            val reqPort = call.request.port()
            val inferredBase = if (reqPort > 0 && reqPort != 80 && reqPort != 443) {
                "http://$reqHost:$reqPort"
            } else {
                "http://$reqHost"
            }
            ServerState.publicBaseUrl = inferredBase
            
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
            // 📺 User-facing index page 📺
            get("/") {
                call.respondText(buildIndexHtml(), ContentType.Text.Html)
            }

            // ── User profile API (no auth — served from main server for all users) ──

            // Returns the profile's disabled-extension set
            get("/api/profile/{profileId}") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val disabled = getProfileDisabled(profileId)
                val disabledJson = disabled.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                call.respondText(
                    """{"profileId":"$profileId","disabledExtensions":[$disabledJson]}""",
                    ContentType.Application.Json
                )
            }

            // Toggle an extension on/off for this profile
            post("/api/profile/{profileId}/toggle") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { emptyMap() }
                val internalName = body["internalName"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val nowEnabled = toggleProfilePlugin(profileId, internalName)
                val disabled = getProfileDisabled(profileId)
                val disabledJson = disabled.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                call.respondText(
                    """{"profileId":"$profileId","disabledExtensions":[$disabledJson],"nowEnabled":$nowEnabled}""",
                    ContentType.Application.Json
                )
            }

            // Returns loaded extensions for the user page
            get("/api/extensions") {
                val sb = StringBuilder("[")
                loadedApis.forEachIndexed { i, api ->
                    if (i > 0) sb.append(",")
                    val name = api.name.replace("\"", "\\\"")
                    val id = nameSlug(api.name).replace("\"", "\\\"")
                    val repoUrl = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                        .find { it.internalName == api.pluginInternalName }?.repoUrl?.replace("\"", "\\\"") ?: ""
                    val repoName = com.cncverse.stremiobridge.state.RepoState.repos.value
                        .find { it.url == repoUrl }?.name?.replace("\"", "\\\"") ?: ""
                    val iconUrl = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                        .find { it.internalName == api.internalName }?.iconUrl?.replace("\"", "\\\"") ?: ""
                    val enabled = !disabledPlugins.contains(nameSlug(api.name)) && !disabledPlugins.contains(api.internalName)
                    sb.append("""{"internalName":"$id","name":"$name","enabled":$enabled,"repoUrl":"$repoUrl","repoName":"$repoName","iconUrl":"$iconUrl"}""")
                }
                sb.append("]")
                call.respondText(sb.toString(), ContentType.Application.Json)
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

            // ── Profile manifests — per-session extension disable ─────────────
            // The profile UUID lives in the browser as localStorage; the manifest
            // it receives only includes extensions not disabled for that profile.
            get("/u/{profileId}/manifest.json") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(buildManifest(profileId = profileId))
            }
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

    /**
     * Converts a plugin display-name to a short alphanumeric slug for use in catalog IDs.
     * Normalises special characters so JIO TV (IND) → JIOTVIND and
     * JIO TV+ (IND) → JIOTVPlusIND, giving each variant a unique catalog ID
     * even when two APIs share the same [internalName].
     */
    private fun nameSlug(name: String): String = name
        .replace("+", "Plus")
        .replace("&", "And")
        .replace(Regex("[^a-zA-Z0-9]"), "")
        .take(48)
        .ifBlank { "unknown" }

    /**
     * Builds the Stremio manifest.
     * @param profileId If non-null, also excludes extensions the profile has disabled.
     */
    suspend fun buildManifest(profileId: String? = null): StremioManifest {
        val profileDisabled = if (profileId != null) getProfileDisabled(profileId) else emptySet()
        val activeApis = loadedApis.filter {
            !disabledPlugins.contains(nameSlug(it.name)) && !disabledPlugins.contains(it.internalName) &&
            !profileDisabled.contains(nameSlug(it.name)) && !profileDisabled.contains(it.internalName)
        }
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
                            id   = "cnc_${nameSlug(api.name)}_$stremioType",
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
        
        // Catalog id format: "cnc_{nameSlug(api.name)}_{type}"
        // Find the API by matching the same slug derived from its display name.
        // Also falls back to internalName-based lookup for any old-format IDs still in circulation.
        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }          // old-format compat
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }    // prefix fallback
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (disabledPlugins.contains(nameSlug(api.name)) || disabledPlugins.contains(api.internalName)) return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                val results = api.search(search)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Pre-warms every plugin's home page by calling [buildCatalog] (→ [getMainPage])
     * for each catalog defined in the manifest.  Called once after server startup and
     * then every hour after the extension refresh, so Stremio users always see
     * instant home pages with fresh content.
     */
    suspend fun preWarmHomepages() {
        val catalogs = buildManifest().catalogs
        if (catalogs.isEmpty()) return
        ServerState.info("🔥 Pre-warming ${catalogs.size} home page(s)…")
        coroutineScope {
            catalogs.map { cat ->
                async(Dispatchers.IO) {
                    runCatching {
                        buildCatalog(cat.type, cat.id, null, 0, null)
                        ServerState.info("🔥 Pre-warmed: ${cat.name}")
                    }.onFailure { e ->
                        ServerState.warn("🔥 Pre-warm failed for ${cat.name}: ${e.message?.take(80)}")
                    }
                }
            }.awaitAll()
        }
        ServerState.info("🔥 Pre-warm complete (${catalogs.size} catalog(s))")
    }


    // ── Meta builder ──────────────────────────────────────────────────────────

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (pluginKey, dataUrl) = StremioIds.decode(id) ?: return null
        val api = loadedApis.find { nameSlug(it.name) == pluginKey }
            ?: loadedApis.find { it.internalName == pluginKey }
            ?: return null
        if (disabledPlugins.contains(nameSlug(api.name)) || disabledPlugins.contains(api.internalName)) return null
        return try {
            api.load(dataUrl)?.toStremiMeta(nameSlug(api.name), type)
        } catch (e: Throwable) {
            ServerState.warn("Meta error for ${api.name}: ${e.message}")
            null
        }
    }


    private suspend fun buildStreams(type: String, id: String): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val api = loadedApis.find { nameSlug(it.name) == pluginKey }
                ?: loadedApis.find { it.internalName == pluginKey }
                ?: return emptyList()
            if (disabledPlugins.contains(nameSlug(api.name)) || disabledPlugins.contains(api.internalName)) return emptyList()
            return try {
                api.loadLinks(dataUrl)
            } catch (e: Throwable) {
                ServerState.warn("Stream error for ${api.name}: ${e.message}")
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


    // ── User-facing index page ─────────────────────────────────────────────────

    private fun buildIndexHtml(): String {
        val port = ServerState.serverPort
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CNCVerse Bridge</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
:root{--bg:#03030a;--card:#0e0e1a;--card2:#14141f;--border:#1e1e2e;--v:#7c3aed;--v4:#a78bfa;--v3:#c4b5fd;--vg:rgba(124,58,237,.18);--vb:rgba(124,58,237,.35);--t:#f0f0ff;--t2:#9b9bba;--mu:#5c5c7a;--green:#4ade80;--red:#f87171}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:var(--bg);color:var(--t);min-height:100vh;font-family:'Inter',-apple-system,sans-serif;font-size:14px;line-height:1.5;-webkit-font-smoothing:antialiased}
.sw{position:relative;display:inline-block;width:42px;height:23px;flex:0 0 auto}
.sw input{opacity:0;width:0;height:0}
.sw .sl{position:absolute;inset:0;background:var(--card2);border:1px solid var(--border);border-radius:999px;transition:.2s;cursor:pointer}
.sw .sl:before{content:"";position:absolute;height:17px;width:17px;left:2px;top:2px;background:var(--mu);border-radius:50%;transition:.2s}
.sw input:checked+.sl{background:var(--v);border-color:var(--v)}
.sw input:checked+.sl:before{transform:translateX(19px);background:#fff}
.hero{text-align:center;padding:44px 20px 28px;background:radial-gradient(ellipse 80% 50% at 50% -10%,rgba(124,58,237,.22),transparent)}
.logo{width:66px;height:66px;border-radius:20px;background:linear-gradient(135deg,#6d28d9,#a78bfa);display:flex;align-items:center;justify-content:center;font-weight:900;font-size:19px;color:#fff;margin:0 auto 14px;box-shadow:0 0 36px rgba(124,58,237,.4)}
.hero h1{font-size:clamp(20px,5vw,30px);font-weight:800;letter-spacing:-.5px;background:linear-gradient(135deg,#fff 40%,#a78bfa);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.hero p{color:var(--t2);margin-top:7px;font-size:13.5px;max-width:480px;margin-left:auto;margin-right:auto}
.tabs{display:flex;gap:5px;justify-content:center;margin-top:20px;flex-wrap:wrap}
.tab{font-size:12.5px;font-weight:600;padding:7px 18px;border-radius:999px;border:1px solid var(--border);color:var(--t2);background:var(--card2);cursor:pointer;transition:.15s}
.tab:hover{border-color:var(--v);color:var(--t)}
.tab.active{background:var(--vg);border-color:var(--vb);color:var(--v3)}
main{max-width:820px;margin:0 auto;padding:18px 14px 60px}
.stl{font-size:10px;font-weight:800;letter-spacing:1.5px;text-transform:uppercase;color:var(--mu);margin-bottom:8px;margin-top:18px;padding-bottom:5px;border-bottom:1px solid rgba(124,58,237,.08)}
.card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px 18px;margin-bottom:10px}
.card h2{font-size:14.5px;font-weight:700;margin-bottom:3px}
.hint{font-size:12px;color:var(--t2);margin-bottom:10px}
.urlbox{display:flex;align-items:center;gap:8px;background:#050510;border:1px solid var(--vb);border-radius:11px;padding:9px 12px;margin:9px 0}
.urlbox code{font-family:ui-monospace,Menlo,monospace;font-size:11.5px;color:var(--v3);word-break:break-all;flex:1}
.cbtn{background:rgba(124,58,237,.14);border:1px solid var(--vb);border-radius:7px;color:var(--v4);padding:4px 11px;font-size:11.5px;font-weight:600;cursor:pointer;flex:0 0 auto;font:inherit}
.cbtn:hover{background:rgba(124,58,237,.26)}
.openbtn{display:inline-flex;align-items:center;gap:5px;font-size:12px;font-weight:600;padding:6px 14px;border-radius:999px;border:1px solid var(--vb);color:var(--v4);background:var(--vg);text-decoration:none;margin-top:8px}
.openbtn:hover{background:rgba(124,58,237,.25)}
.rgroup{margin-bottom:16px}
.rghead{display:flex;align-items:center;gap:9px;margin-bottom:8px}
.rgletter{width:26px;height:26px;border-radius:7px;background:linear-gradient(135deg,#1e1b4b,#6d28d9);display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:800;color:#fff;flex:0 0 auto}
.rgname{font-size:12px;font-weight:700;color:var(--t2)}
.rgcount{font-size:10.5px;color:var(--mu);margin-left:auto}
.egrid{display:grid;gap:7px;grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}
@media(max-width:500px){.egrid{grid-template-columns:1fr}}
.ecard{background:var(--card2);border:1px solid var(--border);border-radius:10px;padding:10px 12px;display:flex;align-items:center;gap:9px}
.eicon{width:32px;height:32px;border-radius:8px;object-fit:cover;flex:0 0 auto}
.eletter{width:32px;height:32px;border-radius:8px;background:linear-gradient(135deg,#1e1b4b,#6d28d9);display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:800;color:#fff;flex:0 0 auto}
.ename{font-weight:600;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.estatus{font-size:10.5px;margin-top:1px}
.son{color:var(--green)} .soff{color:var(--red)}
.steps{display:flex;flex-direction:column;gap:8px;margin-bottom:14px}
.step{display:flex;gap:11px;background:var(--card2);border:1px solid var(--border);border-radius:11px;padding:12px 13px}
.snum{width:24px;height:24px;border-radius:50%;background:linear-gradient(135deg,#7c3aed,#a78bfa);color:#fff;font-weight:800;font-size:11px;display:flex;align-items:center;justify-content:center;flex:0 0 auto;margin-top:1px}
.sbody strong{display:block;font-size:13px;font-weight:700;margin-bottom:1px}
.sbody span{font-size:11.5px;color:var(--t2)}
.bgray{display:inline-block;font-size:10.5px;font-weight:700;padding:2px 8px;border-radius:999px;background:#111120;color:var(--t2)}
.toast{position:fixed;bottom:18px;left:50%;transform:translateX(-50%);background:#111124;border:1px solid var(--vb);color:var(--t);padding:9px 18px;border-radius:11px;font-size:12.5px;box-shadow:0 8px 28px rgba(124,58,237,.25);opacity:0;transition:.2s;pointer-events:none;z-index:99}
.toast.show{opacity:1}
.empty{padding:24px;text-align:center;color:var(--t2);font-size:12.5px}
@media(max-width:500px){.hero{padding:28px 12px 20px}.card{padding:13px 14px}}
</style>
</head>
<body>
<div class="hero">
  <div class="logo">CNC</div>
  <h1>CNCVerse Bridge</h1>
  <p>Your personal Stremio addon gateway. Use the global manifest or create your own profile.</p>
  <div class="tabs">
    <span class="tab active" id="tab-g" onclick="showTab('g')">&#127760; Global</span>
    <span class="tab" id="tab-p" onclick="showTab('p')">&#128100; My Profile</span>
  </div>
</div>
<main>
  <div id="sec-g">
    <div class="stl">Manifest URL</div>
    <div class="card">
      <h2>&#127916; Global Manifest</h2>
      <div class="hint">Add this to Stremio. All admin-enabled extensions are included.</div>
      <div class="urlbox"><code id="g-url"></code><button class="cbtn" onclick="cp('g-url')">Copy</button></div>
      <a id="g-btn" class="openbtn" href="#">&#9654; Open in Stremio</a>
    </div>
    <div class="stl">Extensions by Repo</div>
    <div id="g-body"><div class="empty">Loading extensions...</div></div>
  </div>
  <div id="sec-p" style="display:none">
    <div class="stl">How It Works</div>
    <div class="steps">
      <div class="step"><div class="snum">1</div><div class="sbody"><strong>Your browser gets a unique profile</strong><span>Auto-created and stored locally. Each device or browser has its own ID.</span></div></div>
      <div class="step"><div class="snum">2</div><div class="sbody"><strong>Copy your profile manifest URL below</strong><span>Use it in Stremio instead of the global URL.</span></div></div>
      <div class="step"><div class="snum">3</div><div class="sbody"><strong>Toggle extensions below</strong><span>Pick what appears in your Stremio. Other users are not affected.</span></div></div>
    </div>
    <div class="stl">Your Profile Manifest</div>
    <div class="card">
      <h2>&#128100; Your Personal URL</h2>
      <div class="hint">Add THIS to Stremio. Only extensions you enable below will appear.</div>
      <div class="urlbox"><code id="p-url"></code><button class="cbtn" onclick="cp('p-url')">Copy</button></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:8px">
        <a id="p-btn" class="openbtn" href="#">&#9654; Open in Stremio</a>
        <span class="bgray" id="pid-lbl"></span>
      </div>
    </div>
    <div class="stl">Your Extension Preferences</div>
    <div class="hint" style="margin-bottom:8px">Toggle which extensions appear in your manifest. Admin-installed extensions are not changed for other users.</div>
    <div id="p-body"><div class="empty">Loading...</div></div>
  </div>
</main>
<div class="toast" id="toast"></div>
<script>
"use strict";
var PK = "cnc_pid";
var pid = localStorage.getItem(PK);
if (!pid) { pid = "p" + Math.random().toString(36).substr(2,14) + Date.now().toString(36); localStorage.setItem(PK, pid); }
var pData = null;
var exts = [];
var curTab = "g";

function showTab(t) {
  curTab = t;
  document.getElementById("tab-g").classList.toggle("active", t==="g");
  document.getElementById("tab-p").classList.toggle("active", t==="p");
  document.getElementById("sec-g").style.display = t==="g" ? "" : "none";
  document.getElementById("sec-p").style.display = t==="p" ? "" : "none";
  if (t==="p" && !pData) loadProfile();
  else if (t==="p") renderProfile();
}

function initUrls() {
  var h = window.location.host;
  var base = window.location.protocol + "//" + h;
  document.getElementById("g-url").textContent = base + "/manifest.json";
  document.getElementById("g-btn").href = "stremio://" + h + "/manifest.json";
  document.getElementById("p-url").textContent = base + "/u/" + encodeURIComponent(pid) + "/manifest.json";
  document.getElementById("p-btn").href = "stremio://" + h + "/u/" + encodeURIComponent(pid) + "/manifest.json";
  document.getElementById("pid-lbl").textContent = "ID: " + pid.substring(0,12) + "...";
}

function loadExts() {
  fetch("/api/extensions")
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(list) {
      exts = list;
      renderGlobal();
      if (curTab==="p" && pData) renderProfile();
    })
    .catch(function(e) {
      document.getElementById("g-body").innerHTML = '<div class="empty">Could not load extensions: ' + esc(e.message) + '</div>';
    });
}

function loadProfile() {
  fetch("/api/profile/" + encodeURIComponent(pid))
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p) { pData = p; pData._d = new Set(p.disabledExtensions||[]); renderProfile(); })
    .catch(function(e) { document.getElementById("p-body").innerHTML = '<div class="empty">Could not load profile: ' + esc(e.message) + '</div>'; });
}

function byRepo(list) {
  var m = {}, ord = [];
  list.forEach(function(e) {
    var k = e.repoUrl || "_";
    if (!m[k]) { m[k] = {name: e.repoName || e.repoUrl || "Unknown", items:[]}; ord.push(k); }
    m[k].items.push(e);
  });
  return ord.map(function(k){ return m[k]; });
}

function iconEl(e) {
  if (e.iconUrl)
    return '<img class="eicon" src="' + esc(e.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt=""><div class="eletter" style="display:none">' + esc(e.name.charAt(0).toUpperCase()) + '</div>';
  return '<div class="eletter">' + esc(e.name.charAt(0).toUpperCase()) + '</div>';
}

function repoBlock(groups, cardFn) {
  if (!groups.length) return '<div class="empty">No extensions loaded yet.</div>';
  return groups.map(function(g) {
    return '<div class="rgroup"><div class="rghead"><div class="rgletter">' + esc(g.name.charAt(0).toUpperCase()) + '</div>'
      + '<span class="rgname">' + esc(g.name) + '</span>'
      + '<span class="rgcount">' + g.items.length + (g.items.length===1?" ext":" exts") + '</span></div>'
      + '<div class="egrid">' + g.items.map(cardFn).join("") + '</div></div>';
  }).join("");
}

function renderGlobal() {
  var el = document.getElementById("g-body"); if (!el) return;
  el.innerHTML = repoBlock(byRepo(exts), function(e) {
    return '<div class="ecard">' + iconEl(e)
      + '<div style="min-width:0;flex:1"><div class="ename">' + esc(e.name) + '</div>'
      + '<div class="estatus ' + (e.enabled?"son":"soff") + '">' + (e.enabled?"Active":"Disabled by admin") + '</div></div></div>';
  });
}

function renderProfile() {
  var el = document.getElementById("p-body"); if (!el || !pData) return;
  var active = exts.filter(function(e){ return e.enabled; });
  el.innerHTML = repoBlock(byRepo(active), function(e) {
    var off = pData._d && pData._d.has(e.internalName);
    return '<div class="ecard">' + iconEl(e)
      + '<div style="min-width:0;flex:1"><div class="ename">' + esc(e.name) + '</div>'
      + '<div class="estatus ' + (off?"soff":"son") + '">' + (off?"Hidden from manifest":"In your manifest") + '</div></div>'
      + '<label class="sw"><input type="checkbox" ' + (off?"":"checked") + ' onchange="tog(\'' + esc(e.internalName) + '\')"><span class="sl"></span></label>'
      + '</div>';
  });
}

function tog(name) {
  fetch("/api/profile/" + encodeURIComponent(pid) + "/toggle", {
    method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({internalName:name})
  }).then(function(r){ if(!r.ok) throw new Error("HTTP "+r.status); return r.json(); })
    .then(function(p){ pData=p; pData._d=new Set(p.disabledExtensions||[]); renderProfile(); toast(name+(p.nowEnabled?" added to":" removed from")+" your manifest"); })
    .catch(function(e){ toast("Error: "+e.message); });
}

function cp(id) {
  var t = document.getElementById(id).textContent;
  if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(t).then(function(){ toast("Copied!"); }); return; }
  var ta = document.createElement("textarea"); ta.value=t; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); toast("Copied!");
}

function esc(s) { return String(s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;"); }

function toast(msg) {
  var el = document.getElementById("toast");
  el.textContent=msg; el.classList.add("show");
  clearTimeout(el._t); el._t=setTimeout(function(){ el.classList.remove("show"); }, 2600);
}

initUrls();
loadExts();
setInterval(loadExts, 15000);
</script>
</body>
</html>"""
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
    /** The bare plugin internalName used for repo/settings lookups (no API-name suffix). */
    val pluginInternalName: String get() = internalName
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

