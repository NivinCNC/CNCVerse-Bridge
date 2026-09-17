package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.currentTimeMillis
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
import kotlinx.serialization.Serializable
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
import kotlinx.coroutines.launch
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
     * Server-side profile store. Key = profile UUID (browser cookie),
     * Value = [ProfileRecord] holding the extensions that profile has
     * disabled plus bookkeeping timestamps.
     *
     * Profiles are lightweight: globally-installed extensions stay installed;
     * only their manifest entry is omitted when the profile disables them.
     * The full addon surface (catalog/meta/stream/subtitles) is served under
     * /u/{profileId}/… according to this record, so a profile manifest URL
     * behaves as a complete standalone Stremio addon.
     */
    val profiles: MutableMap<String, ProfileRecord> = mutableMapOf()
    private var profilesFile: File? = null

    /** Persisted, per-profile preferences and bookkeeping. */
    @Serializable
    data class ProfileRecord(
        /** Globally-enabled extensions this profile has turned off. */
        val disabled: Set<String> = emptySet(),
        /** Globally-disabled extensions this profile has explicitly opted into. */
        val enabledOverrides: Set<String> = emptySet(),
        val createdAt: Long = 0,
        val lastSeen: Long = 0,
    )

    /** Request body for POST /api/profile/{id}/set. */
    @Serializable
    data class ProfileSetRequest(
        val disabledExtensions: List<String> = emptyList(),
        /** Globally-disabled extensions the profile wants in its manifest. */
        val enabledExtensions: List<String> = emptyList(),
    )

    private fun loadProfiles() {
        val file = profilesFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                profiles.clear()
                try {
                    // Current format: { "pid": { "disabled": [...], "createdAt": 0, "lastSeen": 0 } }
                    profiles.putAll(serverJson.decodeFromString<Map<String, ProfileRecord>>(json))
                } catch (_: Exception) {
                    // Legacy format: { "pid": ["ext1", "ext2"] }
                    val legacy = serverJson.decodeFromString<Map<String, Set<String>>>(json)
                    val now = currentTimeMillis()
                    legacy.forEach { (k, v) ->
                        profiles[k] = ProfileRecord(disabled = v, createdAt = now, lastSeen = now)
                    }
                }
            } catch (e: Exception) {
                ServerState.warn("Failed to load profiles: ${e.message}")
            }
        }
    }

    private fun saveProfiles() {
        val file = profilesFile ?: return
        try {
            val map: Map<String, ProfileRecord> = profiles
            file.writeText(serverJson.encodeToString(map))
        } catch (e: Exception) {
            ServerState.warn("Failed to save profiles: ${e.message}")
        }
    }

    fun getProfileDisabled(profileId: String): Set<String> =
        profiles[profileId]?.disabled ?: emptySet()

    /** Globally-disabled extensions this profile has explicitly opted into. */
    fun getProfileEnabledOverrides(profileId: String): Set<String> =
        profiles[profileId]?.enabledOverrides ?: emptySet()

    /**
     * Registers a profile access. Creates the record on first sight and
     * refreshes [ProfileRecord.lastSeen] at most once a minute; writes to
     * disk only when [persist] is set (manifest fetches / preference edits).
     */
    fun touchProfile(profileId: String, persist: Boolean = false) {
        val now = currentTimeMillis()
        val existing = profiles[profileId]
        when {
            existing == null -> {
                profiles[profileId] = ProfileRecord(createdAt = now, lastSeen = now)
                if (persist) saveProfiles()
            }
            now - existing.lastSeen > 60_000 -> {
                profiles[profileId] = existing.copy(lastSeen = now)
                if (persist) saveProfiles()
            }
        }
    }

    /** Replaces the whole selection for a profile (bulk select / clear all). */
    fun setProfileSelections(profileId: String, disabled: Set<String>, enabledOverrides: Set<String>) {
        val now = currentTimeMillis()
        val existing = profiles[profileId]
        profiles[profileId] = ProfileRecord(
            disabled = disabled,
            enabledOverrides = enabledOverrides,
            createdAt = existing?.createdAt ?: now,
            lastSeen = now,
        )
        saveProfiles()
    }

    /**
     * Toggles an extension for a profile. Globally-enabled extensions toggle
     * the profile's disabled set; globally-disabled extensions toggle the
     * profile's explicit opt-in (enabledOverrides) so users can pull them
     * into their personal manifest even though the admin keeps them out of
     * the global one.
     */
    fun toggleProfilePlugin(profileId: String, internalName: String): Boolean {
        val now = currentTimeMillis()
        val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
        val nowEnabled: Boolean
        val updated = if (isIdGloballyDisabled(internalName)) {
            val set = existing.enabledOverrides.toMutableSet()
            nowEnabled = if (set.contains(internalName)) {
                set.remove(internalName)
                false
            } else {
                set.add(internalName)
                true
            }
            existing.copy(enabledOverrides = set, lastSeen = now)
        } else {
            val set = existing.disabled.toMutableSet()
            nowEnabled = if (set.contains(internalName)) {
                set.remove(internalName)
                true
            } else {
                set.add(internalName)
                false
            }
            existing.copy(disabled = set, lastSeen = now)
        }
        profiles[profileId] = updated
        saveProfiles()
        return nowEnabled
    }

    /** Serialises a profile for the user-facing JSON API. */
    private fun profileJson(profileId: String, nowEnabled: Boolean? = null): String {
        val rec = profiles[profileId]
        val disabled = rec?.disabled ?: emptySet()
        val overrides = rec?.enabledOverrides ?: emptySet()
        val disabledJson = disabled.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val overridesJson = overrides.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val extra = if (nowEnabled != null) ",\"nowEnabled\":$nowEnabled" else ""
        return "{\"profileId\":\"" + profileId + "\",\"disabledExtensions\":[" + disabledJson +
            "],\"enabledExtensions\":[" + overridesJson +
            "],\"createdAt\":" + (rec?.createdAt ?: 0) + ",\"lastSeen\":" + (rec?.lastSeen ?: 0) + extra + "}"
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

    /** Sets a plugin's global enabled state and persists it. */
    fun setPluginDisabled(internalName: String, disabled: Boolean = true) {
        if (disabled) disabledPlugins.add(internalName) else disabledPlugins.remove(internalName)
        saveDisabledPlugins()
    }

    /** All identifiers a loaded extension answers to (display slug, per-API name, plugin name). */
    private fun apiIds(api: MainApiWrapper): List<String> =
        listOf(nameSlug(api.name), api.internalName, api.pluginInternalName).distinct()

    /** True when the admin has globally disabled this extension. */
    fun isGloballyDisabled(api: MainApiWrapper): Boolean =
        apiIds(api).any { disabledPlugins.contains(it) }

    /** True when [id] (slug / per-API name / plugin name) maps to a globally disabled extension. */
    fun isIdGloballyDisabled(id: String): Boolean {
        if (disabledPlugins.contains(id)) return true
        val api = loadedApis.find { apiIds(it).contains(id) } ?: return false
        return isGloballyDisabled(api)
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

            // Returns the profile record (auto-creates it server-side on first access)
            get("/api/profile/{profileId}") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                touchProfile(profileId, persist = true)
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Toggle an extension on/off for this profile
            post("/api/profile/{profileId}/toggle") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { emptyMap() }
                val internalName = body["internalName"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val nowEnabled = toggleProfilePlugin(profileId, internalName)
                call.respondText(profileJson(profileId, nowEnabled), ContentType.Application.Json)
            }

            // Replace the whole disabled set for this profile (Select all / Clear all)
            post("/api/profile/{profileId}/set") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = try { call.receive<ProfileSetRequest>() } catch (e: Exception) {
                    return@post call.respondText(
                        "{\"error\":\"expected a disabledExtensions string array\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
                setProfileSelections(
                    profileId,
                    disabled = body.disabledExtensions.toSet(),
                    enabledOverrides = body.enabledExtensions.toSet(),
                )
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }


            // Returns loaded extensions for the user page
            get("/api/extensions") {
                val sb = StringBuilder("[")
                val installed = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                val knownRepos = com.cncverse.stremiobridge.state.RepoState.repos.value
                loadedApis.forEachIndexed { i, api ->
                    if (i > 0) sb.append(",")
                    val name = api.name.replace("\"", "\\\"")
                    val id = nameSlug(api.name).replace("\"", "\\\"")
                    val plugin = installed.find { it.internalName == api.pluginInternalName || it.internalName == api.internalName }
                    val rawRepoUrl = plugin?.repoUrl ?: ""
                    // Canonicalize: prefer the URL as stored in the known repos list so that
                    // refs/heads variants don't create phantom repo groups in the UI.
                    val knownRepo = knownRepos.find { it.url == rawRepoUrl }
                        ?: knownRepos.find { normalizeGhUrl(it.url) == normalizeGhUrl(rawRepoUrl) }
                    val repoUrl = (knownRepo?.url ?: rawRepoUrl).replace("\"", "\\\"")
                    val repoName = knownRepo?.name?.replace("\"", "\\\"") ?: ""
                    val iconUrl = plugin?.iconUrl?.replace("\"", "\\\"") ?: ""
                    val enabled = !isGloballyDisabled(api)
                    val typesJson = api.supportedTypes.distinct().joinToString(",") { "\"" + it + "\"" }
                    sb.append("{\"internalName\":\"$id\",\"name\":\"$name\",\"enabled\":$enabled,\"repoUrl\":\"$repoUrl\",\"repoName\":\"$repoName\",\"iconUrl\":\"$iconUrl\",\"types\":[$typesJson]}")
                }
                sb.append("]")
                call.respondText(sb.toString(), ContentType.Application.Json)
            }

            // ── Repos (user-facing) ───────────────────────────────────────────

            // Repos installed on this server, with extension counts and states
            get("/api/repos") {
                val sb = StringBuilder("[")
                com.cncverse.stremiobridge.state.RepoState.repos.value.forEachIndexed { i, repo ->
                    if (i > 0) sb.append(",")
                    val name = repo.name.ifBlank { repo.url }.replace("\"", "\\\"")
                    val url = repo.url.replace("\"", "\\\"")
                    val icon = repo.iconUrl?.replace("\"", "\\\"") ?: ""
                    val desc = repo.description?.replace("\"", "\\\"") ?: ""
                    val count = com.cncverse.stremiobridge.state.RepoState.availablePlugins.value
                        .count { it.repoEntry.url == repo.url }
                    val err = repo.error?.replace("\"", "\\\"") ?: ""
                    sb.append("{\"url\":\"$url\",\"name\":\"$name\",\"iconUrl\":\"$icon\",\"description\":\"$desc\",")
                    sb.append("\"pluginCount\":$count,\"isLoading\":${repo.isLoading},\"error\":\"$err\"}")
                }
                sb.append("]")
                call.respondText(sb.toString(), ContentType.Application.Json)
            }

            // User-initiated repo install: any visitor can add a repo that is
            // not installed yet. It is saved globally and every extension it
            // offers is downloaded automatically, but all of them start
            // globally disabled — users opt in from their profile.
            post("/api/repos/add") {
                val body = try { call.receive<Map<String, String>>() } catch (e: Exception) { emptyMap() }
                val rawUrl = body["url"]?.trim().orEmpty()
                if (rawUrl.isEmpty()) {
                    return@post call.respondText(
                        "{\"ok\":false,\"message\":\"Missing url\"}",
                        ContentType.Application.Json, HttpStatusCode.BadRequest
                    )
                }
                val resolved = com.cncverse.stremiobridge.repo.PluginRepository.resolveShortCode(rawUrl)
                if (com.cncverse.stremiobridge.state.RepoState.repos.value.any { it.url == resolved }) {
                    return@post call.respondText(
                        "{\"ok\":false,\"message\":\"That repository is already installed\"}",
                        ContentType.Application.Json, HttpStatusCode.Conflict
                    )
                }
                val scope = BridgeRuntime.appScope
                    ?: return@post call.respondText(
                        "{\"ok\":false,\"message\":\"Bridge runtime is not running\"}",
                        ContentType.Application.Json, HttpStatusCode.ServiceUnavailable
                    )
                scope.launch(Dispatchers.IO) {
                    val entry = BridgeRuntime.addRepo(
                        resolved,
                        saveGlobally = true,
                        autoInstallAll = true,
                        disableNewPluginsByDefault = true,
                    )
                    when {
                        entry == null -> ServerState.warn("Repo already installed: $resolved")
                        entry.error != null -> ServerState.warn("Repo add failed: ${entry.error}")
                        else -> ServerState.info(
                            "Repo '${entry.name.ifBlank { entry.url }}' installed globally — " +
                                "extensions downloaded, disabled by default"
                        )
                    }
                }
                call.respondText(
                    "{\"ok\":true,\"message\":\"Adding repository — its extensions will appear in My Profile once downloaded\"}",
                    ContentType.Application.Json
                )
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
                touchProfile(profileId, persist = true)
                call.respond(buildManifest(profileId = profileId))
            }
            get("/manifest.json") {
                call.respond(buildManifest())
            }

            // 📺 Catalog 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺
            get("/catalog/{path...}") { call.respondCatalog(null) }

            // Profile-scoped catalog. Stremio resolves resource URLs relative to
            // the manifest URL, so a manifest served from /u/{pid}/manifest.json
            // requests its catalogs from /u/{pid}/catalog/… — served here.
            get("/u/{profileId}/catalog/{path...}") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                touchProfile(profileId)
                call.respondCatalog(profileId)
            }

            // ── Meta ─────────────────────────────────────────────────────────
            get("/meta/{type}/{id}.json") { call.respondMeta(null) }
            get("/u/{profileId}/meta/{type}/{id}.json") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                touchProfile(profileId)
                call.respondMeta(profileId)
            }

            // ── Stream ───────────────────────────────────────────────────────
            get("/stream/{type}/{id}.json") { call.respondStreams(null) }
            get("/u/{profileId}/stream/{type}/{id}.json") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                touchProfile(profileId)
                call.respondStreams(profileId)
            }

            // ── Subtitles ────────────────────────────────────────────────────
            get("/subtitles/{type}/{id}.json") { call.respondSubtitles(null) }
            get("/u/{profileId}/subtitles/{type}/{id}.json") {
                val profileId = call.parameters["profileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                touchProfile(profileId)
                call.respondSubtitles(profileId)
            }
        }
    }

    // ── Route handlers (shared by global and profile-scoped routes) ──────────

    /** Serves a catalog request; applies the profile's disabled set when scoped. */
    private suspend fun ApplicationCall.respondCatalog(profileId: String?) {
        val pathSegments = parameters.getAll("path") ?: emptyList()
        if (pathSegments.size < 2) {
            respond(HttpStatusCode.BadRequest)
            return
        }

        val type = pathSegments[0]

        if (pathSegments.size == 2) {
            val idWithExt = pathSegments[1]
            if (!idWithExt.endsWith(".json")) {
                respond(HttpStatusCode.NotFound)
                return
            }

            val id = idWithExt.removeSuffix(".json")
            val search = request.queryParameters["search"]
            val skip = request.queryParameters["skip"]?.toIntOrNull() ?: 0

            val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, null, profileId) }
            respond(StremioCatalogResponse(metas))
        } else if (pathSegments.size == 3) {
            val id = pathSegments[1]
            val extraWithExt = pathSegments[2]
            if (!extraWithExt.endsWith(".json")) {
                respond(HttpStatusCode.NotFound)
                return
            }

            val extraStr = extraWithExt.removeSuffix(".json")
            val parsedExtra = io.ktor.http.parseQueryString(extraStr)

            val search = parsedExtra["search"] ?: request.queryParameters["search"]
            val skip = (parsedExtra["skip"] ?: request.queryParameters["skip"])?.toIntOrNull() ?: 0
            val genre = parsedExtra["genre"]

            val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, genre, profileId) }
            respond(StremioCatalogResponse(metas))
        } else {
            respond(HttpStatusCode.BadRequest)
        }
    }

    private suspend fun ApplicationCall.respondMeta(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val meta = withContext(Dispatchers.IO) { buildMeta(type, id, profileId) }
        if (meta != null) {
            respond(StremioMetaResponse(meta))
        } else {
            respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun ApplicationCall.respondStreams(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val streams = withContext(Dispatchers.IO) { buildStreams(type, id, profileId) }
        respond(StremioStreamResponse(streams))
    }

    private suspend fun ApplicationCall.respondSubtitles(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val streams = withContext(Dispatchers.IO) { buildStreams(type, id, profileId) }
        val subtitles = streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }

        respond(StremioSubtitleResponse(subtitles))
    }

    /**
     * True when the extension must be hidden from the requesting manifest.
     * Global manifest: hidden when the admin disabled it. Profile manifests:
     * globally-enabled extensions follow the profile's disabled set, while
     * globally-disabled extensions only appear when the profile explicitly
     * opted in (and has not turned them off again since).
     */
    private fun isPluginBlocked(api: MainApiWrapper, profileId: String?): Boolean {
        val globallyDisabled = isGloballyDisabled(api)
        if (profileId == null) return globallyDisabled
        val rec = profiles[profileId]
        val ids = apiIds(api)
        return if (globallyDisabled) {
            ids.none { rec?.enabledOverrides?.contains(it) == true } ||
                ids.any { rec?.disabled?.contains(it) == true }
        } else {
            ids.any { rec?.disabled?.contains(it) == true }
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
     * Normalises raw.githubusercontent.com URLs so that variants with and
     * without /refs/heads/ or /refs/tags/ compare as equal.  Used to
     * canonicalise the repoUrl reported by installedPlugins against the
     * canonical URL stored in RepoState.repos, preventing "phantom" repo tabs
     * in the user-facing Extensions/Profile UI.
     */
    private fun normalizeGhUrl(url: String): String =
        url.replace("/refs/heads/", "/").replace("/refs/tags/", "/")

    /**
     * Builds the Stremio manifest.
     * @param profileId If non-null, also excludes extensions the profile has disabled.
     */
    suspend fun buildManifest(profileId: String? = null): StremioManifest {
        val activeApis = loadedApis.filter { !isPluginBlocked(it, profileId) }
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

        // Unique manifest id per profile so the personal addon can be
        // installed alongside the global one in Stremio without replacing it.
        val profileSuffix = profileId
            ?.replace(Regex("[^a-zA-Z0-9]"), "")
            ?.take(24)
            ?.ifBlank { null }
        return StremioManifest(
            id          = if (profileSuffix == null) "com.cncverse.stremiobridge"
                          else "com.cncverse.stremiobridge." + profileSuffix,
            version     = "1.0.0",
            name        = if (profileId == null) "CNCVerse Bridge" else "CNCVerse Bridge · Profile",
            description = "CS3 plugin bridge for Stremio — powered by CNCVerse extensions",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png",
            types       = types,
            resources   = listOf("catalog", "meta", "stream", "subtitles"),
            catalogs    = catalogs,
        )
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?, profileId: String? = null
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

        if (isPluginBlocked(api, profileId)) return emptyList()

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

    private suspend fun buildMeta(type: String, id: String, profileId: String? = null): StremioMeta? {
        val (pluginKey, dataUrl) = StremioIds.decode(id) ?: return null
        val api = loadedApis.find { nameSlug(it.name) == pluginKey }
            ?: loadedApis.find { it.internalName == pluginKey }
            ?: return null
        if (isPluginBlocked(api, profileId)) return null
        return try {
            api.load(dataUrl)?.toStremiMeta(nameSlug(api.name), type)
        } catch (e: Throwable) {
            ServerState.warn("Meta error for ${api.name}: ${e.message}")
            null
        }
    }


    private suspend fun buildStreams(type: String, id: String, profileId: String? = null): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val api = loadedApis.find { nameSlug(it.name) == pluginKey }
                ?: loadedApis.find { it.internalName == pluginKey }
                ?: return emptyList()
            if (isPluginBlocked(api, profileId)) return emptyList()
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
            val activePlugins = loadedApis.filter { !isPluginBlocked(it, profileId) }
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

    /**
     * Landing page served at "/" — a dark pengu.uk-style dashboard with a
     * fixed sidebar, an overview page, a global extension browser and the
     * personal profile page where users curate which extensions their own
     * profile manifest serves. The profile itself lives on the server
     * (profiles.json), so the profile manifest URL works from any device.
     *
     * NOTE: JavaScript below intentionally avoids template literals and any
     * dollar characters so it can live inside a Kotlin raw string unescaped.
     */
    private fun buildIndexHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>CNCVerse Bridge</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
:root {
  --bg: #090b10;
  --surface: #111520;
  --surface-active: #171d2d;
  --surface-card: #131826;
  --surface-card-hover: #181f30;
  --border: #1e2638;
  --border-focus: #33415e;
  --border-active: #6366f1;
  --text: #f1f3f7;
  --text-sub: #94a3b8;
  --text-dim: #64748b;
  --accent: #6366f1;
  --accent-hover: #4f46e5;
  --accent-glow: rgba(99, 102, 241, 0.18);
  --code-bg: #151a28;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.12);
  --cyan: #38bdf8;
  --cyan-bg: rgba(56, 189, 248, 0.1);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.12);
  --red: #f87171;
  --red-bg: rgba(248, 113, 113, 0.12);
}
[data-theme="light"] {
  --bg: #f8fafc;
  --surface: #ffffff;
  --surface-active: #f1f5f9;
  --surface-card: #ffffff;
  --surface-card-hover: #f8fafc;
  --border: #e2e8f0;
  --border-focus: #cbd5e1;
  --border-active: #4f46e5;
  --text: #0f172a;
  --text-sub: #475569;
  --text-dim: #94a3b8;
  --accent: #4f46e5;
  --accent-hover: #4338ca;
  --accent-glow: rgba(79, 70, 229, 0.12);
  --code-bg: #f1f5f9;
  --green: #059669;
  --green-bg: rgba(5, 150, 105, 0.08);
  --cyan: #0284c7;
  --cyan-bg: rgba(2, 132, 199, 0.08);
  --amber: #d97706;
  --amber-bg: rgba(217, 119, 6, 0.08);
  --red: #dc2626;
  --red-bg: rgba(220, 38, 38, 0.08);
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { overflow-x: hidden; max-width: 100vw; }
body {
  background: var(--bg);
  color: var(--text);
  font: 14px/1.5 'Inter', system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  padding: 1.5rem 1.25rem 4rem;
  min-height: 100vh;
  -webkit-font-smoothing: antialiased;
}
@media (max-width: 680px) {
  body { padding: 12px 10px 4rem; }
}
.container {
  max-width: 68rem;
  width: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-width: 0;
}



/* Header */
.hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 0.1rem;
}
.brand-title {
  font-size: 1.55rem;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.5px;
  margin: 0 0 2px;
}
@media (max-width: 680px) {
  .brand-title { font-size: 1.3rem; }
}
.brand-sub { font-size: 12.5px; color: var(--text-sub); margin: 0; }
.hdr-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.btn-hdr {
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-sub);
  padding: 7px 11px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  text-decoration: none;
}
.btn-hdr:hover { color: var(--text); border-color: var(--border-focus); background: var(--surface-active); }
.hdr-badge {
  font-size: 10px;
  font-weight: 700;
  background: var(--accent-glow);
  color: var(--accent);
  padding: 1px 6px;
  border-radius: 999px;
}

/* Mode Selector (2 Clean Cards) */
.nav-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
}
.nav-card {
  background: var(--surface-card);
  border: 1.5px solid var(--border);
  border-radius: 12px;
  padding: 12px 14px;
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease;
  display: flex;
  flex-direction: column;
  gap: 3px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  min-width: 0;
  overflow: hidden;
  box-sizing: border-box;
  font: inherit;
}
@media (max-width: 480px) {
  .nav-cards { gap: 8px; }
  .nav-card { padding: 10px 10px; gap: 2px; border-radius: 10px; }
}
.nav-card:hover { border-color: var(--border-focus); background: var(--surface-active); }
.nav-card.active {
  border-color: var(--accent);
  background: var(--surface-card);
  box-shadow: 0 4px 16px var(--accent-glow);
}
.nav-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
  width: 100%;
  min-width: 0;
}
.nav-card-icon {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: var(--accent-glow);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.nav-card-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-sub);
  flex-shrink: 0;
}
.nav-card.active .nav-card-badge {
  background: var(--accent-glow);
  border-color: rgba(99, 102, 241, 0.3);
  color: var(--accent);
}
.nav-card-title {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--text);
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  width: 100%;
}
@media (max-width: 480px) { .nav-card-title { font-size: 12.5px; } }
.nav-card-desc {
  font-size: 11px;
  color: var(--text-sub);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  width: 100%;
}
@media (max-width: 480px) { .nav-card-desc { font-size: 10px; } }

