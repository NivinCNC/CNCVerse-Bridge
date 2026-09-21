package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.RepoState
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
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Shared provider catalog cache: provider internalName -> list of StremioCatalogDef.
     * Reused across all profiles so manifests never block on re-fetching sections.
     */
    val providerCatalogCache: MutableMap<String, List<StremioCatalogDef>> = ConcurrentHashMap()

    /**
     * Cached home page catalog results: "$type:$id:$genre" -> list of StremioMeta.
     */
    val homePageCatalogCache: MutableMap<String, List<StremioMeta>> = ConcurrentHashMap()

    /**
     * Cached binary bytes for the official CNCVerse logo / favicon.
     */
    val logoBytes: ByteArray? by lazy {
        runCatching {
            Thread.currentThread().contextClassLoader?.getResourceAsStream("logo.png")?.readBytes()
                ?: File("logo.png").takeIf { it.exists() }?.readBytes()
                ?: File("shared/src/commonMain/resources/logo.png").takeIf { it.exists() }?.readBytes()
        }.getOrNull()
    }

    private val manifestRefreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicRefreshJob: kotlinx.coroutines.Job? = null
    private val isRefreshing = AtomicBoolean(false)
    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes

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

    /**
     * Removes a set of plugin IDs from every profile's disabled / enabledOverrides
     * sets so that deleted-repo extensions don't linger in user profile data.
     *
     * [pluginIds] should contain every identifier variant for the removed plugins
     * (both nameSlug form and raw CS3 internalName) so the cleanup is thorough.
     */
    internal fun cleanRemovedPluginsFromProfiles(pluginIds: Set<String>) {
        if (pluginIds.isEmpty() || profiles.isEmpty()) return
        var changed = false
        val keys = profiles.keys.toList()
        for (pid in keys) {
            val rec = profiles[pid] ?: continue
            val newDisabled = rec.disabled - pluginIds
            val newOverrides = rec.enabledOverrides - pluginIds
            if (newDisabled != rec.disabled || newOverrides != rec.enabledOverrides) {
                profiles[pid] = rec.copy(disabled = newDisabled, enabledOverrides = newOverrides)
                changed = true
            }
        }
        if (changed) saveProfiles()
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

    internal fun saveDisabledPlugins() {
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

        initFastCatalogs()
        startPeriodicRefreshJob()

        return targetPort
    }

    fun stop() {
        stopPeriodicRefreshJob()
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        engine?.stop(0, 500)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    /**
     * Canonical identifiers for a loaded extension: the per-API internalName and the
     * backing plugin's internalName. These are always unique per plugin file and are
     * the primary keys used for the disabled set.
     *
     * NOTE: We deliberately exclude nameSlug(api.name) here. Display names are NOT
     * unique — two repos can ship a plugin with the same display name (e.g. "Netflix"),
     * and using the slug would cause disabling one to silently disable the other.
     * Slug matching is handled separately as a legacy-only, uniqueness-guarded fallback.
     */
    private fun canonicalIds(api: MainApiWrapper): List<String> =
        listOf(api.internalName, api.pluginInternalName).distinct()

    /**
     * Returns the display-name slug for [api] **only if** no other currently loaded
     * API shares the same slug. When two plugins have the same display name (cross-repo
     * duplicates) the slug is ambiguous and must NOT be used to identify either one.
     */
    private fun unambiguousSlug(api: MainApiWrapper): String? {
        val slug = nameSlug(api.name)
        val sharers = loadedApis.count { nameSlug(it.name) == slug }
        return if (sharers == 1) slug else null
    }

    /**
     * Returns all alias keys in [disabledPlugins] that belong exclusively to the
     * extension identified by [internalName]. Slugs are only included when they are
     * unambiguous (unique to this plugin) — this prevents enabling plugin A from
     * accidentally removing a shared slug that plugin B still needs.
     */
    private fun allAliasesInDisabled(internalName: String): Set<String> {
        val result = mutableSetOf(internalName)
        // Collect canonical IDs from the matching loaded API(s)
        val matchingApis = loadedApis.filter { canonicalIds(it).contains(internalName) }
        matchingApis.flatMapTo(result) { canonicalIds(it) }
        // Add unambiguous slug(s) so legacy slug entries are cleaned up on enable
        matchingApis.forEach { api ->
            unambiguousSlug(api)?.let { slug ->
                result += slug
                result += api.name          // exact display name variant
            }
        }
        // Also cover display name from installed plugin metadata (pre-load legacy)
        RepoState.installedPlugins.value
            .find { it.internalName == internalName }
            ?.let { inst ->
                result += inst.internalName
                if (inst.displayName.isNotBlank()) {
                    val dn = inst.displayName
                    val slug = nameSlug(dn)
                    // Only include display name / slug if no OTHER installed plugin shares it
                    val slugSharers = RepoState.installedPlugins.value.count { nameSlug(it.displayName) == slug }
                    if (slugSharers == 1) {
                        result += dn
                        result += slug
                    }
                }
            }
        return result
    }

    /**
     * Toggles a plugin's enabled state and persists it. Returns the new enabled state.
     *
     * When **enabling**, removes ALL alias entries that exclusively belong to this
     * plugin (canonical IDs + unambiguous slug) so legacy ghost entries are cleared
     * without touching sibling plugins that share the same display name.
     */
    fun togglePluginDisabled(internalName: String): Boolean {
        val isCurrentlyDisabled = disabledPlugins.contains(internalName) ||
            loadedApis.any { api -> canonicalIds(api).contains(internalName) && isGloballyDisabled(api) }
        return if (isCurrentlyDisabled) {
            allAliasesInDisabled(internalName).forEach { disabledPlugins.remove(it) }
            saveDisabledPlugins()
            true
        } else {
            disabledPlugins.add(internalName)
            saveDisabledPlugins()
            false
        }
    }

    /** Sets a plugin's global enabled state and persists it. */
    fun setPluginDisabled(internalName: String, disabled: Boolean = true) {
        if (disabled) {
            disabledPlugins.add(internalName)
        } else {
            allAliasesInDisabled(internalName).forEach { disabledPlugins.remove(it) }
        }
        saveDisabledPlugins()
    }

    /**
     * Removes stale/alias entries from [disabledPlugins], keeping only canonical
     * [InstalledPlugin.internalName] values. Run at startup and after bulk installs.
     */
    fun cleanupDisabledPlugins() {
        val installed = RepoState.installedPlugins.value
        if (installed.isEmpty()) return
        val canonicalNames = installed.map { it.internalName }.toSet()
        val before = disabledPlugins.size
        val stale = disabledPlugins.filter { it !in canonicalNames }.toSet()
        if (stale.isNotEmpty()) {
            stale.forEach { disabledPlugins.remove(it) }
            saveDisabledPlugins()
            ServerState.info("Disabled-plugins cleanup: removed ${stale.size} stale alias entries ($before → ${disabledPlugins.size})")
        }
    }

    /**
     * True when the admin has globally disabled this extension.
     *
     * Checks canonical IDs first (internalName, pluginInternalName — always unique).
     * Falls back to slug matching only when the slug is unambiguous (i.e. no other
     * loaded plugin shares the same display name), preventing cross-repo collisions.
     */
    fun isGloballyDisabled(api: MainApiWrapper): Boolean {
        // Primary check: canonical unique IDs
        if (canonicalIds(api).any { disabledPlugins.contains(it) }) return true
        // Legacy fallback: slug, but only if it's unambiguous
        val slug = unambiguousSlug(api) ?: return false
        return disabledPlugins.contains(slug) || disabledPlugins.contains(api.name)
    }

    /** True when [id] (canonical name / slug) maps to a globally disabled extension. */
    fun isIdGloballyDisabled(id: String): Boolean {
        if (disabledPlugins.contains(id)) return true
        val api = loadedApis.find { canonicalIds(it).contains(id) } ?: return false
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
            get("/configure") {
                call.respondText(buildIndexHtml(), ContentType.Text.Html)
            }
            get("/logo.png") {
                val bytes = logoBytes
                if (bytes != null) {
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } else {
                    call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
                }
            }
            get("/favicon.ico") {
                val bytes = logoBytes
                if (bytes != null) {
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } else {
                    call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
                }
            }

            // Stats proxy for community donation goal
            get("/api/community-stats") {
                try {
                    val res = httpClient.get("https://cncverse.pages.dev/api/stats") {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    }
                    call.respondText(res.bodyAsText(), ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("{}", ContentType.Application.Json, HttpStatusCode.BadGateway)
                }
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
                    // Prefer repo-declared tvTypes (from the plugin metadata JSON) over
                    // api.supportedTypes at runtime, since runtime types can be buggy
                    // (e.g. all returning "Others" due to a CS3 issue). The repo manifest
                    // always has the correct declared types — this is the "direct TVtype".
                    val typesJson = if (!plugin?.tvTypes.isNullOrEmpty()) {
                        plugin!!.tvTypes.distinct().joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                    } else {
                        api.supportedTypes.distinct().joinToString(",") { "\"" + it + "\"" }
                    }
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

            // User-initiated repo install is disabled — repos must be managed by the admin.
            post("/api/repos/add") {
                call.respondText(
                    "{\"ok\":false,\"message\":\"Contact admin to add repo\"}",
                    ContentType.Application.Json, HttpStatusCode.Forbidden
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
        respond(StremioStreamResponse(sortStreamsByQuality(streams)))
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
        val ids = canonicalIds(api)
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

    /** Public alias used by WebAdmin to build profile-cleanup ID sets. */
    internal fun publicNameSlug(name: String) = nameSlug(name)

    /**
     * Normalises raw.githubusercontent.com URLs so that variants with and
     * without /refs/heads/ or /refs/tags/ compare as equal.  Used to
     * canonicalise the repoUrl reported by installedPlugins against the
     * canonical URL stored in RepoState.repos, preventing "phantom" repo tabs
     * in the user-facing Extensions/Profile UI.
     */
    private fun normalizeGhUrl(url: String): String =
        url.replace("/refs/heads/", "/").replace("/refs/tags/", "/")

    fun defaultCatalogDefsForApi(api: MainApiWrapper): List<StremioCatalogDef> {
        return api.supportedTypes
            .map { cs3TvTypeToStremio(it) }
            .distinct()
            .map { stremioType ->
                StremioCatalogDef(
                    type = stremioType,
                    id   = "cnc_${nameSlug(api.name)}_$stremioType",
                    name = "${api.name} ($stremioType)",
                    extra = listOf(ExtraEntry("search"), ExtraEntry("skip"))
                )
            }
    }

    suspend fun fetchCatalogDefsForApi(api: MainApiWrapper): List<StremioCatalogDef> {
        val sections = try {
            withTimeoutOrNull(8_000) {
                api.getMainPageSections()
            } ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }

        return api.supportedTypes
            .map { cs3TvTypeToStremio(it) }
            .distinct()
            .map { stremioType ->
                val extra = mutableListOf<ExtraEntry>()
                if (sections.isNotEmpty() && (sections.size > 1 || sections.first().isNotBlank())) {
                    extra.add(ExtraEntry(name = "genre", options = sections))
                }
                extra.add(ExtraEntry("search"))
                extra.add(ExtraEntry("skip"))

                StremioCatalogDef(
                    type = stremioType,
                    id   = "cnc_${nameSlug(api.name)}_$stremioType",
                    name = "${api.name} ($stremioType)",
                    extra = extra
                )
            }
    }

    /**
     * Initializes fast baseline in-memory catalog definitions for all loaded APIs
     * if not already present, ensuring cold-start manifest requests return instantly.
     */
    fun initFastCatalogs() {
        val apis = loadedApis.toList()
        for (api in apis) {
            if (!providerCatalogCache.containsKey(api.internalName)) {
                providerCatalogCache[api.internalName] = defaultCatalogDefsForApi(api)
            }
        }
        val currentNames = apis.map { it.internalName }.toSet()
        providerCatalogCache.keys.retainAll(currentNames)
    }

    fun startPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = manifestRefreshScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                try {
                    ServerState.info("⏰ 30-minute interval reached — starting fresh home page and manifest refresh")
                    refreshManifestAndHomepages()
                } catch (e: Throwable) {
                    ServerState.warn("Periodic refresh failed: ${e.message}")
                }
            }
        }
    }

    fun stopPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Non-blocking stale-while-revalidate background refresh:
     * 1. Clears provider dynamic section caches.
     * 2. Fetches fresh catalog definitions (sections/genres) with bounded concurrency (Semaphore 5).
     * 3. Pre-warms home page catalog items into a staging map.
     * 4. While this runs, all manifest and catalog requests serve the existing (old) cached data.
     * 5. Atomically publishes the new data to live caches only when everything is fetched.
     */
    suspend fun refreshManifestAndHomepages() {
        if (!isRefreshing.compareAndSet(false, true)) {
            ServerState.info("Manifest refresh already in progress, skipping duplicate call")
            return
        }
        try {
            ServerState.info("🔄 Refreshing home pages & provider manifest catalogs in background…")
            val apis = loadedApis.toList()
            if (apis.isEmpty()) return

            // 1. Clear dynamic sections cache on providers so fresh sections are fetched
            apis.forEach { runCatching { it.clearCache() } }

            val newProviderCatalogs = ConcurrentHashMap<String, List<StremioCatalogDef>>()
            val newHomePageCache = ConcurrentHashMap<String, List<StremioMeta>>()

            // 2. Fetch fresh catalog definitions (sections/genres) with bounded concurrency
            val sem = Semaphore(5)
            coroutineScope {
                apis.map { api ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val defs = try {
                                fetchCatalogDefsForApi(api)
                            } catch (e: Throwable) {
                                providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
                            }
                            newProviderCatalogs[api.internalName] = defs
                        }
                    }
                }.awaitAll()
            }

            // 3. Pre-warm home page catalogs into newHomePageCache
            val catalogsToPrewarm = newProviderCatalogs.values.flatten().distinctBy { it.id }
            ServerState.info("🔥 Pre-warming ${catalogsToPrewarm.size} fresh home page(s)…")
            coroutineScope {
                catalogsToPrewarm.map { cat ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            runCatching {
                                val metas = withTimeoutOrNull(15_000) {
                                    fetchCatalogItemsDirect(cat.type, cat.id, null, 0, null)
                                }
                                if (!metas.isNullOrEmpty()) {
                                    newHomePageCache["${cat.type}:${cat.id}:null"] = metas
                                    ServerState.info("🔥 Pre-warmed: ${cat.name}")
                                }
                            }.onFailure { e ->
                                ServerState.warn("🔥 Pre-warm failed for ${cat.name}: ${e.message?.take(80)}")
                            }
                        }
                    }
                }.awaitAll()
            }

            // 4. ATOMIC UPDATE: Stale data was served during the entire fetch above.
            // Now that EVERYTHING is fetched, publish the new data to the live caches!
            providerCatalogCache.putAll(newProviderCatalogs)
            providerCatalogCache.keys.retainAll(apis.map { it.internalName }.toSet())
            homePageCatalogCache.putAll(newHomePageCache)

            ServerState.info("✅ Fresh home page & manifest refresh complete (${newProviderCatalogs.size} providers, ${newHomePageCache.size} home pages)")
        } catch (e: Throwable) {
            ServerState.warn("Manifest refresh error: ${e.message}")
        } finally {
            isRefreshing.set(false)
        }
    }

    /**
     * Pre-warms every plugin's home page by refreshing manifests and home pages in the background.
     */
    suspend fun preWarmHomepages() {
        refreshManifestAndHomepages()
    }

    /**
     * Builds the Stremio manifest.
     * Serves instantly from [providerCatalogCache], reusing provider definitions across all profiles.
     * @param profileId If non-null, excludes extensions the profile has disabled.
     */
    suspend fun buildManifest(profileId: String? = null): StremioManifest {
        val activeApis = loadedApis.filter { !isPluginBlocked(it, profileId) }
        val types = listOf("movie", "series", "other", "tv")

        val catalogs = activeApis.flatMap { api ->
            providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
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

    private suspend fun fetchCatalogItemsDirect(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)

        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }          // old-format compat
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }    // prefix fallback
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                val results = api.search(search)
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
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

    private val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?, profileId: String? = null
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)
        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (isPluginBlocked(api, profileId)) return emptyList()

        val isHomePage = search.isNullOrBlank() && skip == 0
        val cacheKey = "$type:$id:$genre"

        if (isHomePage) {
            val cached = homePageCatalogCache[cacheKey]
            if (!cached.isNullOrEmpty()) {
                return cached
            }
        }

        val metas = fetchCatalogItemsDirect(type, id, search, skip, genre)
        if (isHomePage && metas.isNotEmpty()) {
            homePageCatalogCache[cacheKey] = metas
        }
        return metas
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


    // ── Quality sorting ───────────────────────────────────────────────────────

    private fun streamQualityRank(stream: StremioStream): Int {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".uppercase()
        val resMatch = Regex("(2160|1080|720|480|360)P").find(text)
        if (resMatch != null) {
            return when (resMatch.groupValues[1]) {
                "2160" -> 5
                "1080" -> 4
                "720" -> 3
                "480" -> 2
                "360" -> 1
                else -> 0
            }
        }
        return when {
            Regex("\\b(4K|UHD)\\b").containsMatchIn(text)  -> 5
            Regex("\\b(FHD)\\b").containsMatchIn(text)     -> 4
            Regex("\\b(HD)\\b").containsMatchIn(text)       -> 3
            Regex("\\b(SD)\\b").containsMatchIn(text)       -> 2
            else                                          -> 0
        }
    }

    private fun sortStreamsByQuality(streams: List<StremioStream>): List<StremioStream> =
        streams.sortedByDescending { streamQualityRank(it) }
        
    // ── Main stream builder ───────────────────────────────────────────────────

    /**
     * Deadline-based parallel stream loader.
     * All providers start concurrently on [streamSearchScope]; each appends to a shared list as it
     * finishes. After [STREAM_DEADLINE_MS] any still-running jobs are
     * cancelled and whatever has accumulated is returned — ensuring Stremio always
     * gets a response well within its 60-second addon timeout.
     */
    private val STREAM_DEADLINE_MS = 45_000L
    private val PROVIDER_TIMEOUT_MS = 25_000L
    private val streamSearchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private suspend fun buildStreams(type: String, id: String, profileId: String? = null): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val matchingApis = loadedApis.filter { api ->
                nameSlug(api.name) == pluginKey || api.internalName == pluginKey
            }.filter { !isPluginBlocked(it, profileId) }

            if (matchingApis.isEmpty()) return emptyList()
            ServerState.info("Parallel stream load: ${matchingApis.size} API(s) for key '$pluginKey'")

            val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
            val jobs = matchingApis.map { api ->
                streamSearchScope.launch {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        try {
                            ServerState.info("[${api.name}] Loading links for $dataUrl")
                            val links = api.loadLinks(dataUrl)
                            ServerState.info("[${api.name}] Got ${links.size} stream(s)")
                            accumulated.addAll(links)
                        } catch (e: Throwable) {
                            ServerState.warn("[${api.name}] Stream error: ${e.message}")
                        }
                    }
                }
            }

            val deadline = System.currentTimeMillis() + STREAM_DEADLINE_MS
            while (System.currentTimeMillis() < deadline && jobs.any { it.isActive }) {
                delay(200)
            }
            val remaining = jobs.count { it.isActive }
            if (remaining > 0) {
                ServerState.warn("Deadline reached ($STREAM_DEADLINE_MS ms) — returning ${accumulated.size} stream(s), cancelling $remaining slow provider(s)")
                jobs.forEach { it.cancel() }
            }
            return sortStreamsByQuality(accumulated)
        }

        // Handle generic Stremio requests with TMDB/IMDB IDs
        val idParts = id.split(":")
        val isTmdb = idParts.first() == "tmdb"
        val baseId = if (isTmdb) idParts.getOrNull(1) ?: id else idParts.first()
        val mediaType = if (type == "series") "tv" else "movie"
        val tmdbId = baseId

        ServerState.info("Generic request: id=$id, type=$type, baseId=$baseId, tmdbId=$tmdbId")

        return try {
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

            val activePlugins = loadedApis.filter { !isPluginBlocked(it, profileId) }
            ServerState.info("Searching across ${activePlugins.size} plugin(s)...")

            val sem = Semaphore(20)
            val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
            val jobs = activePlugins.map { api ->
                streamSearchScope.launch {
                    sem.withPermit {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            try {
                                val streams = buildGenericStreamsForApi(api, type, id, title, year)
                                accumulated.addAll(streams)
                            } catch (e: Throwable) {
                                ServerState.warn("[${api.name}] Generic stream error: ${e.message}")
                            }
                        }
                    }
                }
            }

            val deadline = System.currentTimeMillis() + STREAM_DEADLINE_MS
            while (System.currentTimeMillis() < deadline && jobs.any { it.isActive }) {
                delay(200)
            }
            val remaining = jobs.count { it.isActive }
            if (remaining > 0) {
                ServerState.warn("Deadline reached ($STREAM_DEADLINE_MS ms) — returning ${accumulated.size} stream(s), cancelling $remaining slow provider(s)")
                jobs.forEach { it.cancel() }
            }
            ServerState.info("Returning total ${accumulated.size} streams")
            sortStreamsByQuality(accumulated)
        } catch (e: Exception) {
            ServerState.warn("TMDB resolve error for $id: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    // ── Generic per-provider stream fetch (shared by buildStreams + buildStreamsForApi) ─

    /**
     * Resolves streams from a single [api] for a generic title/year.
     * Reuses already-resolved title & year to avoid 95 redundant TMDB requests.
     */
    private suspend fun buildGenericStreamsForApi(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?
    ): List<StremioStream> {
        ServerState.info("[${api.name}] Searching for '$title'")
        val searchResults = api.search(title)
        ServerState.info("[${api.name}] Found ${searchResults.size} results")

        val bestMatch = searchResults.find {
            it.name.equals(title, ignoreCase = true) && (year == null || it.year == null || it.year == year)
        } ?: searchResults.firstOrNull { it.name.contains(title, ignoreCase = true) }
          ?: searchResults.firstOrNull()
          ?: return emptyList()

        ServerState.info("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
        val mediaInfo = api.load(bestMatch.url) ?: run {
            ServerState.warn("[${api.name}] MediaInfo load failed for ${bestMatch.url}")
            return emptyList()
        }
        var dataUrlToLoad = mediaInfo.dataUrl
        if (type == "series" && id.contains(":")) {
            val parts   = id.split(":")
            val season  = parts.getOrNull(1)?.toIntOrNull()
            val episode = parts.getOrNull(2)?.toIntOrNull()
            if (season != null && episode != null) {
                val ep = mediaInfo.episodes?.find { it.season == season && it.episode == episode }
                if (ep != null) {
                    dataUrlToLoad = ep.dataUrl
                    ServerState.info("[${api.name}] Found episode S${season}E${episode}")
                } else {
                    ServerState.warn("[${api.name}] Episode S${season}E${episode} not found")
                    return emptyList()
                }
            }
        }
        ServerState.info("[${api.name}] Loading links for $dataUrlToLoad")
        val links = api.loadLinks(dataUrlToLoad)
        ServerState.info("[${api.name}] Found ${links.size} streams")
        return links.map { stream ->
            val newName = bestMatch.name + (if (!stream.name.isNullOrBlank()) "\n${stream.name}" else "")
            stream.copy(name = newName)
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
<link rel="icon" type="image/png" href="/logo.png">
<link rel="shortcut icon" href="/logo.png">
<link rel="apple-touch-icon" href="/logo.png">
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
.brand-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.brand-logo-container {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: rgba(99, 102, 241, 0.12);
  border: 1px solid rgba(99, 102, 241, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.15);
  overflow: hidden;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.brand-logo-container:hover {
  transform: scale(1.05);
  box-shadow: 0 6px 20px rgba(99, 102, 241, 0.25);
}
.brand-logo {
  width: 30px;
  height: 30px;
  object-fit: contain;
  display: block;
}
.brand-title {
  font-size: 1.55rem;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.5px;
  margin: 0;
  line-height: 1.2;
}
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
@media (max-width: 680px) {
  .brand-wrap {
    gap: 8px;
  }
  .brand-logo-container {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }
  .brand-logo {
    width: 22px;
    height: 22px;
  }
  .brand-title {
    font-size: 1.18rem;
    white-space: nowrap;
    margin: 0;
  }
  .hdr-actions {
    gap: 6px;
  }
  .btn-hdr {
    padding: 6px 9px;
    border-radius: 7px;
  }
  .btn-hdr-text {
    display: none;
  }
  .hdr-badge {
    padding: 1px 5px;
    font-size: 9px;
  }
}

/* Community Donation Goal Bar */
.goal-card {
  background: var(--surface-card);
  border: 1.5px solid var(--border);
  border-radius: 12px;
  padding: 11px 16px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  width: 100%;
  box-sizing: border-box;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.goal-card:hover {
  border-color: var(--border-focus);
}
.goal-donate-btn {
  background: linear-gradient(135deg, #f43f5e 0%, #a855f7 100%);
  color: #ffffff !important;
  font-size: 12.5px;
  font-weight: 700;
  padding: 8px 15px;
  border-radius: 8px;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(244, 63, 94, 0.28);
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  cursor: pointer;
  line-height: 1;
}
.goal-donate-btn:hover {
  filter: brightness(1.1);
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(244, 63, 94, 0.42);
}
.goal-donate-btn:active {
  transform: translateY(0);
}
.goal-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.goal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.goal-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.goal-text b {
  color: var(--text);
  font-weight: 700;
}
.goal-pct {
  font-size: 13px;
  font-weight: 700;
  color: #f43f5e;
  flex-shrink: 0;
  letter-spacing: 0.2px;
}
.goal-track {
  width: 100%;
  height: 8px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  overflow: hidden;
}
[data-theme="light"] .goal-track {
  background: rgba(0, 0, 0, 0.08);
}
.goal-fill {
  height: 100%;
  width: 0%;
  border-radius: 999px;
  background: linear-gradient(90deg, #f43f5e 0%, #ec4899 35%, #a855f7 70%, #6366f1 100%);
  transition: width 0.8s cubic-bezier(0.16, 1, 0.3, 1);
}
@media (max-width: 520px) {
  .goal-card {
    padding: 10px 12px;
    gap: 10px;
  }
  .goal-donate-btn {
    padding: 6px 11px;
    font-size: 11.5px;
    gap: 5px;
  }
  .goal-text {
    font-size: 11.5px;
  }
  .goal-pct {
    font-size: 11.5px;
  }
  .goal-track {
    height: 7px;
  }
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
  min-width: 0;
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
  min-width: 0;
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

.presets-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
}
.presets-nav-btn {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-sub);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  transition: all 0.15s ease;
  user-select: none;
  -webkit-user-select: none;
}
.presets-nav-btn:hover {
  background: var(--surface-active);
  border-color: var(--accent);
  color: var(--text);
  transform: scale(1.08);
}
.presets-nav-btn:active {
  transform: scale(0.96);
}
@media (pointer: coarse) {
  .presets-nav-btn { display: none !important; }
}

.presets-row {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 4px;
  scrollbar-width: thin;
  scrollbar-color: var(--border) transparent;
  width: 100%;
  min-width: 0;
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-x pan-y;
  cursor: grab;
  user-select: none;
  -webkit-user-select: none;
}
.presets-row.grabbing {
  cursor: grabbing;
  scroll-behavior: auto;
}
.presets-row::-webkit-scrollbar {
  height: 4px;
}
.presets-row::-webkit-scrollbar-track {
  background: transparent;
}
.presets-row::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 999px;
}
.presets-row::-webkit-scrollbar-thumb:hover {
  background: var(--text-dim);
}

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
  user-select: none;
  -webkit-user-select: none;
}
.presets-row.grabbing .preset-btn {
  cursor: grabbing;
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

/* Page Footer */
.page-footer {
  margin-top: 1.5rem;
  padding: 1.25rem 0.5rem 0;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  font-size: 12.5px;
  color: var(--text-dim);
  text-align: center;
}
.footer-author-link {
  color: var(--text);
  font-weight: 600;
  text-decoration: none;
  border-bottom: 1px dashed var(--accent);
  padding-bottom: 1px;
  transition: all 0.15s ease;
}
.footer-author-link:hover {
  color: var(--accent);
  border-bottom-style: solid;
}
.footer-credit {
  color: var(--text-sub);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  vertical-align: middle;
  transform: translateY(1.5px);
}
.discord-icon {
  color: #5865f2;
  display: inline-block;
  flex-shrink: 0;
}
.footer-sep {
  color: var(--border-focus);
}
.heart-icon {
  display: inline-block;
  color: #f43f5e;
  margin: 0 1px;
}
</style>
</head>
<body>

<div class="container">
  <!-- HEADER -->
  <header class="hdr">
    <div class="brand-wrap">
      <div class="brand-logo-container">
        <img src="/logo.png" alt="CNCVerse Logo" class="brand-logo" onerror="this.src='https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png'">
      </div>
      <h1 class="brand-title">CNCVerse Bridge</h1>
    </div>
    <div class="hdr-actions">
      <button class="btn-hdr" onclick="openReposModal()" title="Extension Repositories">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
        <span class="btn-hdr-text">Repos</span>
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

  <!-- DONATION GOAL BAR -->
  <div class="goal-card" id="goal-card" title="CNCVerse Community Goal">
    <div class="goal-body">
      <div class="goal-top">
        <div class="goal-text" id="goal-text">&#36;0 raised of &#36;100 goal</div>
        <div class="goal-pct" id="goal-pct">0%</div>
      </div>
      <div class="goal-track">
        <div class="goal-fill" id="goal-fill" style="width: 0%;"></div>
      </div>
    </div>
    <a class="goal-donate-btn" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
      <span>Donate</span>
    </a>
  </div>

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

      <div class="presets-wrapper">
        <button class="presets-nav-btn prev" id="presets-prev" onclick="scrollPresets(-1)" aria-label="Previous filters" style="display:none;" type="button">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>
        </button>
        <div class="presets-row" id="presets-row">
          <button class="preset-btn active" data-filter="all" onclick="applyFilter('all', this)">All Sources</button>
        </div>
        <button class="presets-nav-btn next" id="presets-next" onclick="scrollPresets(1)" aria-label="Next filters" style="display:none;" type="button">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
        </button>
      </div>

      <input type="text" class="search-input" id="ext-search" placeholder="Search providers by name, repo, or content tag..." oninput="renderCards()">
    </div>

    <div class="sources-grid" id="sources-grid">
      <div class="empty">Loading provider sources...</div>
    </div>
  </main>

  <!-- FOOTER -->
  <footer class="page-footer">
    <span>Made with <span class="heart-icon">❤️</span> By <a href="https://t.me/NivinCNC" target="_blank" rel="noopener" class="footer-author-link">NivinCNC</a></span>
    <span class="footer-sep">&bull;</span>
    <span>UI By <span class="footer-credit"><svg class="discord-icon" width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.09 14.09 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.929 1.793 8.18 1.793 12.061 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.894.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/></svg>sleepycat555</span></span>
  </footer>
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
    <div class="modal-hint">Extension repositories active on this bridge. Contact admin to add new repositories.</div>
    <div class="modal-input-row" onclick="addRepo()" style="cursor:pointer">
      <input type="text" id="repo-input" class="modal-input" placeholder="Contact admin to add repo" readonly style="cursor:pointer" onclick="addRepo()">
      <button class="btn-primary" id="repo-add-btn" onclick="addRepo()">Add</button>
    </div>
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
    setTimeout(function() {
      updatePresetsNav();
    }, 20);
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
      renderFilterChips();
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

function getExtTypes(e) {
  var result = [];
  var rawList = e.types || [];
  for (var i = 0; i < rawList.length; i++) {
    var raw = rawList[i];
    if (!raw) continue;
    var parts = String(raw).split(",");
    for (var j = 0; j < parts.length; j++) {
      var t = parts[j].trim();
      if (t && t !== "All" && result.indexOf(t) < 0) {
        result.push(t);
      }
    }
  }
  if (result.length === 0) {
    result.push("Others");
  }
  return result;
}

function getAvailableTypes() {
  var set = new Set();
  for (var i = 0; i < exts.length; i++) {
    var types = getExtTypes(exts[i]);
    for (var j = 0; j < types.length; j++) {
      set.add(types[j]);
    }
  }
  return set;
}

var PREFERRED_ORDER = [
  "Movie", "TvSeries", "Anime", "AnimeMovie", "Live", "AsianDrama",
  "Cartoon", "Documentary", "OVA", "Torrent", "Music", "Audio", "NSFW", "Others"
];

var TYPE_LABELS = {
  "Movie": "Movies",
  "TvSeries": "Series",
  "Anime": "Anime",
  "AnimeMovie": "Anime Movie",
  "Live": "Live TV",
  "AsianDrama": "Asian Drama",
  "Cartoon": "Cartoons",
  "Documentary": "Documentary",
  "OVA": "OVA",
  "Torrent": "Torrents",
  "Music": "Music",
  "Audio": "Audio",
  "NSFW": "NSFW",
  "Others": "Others"
};

function renderFilterChips() {
  var row = document.getElementById("presets-row");
  if (!row) return;

  var available = getAvailableTypes();
  if (currentFilter !== "all" && !available.has(currentFilter)) {
    currentFilter = "all";
  }

  var ordered = [];
  for (var k = 0; k < PREFERRED_ORDER.length; k++) {
    var pt = PREFERRED_ORDER[k];
    if (available.has(pt)) {
      ordered.push(pt);
      available.delete(pt);
    }
  }
  var remaining = Array.from(available).sort();
  for (var r = 0; r < remaining.length; r++) {
    ordered.push(remaining[r]);
  }

  var html = '<button class="preset-btn' + (currentFilter === "all" ? " active" : "") + '" data-filter="all" onclick="applyFilter(\'all\', this)">All Sources</button>';

  for (var i = 0; i < ordered.length; i++) {
    var t = ordered[i];
    var label = TYPE_LABELS[t] || t;
    var isActive = (currentFilter === t);
    html += '<button class="preset-btn' + (isActive ? " active" : "") + '" data-filter="' + esc(t) + '" onclick="applyFilter(\'' + esc(t) + '\', this)">' + esc(label) + '</button>';
  }

  if (row.innerHTML !== html) {
    var prevScroll = row.scrollLeft;
    row.innerHTML = html;
    row.scrollLeft = prevScroll;
  }
  initPresetsDrag();
  updatePresetsNav();
}

function applyFilter(f, btn) {
  currentFilter = f;
  var btns = document.querySelectorAll(".preset-btn");
  var activeBtn = null;
  for (var i = 0; i < btns.length; i++) btns[i].classList.remove("active");
  if (btn) {
    btn.classList.add("active");
    activeBtn = btn;
  } else {
    for (var j = 0; j < btns.length; j++) {
      if (btns[j].getAttribute("data-filter") === f) {
        btns[j].classList.add("active");
        activeBtn = btns[j];
      }
    }
  }
  if (activeBtn && activeBtn.scrollIntoView) {
    activeBtn.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" });
  }
  updatePresetsNav();
  renderCards();
}

function scrollPresets(dir) {
  var row = document.getElementById("presets-row");
  if (!row) return;
  row.scrollBy({ left: dir * 220, behavior: "smooth" });
  setTimeout(updatePresetsNav, 320);
}

function updatePresetsNav() {
  var row = document.getElementById("presets-row");
  var prev = document.getElementById("presets-prev");
  var next = document.getElementById("presets-next");
  if (!row || !prev || !next) return;
  if (row.clientWidth === 0) return;
  var maxScroll = row.scrollWidth - row.clientWidth;
  if (maxScroll <= 2) {
    prev.style.display = "none";
    next.style.display = "none";
    return;
  }
  prev.style.display = (row.scrollLeft > 4) ? "flex" : "none";
  next.style.display = (row.scrollLeft < maxScroll - 4) ? "flex" : "none";
}

function initPresetsDrag() {
  var row = document.getElementById("presets-row");
  if (!row || row._dragInited) return;
  row._dragInited = true;

  var isDown = false;
  var startX = 0;
  var scrollLeft = 0;
  var hasMoved = false;

  row.addEventListener("mousedown", function(e) {
    if (e.button !== 0) return;
    isDown = true;
    hasMoved = false;
    startX = e.pageX - row.offsetLeft;
    scrollLeft = row.scrollLeft;
    row.classList.add("grabbing");
  });

  window.addEventListener("mouseup", function() {
    if (isDown) {
      isDown = false;
      row.classList.remove("grabbing");
      setTimeout(function() { hasMoved = false; }, 60);
    }
  });

  window.addEventListener("mousemove", function(e) {
    if (!isDown) return;
    var x = e.pageX - row.offsetLeft;
    var walk = (x - startX);
    if (Math.abs(walk) > 4) {
      hasMoved = true;
    }
    if (hasMoved) {
      e.preventDefault();
      row.scrollLeft = scrollLeft - walk;
      updatePresetsNav();
    }
  });

  row.addEventListener("dragstart", function(e) {
    e.preventDefault();
  });

  row.addEventListener("click", function(e) {
    if (hasMoved) {
      e.preventDefault();
      e.stopPropagation();
      hasMoved = false;
    }
  }, true);

  row.addEventListener("wheel", function(e) {
    if (e.deltaY !== 0) {
      var maxScroll = row.scrollWidth - row.clientWidth;
      if (maxScroll > 0) {
        var canScroll = (e.deltaY > 0 && row.scrollLeft < maxScroll - 1) || (e.deltaY < 0 && row.scrollLeft > 1);
        if (canScroll) {
          e.preventDefault();
          row.scrollLeft += (e.deltaY * 0.9);
          updatePresetsNav();
        }
      }
    }
  }, { passive: false });

  row.addEventListener("scroll", updatePresetsNav);
  window.addEventListener("resize", updatePresetsNav);
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

        var typeBadges = getExtTypes(e).map(function(t){
          return '<span class="p-type-tag">' + esc(TYPE_LABELS[t] || t) + '</span>';
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
      if (currentFilter !== "all") {
        var types = getExtTypes(e);
        if (types.indexOf(currentFilter) < 0) return false;
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

        var typeBadges = getExtTypes(e).map(function(t){
          return '<span class="p-type-tag">' + esc(TYPE_LABELS[t] || t) + '</span>';
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
  // When a chip filter is active, scope to only those extensions that match it.
  var scope = currentFilter === "all"
    ? exts
    : exts.filter(function(e) {
        return getExtTypes(e).indexOf(currentFilter) >= 0;
      });

  var dis = [], en = [];
  scope.forEach(function(e) {
    if (!e.enabled) {
      if (on) en.push(e.internalName);
    } else {
      if (!on) dis.push(e.internalName);
    }
  });

  // Merge the scoped change into the existing profile state instead of wiping it.
  if (on) {
    en.forEach(function(n) { pData._e.add(n); });
    // Restore any that were manually disabled within this scope.
    scope.forEach(function(e) { pData._d.delete(e.internalName); });
  } else {
    dis.forEach(function(n) { pData._d.add(n); });
    // Remove any manual enables within this scope.
    scope.forEach(function(e) { pData._e.delete(e.internalName); });
  }

  var label = currentFilter === "all" ? "All" : (TYPE_LABELS[currentFilter] || currentFilter);
  updateMetrics();
  renderCards();
  toast(on ? (label + " extensions enabled") : (label + " extensions cleared from profile"));

  var allDis = Array.from(pData._d);
  var allEn  = Array.from(pData._e);
  fetch("/api/profile/" + encodeURIComponent(pid) + "/set", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ disabledExtensions: allDis, enabledExtensions: allEn })
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
  toast("Contact admin to add repo");
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

function loadStats() {
  function applyStats(data) {
    if (!data) return;
    var target = typeof data.targetGoalUsd === "number" ? data.targetGoalUsd : 100;
    var total = typeof data.totalUsd === "number" ? data.totalUsd : 0;
    var pct = typeof data.percent === "number" ? data.percent : Math.round((total / (target || 1)) * 100);
    if (pct < 0) pct = 0;

    var curSym = String.fromCharCode(36);
    var raisedText = curSym + (total % 1 === 0 ? total.toFixed(0) : total.toFixed(2));
    var targetText = curSym + (target % 1 === 0 ? target.toFixed(0) : target.toFixed(2));

    var textEl = document.getElementById("goal-text");
    if (textEl) {
      textEl.innerHTML = "<b>" + esc(raisedText) + "</b> raised of <b>" + esc(targetText) + "</b> goal";
    }
    var pctEl = document.getElementById("goal-pct");
    if (pctEl) {
      pctEl.textContent = pct + "%";
    }
    var fillEl = document.getElementById("goal-fill");
    if (fillEl) {
      fillEl.style.width = Math.min(100, Math.max(0, pct)) + "%";
    }
    var card = document.getElementById("goal-card");
    if (card && data.month) {
      var note = data.month + " Goal";
      if (data.supporterCount) note += " · " + data.supporterCount + " supporters";
      card.setAttribute("title", note);
    }
  }

  fetch("https://cncverse.pages.dev/api/stats")
    .then(function(r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    })
    .then(function(data) {
      applyStats(data);
    })
    .catch(function() {
      fetch("/api/community-stats")
        .then(function(r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
        .then(function(data) { applyStats(data); })
        .catch(function() {});
    });
}

initPresetsDrag();
updateUrls();
loadExts();
loadRepos();
loadProfile();
loadStats();
setInterval(function(){ loadExts(); loadRepos(); }, 15000);
setInterval(function(){ loadStats(); }, 60000);
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
    fun clearCache() {}

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
    videos      = episodes?.mapIndexed { index, ep ->
        StremioVideo(
            id       = StremioIds.encode(pluginInternalName, ep.dataUrl),
            title    = ep.name ?: "Episode ${ep.episode ?: (index + 1)}",
            season   = ep.season ?: 1,
            episode  = ep.episode ?: (index + 1),
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

