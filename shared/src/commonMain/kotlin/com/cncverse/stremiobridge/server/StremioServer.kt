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
                loadedApis.forEachIndexed { i, api ->
                    if (i > 0) sb.append(",")
                    val name = api.name.replace("\"", "\\\"")
                    val id = nameSlug(api.name).replace("\"", "\\\"")
                    val rawRepoUrl = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                        .find { it.internalName == api.pluginInternalName }?.repoUrl ?: ""
                    // Canonicalize: prefer the URL as stored in the known repos list so that
                    // refs/heads variants don't create phantom repo groups in the UI.
                    val knownRepo = com.cncverse.stremiobridge.state.RepoState.repos.value
                        .find { it.url == rawRepoUrl }
                        ?: com.cncverse.stremiobridge.state.RepoState.repos.value
                            .find { normalizeGhUrl(it.url) == normalizeGhUrl(rawRepoUrl) }
                    val repoUrl = (knownRepo?.url ?: rawRepoUrl).replace("\"", "\\\"")
                    val repoName = knownRepo?.name?.replace("\"", "\\\"") ?: ""
                    val iconUrl = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                        .find { it.internalName == api.internalName }?.iconUrl?.replace("\"", "\\\"") ?: ""
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
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CNCVerse Bridge</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#0a0b10;--panel:#0d0e13;--surface:#14151d;--card:#161822;--card2:#1b1d29;
  --border:#232634;--border2:#2a2d3a;
  --text:#e4e5ea;--text2:#9ca3af;--muted:#6b7280;
  --accent:#3b82f6;--accent2:#60a5fa;--accent-bg:rgba(59,130,246,.12);--accent-bd:rgba(59,130,246,.55);
  --pink:#ec4899;--pink2:#f43f5e;--green:#22c55e;--red:#f87171;
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:var(--bg);color:var(--text);min-height:100vh;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:14px;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:inherit;text-decoration:none}
button{font:inherit;cursor:pointer;border:none;background:none;color:inherit}
::-webkit-scrollbar{width:10px;height:10px}
::-webkit-scrollbar-thumb{background:#232634;border-radius:99px;border:2px solid var(--bg)}

/* ── Layout ── */
.sidebar{position:fixed;top:0;left:0;bottom:0;width:250px;background:var(--panel);border-right:1px solid #1a1c28;display:flex;flex-direction:column;z-index:60}
.mainwrap{margin-left:250px;min-height:100vh;display:flex;flex-direction:column}
.content{flex:1;width:100%;max-width:1220px;padding:26px 30px 80px}

/* ── Sidebar ── */
.brand{display:flex;align-items:center;gap:11px;padding:18px 16px 14px}
.brand-logo{width:36px;height:36px;border-radius:11px;background:linear-gradient(135deg,#6d28d9,#a78bfa);display:flex;align-items:center;justify-content:center;font-weight:900;font-size:11px;color:#fff;letter-spacing:-.5px;flex:0 0 auto;box-shadow:0 0 22px rgba(124,58,237,.35)}
.brand-name{font-size:14.5px;font-weight:700;color:#fff;letter-spacing:-.2px}
.brand-sub{font-size:11.5px;color:var(--muted);margin-top:1px}
.navsec{font-size:10.5px;font-weight:800;letter-spacing:.12em;text-transform:uppercase;color:var(--muted);padding:18px 18px 6px}
.navitem{display:flex;align-items:center;gap:11px;margin:2px 10px;padding:9px 12px;border-radius:9px;color:var(--text2);font-size:13px;font-weight:600;cursor:pointer;transition:background .15s,color .15s}
.navitem:hover{background:#12141d;color:var(--text)}
.navitem.active{background:#1a2030;color:#fff}
.navitem .ic{width:18px;height:18px;flex:0 0 auto}
.navbadge{margin-left:auto;min-width:20px;height:20px;padding:0 6px;border-radius:999px;background:var(--accent);color:#fff;font-size:11px;font-weight:700;display:flex;align-items:center;justify-content:center}
.side-foot{margin-top:auto;padding:16px 18px;font-size:11px;color:var(--muted);border-top:1px solid #171925}

/* ── Topbar ── */
.topbar{position:sticky;top:0;z-index:40;display:flex;align-items:center;gap:12px;height:58px;padding:0 30px;background:rgba(10,11,16,.88);backdrop-filter:blur(14px);border-bottom:1px solid #1a1c28}
.t-title{font-size:16px;font-weight:700;color:#fff;letter-spacing:-.2px}
.t-sub{font-size:12.5px;color:var(--muted);margin-top:1px}
.t-actions{margin-left:auto;display:flex;gap:8px}
.ibtn{width:34px;height:34px;border-radius:999px;background:var(--surface);border:1px solid var(--border);color:var(--text2);display:flex;align-items:center;justify-content:center;transition:.15s}
.ibtn:hover{border-color:var(--accent);color:#fff}
.ibtn.heart{color:var(--pink)}
.ibtn.heart:hover{border-color:var(--pink);color:var(--pink2)}
.ibtn svg{width:16px;height:16px}
.burger{display:none;width:34px;height:34px;border-radius:9px;color:var(--text2);align-items:center;justify-content:center}
.burger svg{width:20px;height:20px}
.scrim{position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:55;opacity:0;pointer-events:none;transition:.2s}
.scrim.show{opacity:1;pointer-events:auto}

/* ── Overview ── */
.ohero{padding:6px 0 20px}
.ohero h1{font-size:24px;font-weight:800;letter-spacing:-.5px;color:#fff}
.ohero p{color:var(--text2);font-size:13.5px;margin-top:5px;max-width:640px}
.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:18px}
.stat{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:16px 18px}
.stat .v{font-size:21px;font-weight:800;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.stat .l{font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin-top:4px}
.cols2{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-bottom:14px}
.card{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:18px 20px;margin-bottom:14px}
.card h2{font-size:14px;font-weight:700;color:#fff;display:flex;align-items:center;gap:8px}
.hint{font-size:12.5px;color:var(--text2);margin-top:3px;margin-bottom:12px}
.urlbox{display:flex;align-items:center;gap:8px;background:#0b0c12;border:1px solid var(--border2);border-radius:10px;padding:9px 12px}
.urlbox code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11.5px;color:#93c5fd;word-break:break-all;flex:1}
.cbtn{flex:0 0 auto;background:var(--card2);border:1px solid var(--border2);border-radius:8px;color:var(--text2);padding:5px 12px;font-size:11.5px;font-weight:600;transition:.15s}
.cbtn:hover{color:#fff;border-color:var(--accent)}
.btnrow{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:12px}
.btn-p{display:inline-flex;align-items:center;gap:7px;background:var(--accent);color:#fff;font-size:12.5px;font-weight:700;padding:8px 16px;border-radius:9px;transition:.15s}
.btn-p:hover{background:#2f6fe0}
.btn-p svg{width:13px;height:13px}
.pidtag{font-size:11px;color:var(--muted);background:var(--card2);border:1px solid var(--border);padding:3px 9px;border-radius:999px}
.steps{display:flex;flex-direction:column;gap:9px;margin-top:12px}
.step{display:flex;gap:12px;background:var(--card);border:1px solid var(--border);border-radius:11px;padding:12px 14px}
.snum{width:24px;height:24px;border-radius:50%;background:linear-gradient(135deg,#3b82f6,#60a5fa);color:#fff;font-weight:800;font-size:11px;display:flex;align-items:center;justify-content:center;flex:0 0 auto;margin-top:1px}
.step b{display:block;font-size:13px;font-weight:700;color:var(--text)}
.step span{font-size:12px;color:var(--text2)}

/* ── Page head + filter tabs ── */
.ph h1{font-size:20px;font-weight:800;color:#fff;letter-spacing:-.4px}
.ph p{font-size:13px;color:var(--text2);margin-top:3px;max-width:620px}
.tabs{display:flex;gap:18px;flex-wrap:wrap;margin:16px 0 18px;border-bottom:1px solid #1a1c28}
.ptab{font-size:12.5px;font-weight:600;color:var(--muted);padding:6px 2px 9px;border-bottom:2px solid transparent;cursor:pointer;transition:.15s;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:220px}
.ptab:hover{color:var(--text)}
.ptab.on{color:#fff;border-bottom-color:var(--accent)}

/* ── Repo groups + extension cards ── */
.rgroup{margin-bottom:20px}
.rghead{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.rgletter{width:26px;height:26px;border-radius:8px;background:var(--card2);border:1px solid var(--border);color:var(--accent2);font-size:12px;font-weight:800;display:flex;align-items:center;justify-content:center;flex:0 0 auto}
.rgname{font-size:13px;font-weight:700;color:var(--text);flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.rgcount{font-size:11.5px;color:var(--muted);margin-left:auto}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}
.ecard{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:14px 16px;display:flex;align-items:center;gap:11px;transition:border-color .15s,background .15s}
.ecard.vert{flex-direction:column;align-items:stretch;gap:9px}
.ecard:hover{border-color:#2e3750}
.ecard.selable{cursor:pointer}
.ecard.selable:hover{border-color:var(--accent-bd)}
.ecard.sel{border-color:var(--accent-bd);background:linear-gradient(180deg,rgba(59,130,246,.08),rgba(59,130,246,.02)),var(--surface)}
.eic{width:34px;height:34px;border-radius:9px;object-fit:cover;flex:0 0 auto}
.eletter{background:var(--card2);border:1px solid var(--border);color:var(--accent2);font-weight:800;font-size:13px;display:flex;align-items:center;justify-content:center}
.ec-main{min-width:0;flex:1}
.ename{font-size:13.5px;font-weight:600;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.etags{font-size:11.5px;color:var(--muted);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.st{flex:0 0 auto;font-size:11px;font-weight:700;padding:3px 9px;border-radius:999px}
.st.on{background:rgba(34,197,94,.12);color:var(--green)}
.st.off{background:rgba(248,113,113,.12);color:var(--red)}
.ec-top{display:flex;align-items:center;gap:11px;min-width:0;flex:1}
.radio{width:20px;height:20px;border-radius:50%;border:2px solid #3a4054;flex:0 0 auto;position:relative;transition:.15s}
.ecard.sel .radio{background:var(--accent);border-color:var(--accent)}
.ecard.sel .radio:after{content:"";position:absolute;left:5.5px;top:2px;width:5px;height:10px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}

/* ── Profile progress + section heads + pills ── */
.pcard{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:15px 18px;margin-bottom:22px}
.prow{display:flex;align-items:baseline;justify-content:space-between;gap:10px}
.prow .pl{font-size:13px;color:var(--text2)}
.prow .pl b{color:#fff;font-weight:700}
.ppct{font-size:13.5px;font-weight:800;color:var(--pink)}
.ptrack{height:7px;border-radius:999px;background:#20232f;margin-top:11px;overflow:hidden}
.pfill{height:100%;border-radius:999px;background:linear-gradient(90deg,var(--pink),var(--pink2));transition:width .35s}
.sechead{display:flex;align-items:center;gap:8px;margin:4px 0 12px}
.sechead .sic{width:16px;height:16px;color:var(--accent2);flex:0 0 auto}
.sh-t{font-size:12px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--text)}
.scount{margin-left:auto;font-size:12.5px;color:var(--muted)}
.lnk{font-size:12.5px;font-weight:600;color:var(--accent2);cursor:pointer;margin-left:14px}
.lnk:hover{text-decoration:underline}
.tip{font-size:12px;color:var(--muted);margin-top:14px}
.pillrow{display:flex;gap:8px;flex-wrap:wrap}
.pill{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:7px 16px;border-radius:999px;border:1px solid var(--border2);background:var(--surface);color:var(--text2);font-size:12.5px;font-weight:600;cursor:pointer;transition:.15s}
.pill:hover{color:var(--text)}
.pill.on{background:var(--accent);border-color:var(--accent);color:#fff}

/* ── Repos page ── */
.addrow{display:flex;gap:10px;flex-wrap:wrap}
.addrow input{flex:1;min-width:220px;background:#0b0c12;border:1px solid var(--border2);border-radius:10px;padding:9px 12px;color:var(--text);font:inherit;font-size:13px;outline:none;transition:border-color .15s}
.addrow input:focus{border-color:var(--accent)}
.addrow input::placeholder{color:var(--muted)}
.btn-p:disabled{opacity:.55;cursor:default}
.rgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}
.rcard{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:14px 16px;display:flex;flex-direction:column;gap:7px;transition:border-color .15s}
.rcard:hover{border-color:#2e3750}
.rcard .rurl{font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.goffchip{flex:0 0 auto;font-size:9.5px;font-weight:800;letter-spacing:.05em;text-transform:uppercase;padding:2px 8px;border-radius:999px;background:rgba(251,191,36,.13);color:#fbbf24}
.ecard.goff:not(.sel){border-style:dashed;opacity:.88}

/* ── Misc ── */
.empty{padding:26px;text-align:center;color:var(--muted);font-size:12.5px}
.toast{position:fixed;bottom:18px;left:50%;transform:translateX(-50%);background:var(--card2);border:1px solid var(--border2);color:var(--text);padding:9px 18px;border-radius:11px;font-size:12.5px;box-shadow:0 8px 28px rgba(0,0,0,.45);opacity:0;transition:.2s;pointer-events:none;z-index:99}
.toast.show{opacity:1}

@media(max-width:920px){
  .sidebar{transform:translateX(-100%);transition:transform .22s ease}
  .sidebar.open{transform:none;box-shadow:0 0 40px rgba(0,0,0,.5)}
  .mainwrap{margin-left:0}
  .burger{display:flex}
  .content{padding:16px 14px 70px}
  .topbar{padding:0 14px}
  .cols2{grid-template-columns:1fr}
}
@media(max-width:560px){
  .content{padding:12px 12px 64px}
  .stats{grid-template-columns:repeat(3,1fr)}
  .grid{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}
  .rgrid{grid-template-columns:1fr}
  .t-sub{display:none}
  .ohero h1{font-size:19px}
  .tabs{gap:10px 14px}
  /* Source-filter chips: 2 per row */
  .pill{flex:0 0 calc(50% - 4px);padding:7px 10px}
  .card{padding:14px 14px}
  .addrow input{font-size:12.5px}
  .rcard .rurl{font-size:10.5px}
  .etags{font-size:11px}
}
</style>
</head>
<body>
<div class="scrim" id="scrim" onclick="closeSidebar()"></div>

<aside class="sidebar" id="sidebar">
  <div class="brand">
    <div class="brand-logo">CNC</div>
    <div class="brand-txt">
      <div class="brand-name">CNCVerse Bridge</div>
      <div class="brand-sub">Addon gateway</div>
    </div>
  </div>
  <div class="navsec">Configure</div>
  <a class="navitem active" id="nav-overview" onclick="showPage('overview')">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
    Overview
  </a>
  <a class="navitem" id="nav-extensions" onclick="showPage('extensions')">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/></svg>
    Extensions
    <span class="navbadge" id="nav-badge">0</span>
  </a>
  <a class="navitem" id="nav-repos" onclick="showPage('repos')">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/></svg>
    Repositories
  </a>
  <a class="navitem" id="nav-profile" onclick="showPage('profile')">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5"/></svg>
    My Profile
  </a>
  <div class="navsec">Support</div>
  <a class="navitem" href="https://t.me/cncverse" target="_blank" rel="noopener">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4Z"/></svg>
    Telegram
  </a>
  <a class="navitem" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
    <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
    Support Project
  </a>
  <div class="side-foot">Your profile is stored on this server</div>
</aside>

<div class="mainwrap">
  <header class="topbar">
    <button class="burger" onclick="toggleSidebar()" aria-label="Menu">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
    </button>
    <div class="t-title" id="t-title">Overview</div>
    <div class="t-sub" id="t-sub"></div>
    <div class="t-actions">
      <a class="ibtn" href="https://t.me/cncverse" target="_blank" rel="noopener" title="Telegram">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4Z"/></svg>
      </a>
      <a class="ibtn heart" href="https://cncverse.pages.dev" target="_blank" rel="noopener" title="Support the project">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
      </a>
    </div>
  </header>

  <main class="content">

    <section id="page-overview">
      <div class="ohero">
        <h1>Your personal Stremio addon gateway</h1>
        <p>Every CloudStream extension on this server, straight into Stremio, Nuvio and friends — plus an optional personal profile that curates exactly what appears in yours.</p>
      </div>
      <div class="stats">
        <div class="stat"><div class="v" id="st-ext">…</div><div class="l">Extensions</div></div>
        <div class="stat"><div class="v" id="st-repo">…</div><div class="l">Repositories</div></div>
        <div class="stat"><div class="v" id="st-since">…</div><div class="l">Profile created</div></div>
      </div>
      <div class="cols2">
        <div class="card">
          <h2>Global manifest</h2>
          <div class="hint">Everything the admin has enabled — the same addon for everyone on this server.</div>
          <div class="urlbox"><code id="g-url"></code><button class="cbtn" onclick="cpEl('g-url')">Copy</button></div>
          <div class="btnrow"><a class="btn-p" id="g-btn" href="#"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4.5v15l13-7.5Z"/></svg>Open in Stremio</a></div>
        </div>
        <div class="card">
          <h2>Your profile manifest</h2>
          <div class="hint">Only the extensions you pick — stored on this server, so the URL keeps working from any device or player.</div>
          <div class="urlbox"><code id="p-url"></code><button class="cbtn" onclick="cpEl('p-url')">Copy</button></div>
          <div class="btnrow"><a class="btn-p" id="p-btn" href="#"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4.5v15l13-7.5Z"/></svg>Open in Stremio</a><span class="pidtag" id="pid-lbl"></span></div>
        </div>
      </div>
      <div class="card">
        <h2>How it works</h2>
        <div class="steps">
          <div class="step"><div class="snum">1</div><div><b>Your browser gets a unique profile</b><span>Auto-created and stored on this server. Each device or browser keeps its own ID.</span></div></div>
          <div class="step"><div class="snum">2</div><div><b>Copy your profile manifest URL</b><span>Use it in Stremio instead of the global URL. Your picks follow the URL, on any player.</span></div></div>
          <div class="step"><div class="snum">3</div><div><b>Toggle extensions in My Profile</b><span>Pick what appears in your Stremio — even extensions that are disabled globally. Other users are not affected.</span></div></div>
          <div class="step"><div class="snum">4</div><div><b>Missing a repository? Add it</b><span>Every extension it offers downloads automatically, disabled by default — enable the ones you want in your profile.</span></div></div>
        </div>
      </div>
    </section>

    <section id="page-extensions" style="display:none">
      <div class="ph">
        <h1>Extensions</h1>
        <p>Everything installed on this server. The admin decides what the global manifest serves — anything disabled globally can still be added to your own profile.</p>
      </div>
      <div class="tabs" id="ext-tabs"></div>
      <div id="ext-body"><div class="empty">Loading extensions…</div></div>
    </section>

    <section id="page-repos" style="display:none">
      <div class="ph">
        <h1>Repositories</h1>
        <p>Extension repositories installed on this server. Add one that is missing and every extension it offers is downloaded automatically — disabled by default, so you choose what shows up in your profile.</p>
      </div>
      <div class="card">
        <h2>Add a repository</h2>
        <div class="hint">Paste a repo.json URL, a GitHub shorthand like <b>user/repo</b>, or a shortcode such as <b>Hexated</b>.</div>
        <div class="addrow">
          <input type="text" id="repo-input" placeholder="https://raw.githubusercontent.com/user/repo/builds/repo.json" onkeydown="if(event.key==='Enter')addRepo()">
          <button class="btn-p" id="repo-add-btn" onclick="addRepo()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg><span id="repo-add-lbl">Add repository</span></button>
        </div>
        <div class="tip" style="margin-top:11px">Repos added here are installed globally for everyone on this server. Their extensions stay disabled until you add them from My Profile.</div>
      </div>
      <div class="sechead" style="margin-top:22px">
        <svg class="sic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/></svg>
        <span class="sh-t">Installed repositories</span>
        <span class="scount" id="repo-count">0</span>
      </div>
      <div class="rgrid" id="repo-grid"><div class="empty">Loading repositories…</div></div>
    </section>

    <section id="page-profile" style="display:none">
      <div class="ph">
        <h1>My Profile</h1>
        <p>Select providers for your personal manifest — including ones the admin keeps out of the global manifest. Stored on this server — other users are not affected.</p>
      </div>
      <div class="pcard">
        <div class="prow"><span class="pl"><b id="pf-count">0 of 0</b> extensions in your manifest</span><span class="ppct" id="pf-pct">0%</span></div>
        <div class="ptrack"><div class="pfill" id="pf-fill" style="width:0%"></div></div>
      </div>
      <div class="sechead">
        <svg class="sic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
        <span class="sh-t">Extensions</span>
        <span class="scount" id="pf-selected">0 selected</span>
        <a class="lnk" onclick="setAll(true)">Select all</a>
        <a class="lnk" onclick="setAll(false)">Clear all</a>
      </div>
      <div class="tabs" id="pro-tabs"></div>
      <div class="grid" id="pro-grid"><div class="empty">Loading…</div></div>
      <div class="tip">Tip: pick your favourite sources instead of everything — fewer catalogs means Discover loads faster in Stremio. Add more only if you watch regional or anime content.</div>
      <div class="sechead" style="margin-top:26px">
        <svg class="sic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M4 17h16"/><circle cx="14" cy="7" r="2.5"/><circle cx="8" cy="17" r="2.5"/></svg>
        <span class="sh-t">Content types</span>
      </div>
      <div class="pillrow" id="type-pills"></div>
    </section>

  </main>
</div>

<div class="toast" id="toast"></div>

<script>
"use strict";
var PK = "cnc_pid";
var pid = localStorage.getItem(PK);
if (!pid) { pid = "p" + Math.random().toString(36).substr(2,14) + Date.now().toString(36); localStorage.setItem(PK, pid); }
var exts = [], repos = [], pData = null, pReady = false, booted = false;
var curPage = "overview";
var repoFilter = "_all", repoKeys = ["_all"], typeOn = {};

function esc(s) { return String(s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;"); }
function toast(m) { var el = document.getElementById("toast"); el.textContent = m; el.classList.add("show"); clearTimeout(el._t); el._t = setTimeout(function(){ el.classList.remove("show"); }, 2600); }
function cpEl(id) { cpTxt(document.getElementById(id).textContent); }
function cpTxt(t) {
  if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(t).then(function(){ toast("Copied to clipboard!"); }); return; }
  var ta = document.createElement("textarea"); ta.value = t; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); toast("Copied to clipboard!");
}
function toggleSidebar() { document.getElementById("sidebar").classList.toggle("open"); document.getElementById("scrim").classList.toggle("show"); }
function closeSidebar() { document.getElementById("sidebar").classList.remove("open"); document.getElementById("scrim").classList.remove("show"); }

function showPage(p) {
  curPage = p;
  var pages = ["overview","extensions","repos","profile"];
  for (var i = 0; i < pages.length; i++) {
    var key = pages[i];
    document.getElementById("nav-" + key).classList.toggle("active", key === p);
    document.getElementById("page-" + key).style.display = key === p ? "" : "none";
  }
  closeSidebar();
  if (p === "profile" && !pReady) loadProfile();
  render();
}

function initUrls() {
  var base = window.location.protocol + "//" + window.location.host;
  var host = window.location.host;
  document.getElementById("g-url").textContent = base + "/manifest.json";
  document.getElementById("g-btn").href = "stremio://" + host + "/manifest.json";
  document.getElementById("p-url").textContent = base + "/u/" + encodeURIComponent(pid) + "/manifest.json";
  document.getElementById("p-btn").href = "stremio://" + host + "/u/" + encodeURIComponent(pid) + "/manifest.json";
  document.getElementById("pid-lbl").textContent = "ID " + pid.substring(0,10) + "…";
}

function activeExts() { return exts.filter(function(e){ return e.enabled; }); }
function isOn(e) {
  if (!e.enabled) return !!(pData && pData._e && pData._e.has(e.internalName));
  return !(pData && pData._d && pData._d.has(e.internalName));
}

function byRepo(list) {
  var m = {}, ord = [];
  list.forEach(function(e) {
    var k = e.repoUrl || "_";
    if (!m[k]) { m[k] = { key: k, name: e.repoName || e.repoUrl || "Unknown repo", items: [] }; ord.push(k); }
    m[k].items.push(e);
  });
  return ord.map(function(k){ return m[k]; });
}

function prettyType(t) {
  t = String(t||"").toLowerCase();
  if (t === "movie") return "Movies";
  if (t === "tv" || t === "live") return "Live TV";
  if (t === "series") return "Series";
  if (t === "other") return "Other";
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function tagsFor(e) {
  var t = (e.types||[]).map(prettyType);
  if (t.length > 3) t = t.slice(0,3);
  if (e.repoName) t.push(e.repoName);
  return t.join(" · ");
}

function iconHtml(e) {
  var initial = esc((e.name||"?").charAt(0).toUpperCase());
  if (e.iconUrl)
    return '<img class="eic" src="' + esc(e.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt=""><div class="eic eletter" style="display:none">' + initial + '</div>';
  return '<div class="eic eletter">' + initial + '</div>';
}

function repoTabs(groups) {
  repoKeys = ["_all"];
  var h = '<a class="ptab' + (repoFilter === "_all" ? " on" : "") + '" onclick="setRepo(0)">All</a>';
  groups.forEach(function(g, i) {
    repoKeys.push(g.key);
    h += '<a class="ptab' + (repoFilter === g.key ? " on" : "") + '" onclick="setRepo(' + (i+1) + ')">' + esc(g.name) + '</a>';
  });
  return h;
}
function setRepo(i) { repoFilter = repoKeys[i] || "_all"; render(); }

function typeOk(e) {
  var list = e.types || [];
  if (!list.length) return true;
  for (var i = 0; i < list.length; i++) { if (typeOn[list[i]] !== false) return true; }
  return false;
}
function cardVisible(e) {
  var repoOk = repoFilter === "_all" || (e.repoUrl || "_") === repoFilter;
  return repoOk && typeOk(e);
}

function renderTopbar() {
  var titles = { overview: "Overview", extensions: "Extensions", repos: "Repositories", profile: "My Profile" };
  document.getElementById("t-title").textContent = titles[curPage] || "Overview";
  var act = activeExts(), repoMap = {};
  act.forEach(function(e){ repoMap[e.repoUrl || "_"] = 1; });
  var nR = Object.keys(repoMap).length;
  var sub = act.length + " extension" + (act.length === 1 ? "" : "s") + " · " + nR + " repositor" + (nR === 1 ? "y" : "ies");
  if (curPage === "repos" && repos.length) {
    sub = repos.length + " repositor" + (repos.length === 1 ? "y" : "ies") + " installed";
  }
  if (curPage === "profile" && pReady && pData) {
    var sel = exts.filter(isOn).length;
    sub = sel + " of " + exts.length + " in your manifest";
  }
  document.getElementById("t-sub").textContent = sub;
  document.getElementById("nav-badge").textContent = act.length;
}

function renderOverview() {
  var el = document.getElementById("st-since");
  if (pReady && pData && pData.createdAt > 0) {
    var d = new Date(pData.createdAt);
    el.textContent = d.toLocaleDateString();
  } else if (pReady) {
    el.textContent = "—";
  }
  if (!booted) return;
  var act = activeExts(), repoMap = {};
  act.forEach(function(e){ repoMap[e.repoUrl || "_"] = 1; });
  document.getElementById("st-ext").textContent = exts.length;
  document.getElementById("st-repo").textContent = repos.length || Object.keys(repoMap).length;
}

function renderExtensions() {
  if (!booted) return;
  var groups = byRepo(exts);
  document.getElementById("ext-tabs").innerHTML = repoTabs(groups);
  var el = document.getElementById("ext-body");
  if (!exts.length) { el.innerHTML = '<div class="empty">No extensions loaded yet.</div>'; return; }
  var vis = exts.filter(cardVisible);
  var g2 = byRepo(vis);
  el.innerHTML = g2.map(function(g) {
    return '<div class="rgroup"><div class="rghead"><div class="rgletter">' + esc(g.name.charAt(0).toUpperCase()) + '</div>'
      + '<span class="rgname">' + esc(g.name) + '</span>'
      + '<span class="rgcount">' + g.items.length + (g.items.length === 1 ? " extension" : " extensions") + '</span></div>'
      + '<div class="grid">' + g.items.map(function(e) {
        return '<div class="ecard">' + iconHtml(e)
          + '<div class="ec-main"><div class="ename">' + esc(e.name) + '</div>'
          + '<div class="etags">' + esc(tagsFor(e)) + '</div></div>'
          + '<span class="st ' + (e.enabled ? "on" : "off") + '">' + (e.enabled ? "Active" : "Disabled by admin") + '</span></div>';
      }).join("") + '</div></div>';
  }).join("") || '<div class="empty">Nothing matches this filter.</div>';
}

function typePillsHtml() {
  var seen = {}, list = [];
  exts.forEach(function(e) {
    (e.types||[]).forEach(function(t) { if (!seen[t]) { seen[t] = 1; list.push(t); } });
  });
  list.sort();
  if (list.length < 2) return '<span class="tip" style="margin:0">All content types are already included.</span>';
  return list.map(function(t) {
    var on = typeOn[t] !== false;
    return '<a class="pill' + (on ? " on" : "") + '" onclick="togType(\'' + esc(t) + '\')">' + esc(prettyType(t)) + (on ? " ✓" : "") + '</a>';
  }).join("");
}
function togType(t) { typeOn[t] = typeOn[t] === false; render(); }

function renderProfile() {
  var el = document.getElementById("pro-grid");
  if (!booted) return;
  if (!pReady) { el.innerHTML = '<div class="empty">Loading your profile…</div>'; return; }
  var all = exts;
  var groups = byRepo(all);
  document.getElementById("pro-tabs").innerHTML = repoTabs(groups);
  var vis = all.filter(cardVisible);
  var sel = all.filter(isOn).length;
  var pct = all.length ? Math.round(sel * 100 / all.length) : 0;
  document.getElementById("pf-count").textContent = sel + " of " + all.length;
  document.getElementById("pf-pct").textContent = pct + "%";
  document.getElementById("pf-fill").style.width = (sel > 0 ? Math.max(pct, 4) : 0) + "%";
  document.getElementById("pf-selected").textContent = sel + " selected";
  document.getElementById("type-pills").innerHTML = typePillsHtml();
  if (!all.length) { el.innerHTML = '<div class="empty">No extensions available yet.</div>'; return; }
  el.innerHTML = vis.map(function(e) {
    var on = isOn(e);
    return '<div class="ecard vert selable' + (on ? " sel" : "") + (e.enabled ? "" : " goff") + '" onclick="tog(\'' + esc(e.internalName) + '\')">'
      + '<div class="ec-top">' + iconHtml(e)
      + '<div class="ename">' + esc(e.name) + '</div>'
      + (e.enabled ? "" : '<span class="goffchip" title="Disabled in the global manifest — add it here to use it">Global off</span>')
      + '<span class="radio"></span></div>'
      + '<div class="etags">' + esc(tagsFor(e)) + '</div></div>';
  }).join("") || '<div class="empty">Nothing matches this filter.</div>';
}

function renderRepos() {
  var el = document.getElementById("repo-grid");
  if (!el) return;
  document.getElementById("repo-count").textContent = repos.length + (repos.length === 1 ? " repository" : " repositories");
  if (!repos.length) { el.innerHTML = '<div class="empty">No repositories installed yet.</div>'; return; }
  el.innerHTML = repos.map(function(r) {
    var initial = esc((r.name||"?").charAt(0).toUpperCase());
    var icon = r.iconUrl
      ? '<img class="eic" src="' + esc(r.iconUrl) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\'" alt=""><div class="eic eletter" style="display:none">' + initial + '</div>'
      : '<div class="eic eletter">' + initial + '</div>';
    var status;
    if (r.isLoading) status = '<span class="st on" style="background:rgba(59,130,246,.12);color:var(--accent2)">Installing…</span>';
    else if (r.error) status = '<span class="st off" title="' + esc(r.error) + '">Failed</span>';
    else status = '<span class="st on">Active</span>';
    return '<div class="rcard">'
      + '<div class="ec-top">' + icon
      + '<div class="ec-main"><div class="ename">' + esc(r.name) + '</div>'
      + '<div class="rurl">' + esc(r.url) + '</div></div>' + status + '</div>'
      + '<div class="etags">' + r.pluginCount + (r.pluginCount === 1 ? " extension" : " extensions")
      + (r.description ? " · " + esc(r.description) : "") + '</div></div>';
  }).join("");
}

function render() {
  renderTopbar();
  renderOverview();
  renderExtensions();
  renderRepos();
  renderProfile();
}

function loadExts() {
  fetch("/api/extensions")
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(list) { exts = list || []; booted = true; render(); })
    .catch(function() {});
}

function loadRepos() {
  fetch("/api/repos")
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(list) { repos = list || []; renderRepos(); renderTopbar(); renderOverview(); })
    .catch(function() {});
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
  if (!url) { toast("Enter a repository URL"); return; }
  var btn = document.getElementById("repo-add-btn");
  var lbl = document.getElementById("repo-add-lbl");
  if (btn) btn.disabled = true;
  if (lbl) lbl.textContent = "Adding…";
  fetch("/api/repos/add", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url: url })
  }).then(function(r) { return r.json().catch(function() { return {}; }).then(function(j) {
    if (!r.ok && !j.message) throw new Error("HTTP " + r.status);
    return j;
  }); })
    .then(function(j) {
      if (j.ok === false) { toast(j.message || "Could not add repository"); return; }
      toast(j.message || "Adding repository…");
      if (input) input.value = "";
      loadRepos();
      var n = 0;
      var t = setInterval(function() { loadRepos(); loadExts(); if (++n > 40) clearInterval(t); }, 3000);
    })
    .catch(function(e) { toast("Error: " + e.message); })
    .finally(function() {
      var b = document.getElementById("repo-add-btn");
      var l = document.getElementById("repo-add-lbl");
      if (b) b.disabled = false;
      if (l) l.textContent = "Add repository";
    });
}

function loadProfile() {
  fetch("/api/profile/" + encodeURIComponent(pid))
    .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p) { pData = p; pData._d = new Set(p.disabledExtensions || []); pData._e = new Set(p.enabledExtensions || []); pReady = true; render(); })
    .catch(function() { document.getElementById("pro-grid").innerHTML = '<div class="empty">Could not load your profile.</div>'; });
}

function tog(name) {
  fetch("/api/profile/" + encodeURIComponent(pid) + "/toggle", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ internalName: name })
  }).then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p) { pData = p; pData._d = new Set(p.disabledExtensions || []); pData._e = new Set(p.enabledExtensions || []); pReady = true; render(); toast(name + (p.nowEnabled ? " added to" : " removed from") + " your manifest"); })
    .catch(function(e) { toast("Error: " + e.message); });
}

function setAll(on) {
  var dis = [], en = [];
  exts.forEach(function(e) {
    if (!e.enabled) { if (on) en.push(e.internalName); }
    else if (!on) { dis.push(e.internalName); }
  });
  fetch("/api/profile/" + encodeURIComponent(pid) + "/set", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ disabledExtensions: dis, enabledExtensions: en })
  }).then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p) { pData = p; pData._d = new Set(p.disabledExtensions || []); pData._e = new Set(p.enabledExtensions || []); pReady = true; render(); toast(on ? "All extensions added to your manifest" : "Manifest cleared"); })
    .catch(function(e) { toast("Error: " + e.message); });
}

initUrls();
loadProfile();
loadExts();
loadRepos();
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