/* Tab Panels */
.tab-panel {
  display: none;
  flex-direction: column;
  gap: 1rem;
  width: 100%;
}
.tab-panel.active { display: flex; }

/* Hero Card */
.hero-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: 0 6px 20px -6px rgba(0, 0, 0, 0.35);
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}
@media (max-width: 680px) { .hero-card { padding: 14px 14px; gap: 11px; } }
.hero-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.hero-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 700;
  color: var(--green);
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 7px var(--green);
  flex-shrink: 0;
}
.hero-scope {
  font-size: 11.5px;
  color: var(--text-sub);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.hero-desc {
  font-size: 12.5px;
  color: var(--text-sub);
  line-height: 1.45;
}



/* Install Button CTA */
.btn-install {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  width: 100%;
  background: var(--accent);
  color: #ffffff !important;
  font-weight: 700;
  font-size: 14.5px;
  padding: 12px 20px;
  border-radius: 9px;
  text-decoration: none;
  cursor: pointer;
  box-shadow: 0 4px 14px var(--accent-glow);
  transition: all 0.15s ease;
}
.btn-install:hover {
  background: var(--accent-hover);
  box-shadow: 0 6px 18px rgba(99, 102, 241, 0.35);
  transform: translateY(-1px);
}
.btn-install:active { transform: translateY(0); }

/* Single-Line Compact Manifest Group */
.manifest-input-group {
  display: flex;
  align-items: center;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 4px 5px 4px 10px;
  width: 100%;
  box-sizing: border-box;
  gap: 8px;
  overflow: hidden;
  transition: border-color 0.15s;
}
.manifest-input-group:focus-within { border-color: var(--accent); }
.manifest-tag {
  font-size: 9.5px;
  font-weight: 800;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  background: var(--surface);
  color: var(--text-dim);
  border: 1px solid var(--border);
  border-radius: 5px;
  padding: 3px 6px;
  flex-shrink: 0;
}
.manifest-input {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: var(--text);
  background: transparent;
  border: none;
  outline: none;
  flex: 1 1 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.btn-copy-manifest {
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 11.5px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.btn-copy-manifest:hover { background: var(--surface-active); border-color: var(--border-focus); }

/* Bridge Specifications Card */
.features-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}
.features-title {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: var(--text-dim);
}
.features-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
}
@media (max-width: 480px) {
  .features-grid { grid-template-columns: 1fr; gap: 8px; }
}
.feature-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 12px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 12px;
}
.feat-label { color: var(--text-sub); font-weight: 500; }
.feat-val { color: var(--text); font-weight: 700; }

/* Customize Prompt Banner */
.customize-prompt-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  transition: all 0.18s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}
.customize-prompt-card:hover {
  border-color: var(--accent);
  background: var(--surface-card-hover);
  transform: translateY(-1px);
  box-shadow: 0 4px 16px var(--accent-glow);
}
.cp-content { display: flex; align-items: center; gap: 12px; min-width: 0; }
.cp-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--accent-glow);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.cp-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cp-title { font-size: 13.5px; font-weight: 700; color: var(--text); letter-spacing: -0.2px; }
.cp-sub { font-size: 11.5px; color: var(--text-sub); line-height: 1.35; }
.cp-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 700;
  color: var(--accent);
  flex-shrink: 0;
}
@media (max-width: 480px) {
  .customize-prompt-card { padding: 12px 14px; gap: 10px; }
  .cp-sub { display: none; }
}

/* Customizer Bar */
.customizer-bar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.customizer-hdr {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}
.customizer-title {
  font-size: 1.1rem;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.3px;
}
.customizer-sub { font-size: 12px; color: var(--text-sub); margin-top: 2px; }
.customizer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.btn-reset-action {
  font-size: 11.5px;
  color: var(--accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
  white-space: nowrap;
  font-weight: 600;
}
.btn-reset-action:hover { text-decoration: underline; }

.presets-row {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
  width: 100%;
}
.presets-row::-webkit-scrollbar { display: none; }
.preset-btn {
  font-size: 11.5px;
  font-weight: 500;
  padding: 4px 11px;
  border-radius: 999px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-sub);
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  transition: all 0.15s ease;
}
.preset-btn:hover { color: var(--text); border-color: var(--border-focus); }
.preset-btn.active {
  background: var(--surface-active);
  color: var(--text);
  border-color: var(--accent);
  font-weight: 600;
}
.search-input {
  width: 100%;
  font: inherit;
  font-size: 13px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  padding: 8px 12px;
  outline: none;
  box-sizing: border-box;
}
.search-input:focus { border-color: var(--accent); }

/* Sources Grid (Strict 2 columns on mobile) */
.sources-section-hdr {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
  margin-top: 0.1rem;
}
.sources-section-title {
  font-size: 1.1rem;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.3px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.tab-badge {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--accent-glow);
  color: var(--accent);
  font-weight: 700;
}
.btn-customize-cta {
  font-size: 12px;
  color: var(--accent);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 7px;
  padding: 6px 12px;
  cursor: pointer;
  font-weight: 600;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-customize-cta:hover { background: var(--surface-active); border-color: var(--accent); }

.sources-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
  width: 100%;
  min-width: 0;
}
@media (max-width: 680px) {
  .sources-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
}

/* Cards */
.provider-card, .overview-source-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 11px;
  padding: 12px 13px;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 7px;
  user-select: none;
  min-width: 0;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}
@media (max-width: 680px) {
  .provider-card, .overview-source-card {
    padding: 10px 10px;
    gap: 6px;
    border-radius: 9px;
  }
}
.provider-card:hover, .overview-source-card:hover {
  border-color: var(--border-focus);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25);
}
.provider-card.selected {
  border-color: var(--accent);
  background: var(--surface-active);
  box-shadow: 0 4px 14px var(--accent-glow);
}
.p-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 6px;
}
.p-avatar {
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: var(--surface);
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 800;
  color: var(--accent);
  flex-shrink: 0;
}
.p-icon-img {
  width: 30px;
  height: 30px;
  border-radius: 7px;
  object-fit: cover;
  background: var(--code-bg);
  border: 1px solid var(--border);
  flex-shrink: 0;
}
.chk {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid var(--border-focus);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 18px;
  transition: all 0.12s ease;
  background: transparent;
}
.provider-card.selected .chk {
  background: var(--accent);
  border-color: var(--accent);
}
.chk-svg { display: none; stroke: #ffffff; }
.provider-card.selected .chk-svg { display: block; }

.oc-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--green);
  background: var(--green-bg);
  padding: 2px 6px;
  border-radius: 999px;
  border: 1px solid rgba(16, 185, 129, 0.25);
  flex-shrink: 0;
}

.p-main-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  width: 100%;
}
.p-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.25;
}
@media (max-width: 680px) { .p-name { font-size: 12px; } }
.p-repo {
  font-size: 10.5px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.p-badges-row {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.p-type-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--code-bg);
  color: var(--text-sub);
  border: 1px solid var(--border);
  text-transform: capitalize;
}
.p-tag-goff {
  font-size: 9px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--amber-bg);
  color: var(--amber);
  border: 1px solid rgba(245, 158, 11, 0.25);
  text-transform: uppercase;
}
.p-desc {
  font-size: 11px;
  color: var(--text-dim);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* Modals */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 16px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease;
}
.modal-overlay.open { opacity: 1; pointer-events: auto; }
.modal-box {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 14px;
  width: 100%;
  max-width: 520px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.5);
  max-height: 88vh;
  overflow-y: auto;
}
.modal-hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.modal-title { font-size: 16px; font-weight: 700; color: var(--text); }
.modal-close {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
}
.modal-close:hover { color: var(--text); background: var(--surface-active); }
.modal-hint { font-size: 12px; color: var(--text-sub); }
.modal-input-row {
  display: flex;
  gap: 8px;
}
.modal-input {
  flex: 1;
  font: inherit;
  font-size: 13px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  padding: 9px 12px;
  outline: none;
}
.modal-input:focus { border-color: var(--accent); }
.btn-primary {
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 9px 15px;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.15s;
  flex-shrink: 0;
}
.btn-primary:hover { background: var(--accent-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: default; }
.repos-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
}
.repo-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.repo-info { min-width: 0; flex: 1; }
.repo-name { font-size: 13px; font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.repo-url { font-size: 10.5px; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.repo-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-sub);
  flex-shrink: 0;
}

/* Toast */
.toast {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%) translateY(20px);
  background: var(--surface-card);
  border: 1px solid var(--border-focus);
  color: var(--text);
  font-size: 12.5px;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  opacity: 0;
  pointer-events: none;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 200;
  white-space: nowrap;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
.empty {
  grid-column: 1 / -1;
  text-align: center;
  padding: 2rem 1rem;
  color: var(--text-dim);
  font-size: 13px;
}
</style>
</head>
<body>

<div class="container">
  <!-- HEADER -->
  <header class="hdr">
    <div>
      <h1 class="brand-title">CNCVerse Bridge</h1>
      <p class="brand-sub">Universal CloudStream provider gateway for Stremio &amp; Nuvio</p>
    </div>
    <div class="hdr-actions">
      <button class="btn-hdr" onclick="openReposModal()" title="Extension Repositories">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
        <span>Repos</span>
        <span class="hdr-badge" id="hdr-repo-count">0</span>
      </button>
      <a class="btn-hdr" href="https://t.me/cncverse" target="_blank" rel="noopener" title="Telegram Community">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4Z"/></svg>
      </a>
      <button class="btn-hdr" onclick="toggleTheme()" id="theme-btn" title="Toggle theme">
        <svg id="theme-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
      </button>
    </div>
  </header>

  <!-- 2-CARD MODE SELECTOR -->
  <nav class="nav-cards">
    <div class="nav-card active" id="nav-card-overview" onclick="switchTab('overview')">
      <div class="nav-card-top">
        <div class="nav-card-icon">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        </div>
        <span class="nav-card-badge">Official Addon</span>
      </div>
      <div class="nav-card-title">Global Manifest</div>
      <div class="nav-card-desc">Direct access to all globally enabled streams</div>
    </div>

    <div class="nav-card" id="nav-card-customize" onclick="switchTab('customize')">
      <div class="nav-card-top">
        <div class="nav-card-icon">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg>
        </div>
        <span class="nav-card-badge">Personal Profile</span>
      </div>
      <div class="nav-card-title">Customize Sources</div>
      <div class="nav-card-desc">Filter providers, exclude catalogs, curate manifest</div>
    </div>
  </nav>

  <!-- TAB 1: OVERVIEW & GLOBAL MANIFEST -->
  <main class="tab-panel active" id="panel-overview">
    <div class="hero-card">
      <div class="hero-top">
        <div class="hero-status">
          <span class="status-dot"></span>
          <span>Online</span>
        </div>
      </div>

      <div class="hero-desc">
        All enabled CloudStream extensions in a single unified Stremio addon.
      </div>

      <a id="install-btn-overview" class="btn-install" href="#">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        Install Addon
      </a>

      <div class="manifest-input-group">
        <span class="manifest-tag">URL</span>
        <input type="text" id="manifest-url-overview" class="manifest-input" readonly value="Loading manifest URL..." onclick="this.select()">
        <button class="btn-copy-manifest" onclick="cpManifest(false, this)" title="Copy Manifest URL">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
          <span class="copy-lbl">Copy</span>
        </button>
      </div>
    </div>

    <!-- BRIDGE SPECIFICATIONS -->
    <div class="features-card">
      <div class="features-title">BRIDGE SPECIFICATIONS</div>
      <div class="features-grid">
        <div class="feature-item">
          <span class="feat-label">Protocol</span>
          <span class="feat-val">Direct &amp; HLS Streams</span>
        </div>
        <div class="feature-item">
          <span class="feat-label">Resolution</span>
          <span class="feat-val">4K &middot; 1080p &middot; 720p</span>
        </div>
        <div class="feature-item">
          <span class="feat-label">Supported Types</span>
          <span class="feat-val">Movies &middot; Series &middot; Anime &middot; TV</span>
        </div>
        <div class="feature-item">
          <span class="feat-label">Auto Updates</span>
          <span class="feat-val" style="color:var(--green)">Live Sync</span>
        </div>
      </div>
    </div>

    <!-- CUSTOMIZE PROMPT BANNER -->
    <div class="customize-prompt-card" onclick="switchTab('customize')" role="button" tabindex="0" title="Click to customize provider sources">
      <div class="cp-content">
        <div class="cp-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg>
        </div>
        <div class="cp-text">
          <div class="cp-title">Customize Sources</div>
          <div class="cp-sub">Filter providers, exclude unwanted catalogs, or create a personalized Stremio manifest.</div>
        </div>
      </div>
      <div class="cp-action">
        <span>Configure</span>
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
      </div>
    </div>

    <!-- AVAILABLE EXTENSIONS LIST -->
    <div class="sources-section-hdr">
      <div class="sources-section-title">
        Included Providers
        <span class="tab-badge" id="overview-ext-count">0</span>
      </div>
      <button class="btn-customize-cta" onclick="switchTab('customize')">
        Customize &rarr;
      </button>
    </div>

    <div class="sources-grid" id="overview-grid">
      <div class="empty">Loading providers...</div>
    </div>
  </main>

  <!-- TAB 2: CUSTOMIZE SOURCES -->
  <main class="tab-panel" id="panel-customize">
    <div class="hero-card">
      <div class="hero-top">
        <div class="hero-status">
          <span class="status-dot"></span>
          <span>Personal Profile</span>
        </div>
      </div>

      <div class="hero-desc">
        Select which providers appear in your addon. Your picks are saved on this server and follow your profile link.
      </div>

      <a id="install-btn-custom" class="btn-install" href="#">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        Install Addon
      </a>

      <div class="manifest-input-group">
        <span class="manifest-tag">Profile URL</span>
        <input type="text" id="manifest-url-custom" class="manifest-input" readonly value="Loading personal manifest URL..." onclick="this.select()">
        <button class="btn-copy-manifest" onclick="cpManifest(true, this)" title="Copy Personal Manifest URL">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
          <span class="copy-lbl">Copy</span>
        </button>
      </div>
    </div>

    <!-- FILTER & SEARCH CONTROLS -->
    <div class="customizer-bar">
      <div class="customizer-hdr">
        <div>
          <h2 class="customizer-title">Customize Providers</h2>
          <div class="customizer-sub">Click any card to enable or disable it for your profile.</div>
        </div>
        <div class="customizer-actions">
          <button class="btn-reset-action" onclick="setAll(true)">Select All</button>
          <span style="color:var(--text-dim)">&middot;</span>
          <button class="btn-reset-action" onclick="setAll(false)">Clear All</button>
        </div>
      </div>

      <div class="presets-row" id="presets-row">
        <button class="preset-btn active" onclick="applyFilter('all', this)">All Sources</button>
        <button class="preset-btn" onclick="applyFilter('movies', this)">Movies &amp; Series</button>
        <button class="preset-btn" onclick="applyFilter('anime', this)">Anime</button>
        <button class="preset-btn" onclick="applyFilter('live', this)">Live TV</button>
      </div>

      <input type="text" class="search-input" id="ext-search" placeholder="Search providers by name, repo, or content tag..." oninput="renderCards()">
    </div>

    <div class="sources-grid" id="sources-grid">
      <div class="empty">Loading provider sources...</div>
    </div>
  </main>
</div>

<!-- REPOSITORIES MODAL -->
<div class="modal-overlay" id="repos-modal" onclick="if(event.target===this)closeReposModal()">
  <div class="modal-box">
    <div class="modal-hdr">
      <div class="modal-title">Extension Repositories</div>
      <button class="modal-close" onclick="closeReposModal()" title="Close">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div class="modal-hint">Enter a repository URL, GitHub shorthand, or shortcode. Every extension the repo offers downloads automatically.</div>
    <div class="modal-input-row">
      <input type="text" id="repo-input" class="modal-input" placeholder="Hexated, user/repo, or repo.json URL" onkeydown="if(event.key==='Enter')addRepo()">
      <button class="btn-primary" id="repo-add-btn" onclick="addRepo()">Add</button>
    </div>
    <div style="font-size:11px;color:var(--text-dim)">Shortcuts: <code>Hexated</code> &middot; <code>!pymd</code> &middot; <code>user/repo</code> &middot; <code>user/repo/branch</code></div>
    <div class="repos-list" id="repos-list">
      <div class="empty" style="padding:16px">Loading repositories...</div>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
"use strict";

function applyTheme(t) {
  document.documentElement.setAttribute("data-theme", t);
  localStorage.setItem("cnc_theme", t);
  var icon = document.getElementById("theme-icon");
  if (icon) {
    if (t === "light") {
      icon.innerHTML = '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>';
    } else {
      icon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>';
    }
  }
}
function toggleTheme() {
  var cur = document.documentElement.getAttribute("data-theme") || "dark";
  applyTheme(cur === "dark" ? "light" : "dark");
}
applyTheme(localStorage.getItem("cnc_theme") || "dark");

function dismissAnnouncement() {
  var el = document.getElementById("top-announcement");
  if (el) {
    el.style.opacity = "0";
    setTimeout(function() { el.style.display = "none"; }, 200);
    localStorage.setItem("cnc_announcement_dismissed", "1");
  }
}
if (localStorage.getItem("cnc_announcement_dismissed") === "1") {
  var ann = document.getElementById("top-announcement");
  if (ann) ann.style.display = "none";
}

var PK = "cnc_pid";
var pid = localStorage.getItem(PK);
if (!pid) {
  pid = "p" + Math.random().toString(36).substr(2, 14) + Date.now().toString(36);
  localStorage.setItem(PK, pid);
}
var pData = { disabledExtensions: [], enabledExtensions: [] };
pData._d = new Set();
pData._e = new Set();
var exts = [];
var repos = [];
var currentFilter = "all";
var activeTab = "overview";

function switchTab(tab) {
  activeTab = tab;
  document.getElementById("nav-card-overview").classList.toggle("active", tab === "overview");
  document.getElementById("nav-card-customize").classList.toggle("active", tab === "customize");
  document.getElementById("panel-overview").classList.toggle("active", tab === "overview");
  document.getElementById("panel-customize").classList.toggle("active", tab === "customize");
  if (tab === "customize") {
    var searchInput = document.getElementById("ext-search");
    if (searchInput) searchInput.focus();
  }
}

function updateUrls() {
  var h = window.location.host;
  var proto = window.location.protocol;
  var gUrl = proto + "//" + h + "/manifest.json";
  var pUrl = proto + "//" + h + "/u/" + encodeURIComponent(pid) + "/manifest.json";

  var gStremio = "stremio://" + h + "/manifest.json";
  var pStremio = "stremio://" + h + "/u/" + encodeURIComponent(pid) + "/manifest.json";

  var gInp = document.getElementById("manifest-url-overview");
  var pInp = document.getElementById("manifest-url-custom");
  if (gInp) gInp.value = gUrl;
  if (pInp) pInp.value = pUrl;

  var gBtn = document.getElementById("install-btn-overview");
  var pBtn = document.getElementById("install-btn-custom");
  if (gBtn) gBtn.href = gStremio;
  if (pBtn) pBtn.href = pStremio;
}

function loadExts() {
  fetch("/api/extensions")
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(data){
      exts = data || [];
      updateMetrics();
      renderCards();
    })
    .catch(function(){});
}

function loadRepos() {
  fetch("/api/repos")
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(data){
      repos = data || [];
      document.getElementById("hdr-repo-count").textContent = repos.length;
      document.getElementById("st-repo-count").textContent = repos.length;
      renderReposModal();
    })
    .catch(function(){});
}

function loadProfile() {
  fetch("/api/profile/" + encodeURIComponent(pid))
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p){
      pData = p;
      pData._d = new Set(p.disabledExtensions || []);
      pData._e = new Set(p.enabledExtensions || []);
      pData._loaded = true;
      updateMetrics();
      renderCards();
    })
    .catch(function(){});
}

function isExtActiveInProfile(e) {
  if (e.enabled) {
    return !pData._d.has(e.internalName);
  } else {
    return pData._e.has(e.internalName);
  }
}

function updateMetrics() {
  var globalCount = exts.filter(function(e){ return e.enabled; }).length;
  var customCount = exts.filter(function(e){ return isExtActiveInProfile(e); }).length;

  var stExt = document.getElementById("st-ext-count");
  if (stExt) stExt.textContent = globalCount;

  var ovBadge = document.getElementById("overview-ext-count");
  if (ovBadge) ovBadge.textContent = globalCount;

  var customBadge = document.getElementById("custom-active-count");
  if (customBadge) customBadge.textContent = customCount + " of " + exts.length + " Sources Active";
}

function applyFilter(f, btn) {
  currentFilter = f;
  var btns = document.querySelectorAll(".preset-btn");
  for (var i = 0; i < btns.length; i++) btns[i].classList.remove("active");
  if (btn) btn.classList.add("active");
  renderCards();
}

function renderCards() {
  var q = (document.getElementById("ext-search") ? document.getElementById("ext-search").value : "").toLowerCase().trim();

  // 1. Overview Grid (Globally enabled only)
  var ovEl = document.getElementById("overview-grid");
  if (ovEl) {
    var ovExts = exts.filter(function(e){ return e.enabled; });
    if (!ovExts.length) {
      ovEl.innerHTML = '<div class="empty">No extensions enabled globally.</div>';
    } else {
      ovEl.innerHTML = ovExts.map(function(e){
        var initial = (e.name || "?").charAt(0).toUpperCase();
        var iconHtml = e.iconUrl
          ? '<img class="p-icon-img" src="' + esc(e.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt=""><div class="p-avatar" style="display:none">' + esc(initial) + '</div>'
          : '<div class="p-avatar">' + esc(initial) + '</div>';

        var typeBadges = (e.types || []).map(function(t){
          return '<span class="p-type-tag">' + esc(t) + '</span>';
        }).join("");

        return '<div class="overview-source-card" onclick="switchTab(\'customize\')">' +
          '<div class="p-top-row">' +
            iconHtml +
            '<span class="oc-status-pill"><span class="status-dot"></span>Active</span>' +
          '</div>' +
          '<div class="p-main-info">' +
            '<div class="p-name" title="' + esc(e.name) + '">' + esc(e.name) + '</div>' +
            '<div class="p-repo">' + esc(e.repoName || "Installed") + '</div>' +
          '</div>' +
          '<div class="p-badges-row">' + typeBadges + '</div>' +
          (e.description ? '<div class="p-desc">' + esc(e.description) + '</div>' : '') +
        '</div>';
      }).join("");
    }
  }

  // 2. Customize Grid (Interactive with checkboxes)
  var custEl = document.getElementById("sources-grid");
  if (custEl) {
    var filtered = exts.filter(function(e){
      if (q) {
        var matchName = (e.name || "").toLowerCase().indexOf(q) >= 0;
        var matchRepo = (e.repoName || "").toLowerCase().indexOf(q) >= 0;
        var matchDesc = (e.description || "").toLowerCase().indexOf(q) >= 0;
        if (!matchName && !matchRepo && !matchDesc) return false;
      }
      if (currentFilter === "movies") {
        return (e.types || []).some(function(t){ return /movie|series|tv/i.test(t); });
      } else if (currentFilter === "anime") {
        return (e.types || []).some(function(t){ return /anime/i.test(t); }) || /anime/i.test(e.name);
      } else if (currentFilter === "live") {
        return (e.types || []).some(function(t){ return /live|stream|iptv/i.test(t); }) || /live|iptv|tv/i.test(e.name);
      }
      return true;
    });

    if (!filtered.length) {
      custEl.innerHTML = '<div class="empty">No extensions match your filter.</div>';
    } else {
      custEl.innerHTML = filtered.map(function(e){
        var isSelected = isExtActiveInProfile(e);
        var initial = (e.name || "?").charAt(0).toUpperCase();

        var iconHtml = e.iconUrl
          ? '<img class="p-icon-img" src="' + esc(e.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt=""><div class="p-avatar" style="display:none">' + esc(initial) + '</div>'
          : '<div class="p-avatar">' + esc(initial) + '</div>';

        var chkHtml = '<div class="chk"><svg class="chk-svg" width="10" height="8" viewBox="0 0 10 8" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M1 4.2L3.5 6.7L9 1.2"/></svg></div>';

        var typeBadges = (e.types || []).map(function(t){
          return '<span class="p-type-tag">' + esc(t) + '</span>';
        }).join("");

        var goffBadge = !e.enabled ? '<span class="p-tag-goff">Global Off</span>' : '';

        return '<div class="provider-card ' + (isSelected ? "selected" : "") + '" onclick="toggleCard(\'' + esc(e.internalName).replace(/\'/g, "%27") + '\')">' +
          '<div class="p-top-row">' +
            iconHtml +
            chkHtml +
          '</div>' +
          '<div class="p-main-info">' +
            '<div class="p-name" title="' + esc(e.name) + '">' + esc(e.name) + '</div>' +
            '<div class="p-repo">' + esc(e.repoName || "Installed") + '</div>' +
          '</div>' +
          '<div class="p-badges-row">' + typeBadges + goffBadge + '</div>' +
          (e.description ? '<div class="p-desc">' + esc(e.description) + '</div>' : '') +
        '</div>';
      }).join("");
    }
  }
}

function toggleCard(name) {
  name = decodeURIComponent(name.replace(/%27/g, "'"));
  var ext = exts.find(function(e){ return e.internalName === name; });
  if (!ext) return;

  var willBeActive = !isExtActiveInProfile(ext);
  if (ext.enabled) {
    if (willBeActive) pData._d.delete(name); else pData._d.add(name);
  } else {
    if (willBeActive) pData._e.add(name); else pData._e.delete(name);
  }

  updateMetrics();
  renderCards();
  toast(ext.name + (willBeActive ? " added to" : " removed from") + " your profile");

  fetch("/api/profile/" + encodeURIComponent(pid) + "/toggle", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ internalName: name })
  }).then(function(r){ return r.json(); })
    .then(function(p){
      pData = p;
      pData._d = new Set(p.disabledExtensions || []);
      pData._e = new Set(p.enabledExtensions || []);
      updateMetrics();
      renderCards();
    })
    .catch(function(e){ toast("Error: " + e.message); });
}

function setAll(on) {
  var dis = [], en = [];
  exts.forEach(function(e) {
    if (!e.enabled) {
      if (on) en.push(e.internalName);
    } else {
      if (!on) dis.push(e.internalName);
    }
  });

  if (on) {
    pData._d = new Set();
    pData._e = new Set(en);
  } else {
    pData._d = new Set(dis);
    pData._e = new Set();
  }

  updateMetrics();
  renderCards();
  toast(on ? "All extensions enabled for your profile" : "Profile manifest cleared");

  fetch("/api/profile/" + encodeURIComponent(pid) + "/set", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ disabledExtensions: dis, enabledExtensions: en })
  }).then(function(r){ return r.json(); })
    .then(function(p){
      pData = p;
      pData._d = new Set(p.disabledExtensions || []);
      pData._e = new Set(p.enabledExtensions || []);
      updateMetrics();
      renderCards();
    })
    .catch(function(e){ toast("Error: " + e.message); });
}

function cpManifest(isCustom, btn) {
  var h = window.location.host;
  var base = window.location.protocol + "//" + h;
  var path = isCustom ? ("/u/" + encodeURIComponent(pid) + "/manifest.json") : "/manifest.json";
  var url = base + path;

  function onDone() {
    if (btn) {
      var lbl = btn.querySelector(".copy-lbl");
      if (lbl) {
        var origText = lbl.textContent;
        lbl.textContent = "Copied!";
        btn.style.borderColor = "var(--green)";
        btn.style.color = "var(--green)";
        setTimeout(function(){
          lbl.textContent = origText;
          btn.style.borderColor = "";
          btn.style.color = "";
        }, 1800);
      }
    }
    toast("Copied Stremio manifest URL!");
  }

  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url).then(onDone);
    return;
  }
  var ta = document.createElement("textarea");
  ta.value = url;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  document.execCommand("copy");
  document.body.removeChild(ta);
  onDone();
}

/* Repositories Modal */
function openReposModal() {
  var m = document.getElementById("repos-modal");
  if (m) m.classList.add("open");
  renderReposModal();
}
function closeReposModal() {
  var m = document.getElementById("repos-modal");
  if (m) m.classList.remove("open");
}
function renderReposModal() {
  var el = document.getElementById("repos-list");
  if (!el) return;
  if (!repos.length) {
    el.innerHTML = '<div class="empty" style="padding:16px">No repositories connected yet.</div>';
    return;
  }
  el.innerHTML = repos.map(function(r) {
    var status = r.isLoading
      ? '<span class="repo-badge" style="color:var(--accent)">Installing...</span>'
      : (r.error ? '<span class="repo-badge" style="color:var(--red)">Failed</span>' : '<span class="repo-badge" style="color:var(--green)">Active</span>');
    return '<div class="repo-card">' +
      '<div class="repo-info">' +
        '<div class="repo-name">' + esc(r.name || r.url) + '</div>' +
        '<div class="repo-url" title="' + esc(r.url) + '">' + esc(r.url) + '</div>' +
      '</div>' +
      '<div style="display:flex;align-items:center;gap:6px">' +
        '<span class="repo-badge">' + r.pluginCount + ' ext</span>' +
        status +
      '</div>' +
    '</div>';
  }).join("");
}

function normalizeRepoUrl(raw) {
  raw = (raw || "").trim();
  if (!raw) return null;
  if (!raw.includes("/") && !raw.includes(".") && !raw.includes(":")) return raw;
  if (!raw.includes("://") && raw.indexOf(".") < 0) {
    var parts = raw.split("/").filter(Boolean);
    if (parts.length === 2) return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/builds/repo.json";
    if (parts.length >= 3) return "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/repo.json";
  }
  if (raw.indexOf("github.com") >= 0 && raw.indexOf("raw.githubusercontent.com") < 0 && raw.indexOf(".json") < 0) {
    var seg = raw.replace(/^https?:\/\//, "").replace(/^\/+/, "").replace(/^github\.com\//, "").split("/").filter(Boolean);
    if (seg.length >= 2) return "https://raw.githubusercontent.com/" + seg[0] + "/" + seg[1] + "/builds/repo.json";
  }
  if (raw.indexOf("://") < 0) raw = "https://" + raw;
  return raw;
}

function addRepo() {
  var input = document.getElementById("repo-input");
  var url = normalizeRepoUrl(input ? input.value : "");
  if (!url) { toast("Please enter a valid repository URL"); return; }

  var btn = document.getElementById("repo-add-btn");
  if (btn) { btn.disabled = true; btn.textContent = "Adding..."; }

  fetch("/api/repos/add", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: url })
  }).then(function(r){ return r.json(); })
    .then(function(res){
      toast(res.message || "Adding repository...");
      if (input) input.value = "";
      loadRepos();
      loadExts();
      var count = 0;
      var interval = setInterval(function(){
        loadRepos();
        loadExts();
        if (++count > 10) clearInterval(interval);
      }, 3000);
    })
    .catch(function(err){ toast("Error: " + err.message); })
    .finally(function(){
      if (btn) { btn.disabled = false; btn.textContent = "Add"; }
    });
}

function esc(s) {
  return String(s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function toast(msg) {
  var el = document.getElementById("toast");
  if (!el) return;
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(function(){ el.classList.remove("show"); }, 2400);
}

updateUrls();
loadExts();
loadRepos();
loadProfile();
setInterval(function(){ loadExts(); loadRepos(); }, 15000);
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

