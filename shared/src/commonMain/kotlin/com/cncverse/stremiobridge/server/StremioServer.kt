package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.StreamTracker
import com.cncverse.stremiobridge.state.currentTimeMillis
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
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
    try {
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    } catch (_: Throwable) {}
    HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(serverJson)
        }
        try {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        } catch (e: Throwable) {}
    }
}

/**
 * Thread-safe list of loaded APIs that bumps [version] on every mutation, so
 * derived lookups (display-name slug counts) can be cached and invalidated
 * cheaply. The plugin loader mutates it while request threads iterate it.
 */
class LoadedApiList : java.util.concurrent.CopyOnWriteArrayList<MainApiWrapper>() {
    @Volatile var version: Long = 0L
        private set

    private fun bump() { version++ }

    override fun add(element: MainApiWrapper): Boolean = super.add(element).also { bump() }
    override fun add(index: Int, element: MainApiWrapper) { super.add(index, element); bump() }
    override fun addAll(elements: Collection<MainApiWrapper>): Boolean = super.addAll(elements).also { bump() }
    override fun addAll(index: Int, elements: Collection<MainApiWrapper>): Boolean = super.addAll(index, elements).also { bump() }
    override fun set(index: Int, element: MainApiWrapper): MainApiWrapper = super.set(index, element).also { bump() }
    override fun remove(element: MainApiWrapper): Boolean = super.remove(element).also { bump() }
    override fun removeAt(index: Int): MainApiWrapper = super.removeAt(index).also { bump() }
    override fun removeAll(elements: Collection<MainApiWrapper>): Boolean = super.removeAll(elements).also { bump() }
    override fun retainAll(elements: Collection<MainApiWrapper>): Boolean = super.retainAll(elements).also { bump() }
    override fun clear() { super.clear(); bump() }
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
    private val NON_ALNUM = Regex("[^a-zA-Z0-9]")
    private val slugMemo = ConcurrentHashMap<String, String>()

    private val apiList = LoadedApiList()
    val loadedApis: MutableList<MainApiWrapper> = apiList

    /**
     * Bounded pool for plugin work (catalog/meta/stream/pre-warm). Extensions
     * can be CPU-heavy (HTML parsing, extractor fuzzy matching); capping how
     * many run at once keeps the CPU from being oversubscribed so the HTTP
     * server, manifest/admin requests and the local proxies stay responsive
     * even under load.
     *
     * Sized for blocking I/O, not CPU: plugins mostly make blocking OkHttp calls
     * that hold their thread while waiting on the network. Sized by CPU count
     * (2 vCPU → 8 threads) the whole server could only have 8 plugin requests on
     * the wire, so stream searches (20 providers each), home pages and sweeps
     * queued behind each other and most providers hit their 38 s timeout.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pluginDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(64)

    /**
     * Shared provider catalog cache: provider internalName -> list of StremioCatalogDef.
     * Kept between refreshes so manifests never block on re-fetching sections.
     */
    val providerCatalogCache: MutableMap<String, List<StremioCatalogDef>> = ConcurrentHashMap()

    /**
     * Cached home page catalog results: "$type:$id:$genre" -> list of StremioMeta.
     */
    val homePageCatalogCache: MutableMap<String, List<StremioMeta>> = ConcurrentHashMap()
    /** When each home-page cache entry was filled (live-only providers refresh after [LIVE_HOME_TTL_MS]). */
    private val homePageCachedAt = ConcurrentHashMap<String, Long>()
    private val homePageRefreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val LIVE_HOME_TTL_MS = 2 * 60_000L
    private const val LIVE_PLAYLIST_TTL_MS = 15 * 60_000L
    /** Non-live home pages: refreshed on request once older than this. */
    private const val HOME_PAGE_TTL_MS = 30 * 60_000L

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

    private val manifestRefreshScope = CoroutineScope(pluginDispatcher + SupervisorJob())
    private var periodicRefreshJob: kotlinx.coroutines.Job? = null
    private val isRefreshing = AtomicBoolean(false)
    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes

    val disabledPlugins: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Sources (multi-source plugins) switched on individually, even while their plugin is off. */
    val enabledSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val enabledSourcesFile: java.io.File? get() = disabledPluginsFile?.let { java.io.File(it.parentFile, "enabled_sources.json") }
    private var disabledPluginsFile: File? = null

    /**
     * Stream preferences for this bridge: filtering, sorting and catalog choices,
     * edited in the app (Settings → Streams) and applied to every request.
     */
    @Serializable
    data class StreamPrefs(
        /** Catalog ids (`<type>/<id>`) hidden from the manifest. */
        val disabledCatalogs: Set<String> = emptySet(),
        /** Allowed resolutions (e.g. ["2160p", "1080p", "720p"]). Empty = all allowed. */
        val allowedResolutions: Set<String> = emptySet(),
        /** Exclude CAM / TeleSync / Screener recordings. */
        val excludeCam: Boolean = false,
        /** Maximum streams per quality tier (0 = unlimited). */
        val maxStreamsPerResolution: Int = 0,
        /** Streams go out without subtitle tracks and /subtitles answers empty. */
        val hideSubtitles: Boolean = false,
        /** How streams are grouped: default (by quality) | provider. */
        val groupBy: String = "default",
        /** Order inside a group: default | size (largest first, unknown size last). */
        val sortBy: String = "default",
        /** Streams with a KNOWN file size outside [minSizeGb, maxSizeGb] are dropped; 0 = no bound. */
        val minSizeGb: Double = 0.0,
        val maxSizeGb: Double = 0.0,
        /** Extension names in priority order: earlier ones come first inside each group. */
        val providerOrder: List<String> = emptyList(),
        /** Show the "support the project" entry on top of stream lists. */
        val showSupport: Boolean = true,
        /** Pixeldrain links (file ids) are sent through the fast mirror cdn.pixeldrain.eu.cc. */
        val pixeldrainBypass: Boolean = true,
        /** Hide direct-download files whose server cannot seek (no byte ranges). Relay/proxy links are never hidden. */
        val filterNonSeekable: Boolean = false,
        /**
         * Resolution cap, e.g. "1080p": streams above it are listed after everything within the cap
         * (a TV or phone that cannot decode 4K gets playable links first). "" = no cap.
         */
        val maxResolution: String = "",
        /** Streams with a KNOWN resolution below this are hidden ("720p" by default; "" = show everything). */
        val minResolution: String = "720p",
        /** Read adaptive HLS playlists to find their real resolution instead of listing them as "Auto". */
        val probeHls: Boolean = true,
        /** Show the results once, when every extension has answered (up to 45 s) instead of after a few seconds. */
        val waitForAll: Boolean = true,
        /** Adaptive (auto-resolution) HLS streams always play their highest resolution and are labelled with it. */
        val bestHls: Boolean = true,
        /** After an episode's links are loaded, load the next episode's links in the background. */
        val prefetchNext: Boolean = true,
        /** Offer each rendition of an adaptive HLS playlist as its own stream (1080p / 720p / ...). */
        val splitHls: Boolean = false,
        /** Drop streams whose resolution cannot be found at all. */
        val hideUnknownQuality: Boolean = false,
        /** Anime audio preference: "all" | "sub" | "dub" - the preferred kind is listed first. */
        val animeAudio: String = "all",
        /** With [animeAudio] sub/dub: hide the other kind completely. */
        val animeAudioOnly: Boolean = false,
    )

    @Volatile var streamPrefs: StreamPrefs = StreamPrefs()
        private set
    private var streamPrefsFile: File? = null

    private fun loadStreamPrefs() {
        val file = streamPrefsFile ?: return
        if (!file.exists()) return
        streamPrefs = runCatching { serverJson.decodeFromString<StreamPrefs>(file.readText()) }
            .onFailure { ServerState.warn("Failed to load stream_prefs.json: ${it.message}") }
            .getOrDefault(StreamPrefs())
    }

    /** Validates, applies and saves [p]. */
    fun updateStreamPrefs(p: StreamPrefs) {
        val max = p.maxSizeGb.coerceIn(0.0, 1000.0)
        val min = p.minSizeGb.coerceIn(0.0, 1000.0).let { if (max > 0 && it > max) max else it }
        val cleaned = p.copy(
            groupBy = p.groupBy.takeIf { it in setOf("default", "provider") } ?: "default",
            sortBy = p.sortBy.takeIf { it in setOf("default", "size") } ?: "default",
            minSizeGb = min,
            maxSizeGb = max,
            maxStreamsPerResolution = p.maxStreamsPerResolution.coerceIn(0, 100),
            maxResolution = p.maxResolution.takeIf { it in SELECTABLE_RESOLUTIONS } ?: "",
            minResolution = p.minResolution.takeIf { it in SELECTABLE_RESOLUTIONS } ?: "",
            animeAudio = p.animeAudio.takeIf { it in setOf("all", "sub", "dub") } ?: "all",
            providerOrder = p.providerOrder.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        streamPrefs = cleaned
        val file = streamPrefsFile ?: return
        runCatching { file.writeText(serverJson.encodeToString(cleaned)) }
            .onFailure { ServerState.warn("Failed to save stream_prefs.json: ${it.message}") }
    }

    /** Present ⇔ catalogs are turned off globally (survives restarts). */
    private var catalogsOffMarker: File? = null

    internal fun saveGlobalCatalogSetting() {
        val f = catalogsOffMarker ?: return
        runCatching { if (ServerState.disableCatalogsGlobally) f.writeText("1") else f.delete() }
            .onFailure { ServerState.warn("Failed to save global catalog setting: ${it.message}") }
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
        enabledSourcesFile?.takeIf { it.exists() }?.let { f ->
            runCatching {
                enabledSources.clear()
                enabledSources.addAll(serverJson.decodeFromString<Set<String>>(f.readText()))
            }.onFailure { ServerState.warn("Failed to load enabled sources: ${it.message}") }
        }
    }

    internal fun saveDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        try {
            val json = serverJson.encodeToString(disabledPlugins)
            file.writeText(json)
            enabledSourcesFile?.writeText(serverJson.encodeToString(enabledSources.toSet()))
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

    @Volatile private var configLoadedFrom: String? = null

    /**
     * Loads the saved configuration (disabled extensions, stream preferences,
     * catalogs switch, formatter, stream cache) from [cacheDir] once. Called at app
     * start so the settings screens edit the real, persisted values before the
     * server runs, and again (no-op) by [start].
     */
    fun loadConfig(cacheDir: String) {
        if (configLoadedFrom == cacheDir) return
        synchronized(this) {
            if (configLoadedFrom == cacheDir) return
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
            streamPrefsFile = File(cacheDir, "stream_prefs.json")
            loadStreamPrefs()
            catalogsOffMarker = File(cacheDir, "catalogs_disabled_globally")
            ServerState.disableCatalogsGlobally = catalogsOffMarker?.exists() == true
            com.cncverse.stremiobridge.format.StreamFormatter.init(cacheDir)
            com.cncverse.stremiobridge.cache.StreamCacheManager.init(cacheDir)
            MediaServer.init(cacheDir)
            UpstreamAddons.init(cacheDir); com.cncverse.stremiobridge.state.StreamHistory.init(cacheDir)
            com.cncverse.stremiobridge.network.CfCookieStore.initIfNeeded(cacheDir)
            configLoadedFrom = cacheDir
        }
    }

    /** Re-reads every saved setting (after a backup import). */
    fun reloadConfig(cacheDir: String) {
        synchronized(this) {
            streamPrefs = StreamPrefs()
            disabledPlugins.clear()
            enabledSources.clear()
            configLoadedFrom = null
        }
        loadConfig(cacheDir)
        com.cncverse.stremiobridge.cache.StreamCacheManager.reloadConfigFromDisk()
        homePageCatalogCache.clear()
        encodedCatalogs.clear()
    }

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort
        if (cacheDir != null) {
            loadConfig(cacheDir)
        } else {
            val defaultCache = System.getProperty("user.home") + "/.cncverse_bridge"
            com.cncverse.stremiobridge.cache.StreamCacheManager.init(defaultCache)
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
        com.cncverse.stremiobridge.cache.StreamCacheManager.shutdown()
        MediaServer.shutdown()
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
        return if ((slugCounts()[slug] ?: 0) == 1) slug else null
    }

    /** slug -> number of loaded APIs sharing it; rebuilt only when [loadedApis] changes. */
    @Volatile private var slugCountCache: Pair<Long, Map<String, Int>>? = null

    private fun slugCounts(): Map<String, Int> {
        val version = apiList.version
        slugCountCache?.let { (v, counts) -> if (v == version) return counts }
        val counts = HashMap<String, Int>()
        for (api in apiList) {
            val slug = nameSlug(api.name)
            counts[slug] = (counts[slug] ?: 0) + 1
        }
        slugCountCache = version to counts
        return counts
    }

    /** Catalog/meta/stream key for an API: unambiguous slug, or slug + plugin name. */
    private fun apiKey(api: MainApiWrapper): String =
        unambiguousSlug(api) ?: (nameSlug(api.name) + "_" + nameSlug(api.pluginInternalName))

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
        // Per-source entries of multi-source plugins are the admin's own choices — the
        // plugin switch never clears them (only single-source plugins alias their API id)
        matchingApis.flatMapTo(result) { api ->
            if ((apisPerPlugin()[api.pluginInternalName] ?: 1) > 1 && api.internalName != internalName) listOf(api.pluginInternalName)
            else canonicalIds(api)
        }
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
        if (isSourceId(internalName)) {
            val api = loadedApis.first { it.internalName == internalName }
            val nowDisabled = !isGloballyDisabled(api)
            setSourceDisabled(internalName, nowDisabled)
            return !nowDisabled
        }
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

    /**
     * Global on/off for one source of a multi-source plugin, independent of the
     * plugin switch: an enabled source stays on even while its plugin is off,
     * a disabled one stays off when the plugin is switched on.
     */
    fun setSourceDisabled(apiInternalName: String, disabled: Boolean) {
        if (disabled) {
            disabledPlugins.add(apiInternalName)
            enabledSources.remove(apiInternalName)
        } else {
            disabledPlugins.remove(apiInternalName)
            enabledSources.add(apiInternalName)
        }
        saveDisabledPlugins()
    }

    /** Number of home-page items cached for [api] (any type/section), 0 when none. */
    fun cachedHomeItems(api: MainApiWrapper): Int {
        val key = apiKey(api)
        return homePageCatalogCache.entries.filter { (k, _) -> k.contains(":cnc_${key}_") }.sumOf { it.value.size }
    }

    /** True when [id] is the per-source id of a plugin that has several sources. */
    fun isSourceId(id: String): Boolean =
        loadedApis.any { it.internalName == id && it.internalName != it.pluginInternalName } &&
            (apisPerPlugin()[loadedApis.first { it.internalName == id }.pluginInternalName] ?: 1) > 1

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
        // Per-source entries ("<plugin>_<source>") of installed plugins are kept
        val isKept = { id: String -> id in canonicalNames || canonicalNames.any { id.startsWith(it + "_") } }
        val stale = disabledPlugins.filter { !isKept(it) }.toSet()
        enabledSources.removeIf { !isKept(it) }
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
        // A source's own choice wins over its plugin's (multi-source plugins: PlayFy = Live
        // Events + Highlights + one source per playlist enabled in its settings)
        if (api.internalName != api.pluginInternalName) {
            if (disabledPlugins.contains(api.internalName)) return true
            if (enabledSources.contains(api.internalName)) return false
        }
        // Primary check: canonical unique IDs
        if (canonicalIds(api).any { disabledPlugins.contains(it) }) return true
        // Legacy fallback: slug, but only if it's unambiguous
        val slug = unambiguousSlug(api) ?: return false
        return disabledPlugins.contains(slug) || disabledPlugins.contains(api.name)
    }

    /** True when [id] (canonical name / slug) maps to a globally disabled extension. */
    fun isIdGloballyDisabled(id: String): Boolean {
        if (disabledPlugins.contains(id)) return true
        val api = loadedApis.find { profileMatchIds(it).contains(id) } ?: return false
        return isGloballyDisabled(api)
    }


    private fun Application.setupPlugins() {
        install(ContentNegotiation) { json(serverJson) }
        // JSON was sent uncompressed: the biggest catalog is 14.6 MB raw vs 0.6 MB gzipped, and copying
        // that per viewer was a large share of the server's kernel time. Video segments and playlists
        // aren't JSON and stay untouched; pre-compressed catalogs opt out (suppressCompression).
        install(io.ktor.server.plugins.compression.Compression) {
            gzip {
                matchContentType(io.ktor.http.ContentType.Application.Json)
                minimumSize(1024)
                // Catalog routes gzip their own cached bytes once (respondCatalogJson); compressing
                // here as well sent big catalogs gzipped twice
                condition { !request.path().contains("/catalog/") }
            }
        }
        
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
            if (ServerState.ownHosts.size < 64) ServerState.ownHosts.add(reqHost.lowercase())
            
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                return@intercept // End pipeline for OPTIONS
            }
        }
    }

    private fun Application.setupRoutes() {
        setupMpdProxyRoutes()
        routing {
            // Configuration lives in the app; the root just says the addon is up
            get("/") {
                call.respondText(
                    "CNCVerse Bridge is running. Add /manifest.json to Stremio or Nuvio; configure it in the CNCVerse Bridge app.",
                    ContentType.Text.Plain,
                )
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

            // Links to an extension's own server on this machine (Re:ANIME: http://127.0.0.1:41949/…)
            get("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }
            head("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@head call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }

            // ── Media server: files and HLS cached on this device, then served to the player ──
            get("/media/{id}/file/{name}") { MediaServer.serveFile(call, call.parameters["id"].orEmpty(), call.parameters["name"].orEmpty()) }
            head("/media/{id}/file/{name}") { MediaServer.serveFile(call, call.parameters["id"].orEmpty(), call.parameters["name"].orEmpty()) }
            get("/media/{id}/pl/{tok}") { MediaServer.servePlaylist(call, call.parameters["id"].orEmpty(), call.parameters["tok"].orEmpty()) }
            get("/media/{id}/seg/{tok}") { MediaServer.serveSegment(call, call.parameters["id"].orEmpty(), call.parameters["tok"].orEmpty()) }

            // ── Addon ────────────────────────────────────────────────────────
            // /u/{id}/… are older per-profile addon URLs; profiles are gone, so they
            // serve the same addon (installs made with them keep working).
            get("/manifest.json") { ServerState.info("[Request] manifest"); call.respond(buildManifest()) }
            get("/u/{profileId}/manifest.json") { call.respond(buildManifest()) }

            get("/catalog/{path...}") { call.respondCatalog() }
            get("/u/{profileId}/catalog/{path...}") { call.respondCatalog() }

            get("/meta/{type}/{id}.json") { call.respondMeta() }
            get("/u/{profileId}/meta/{type}/{id}.json") { call.respondMeta() }

            get("/stream/{type}/{id}.json") { call.respondStreams() }
            get("/u/{profileId}/stream/{type}/{id}.json") { call.respondStreams() }

            get("/subtitles/{type}/{id}.json") { call.respondSubtitles() }
            get("/u/{profileId}/subtitles/{type}/{id}.json") { call.respondSubtitles() }
        }
    }

    // ── Route handlers ───────────────────────────────────────────────────────

    /** Serves a catalog request (empty when catalogs are turned off). */
    private suspend fun ApplicationCall.respondCatalog() {
        if (ServerState.disableCatalogsGlobally) {
            respond(StremioCatalogResponse(emptyList()))
            return
        }

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

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, null) }
            respondCatalogJson(metas, cacheable = search.isNullOrBlank())
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

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, genre) }
            respondCatalogJson(metas, cacheable = search.isNullOrBlank())
        } else {
            respond(HttpStatusCode.BadRequest)
        }
    }

    /**
     * Big home-page catalogs (live TV playlists: thousands of channels) are served from
     * homePageCatalogCache as the same list instance until refreshed, yet were re-encoded to
     * JSON on every request — one of the larger CPU costs. Their bytes are kept with the exact
     * list they came from (identity check), so a refreshed page is re-encoded once and a stale
     * encoding can never be served.
     */
    /** Only the gzipped JSON is kept (~0.6 MB for a 15 MB catalog); the raw form is inflated on demand. */
    private class EncodedCatalog(val metas: List<StremioMeta>, val gzipped: ByteArray)
    private val encodedCatalogs = ConcurrentHashMap<Int, EncodedCatalog>()
    private const val ENCODE_CACHE_MIN_ITEMS = 200
    private const val ENCODE_CACHE_MAX = 64
    /** Encoded catalogs can be ~15 MB each (big playlists): cap the total, not just the count. */
    private const val ENCODE_CACHE_MAX_BYTES = 300L * 1024 * 1024

    private suspend fun ApplicationCall.respondCatalogJson(metas: List<StremioMeta>, cacheable: Boolean) {
        if (!cacheable || metas.size < ENCODE_CACHE_MIN_ITEMS) {
            respond(StremioCatalogResponse(metas))
            return
        }
        val key = System.identityHashCode(metas)
        val encoded = encodedCatalogs[key]?.takeIf { it.metas === metas }
            ?: withContext(Dispatchers.Default) {
                // Streamed straight into gzip: building the JSON as one String (~30 MB for the
                // biggest playlists) plus a 15 MB byte copy, several at once, filled the heap with
                // oversized (humongous) objects and crashed the server with OutOfMemoryError.
                val out = java.io.ByteArrayOutputStream(256 * 1024)
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                java.util.zip.GZIPOutputStream(out, 64 * 1024).use { gz ->
                    serverJson.encodeToStream(StremioCatalogResponse.serializer(), StremioCatalogResponse(metas), gz)
                }
                EncodedCatalog(metas, out.toByteArray())
            }.also {
                if (encodedCatalogs.size >= ENCODE_CACHE_MAX ||
                    encodedCatalogs.values.sumOf { e -> e.gzipped.size.toLong() } + it.gzipped.size > ENCODE_CACHE_MAX_BYTES
                ) encodedCatalogs.clear()
                encodedCatalogs[key] = it
            }
        if (request.headers[io.ktor.http.HttpHeaders.AcceptEncoding]?.contains("gzip", ignoreCase = true) == true) {
            response.headers.append(io.ktor.http.HttpHeaders.ContentEncoding, "gzip")
            response.headers.append(io.ktor.http.HttpHeaders.Vary, io.ktor.http.HttpHeaders.AcceptEncoding)
            respondBytes(encoded.gzipped, io.ktor.http.ContentType.Application.Json)
        } else {
            // Rare (clients without gzip): inflate on the fly
            respondOutputStream(io.ktor.http.ContentType.Application.Json) {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(encoded.gzipped)).use { it.copyTo(this) }
            }
        }
    }

    private suspend fun ApplicationCall.respondMeta() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val meta = withContext(pluginDispatcher) { buildMeta(type, id) }
        if (meta != null) {
            respond(StremioMetaResponse(meta))
        } else {
            respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun ApplicationCall.respondStreams() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)
        val requestStart = System.currentTimeMillis()
        streamRequestStart["$type:$id"] = requestStart
        ServerState.info("[Request] streams for $type/$id from ${request.local.remoteHost.let { request.headers["X-Forwarded-For"] ?: it }}")

        // The player is not kept waiting for ever: after RESPONSE_CAP_MS an empty list goes out (late results
        // are in the cache for the next try)
        // The search runs detached: a player that hangs up (or this cap) must not cancel it, it keeps filling the cache
        val search = streamSearchScope.async(pluginDispatcher) { buildStreams(type, id) }
        val built = withTimeoutOrNull(RESPONSE_CAP_MS) { search.await() }
        if (built == null) ServerState.warn("[Request] $type/$id: search still running after ${RESPONSE_CAP_MS / 1000}s - answering now, results will be in the cache for the next try")
        val tBuilt = System.currentTimeMillis()
        // Archive downloads (".zip" season packs) can never play in any player
        val streams = (built ?: emptyList()).filter { !SeekProbe.isArchiveUrl(it.url) }
        // Filters and ordering from the app's stream settings
        val p = streamPrefs
        // Adaptive HLS links report no quality: read their playlist for the real resolution
        val probed = if (p.probeHls || p.splitHls) QualityProbe.enrich(streams, p.splitHls, 4_000L, p.bestHls) else streams
        val tProbed = System.currentTimeMillis()
        val sorted = sortStreamsByQuality(probed)

        val kept = filterStreamsByProfile(sorted, p)
        // Whatever the settings above did, the list that goes out is sorted by resolution and has nothing below 720p
        val seekable = if (p.filterNonSeekable) SeekProbe.dropNonSeekable(kept) else kept
        val tSeek = System.currentTimeMillis()
        val filtered = enforceResolutionOrder(hardsubFirst(applyAnimeAudio(arrangeStreams(seekable, p), p)))
        ServerState.info("[Request] $type/$id timing: search ${(tBuilt - requestStart) / 1000.0}s, quality probe ${(tProbed - tBuilt) / 1000.0}s, seek check ${(tSeek - tProbed) / 1000.0}s")

        val out: List<StremioStream> = try {
            // Drop results whose link is exactly the same as an earlier (better ranked) one; different links are never merged
            val unique = filtered.distinctBy<StremioStream, Any> { it.url ?: it.externalUrl ?: it.infoHash?.let { h -> "magnet:$h" } ?: it.ytId?.let { y -> "yt:$y" } ?: it }

            // Streams of upstream add-ons already carry their add-on's own name and description: keep those
            // (marked with the add-on's name) and format only this bridge's own streams
            val (fromUpstream, own) = unique.partition { it.info?.linkType == "UPSTREAM" }
            val ownFormatted = com.cncverse.stremiobridge.format.StreamFormatter.applyFor(
                own,
                com.cncverse.stremiobridge.format.StreamFormatter.contextFromId(type, id),
                null,
            ).associateBy { it.url ?: it.infoHash ?: it.hashCode().toString() }
            val upstreamTagged = fromUpstream.associateBy({ it.url ?: it.infoHash ?: it.hashCode().toString() }) { u ->
                val addon = u.info?.addonName.orEmpty()
                u.copy(name = listOf(addon.ifBlank { "Upstream" }, u.name?.takeIf { n -> n != addon }).filterNotNull().joinToString(" • "))
            }
            val formatted = unique.map { st ->
                val k = st.url ?: st.infoHash ?: st.hashCode().toString()
                ownFormatted[k] ?: upstreamTagged[k] ?: st
            }
            // "Hide subtitles": no subtitle tracks go out (after formatting, so the description stays truthful)
            val out = (if (p.hideSubtitles) formatted.map { it.copy(subtitles = null) } else formatted)
                // One odd link must never cost the whole answer: a link whose rewrite fails goes out as it is
                .map { st ->
                    try {
                        // Media server: links that need a Referer / Cookie are fetched by this bridge and cached here
                        MediaServer.rewrite(LocalRelay.rewriteStream(withStreamBase(pixeldrainBypass(st, p))), mediaBase())
                    } catch (e: Throwable) {
                        ServerState.warn("[Request] could not prepare a link (${st.info?.addonName}): ${e.javaClass.simpleName}: ${e.message}")
                        st
                    }
                }
                .let { list ->
                    // "Support the project" entry on top, only above real results (can be hidden in the app)
                    if (p.showSupport && list.isNotEmpty()) listOf(SUPPORT_STREAM) + list else list
                }
                .map { s -> s.subtitles?.let { subs -> s.copy(subtitles = subs.map { it.copy(lang = com.cncverse.stremiobridge.format.SubtitleLangs.normalize(it.lang)) }) } ?: s }

            out
        } catch (e: Throwable) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[Request] $type/$id: preparing the list failed (${e.javaClass.simpleName}: ${e.message}) - sending the plain list. ${e.stackTraceToString().take(900)}")
            filtered.mapNotNull { st -> if (st.url == null && st.externalUrl == null && st.infoHash == null) null else st.copy(subtitles = null) }
        }
        // Text with a broken emoji (a lone surrogate half) cannot be written as UTF-8 and would fail the whole answer
        val safeOut = out.map { sanitizeStream(it) }
        try {
            respond(StremioStreamResponse(safeOut))
        } catch (e: Throwable) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[Request] $type/$id: sending the list failed (${e.javaClass.simpleName}: ${e.message}) - sending it without subtitles")
            respond(StremioStreamResponse(safeOut.map { it.copy(subtitles = null) }))
        }
        ServerState.info("[Request] sent ${out.size} link(s) for $type/$id (${(System.currentTimeMillis() - requestStart) / 1000.0}s)")
        runCatching {
            val parts = id.split(':')
            val ep = if ((type == "series" || type == "anime") && parts.size >= 3) {
                if (parts[0] == "kitsu" || parts[0] == "mal" || parts[0] == "anilist" || parts[0] == "anidb") "E${parts.last()}"
                else "S${parts[parts.size - 2]} E${parts.last()}"
            } else ""
            com.cncverse.stremiobridge.state.StreamHistory.add(
                com.cncverse.stremiobridge.state.HistoryEntry(
                    time = System.currentTimeMillis(), type = type, id = id,
                    title = com.cncverse.stremiobridge.state.StreamHistory.titleFor(id) ?: id.substringBefore(':'),
                    episode = ep, found = streams.size, shown = out.size,
                    tookMs = System.currentTimeMillis() - requestStart,
                    providers = streams.groupingBy { it.info?.addonName ?: it.name ?: "?" }.eachCount()
                        .entries.sortedByDescending { it.value }.map { "${it.key}: ${it.value}" },
                    lines = out.map { st -> listOfNotNull(st.name, st.title).joinToString(" | ").replace("\n", " / ").replace("\r", "") },
                )
            )
        }
        if (p.prefetchNext) prefetchNextEpisode(type, id)
    }

    private val prefetchedNext = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The id of the episode after [id] ("tt1:1:5" -> "tt1:1:6", "kitsu:7:12" -> "kitsu:7:13"), or null for movies / unknown shapes. */
    private fun nextEpisodeId(type: String, id: String): String? {
        if (type != "series" && type != "anime") return null
        val parts = id.split(':')
        if (parts.size < 3) return null
        val ep = parts.last().toIntOrNull() ?: return null
        if (ep < 0) return null
        return parts.dropLast(1).joinToString(":") + ":" + (ep + 1)
    }

    /** Loads the next episode's links in the background so pressing "next" is instant (results land in the stream cache). */
    private fun prefetchNextEpisode(type: String, id: String) {
        val next = nextEpisodeId(type, id) ?: return
        if (prefetchedNext.size > 500) prefetchedNext.clear()
        if (!prefetchedNext.add(next)) return
        streamSearchScope.launch {
            delay(1_500)
            runCatching {
                ServerState.info("[Streams] Preparing the next episode in the background: $next")
                withContext(pluginDispatcher) { buildStreams(type, next) }
            }.onFailure { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
        }
    }

    /** Where clients reach this bridge (for the links the media server hands out). */
    private fun mediaBase(): String =
        ServerState.streamBaseUrl ?: ServerState.publicBaseUrl.ifBlank { "http://127.0.0.1:${ServerState.serverPort}" }

    private val SUB_TAG = Regex("\\[sub(?:bed)?\\]", RegexOption.IGNORE_CASE)
    private val DUB_TAG = Regex("\\[dub(?:bed)?\\]", RegexOption.IGNORE_CASE)

    /** "sub" / "dub" when the extension labelled the link (the bridge tags anime variants "[Sub]" / "[Dub]"). */
    private fun audioKind(s: StremioStream): String? {
        val text = s.title.orEmpty() + " " + s.name.orEmpty() + " " + s.info?.linkName.orEmpty()
        return when {
            DUB_TAG.containsMatchIn(text) -> "dub"
            SUB_TAG.containsMatchIn(text) -> "sub"
            else -> null
        }
    }

    /**
     * Anime: links with the subtitles burned into the video (hardsub) go on top - they play in every
     * player with no subtitle setup. Order inside each group is unchanged (best quality first). Lists
     * without any hardsub link are left exactly as they are.
     */
    fun hardsubFirst(streams: List<StremioStream>): List<StremioStream> {
        val hard = streams.filter { com.cncverse.stremiobridge.format.StreamVariables.subTypeOf(it) == "Hardsub" }
        if (hard.isEmpty() || hard.size == streams.size) return streams
        val dubs = streams.filter { audioKind(it) == "dub" }
        // A dub list keeps the dub on top when the dub is what was asked for
        val keep = if (streamPrefs.animeAudio == "dub" && dubs.isNotEmpty()) return streams else hard
        return keep + streams.filter { it !in keep }
    }

    /** Anime: the preferred audio (sub or dub) first; optionally the other kind is hidden. */
    fun applyAnimeAudio(streams: List<StremioStream>, p: StreamPrefs): List<StremioStream> {
        if (p.animeAudio == "all") return streams
        val preferred = streams.filter { audioKind(it) == p.animeAudio }
        if (preferred.isEmpty()) return streams // nothing of that kind: never leave the list empty
        val unlabelled = streams.filter { audioKind(it) == null }
        val other = streams.filter { val k = audioKind(it); k != null && k != p.animeAudio }
        return if (p.animeAudioOnly) preferred + unlabelled else preferred + unlabelled + other
    }

    /** Drops unpaired UTF-16 surrogates (cut-off emoji) that make JSON/UTF-8 encoding throw MalformedInputException. */
    fun cleanText(t: String?): String? {
        if (t == null) return null
        var bad = false
        for (i in t.indices) {
            val c = t[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 >= t.length || !Character.isLowSurrogate(t[i + 1])) { bad = true; break } }
            else if (Character.isLowSurrogate(c)) { if (i == 0 || !Character.isHighSurrogate(t[i - 1])) { bad = true; break } }
        }
        if (!bad) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (Character.isHighSurrogate(c) && i + 1 < t.length && Character.isLowSurrogate(t[i + 1])) { sb.append(c).append(t[i + 1]); i += 2; continue }
            if (!Character.isSurrogate(c)) sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun sanitizeStream(s: StremioStream): StremioStream = s.copy(
        name = cleanText(s.name), title = cleanText(s.title), url = cleanText(s.url),
        externalUrl = cleanText(s.externalUrl),
        subtitles = s.subtitles?.map { it.copy(id = cleanText(it.id) ?: "", lang = cleanText(it.lang) ?: "", url = cleanText(it.url) ?: "") },
        behaviorHints = s.behaviorHints?.let { h ->
            h.copy(
                bingeGroup = cleanText(h.bingeGroup),
                proxyHeaders = h.proxyHeaders?.let { ph -> ph.copy(request = ph.request?.entries?.associate { (k, v) -> (cleanText(k) ?: "") to (cleanText(v) ?: "") }) },
            )
        },
    )

    private val PIXELDRAIN = Regex("^https?://(?:www\\.)?pixeldrain\\.(?:com|dev|net)/(?:api/file|u)/([A-Za-z0-9_-]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

    /** pixeldrain.com / .dev / .net file links -> https://cdn.pixeldrain.eu.cc/<id> (no hotlink / download limits). */
    fun pixeldrainBypass(s: StremioStream, p: StreamPrefs): StremioStream {
        if (!p.pixeldrainBypass) return s
        val id = s.url?.let { PIXELDRAIN.matchEntire(it) }?.groupValues?.get(1) ?: return s
        return s.copy(url = "https://cdn.pixeldrain.eu.cc/$id")
    }

    private val SUPPORT_STREAM = StremioStream(
        name = "✨ | support the project!",
        title = "Click to donate this project ❤️\n(you can hide this in the app settings)",
        externalUrl = "https://cncverse.pages.dev",
    )

    /** Paths of the bridge's own relay endpoints (links that point back at this server). */
    private val RELAY_PATH = Regex("^https?://([^/:]+)(?::[0-9]+)?(/(proxy/|decrypt|init_decrypt).*)$")

    /**
     * Points the bridge's own relay links at [ServerState.streamBaseUrl] (the server IP) - also
     * links cached earlier under the domain. Extension links to other sites are untouched.
     */
    private fun withStreamBase(s: StremioStream): StremioStream {
        val base = ServerState.streamBaseUrl ?: return s
        fun fix(u: String?): String? {
            val m = u?.let { RELAY_PATH.matchEntire(it) } ?: return u
            // Only links back to this bridge; another site's own /proxy/ path is left alone
            if (m.groupValues[1].lowercase() !in ServerState.ownHosts) return u
            return base + m.groupValues[2]
        }
        val subs = s.subtitles?.map { it.copy(url = fix(it.url) ?: it.url) }
        return s.copy(url = fix(s.url), subtitles = subs)
    }

    private suspend fun ApplicationCall.respondSubtitles() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        if (streamPrefs.hideSubtitles) {
            respond(StremioSubtitleResponse(emptyList()))
            return
        }
        val streams = withContext(pluginDispatcher) { buildStreams(type, id) }
        val subtitles = streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }
            .map { it.copy(lang = com.cncverse.stremiobridge.format.SubtitleLangs.normalize(it.lang)) }

        respond(StremioSubtitleResponse(subtitles))
    }

    /**
     * Identifiers that can match this API in a profile record.
     * Includes canonical IDs (always unique per plugin/API) and the display-name slug
     * ONLY if unambiguous (unique across all loaded extensions), preventing cross-repo
     * collisions when two repos ship an extension with the same name.
     */
    private fun profileMatchIds(api: MainApiWrapper): List<String> {
        val ids = mutableListOf(api.internalName)
        // A plugin with several sources (VegaMovies plugin = VegaMovies + Rogmovies) gets one
        // card per source, and its plugin id often equals one source's card id. Matching
        // every source by the plugin id made deselecting "VegaMovies" also hide Rogmovies —
        // so only single-source plugins are matched by their plugin id.
        if ((apisPerPlugin()[api.pluginInternalName] ?: 1) <= 1) ids.add(api.pluginInternalName)
        unambiguousSlug(api)?.let { ids.add(it) }
        return ids.distinct()
    }

    /** pluginInternalName -> number of loaded APIs; rebuilt only when [loadedApis] changes. */
    @Volatile private var apisPerPluginCache: Pair<Long, Map<String, Int>>? = null

    private fun apisPerPlugin(): Map<String, Int> {
        val version = apiList.version
        apisPerPluginCache?.let { (v, counts) -> if (v == version) return counts }
        val counts = HashMap<String, Int>()
        for (api in apiList) counts[api.pluginInternalName] = (counts[api.pluginInternalName] ?: 0) + 1
        apisPerPluginCache = version to counts
        return counts
    }



    // 📺 Manifest builder 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺

    /**
     * Converts a plugin display-name to a short alphanumeric slug for use in catalog IDs.
     * Normalises special characters so JIO TV (IND) → JIOTVIND and
     * JIO TV+ (IND) → JIOTVPlusIND, giving each variant a unique catalog ID
     * even when two APIs share the same [internalName].
     */
    private fun nameSlug(name: String): String {
        slugMemo[name]?.let { return it }
        val slug = name
            .replace("+", "Plus")
            .replace("&", "And")
            .replace(NON_ALNUM, "")
            .take(48)
            .ifBlank { "unknown" }
        if (slugMemo.size > 10_000) slugMemo.clear()
        slugMemo[name] = slug
        return slug
    }

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
        val slug = apiKey(api)
        return api.supportedTypes
            .map { cs3TvTypeToStremio(it) }
            .distinct()
            .map { stremioType ->
                StremioCatalogDef(
                    type = stremioType,
                    id   = "cnc_${slug}_$stremioType",
                    name = "${api.name} ($stremioType)",
                    extra = listOf(ExtraEntry("search"), ExtraEntry("skip"))
                )
            }
    }

    suspend fun fetchCatalogDefsForApi(api: MainApiWrapper): List<StremioCatalogDef> {
        val sections = try {
            withTimeoutOrNull(25_000) {
                api.getMainPageSections()
            } ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
        val slug = apiKey(api)

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
                    id   = "cnc_${slug}_$stremioType",
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

    private const val HOME_REFRESH_INTERVAL_MS = 30 * 60_000L

    /** Reloads every extension's home page (genres + items) every 30 minutes while the server runs. */
    fun startPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = manifestRefreshScope.launch {
            while (isActive) {
                delay(HOME_REFRESH_INTERVAL_MS)
                runCatching { refreshManifestAndHomepages() }
                    .onFailure { ServerState.warn("Periodic home page refresh failed: ${it.message}") }
            }
        }
    }

    /** Completed once the home pages' genres (catalog definitions) have been loaded the first time. */
    private val firstCatalogDefs = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * The first manifest a player fetches should already list each extension's
     * home-page sections as genres: start the home-page load if nobody has yet
     * and wait for the genres (bounded, so the player never hangs).
     */
    private suspend fun awaitFirstCatalogDefs() {
        if (firstCatalogDefs.isCompleted || loadedApis.isEmpty()) return
        if (!isRefreshing.get()) manifestRefreshScope.launch { refreshManifestAndHomepages() }
        withTimeoutOrNull(30_000) { firstCatalogDefs.await() }
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
    /** Scope of the in-flight background refresh, so a plugin reload can cancel it. */
    @Volatile private var refreshWork: kotlinx.coroutines.Job? = null

    /**
     * Cancels an in-flight background refresh. Called before plugins are
     * unloaded: the refresh holds a snapshot of the old APIs (keeping the old
     * plugin classloaders alive) and would keep calling unloaded plugins.
     */
    fun cancelBackgroundRefresh() {
        refreshWork?.cancel()
    }

    suspend fun refreshManifestAndHomepages() {
        if (!isRefreshing.compareAndSet(false, true)) {
            ServerState.info("Manifest refresh already in progress, skipping duplicate call")
            return
        }
        try {
            coroutineScope {
                refreshWork = coroutineContext[kotlinx.coroutines.Job]
                refreshManifestAndHomepagesInner()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Our own scope was cancelled by a plugin reload — the caller keeps running
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            ServerState.info("Background refresh cancelled (plugins reloaded)")
        } catch (e: Throwable) {
            ServerState.warn("Manifest refresh error: ${e.message}")
        } finally {
            refreshWork = null
            isRefreshing.set(false)
        }
    }

    private suspend fun refreshManifestAndHomepagesInner() {
        run {
            ServerState.info("🔄 Refreshing home pages & provider manifest catalogs in background…")
            val apis = loadedApis.toList()
            if (apis.isEmpty()) return

            // 1. Clear dynamic sections cache on providers so fresh sections are fetched
            apis.forEach { runCatching { it.clearCache() } }

            val newProviderCatalogs = ConcurrentHashMap<String, List<StremioCatalogDef>>()
            val newHomePageCache = ConcurrentHashMap<String, List<StremioMeta>>()

            // 2. Fetch fresh catalog definitions (sections/genres) with bounded concurrency.
            // Kept low: this is background work and must leave room for live requests.
            val sem = Semaphore(3)
            coroutineScope {
                apis.map { api ->
                    async(pluginDispatcher) {
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
            // Genres are known now: publish them right away (the manifest waits for this),
            // the home-page items follow below
            providerCatalogCache.putAll(newProviderCatalogs)
            firstCatalogDefs.complete(Unit)

            // 3. Pre-warm home page catalogs into newHomePageCache
            val catalogsToPrewarm = newProviderCatalogs.values.flatten().distinctBy { it.id }
            ServerState.info("🔥 Pre-warming ${catalogsToPrewarm.size} fresh home page(s)…")
            coroutineScope {
                catalogsToPrewarm.map { cat ->
                    async(pluginDispatcher) {
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
        }
    }

    /**
     * Pre-warms every plugin's home page by refreshing manifests and home pages in the background.
     */
    suspend fun preWarmHomepages() {
        refreshManifestAndHomepages()
    }

    /**
     * Builds the Stremio manifest from [providerCatalogCache]: every enabled extension's
     * catalogs with its home-page sections as genres, minus catalogs hidden in the app.
     */
    suspend fun buildManifest(): StremioManifest {
        val types = listOf("movie", "series", "other", "tv")
        val prefs = streamPrefs

        val catalogs = if (ServerState.disableCatalogsGlobally) {
            emptyList()
        } else {
            awaitFirstCatalogDefs()
            loadedApis.filter { !isGloballyDisabled(it) }.flatMap { api ->
                providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
            }.distinctBy { it.id }
                .filter { "${it.type}/${it.id}" !in prefs.disabledCatalogs }
        }
        val hasCatalogs = catalogs.isNotEmpty()

        // Changes whenever the catalog list does, so players drop their cached manifest
        val configHash = (catalogs.joinToString(",") { it.id + it.extra.size }.hashCode() xor catalogs.size)
            .let { kotlin.math.abs(it) % 10000 }

        return StremioManifest(
            id          = "com.cncverse.stremiobridge",
            version     = "1.0.$configHash",
            name        = "CNCVerse Bridge",
            description = "Cloudstream plugin bridge for Stremio \u2014 Developed by NivinCNC",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Bridge/refs/heads/main/logo.png",
            types       = types,
            resources   = if (hasCatalogs) listOf("catalog", "meta", "stream", "subtitles") else listOf("meta", "stream", "subtitles"),
            catalogs    = catalogs,
            behaviorHints = BehaviorHints(configurable = false),
        )
    }

    /** Every catalog an enabled extension offers (`<type>/<id>` \u2192 name), for the app's catalog switches. */
    fun allCatalogs(): List<Pair<String, String>> =
        loadedApis.filter { !isGloballyDisabled(it) }.flatMap { api ->
            providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
        }.distinctBy { it.id }.map { "${it.type}/${it.id}" to it.name }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun fetchCatalogItemsDirect(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)

        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { apiKey(it) == nameSlugFromId }
            ?: loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }          // old-format compat
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }    // prefix fallback
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                // Cached per extension + query (shared with the stream lookup's searches)
                val results = SearchLoadCache.getSearch(api.internalName, search)
                    ?: api.search(search).also { SearchLoadCache.putSearch(api.internalName, search, it) }
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }.also { rememberTitles(it) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }.also { rememberTitles(it) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Stremio item id → display title for items we served in catalogs/meta.
     * Stream requests only carry the id, so this is how streams opened from our
     * own catalogs (live events, provider home pages) learn their title for the
     * stream formatter's metadata.title. Bounded LRU.
     */
    private val metaTitles = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 20_000
    }
    private val LEADING_SYMBOLS = Regex("^[^\\p{L}\\p{N}]+")

    private fun rememberTitles(metas: List<StremioMeta>) {
        synchronized(metaTitles) { metas.forEach { m -> if (m.name.isNotBlank()) metaTitles[m.id] = m.name } }
    }

    /** Remembered title for [id], minus leading status emoji like "🔴 ". */
    private fun titleForId(id: String): String? =
        synchronized(metaTitles) { metaTitles[id] }?.replace(LEADING_SYMBOLS, "")?.trim()?.takeIf { it.isNotEmpty() }

    private fun withMetadataTitle(streams: List<StremioStream>, title: String?): List<StremioStream> {
        if (title == null) return streams
        return streams.map { st ->
            val info = st.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            if (info.metadataTitle != null) st else st.copy(info = info.copy(metadataTitle = title))
        }
    }

    private val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    // api.tmdb.org answers in ~0.4 s from the server; api.themoviedb.org (same API) gets its
    // connections reset about half the time (hostname filtered on Indian networks). Both are
    // asked at once and the first answer wins, so a hiccup on one costs nothing. A short
    // per-host timeout was tried instead: under load the server itself was slower than that
    // and every lookup failed.
    private val TMDB_HOSTS = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private const val TMDB_TIMEOUT_MS = 8_000L

    /** TMDB/IMDb id → (title, year). Bounded LRU: one entry per title ever requested would grow forever. */
    private val genericMediaCache: MutableMap<String, Pair<String, Int?>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, Int?>>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Int?>>?) = size > 5_000
        }
    )

    private suspend fun fetchTmdbJson(endpointPathAndQuery: String): JsonObject? {
        val cleanPath = endpointPathAndQuery.trimStart('/')
        val delimiter = if (cleanPath.contains("?")) "&" else "?"
        val answers = kotlinx.coroutines.channels.Channel<JsonObject?>(TMDB_HOSTS.size)
        return coroutineScope {
            val tries = TMDB_HOSTS.map { host ->
                launch {
                    val url = "$host/$cleanPath${delimiter}api_key=$TMDB_API_KEY"
                    ServerState.debug("Fetching TMDB: $url")
                    val json = try {
                        serverJson.parseToJsonElement(httpClient.get(url).bodyAsText()).jsonObject
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ServerState.debug("TMDB error on $host: ${e.message?.take(80)}")
                        null
                    }
                    answers.send(json)
                }
            }
            // First successful answer wins; a failed host just leaves it to the other one
            val result = withTimeoutOrNull(TMDB_TIMEOUT_MS) {
                var found: JsonObject? = null
                repeat(TMDB_HOSTS.size) { if (found == null) found = answers.receive() }
                found
            }
            tries.forEach { it.cancel() }
            if (result == null) ServerState.warn("TMDB lookup failed on all hosts: $cleanPath")
            result
        }
    }

    private val tmdbSeasonCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Number of episodes TMDB lists for season [season] of the series behind [ext]; null when unknown. */
    private suspend fun tmdbSeasonEpisodeCount(ext: ExternalId, season: Int): Int? {
        val key = "${ext.scheme}:${ext.key}:$season"
        tmdbSeasonCounts[key]?.let { return it }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "imdb", "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return null
            val n = (fetchTmdbJson("tv/$tvId/season/$season")?.get("episodes") as? kotlinx.serialization.json.JsonArray)?.size
            if (n != null && n > 0) tmdbSeasonCounts[key] = n
            n?.takeIf { it > 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    private val tmdbAllCounts = java.util.concurrent.ConcurrentHashMap<String, Map<Int, Int>>()

    /** Season number -> number of episodes TMDB lists, for the whole series behind [ext] (specials excluded). */
    private suspend fun tmdbAllSeasonCounts(ext: ExternalId): Map<Int, Int>? {
        val key = "${ext.scheme}:${ext.key}"
        tmdbAllCounts[key]?.let { return it }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "imdb", "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return null
            val seasons = fetchTmdbJson("tv/$tvId")?.get("seasons") as? kotlinx.serialization.json.JsonArray ?: return null
            val m = HashMap<Int, Int>()
            for (e in seasons) {
                val o = e as? kotlinx.serialization.json.JsonObject ?: continue
                val n = o["season_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                val c = o["episode_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                if (n > 0 && c > 0) m[n] = c
            }
            if (m.isNotEmpty()) tmdbAllCounts[key] = m
            m.takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    /** Title + year for any supported id; null when the source has no such item. */
    private suspend fun resolveExternal(type: String, ext: ExternalId): Pair<String, Int?>? {
        if (ext.scheme == "imdb" || ext.scheme == "tmdb") return resolveGenericMedia(type, ext.key)
        val cacheKey = "$type:${ext.scheme}:${ext.key}"
        genericMediaCache[cacheKey]?.let { return it }
        val result = try {
            when (ext.scheme) {
                "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=tvdb_id")?.let { j ->
                    val r = ((j["tv_results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                        ?: (j["movie_results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull())?.jsonObject
                    val title = r?.get("name")?.jsonPrimitive?.contentOrNull ?: r?.get("title")?.jsonPrimitive?.contentOrNull
                    val date = r?.get("first_air_date")?.jsonPrimitive?.contentOrNull ?: r?.get("release_date")?.jsonPrimitive?.contentOrNull
                    title?.let { it to date?.take(4)?.toIntOrNull() }
                }
                "tvmaze" -> getJson("https://api.tvmaze.com/shows/${ext.key}")?.let { j ->
                    j["name"]?.jsonPrimitive?.contentOrNull?.let { it to j["premiered"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull() }
                }
                "kitsu" -> getJson("https://kitsu.io/api/edge/anime/${ext.key}", accept = "application/vnd.api+json")?.let { j ->
                    val a = j["data"]?.jsonObject?.get("attributes")?.jsonObject
                    val titles = a?.get("titles")?.jsonObject
                    val title = titles?.get("en")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: a?.get("canonicalTitle")?.jsonPrimitive?.contentOrNull
                        ?: titles?.get("en_jp")?.jsonPrimitive?.contentOrNull
                    title?.let { it to a?.get("startDate")?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull() }
                }
                "mal" -> aniList("idMal", ext.key)
                "anilist" -> aniList("id", ext.key)
                "anidb" -> getJson("https://arm.haglund.dev/api/v2/ids?source=anidb&id=${ext.key}")
                    ?.get("anilist")?.jsonPrimitive?.contentOrNull?.let { aniList("id", it) }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ServerState.warn("Resolve ${ext.scheme}:${ext.key} failed: ${e.message?.take(80)}")
            null
        }
        if (result != null) {
            genericMediaCache[cacheKey] = result
            ServerState.debug("Resolved ${ext.scheme}:${ext.key} -> '${result.first}' (${result.second})")
        }
        return result
    }

    /**
     * ID-lookup APIs (Kitsu, AniList, the AniDB mapping) go through OkHttp: Ktor's CIO client
     * fails the TLS handshake with some of them (arm.haglund.dev: "ProtocolVersion").
     */
    private val idApiClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private suspend fun idApiCall(req: okhttp3.Request): String? = withContext(Dispatchers.IO) {
        idApiClient.newCall(req).execute().use { r -> if (r.code == 200) r.body?.string() else null }
    }

    private suspend fun getJson(url: String, accept: String = "application/json"): JsonObject? =
        idApiCall(okhttp3.Request.Builder().url(url).header("Accept", accept).build())
            ?.let { serverJson.parseToJsonElement(it).jsonObject }

    /** AniList lookup by its own id or the MAL id: English title, else romaji. */
    private suspend fun aniList(field: String, key: String): Pair<String, Int?>? {
        val id = key.toIntOrNull() ?: return null
        val query = "query{Media(" + field + ":" + id + ",type:ANIME){title{english romaji}seasonYear startDate{year}}}"
        val body = kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive(query)) }.toString()
        val text = idApiCall(
            okhttp3.Request.Builder().url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
                .build()
        ) ?: return null
        val media = serverJson.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject ?: return null
        val t = media["title"]?.jsonObject
        val title = t?.get("english")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: t?.get("romaji")?.jsonPrimitive?.contentOrNull ?: return null
        val year = media["seasonYear"]?.jsonPrimitive?.intOrNull
            ?: media["startDate"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull
        return title to year
    }

    private suspend fun resolveGenericMedia(type: String, tmdbId: String): Pair<String, Int?>? {
        val cacheKey = "$type:$tmdbId"
        genericMediaCache[cacheKey]?.let { return it }

        val isImdbId = tmdbId.startsWith("tt")
        val mediaType = if (type == "series") "tv" else "movie"

        // 1. If it's an IMDb ID, try Cinemeta first (extremely fast & resilient)
        if (isImdbId) {
            try {
                val cinemetaType = if (type == "series") "series" else "movie"
                val cinemetaUrl = "https://v3-cinemeta.strem.io/meta/$cinemetaType/$tmdbId.json"
                val responseText = withTimeoutOrNull(8_000) {
                    httpClient.get(cinemetaUrl).bodyAsText()
                }
                if (responseText != null) {
                    val json = serverJson.parseToJsonElement(responseText).jsonObject
                    val meta = json["meta"]?.jsonObject
                    val title = meta?.get("name")?.jsonPrimitive?.content
                    if (!title.isNullOrBlank()) {
                        val yearStr = meta["year"]?.jsonPrimitive?.content
                            ?: meta["releaseInfo"]?.jsonPrimitive?.content
                        val year = yearStr?.take(4)?.toIntOrNull()
                        val result = Pair(title, year)
                        genericMediaCache[cacheKey] = result
                        ServerState.debug("Cinemeta resolved $tmdbId -> '$title' ($year)")
                        return result
                    }
                }
            } catch (e: Throwable) {
                ServerState.warn("Cinemeta resolve error for $tmdbId: ${e.message?.take(80)}")
            }
        }

        // 2. Query TMDB with multi-host fallback (api.tmdb.org -> api.themoviedb.org)
        val endpoint = if (isImdbId) {
            "find/$tmdbId?external_source=imdb_id"
        } else {
            "$mediaType/$tmdbId"
        }

        val jsonObject = fetchTmdbJson(endpoint) ?: return null

        val mediaObj = if (isImdbId) {
            val movieResults = jsonObject["movie_results"] as? kotlinx.serialization.json.JsonArray
            val tvResults = jsonObject["tv_results"] as? kotlinx.serialization.json.JsonArray
            (movieResults?.firstOrNull() ?: tvResults?.firstOrNull())?.jsonObject
        } else {
            jsonObject
        } ?: return null

        val title = mediaObj["title"]?.jsonPrimitive?.content
            ?: mediaObj["name"]?.jsonPrimitive?.content
            ?: return null

        val year = mediaObj["release_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()
            ?: mediaObj["first_air_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()

        val result = Pair(title, year)
        genericMediaCache[cacheKey] = result
        ServerState.debug("TMDB resolved $tmdbId -> '$title' ($year)")
        return result
    }
    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)
        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { apiKey(it) == nameSlugFromId }
            ?: loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (ServerState.disableCatalogsGlobally) return emptyList()
        if (isGloballyDisabled(api)) return emptyList()

        // Live-TV sources (SKTech, PlayZTV, PlayFy…) answer a search by downloading, decrypting and
        // re-parsing every playlist — thousands of channels, per source, per search — and Stremio
        // sends a search to every catalog. Their home pages are already parsed and cached, so a
        // search is just a filter over those channels.
        if (!search.isNullOrBlank() && api.supportedTypes.all { it == "tv" }) {
            val pages = homePageCatalogCache.filterKeys { it.startsWith("$type:$id:") }.values
            if (pages.isNotEmpty()) {
                // Plain lower-case contains: normalizeTitle's regexes over ~15k channels per
                // catalog per search showed up in the CPU profile
                val words = normalizeTitle(search).split(' ').filter { it.isNotEmpty() }
                return pages.asSequence().flatten().distinctBy { it.id }
                    .filter { m -> val n = m.name.lowercase(); words.all { n.contains(it) } }
                    .drop(skip).take(100).toList()
            }
        }

        val isHomePage = search.isNullOrBlank() && skip == 0
        val cacheKey = "$type:$id:$genre"

        if (isHomePage) {
            val cached = homePageCatalogCache[cacheKey]
            if (!cached.isNullOrEmpty()) {
                // Home pages are loaded once at startup (and after a plugin reload). After that a
                // page is refreshed only when someone opens it and it is older than its TTL: the
                // cached page is served at once and that one page reloads in the background.
                // (A timer used to reload every home page every 30 min, opened or not.)
                val liveOnly = api.supportedTypes.all { it == "tv" }
                val age = System.currentTimeMillis() - (homePageCachedAt[cacheKey] ?: 0L)
                // Live events change by the minute; channel playlists re-parse thousands of
                // lines, so 15 min; everything else 30 min.
                val ttl = when {
                    !liveOnly -> HOME_PAGE_TTL_MS
                    cached.size <= 150 -> LIVE_HOME_TTL_MS
                    else -> LIVE_PLAYLIST_TTL_MS
                }
                // Live: one refresh per provider at a time (its sections share one fetch);
                // others: one per page
                val refreshKey = if (liveOnly) api.internalName else cacheKey
                if (age > ttl && homePageRefreshing.add(refreshKey)) {
                    streamSearchScope.launch {
                        try {
                            val fresh = fetchCatalogItemsDirect(type, id, null, 0, genre)
                            if (fresh.isNotEmpty()) {
                                homePageCatalogCache[cacheKey] = fresh
                                homePageCachedAt[cacheKey] = System.currentTimeMillis()
                            }
                        } finally {
                            homePageRefreshing.remove(refreshKey)
                        }
                    }
                }
                return cached
            }
        }

        val metas = fetchCatalogItemsDirect(type, id, search, skip, genre)
        if (isHomePage && metas.isNotEmpty()) {
            homePageCatalogCache[cacheKey] = metas
            homePageCachedAt[cacheKey] = System.currentTimeMillis()
        }
        return metas
    }


    // ── Meta builder ──────────────────────────────────────────────────────────

    /** A source that has not answered its detail page by now is skipped (Stremio gives up long before). */
    private const val META_LOAD_TIMEOUT_MS = 25_000L

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (pluginKey, dataUrl) = StremioIds.decode(id) ?: return null
        // Item ids carry the source's display-name slug, which several extensions can share
        // (SKTech and LivXow both have "📺 SUN NXT"). Taking the first match could pick a
        // copy that is disabled for this user → 404 and a detail page that never loads.
        // Try every enabled match instead: exact keys first, then the name slug.
        val candidates = (loadedApis.filter { apiKey(it) == pluginKey } +
            loadedApis.filter { nameSlug(it.name) == pluginKey } +
            loadedApis.filter { it.internalName == pluginKey })
            .distinct()
            .filter { !isGloballyDisabled(it) }
        for (api in candidates) {
            val meta = try {
                // Opening the same detail page again used to re-scrape the site every time
                val key = apiKey(api)
                val info = SearchLoadCache.getLoad(key, dataUrl)?.info
                    ?: kotlinx.coroutines.withTimeoutOrNull(META_LOAD_TIMEOUT_MS) { api.load(dataUrl) }
                        ?.also { SearchLoadCache.putLoad(key, dataUrl, it) }
                // The meta keeps the id it was asked for. toStremiMeta() names a movie by its
                // load() data (often a JSON blob of players); clients that store the returned
                // id (Nuvio) then asked meta for that blob, which load() can't open → 404.
                info?.toStremiMeta(nameSlug(api.name), type)?.copy(id = id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ServerState.warn("Meta error for ${api.name}: ${e.message}")
                null
            }
            if (meta != null) return meta.also { rememberTitles(listOf(it)) }
        }
        return null
    }


    // ── Quality sorting ───────────────────────────────────────────────────────

    /** Higher is listed earlier: the stream's real resolution height (unknown = 0, listed after every known one). */
    private fun streamQualityRank(stream: StremioStream): Int = resolutionHeight(stream) ?: 0

    /** Lowest resolution that is ever shown. Streams with a KNOWN lower resolution are always dropped. */

    /** The stream's resolution as a pixel height (2160, 1080, 720, 480, 360 ...), or null when it cannot be found. */
    fun resolutionHeight(stream: StremioStream): Int? =
        com.cncverse.stremiobridge.format.StreamVariables.resolutionOf(stream)
            ?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * Final, settings-independent pass over every list that is sent out: streams whose resolution is known
     * and below 720p are removed, and the rest is ordered by resolution, highest first (unknown last).
     * The sort is stable, so inside one resolution the order chosen by the other settings (provider order,
     * size, hardsub first, anime audio) is kept.
     */
    fun enforceResolutionOrder(streams: List<StremioStream>): List<StremioStream> {
        // Settings -> Streams -> "Hide links below" (default 720p; "" = show all)
        val min = streamPrefs.minResolution.filter { it.isDigit() }.toIntOrNull() ?: 0
        val keyed = streams.map { it to resolutionHeight(it) }
        // Never leave a title with no links just because every link is low quality
        val visible = keyed.filter { (_, h) -> h == null || h >= min }.ifEmpty { keyed }
        return visible.sortedByDescending { (_, h) -> h ?: 0 }.map { it.first }
    }

    /**
     * Best quality first. Ties are broken the same way on every load (provider, then name, then link) -
     * they used to keep the order in which providers happened to answer, so equal-quality links changed
     * places from one request to the next.
     */
    private fun sortStreamsByQuality(streams: List<StremioStream>): List<StremioStream> {
        class K(val s: StremioStream, val rank: Int, val provider: String, val label: String, val link: String)
        val keyed = streams.map {
            K(
                it, streamQualityRank(it),
                (it.info?.providerName ?: com.cncverse.stremiobridge.format.StreamVariables.addonOf(it)).orEmpty().lowercase(),
                (it.info?.linkName ?: it.title ?: it.name).orEmpty().lowercase(),
                it.url ?: it.infoHash ?: "",
            )
        }
        return keyed.sortedWith(compareByDescending<K> { it.rank }.thenBy { it.provider }.thenBy { it.label }.thenBy { it.link }).map { it.s }
    }

    /** Detects standard resolution tier: 2160p, 1080p, 720p, 480p, 360p, or other. */
    fun detectStreamResolution(stream: StremioStream): String =
        // Same rule as the formatter: reported quality, else the link's own name —
        // never the matched release title (multi-quality pack pages say "4K 1080p 720p").
        when (com.cncverse.stremiobridge.format.StreamVariables.resolutionOf(stream)) {
            "4320p", "2160p" -> "2160p"
            "1440p", "1080p" -> "1080p"
            "720p" -> "720p"
            "576p", "480p" -> "480p"
            "360p", "240p", "144p", "180p" -> "360p"
            else -> "other"
        }

    /** Returns true if the stream name/title indicates a CAM / TeleSync / Screener recording. */
    fun isCamStream(stream: StremioStream): Boolean {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".uppercase()
        // No bare "TS"/"SCR": they match MPEG-TS / HLS ".ts" segments and language tags.
        return CAM_RX.containsMatchIn(text)
    }

    private val CAM_RX = Regex("\\b(CAM|CAMRIP|CAM-RIP|HDCAM|HD-CAM|TELESYNC|HDTS|HD-TS|SCREENER|DVDSCR|DVDSCREENER)\\b")

    /** Resolution tiers the configure page lets users pick; anything else is never filtered out. */
    private val SELECTABLE_RESOLUTIONS = setOf("2160p", "1080p", "720p", "480p", "360p")

    /**
     * Orders already-filtered streams by the profile's grouping, sorting and provider order.
     * Default/quality: best resolution first, inside a resolution the provider order (or, with
     * "sort by size", the biggest file first, unknown last). Provider grouping: all of one
     * provider together, providers in the chosen order, best resolution first inside each.
     */
    fun arrangeStreams(streams: List<StremioStream>, profile: StreamPrefs): List<StremioStream> {
        val bySize = profile.sortBy == "size"
        val byProvider = profile.groupBy == "provider"
        if (profile.providerOrder.isEmpty() && !bySize && !byProvider) return streams
        val rank = profile.providerOrder.withIndex().associate { (i, n) -> n.lowercase() to i }
        // Sort keys are computed ONCE per stream: the size lookup is a text search and a sort
        // compares each stream about log2(n) times (6 ms -> well under 1 ms for 250 streams)
        class Keyed(val stream: StremioStream, val providerRank: Int, val provider: String, val quality: Int, val sizeKey: Long)
        val keyed = streams.map { st ->
            // The configure page lists extensions by display name ("4K HDHUB"); a stream's addon
            // label is often the plugin file name ("FourKHDHub"), so match on the display name
            val provider = (st.info?.providerName ?: com.cncverse.stremiobridge.format.StreamVariables.addonOf(st))?.lowercase().orEmpty()
            // Largest first via a negated key; unknown size gets +1 so it sorts after every known one
            val sizeKey = if (!bySize) 0L else -(com.cncverse.stremiobridge.format.StreamVariables.sizeBytesOf(st) ?: -1L)
            Keyed(st, rank[provider] ?: Int.MAX_VALUE, provider, streamQualityRank(st), sizeKey)
        }
        val order = if (byProvider)
            compareBy<Keyed>({ it.providerRank }, { it.provider }, { -it.quality }, { it.sizeKey })
        else
            compareBy<Keyed>({ -it.quality }, { it.sizeKey }, { it.providerRank })
        return keyed.sortedWith(order).map { it.stream } // stable: ties keep their arrival order
    }

    /** Filters a list of streams against a user's profile quality preferences. */
    fun filterStreamsByProfile(streams: List<StremioStream>, profile: StreamPrefs): List<StremioStream> {
        var result = streams

        // 1. Exclude CAM / Screener rips
        if (profile.excludeCam) {
            result = result.filter { !isCamStream(it) }
        }

        // 1b. Streams whose resolution cannot be found at all
        if (profile.hideUnknownQuality) {
            result = result.filter { detectStreamResolution(it) != "other" }
        }

        // 2. Filter allowed resolutions if user chose specific ones
        // Streams without a selectable tier (unlabeled HLS "Auto", live TV, 240p…) always pass:
        // the UI has no checkbox for them, so filtering them would silently empty whole titles.
        val allowed = profile.allowedResolutions.map { it.lowercase().trim() }.toSet()
        if (allowed.isNotEmpty() && !allowed.containsAll(SELECTABLE_RESOLUTIONS)) {
            result = result.filter { stream ->
                val res = detectStreamResolution(stream).lowercase()
                res !in SELECTABLE_RESOLUTIONS || res in allowed
            }
        }

        // 2b. File size window: only streams whose size is KNOWN can be outside it (unknown always pass)
        if (profile.minSizeGb > 0 || profile.maxSizeGb > 0) {
            val gb = 1024.0 * 1024 * 1024
            val lo = (profile.minSizeGb * gb).toLong()
            val hi = if (profile.maxSizeGb > 0) (profile.maxSizeGb * gb).toLong() else Long.MAX_VALUE
            result = result.filter { st ->
                val size = com.cncverse.stremiobridge.format.StreamVariables.sizeBytesOf(st) ?: return@filter true
                size in lo..hi
            }
        }

        // 3. Limit streams per resolution tier if configured
        if (profile.maxStreamsPerResolution > 0) {
            val groups = LinkedHashMap<String, MutableList<StremioStream>>()
            for (st in result) {
                val res = detectStreamResolution(st)
                val list = groups.getOrPut(res) { mutableListOf() }
                if (list.size < profile.maxStreamsPerResolution) {
                    list.add(st)
                }
            }
            result = groups.values.flatten()
        }

        return result
    }
        
    // ── Main stream builder ───────────────────────────────────────────────────

    /**
     * Deadline-based parallel stream loader.
     * All providers start concurrently on [streamSearchScope]; each appends to a shared list as it
     * finishes. After [STREAM_DEADLINE_MS] any still-running jobs are
     * cancelled and whatever has accumulated is returned — ensuring Stremio always
     * gets a response well within its 60-second addon timeout.
     */
    private val STREAM_DEADLINE_MS = 90_000L
    /** Answer with what has loaded after this long; the rest load into the cache until STREAM_DEADLINE_MS. */
    private val streamRequestStart = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val STREAM_SOFT_DEADLINE_MS = 8_000L
    /** Never keep a client waiting longer than this for the first answer. */
    private val STREAM_ANSWER_CAP_MS = 32_000L
    /** Hard limit for the whole search part of a stream request. */
    private val RESPONSE_CAP_MS = 38_000L
    /** Time budget for the anime name / episode mapping lookups before the providers are started. */
    private val ANIME_RESOLVE_BUDGET_MS = 25_000L
    /** The providers are always given at least this long before the answer cap can end the wait. */
    private val STREAM_MIN_WINDOW_MS = 12_000L
    private val PROVIDER_TIMEOUT_MS = 80_000L
    private val streamSearchScope = CoroutineScope(pluginDispatcher + SupervisorJob())

    /**
     * Short-lived TTL cache for [search] and [load] results used in the generic
     * TMDB stream path. Avoids redundant plugin calls for the same title when
     * multiple concurrent Stremio requests arrive or a user replays a stream.
     *
     * Modeled on CloudStream's APIRepository cache (LRU-style, keyed by
     * "pluginInternalName::query" or "pluginInternalName::url").
     */
    private object SearchLoadCache {
        private const val TTL_MS = 10 * 60 * 1_000L   // load() results: 10 minutes
        private const val MAX_ENTRIES = 500
        // Search results (shared by Stremio's catalog search and the IMDb/TMDB stream lookup):
        // hits 30 min, empty results 5 min so a site that hiccupped isn't "no results" for long
        private const val SEARCH_TTL_MS = 30 * 60 * 1_000L
        private const val EMPTY_SEARCH_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_SEARCH_ENTRIES = 5_000

        private data class Entry<T>(val value: T, val timestamp: Long = System.currentTimeMillis())
        /** Wraps a nullable MediaInfo so we can cache a 'null' result (load returned nothing). */
        data class CachedLoad(val info: MediaInfo?)

        private val searchCache = ConcurrentHashMap<String, Entry<List<SearchResult>>>()
        private val loadCache   = ConcurrentHashMap<String, Entry<CachedLoad>>()

        /** "Dune", " dune " and "DUNE" share an entry. */
        private fun searchKey(apiKey: String, query: String) =
            "$apiKey::search::" + query.trim().lowercase().replace(WHITESPACE, " ")
        private val WHITESPACE = Regex("[ \t\r\n]+")
        private fun loadKey(apiKey: String, url: String)    = "$apiKey::load::$url"

        fun getSearch(apiKey: String, query: String): List<SearchResult>? {
            val k = searchKey(apiKey, query)
            val e = searchCache[k] ?: return null
            val ttl = if (e.value.isEmpty()) EMPTY_SEARCH_TTL_MS else SEARCH_TTL_MS
            if (System.currentTimeMillis() - e.timestamp > ttl) { searchCache.remove(k); return null }
            return e.value
        }

        /** Only call with the result of a search that completed (errors are never cached). */
        fun putSearch(apiKey: String, query: String, value: List<SearchResult>) {
            if (searchCache.size >= MAX_SEARCH_ENTRIES) {
                // Drop the oldest tenth
                searchCache.entries.sortedBy { it.value.timestamp }.take(MAX_SEARCH_ENTRIES / 10)
                    .forEach { searchCache.remove(it.key) }
            }
            searchCache[searchKey(apiKey, query)] = Entry(value)
        }

        /** Returns the cached [CachedLoad] wrapper, or `null` if not cached / expired. */
        fun getLoad(apiKey: String, url: String): CachedLoad? {
            val k = loadKey(apiKey, url)
            val e = loadCache[k] ?: return null
            if (System.currentTimeMillis() - e.timestamp > TTL_MS) { loadCache.remove(k); return null }
            return e.value
        }

        fun putLoad(apiKey: String, url: String, value: MediaInfo?) {
            if (loadCache.size >= MAX_ENTRIES) loadCache.keys.take(50).forEach { loadCache.remove(it) }
            loadCache[loadKey(apiKey, url)] = Entry(CachedLoad(value))
        }

        fun invalidate(apiKey: String) {
            searchCache.keys.filter { it.startsWith(apiKey) }.forEach { searchCache.remove(it) }
            loadCache.keys.filter   { it.startsWith(apiKey) }.forEach { loadCache.remove(it) }
        }

        fun clear() { searchCache.clear(); loadCache.clear() }
    }

    private suspend fun buildStreams(type: String, id: String): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val matchingApis = loadedApis.filter { api ->
                val slug = apiKey(api)
                slug == pluginKey || nameSlug(api.name) == pluginKey || api.internalName == pluginKey
            }.filter { !isGloballyDisabled(it) }

            if (matchingApis.isEmpty()) return emptyList()

            val directCacheKey = "stream:direct:$pluginKey:$dataUrl"
            // Live TV / live events: links are short-lived session URLs — never cache them.
            val isLive = type == "tv" || matchingApis.all { api -> api.supportedTypes.all { it == "tv" } }
            return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetchResult(directCacheKey, pluginKey) {
                ServerState.info("Parallel stream load: ${matchingApis.size} API(s) for key '$pluginKey'")

                val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
                val jobs = matchingApis.map { api ->
                    streamSearchScope.launch {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            try {
                                ServerState.debug("[${api.name}] Loading links for $dataUrl")
                                fun loadData(mi: MediaInfo?) = mi?.let {
                                    it.dataUrl.takeIf { d -> d.isNotBlank() && d != dataUrl }
                                        ?: it.episodes?.firstOrNull()?.dataUrl
                                }?.takeIf { it != dataUrl }
                                // A movie's id is its page URL (the id the client asked meta for); its
                                // detail page was usually just opened, so load() is cached — go straight
                                // to the links instead of trying the page URL first.
                                val cachedData = loadData(SearchLoadCache.getLoad(apiKey(api), dataUrl)?.info)
                                var links = api.loadLinksAll(cachedData ?: dataUrl)
                                // Catalog items opened straight from a row (defaultVideoId) carry the
                                // item URL, not the load() data — e.g. Netflix mirrors need the title
                                // that only load() adds. Resolve it once and retry.
                                if (links.isEmpty() && cachedData == null && !api.supportedTypes.all { it == "tv" }) {
                                    val resolved = loadData(runCatching { api.load(dataUrl) }.getOrNull())
                                    if (resolved != null) {
                                        ServerState.info("[${api.name}] Retrying with load() data")
                                        links = api.loadLinksAll(resolved)
                                    }
                                }
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, links.size, null)
                                if (links.isNotEmpty()) {
                                    ServerState.debug("[STREAM_SUCCESS] [${api.name}] Resolved ${links.size} streamable link(s)")
                                } else {
                                    ServerState.debug("[STREAM_EMPTY] [${api.name}] 0 streamable links returned")
                                }
                                accumulated.addAll(withMetadataTitle(links, titleForId(id)))
                            } catch (e: Throwable) {
                                // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
                                ServerState.error("[STREAM_ERROR] [${api.name}] Stream error: ${e.message}")
                            }
                        }
                    }
                }

                withTimeoutOrNull(STREAM_DEADLINE_MS) { jobs.joinAll() }
                val remaining = jobs.count { it.isActive }
                if (remaining > 0) {
                    ServerState.warn("Deadline reached ($STREAM_DEADLINE_MS ms) — returning ${accumulated.size} stream(s), cancelling $remaining slow provider(s)")
                    jobs.forEach { it.cancel() }
                }
                // Partial (deadline hit) or live results are served but not cached
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(
                    sortStreamsByQuality(accumulated),
                    cacheable = remaining == 0 && !isLive,
                )
            }
        }

        // Handle generic Stremio requests with TMDB/IMDB IDs
        // IMDb, TMDB, TVDB, TVmaze and anime (Kitsu, MAL, AniList, AniDB) ids; anything else
        // (other add-ons' own items) can never be looked up, so it is skipped right away
        val ext = ExternalIds.parse(id) ?: return emptyList()

        ServerState.debug("Generic request: id=$id, type=$type, ext=$ext")

        // Key on the exact set of extensions this request may use, so profiles with
        // different opt-ins / disables and admin enable/disable changes never share results.
        val activeSig = loadedApis.asSequence()
            .filter { api -> !isGloballyDisabled(api) && api.supportedTypes.any { it != "tv" } }
            .map { it.internalName }.sorted().plus(UpstreamAddons.active().map { it.url }).joinToString(",").hashCode()
        val aggCacheKey = "stream:generic:$type:$id:$activeSig"

        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetchResult(aggCacheKey, null) {
            try {
                // Anime ids: every title the show is known by (English, romaji, synonyms) for the matching
                val animeInfo = if (ext.scheme in ExternalIds.ANIME_SCHEMES) AnimeResolver.resolve(ext) else null
                val resolved = resolveExternal(type, ext) ?: animeInfo?.let { it.titles.first() to it.year }
                if (resolved == null) {
                    ServerState.warn("Media resolve failed: no title/year found for $id")
                    return@getOrFetchResult com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(emptyList())
                }
                val (title, year) = resolved
                com.cncverse.stremiobridge.state.StreamHistory.noteTitle(id, title)
                ServerState.debug("Media resolve success: title='$title', year=$year")
                // tt / tmdb / tvdb ids of anime: collect every alternative name (AniList, Kitsu, MyAnimeList) too,
                // and map TMDB (season, episode) onto the AniList entry and the episode number inside it.
                // This is only a refinement of the search, so it gets a time budget: the lookups are blocking HTTP
                // calls (AniList / Kitsu / MAL / TMDB) and when one is slow they used to eat the whole answer time,
                // so the providers started late and the player got 0 links. The lookup keeps running detached
                // (it fills the resolver cache for the next try); this request carries on with the plain title.
                val animeResolution = streamSearchScope.async {
                    val names = animeInfo ?: if ((type == "series" || type == "anime") && ext.scheme in setOf("imdb", "tmdb", "tvdb")) {
                        val base = AnimeResolver.fromTitle(title, year)
                        val sn = ext.season
                        if (base != null && sn != null && sn > 1) {
                            // Later season: AniList lists it as its own entry (own names, own episode count)
                            val s2 = AnimeResolver.fromTitle("$title Season $sn", null)
                                ?: AnimeResolver.fromTitle("$title ${mapOf(2 to "2nd", 3 to "3rd").getOrDefault(sn, "${sn}th")} Season", null)
                            if (s2 != null && AnimeMatcher.seasonNumber(AnimeMatcher.normalize(s2.titles.first())) == sn)
                                s2.copy(titles = (s2.titles + base.titles).distinct(), seasonHint = sn)
                            else base.copy(seasonHint = sn, episodeCount = null, aniListId = null, malId = null)
                        } else base
                    } else null
                    val target: AnimeTarget? = if (names != null && ext.scheme in setOf("imdb", "tmdb", "tvdb") &&
                        ext.season != null && ext.episode != null && names.aniListId != null) {
                        tmdbAllSeasonCounts(ext)?.let { counts -> AnimeResolver.mapEpisode(names, ext.season, ext.episode, counts) }
                    } else null
                    names to target
                }
                var animeTimedOut = false
                val (animeNames, animeTarget) = withTimeoutOrNull(ANIME_RESOLVE_BUDGET_MS) { animeResolution.await() }
                    ?: run {
                        animeTimedOut = true
                        ServerState.warn("[Streams] '$title': anime lookup took longer than ${ANIME_RESOLVE_BUDGET_MS / 1000}s - searching with the plain title first, the anime names are added when the lookup finishes")
                        animeInfo to null
                    }
                val animeForSearch = animeTarget?.entry?.copy(titles = (animeTarget.entry.titles + (animeNames?.titles.orEmpty())).distinct()) ?: animeNames

                // Exclude live-TV-only extensions from generic TMDB VOD searches —
                // they don't carry on-demand movie/series content.
                // Plugins still loading (app just started): wait for them instead of answering "0 streams"
                if (!com.cncverse.stremiobridge.plugin.GlobalPluginManager.isPluginsLoaded.value) {
                    ServerState.info("[Streams] plugins still loading - waiting up to 20 s before searching")
                    withTimeoutOrNull(20_000) {
                        com.cncverse.stremiobridge.plugin.GlobalPluginManager.isPluginsLoaded.first { it }
                    }
                }
                val activePlugins = loadedApis.filter { api ->
                    !isGloballyDisabled(api) &&
                    api.supportedTypes.any { it != "tv" }
                }
                ServerState.debug("Searching across ${activePlugins.size} plugin(s) (live-TV-only excluded)...")

                val sem = Semaphore(20)
                val started = System.currentTimeMillis()
                val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
                // Set once the request has been answered early: from then on every provider that
                // finishes re-saves the cached list, so reloads see links arrive one by one.
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val publish = {
                    com.cncverse.stremiobridge.cache.StreamCacheManager.put(aggCacheKey, sortStreamsByQuality(accumulated))
                }
                val jobs = activePlugins.map { api ->
                    streamSearchScope.launch {
                        sem.withPermit {
                            val res = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                try {
                                    val streams = buildGenericStreamsForApi(api, type, id, title, year, animeForSearch, animeTarget)
                                    accumulated.addAll(streams)
                                } catch (e: Throwable) {
                                    // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    ServerState.warn("[${api.name}] Generic stream error: ${e.message}")
                                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message ?: "Stream error")
                                }
                            }
                            if (res == null) {
                                ServerState.debug("[STREAM_TIMEOUT] [${api.name}] Provider timed out after ${PROVIDER_TIMEOUT_MS}ms")
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Timed out after ${PROVIDER_TIMEOUT_MS}ms")
                            }
                        }
                        if (answered.get() && accumulated.isNotEmpty()) publish()
                    }
                } + UpstreamAddons.active().map { up ->
                    streamSearchScope.launch {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            try {
                                val got = UpstreamAddons.fetch(up, type, id)
                                accumulated.addAll(got)
                                ServerState.info("[Upstream] ${up.name}: ${got.size} stream(s)")
                            } catch (e: Throwable) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                ServerState.warn("[Upstream] ${up.name}: ${e.message}")
                            }
                        }
                        if (answered.get() && accumulated.isNotEmpty()) publish()
                    }
                }

                // The anime lookup missed its time budget: the extensions above were searched with the plain title only.
                // When the lookup does finish (it keeps running detached), search every extension again with all the
                // names / the exact season entry and add whatever is new - to this answer if it is still open, else to the cache.
                if (animeTimedOut) {
                    streamSearchScope.launch {
                        val late = withTimeoutOrNull(STREAM_DEADLINE_MS) { animeResolution.await() } ?: return@launch
                        val (lateNames, lateTarget) = late
                        val lateSearch = lateTarget?.entry?.copy(titles = (lateTarget.entry.titles + (lateNames?.titles.orEmpty())).distinct()) ?: lateNames
                            ?: return@launch // not an anime after all: nothing more to find
                        ServerState.info("[Streams] '$title': anime lookup finished late - searching the extensions again with the anime names")
                        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                        accumulated.forEach { st -> (st.url ?: st.infoHash ?: st.externalUrl)?.let { seen.add(it) } }
                        activePlugins.map { api ->
                            launch {
                                sem.withPermit {
                                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                        try {
                                            val more = doBuildGenericStreamsForApi(api, type, id, title, year, lateSearch, lateTarget)
                                            accumulated.addAll(more.filter { st -> (st.url ?: st.infoHash ?: st.externalUrl)?.let { seen.add(it) } ?: true })
                                        } catch (e: Throwable) {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        }
                                    }
                                }
                            }
                        }.joinAll()
                        if (accumulated.isNotEmpty()) publish()
                    }
                }

                // Answer when everything is done, or once STREAM_SOFT_DEADLINE_MS has passed and
                // there is something to show; with nothing yet, wait up to STREAM_DEADLINE_MS.
                val waitAll = streamPrefs.waitForAll
                var firstFoundAt = 0L
                withTimeoutOrNull(STREAM_DEADLINE_MS) {
                    while (jobs.any { it.isActive }) {
                        val now = System.currentTimeMillis()
                        val t = now - started
                        if (accumulated.isNotEmpty() && firstFoundAt == 0L) firstFoundAt = now
                        // Something found: answer once 8 s have passed and the first links are 4 s old (others arriving
                        // right behind it are included); the rest keeps filling the cache for the next reload
                        if (!waitAll && firstFoundAt != 0L && t >= STREAM_SOFT_DEADLINE_MS && now - firstFoundAt >= 4_000L) break
                        // Never later than ANSWER_CAP after the request arrived (searching the title already took time) -
                        // but the providers always get STREAM_MIN_WINDOW_MS first: when the title lookup used up the
                        // cap, this used to break on the very first pass and answer 0 links with every provider
                        // still running. The hard stop leaves time to send before RESPONSE_CAP_MS cuts the request off.
                        val sinceRequest = now - (streamRequestStart["$type:$id"]?.takeIf { now - it < 120_000L } ?: started)
                        if (sinceRequest >= RESPONSE_CAP_MS - 2_000L) break
                        if (sinceRequest >= STREAM_ANSWER_CAP_MS && t >= STREAM_MIN_WINDOW_MS) break
                        delay(200)
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                val remaining = jobs.count { it.isActive }
                if (remaining > 0 && elapsed < STREAM_DEADLINE_MS) {
                    // Early answer: the rest keep going until STREAM_DEADLINE_MS from the start —
                    // each one that finishes adds its links to the cached list — then all are killed.
                    answered.set(true)
                    streamSearchScope.launch {
                        withTimeoutOrNull(STREAM_DEADLINE_MS - elapsed) { jobs.joinAll() }
                        val killed = jobs.count { it.isActive }
                        jobs.forEach { it.cancel() }
                        if (accumulated.isNotEmpty()) publish()
                        ServerState.info("[Streams] '$title': ${accumulated.size} streams after ${(System.currentTimeMillis() - started) / 1000}s (background done, $killed slow provider(s) stopped)")
                    }
                } else if (remaining > 0) {
                    jobs.forEach { it.cancel() }
                }
                ServerState.info(
                    "[Streams] '$title': answered with ${accumulated.size} streams in ${elapsed / 1000.0}s, " +
                        "${activePlugins.size - remaining}/${activePlugins.size} providers done" +
                        (if (remaining > 0 && elapsed < STREAM_DEADLINE_MS) ", $remaining still loading into cache" else "")
                )
                // Cached even when partial: reloads get it at once while the cache keeps filling
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(
                    sortStreamsByQuality(accumulated),
                    cacheable = accumulated.isNotEmpty(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerState.warn("Generic stream resolve error for $id: ${e.stackTraceToString()}")
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(emptyList())
            }
        }
    }

    // ── Generic per-provider stream fetch (shared by buildStreams + buildStreamsForApi) ─

    /**
     * Resolves streams from a single [api] for a generic title/year.
     * Reuses already-resolved title & year to avoid redundant TMDB requests.
     */
    internal suspend fun buildGenericStreamsForApi(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?,
        anime: AnimeInfo? = null,
        target: AnimeTarget? = null,
    ): List<StremioStream> {
        if (id.startsWith("probe_")) {
            return doBuildGenericStreamsForApi(api, type, id, title, year, anime, target)
        }
        val providerCacheKey = "stream:provider:${api.internalName}:$type:$id"
        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetch(providerCacheKey, api.internalName) {
            doBuildGenericStreamsForApi(api, type, id, title, year, anime, target)
        }
    }

    /** Lower-cases and strips punctuation/extra spaces so "Spider-Man: No Way Home" == "spider man no way home". */
    private fun normalizeTitle(s: String): String = s.lowercase()
        .replace("&", " and ")
        .replace(TITLE_PUNCT, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private val IMDB_ID = Regex("tt[0-9]+")
    private val TITLE_PUNCT = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")

    /**
     * Picks the search result for [title]/[year], in order of confidence:
     *  1. exact title + same year
     *  2. exact title, result has no year
     *  3. partial title (result contains the whole title as words) + same year
     *  4. partial title, result has no year
     * When TMDB gave no year, the year condition is dropped. A result whose
     * year is known but differs is never accepted, and a result whose title
     * doesn't match is never picked (no "first result" fallback) — so a
     * provider without the title returns nothing instead of a wrong film.
     */
    private fun pickBestMatch(results: List<SearchResult>, title: String, year: Int?): SearchResult? {
        val wanted = normalizeTitle(title)
        if (wanted.isEmpty()) return null
        val candidates = results.map { it to normalizeTitle(it.name) }
        val exact = candidates.filter { (_, n) -> n == wanted }.map { it.first }
        // Whole-word containment, result ⊇ title only: "dune part two 2024 hindi"
        // matches "Dune Part Two", but "Up" never matches "Upgrade" and a
        // shorter "Dune" never stands in for "Dune Part Two".
        val partial = candidates.filter { (_, n) ->
            n != wanted && " $n ".contains(" $wanted ")
        }.map { it.first }

        if (year == null) return exact.firstOrNull() ?: partial.firstOrNull()
        return exact.firstOrNull { it.year == year }
            ?: exact.firstOrNull { it.year == null }
            ?: partial.firstOrNull { it.year == year }
            ?: partial.firstOrNull { it.year == null }
    }

    private suspend fun doBuildGenericStreamsForApi(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?,
        anime: AnimeInfo? = null,
        target: AnimeTarget? = null,
    ): List<StremioStream> {
        ServerState.debug("[${api.name}] Searching for '$title'")
        val cacheKey = api.internalName
        val searchResults = try {
            SearchLoadCache.getSearch(cacheKey, title)
                ?: api.search(title).also { SearchLoadCache.putSearch(cacheKey, title, it) }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[STREAM_ERROR] [${api.name}] Search failed: ${e.message}")
            if (anime == null) {
                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Search error: ${e.message}")
                return emptyList()
            }
            // Anime: one spelling can break a site's search (punctuation, long names) - the other names are still tried
            emptyList<SearchResult>()
        }
        ServerState.debug("[${api.name}] Found ${searchResults.size} results")

        val requestedSeason = if (type == "series" && id.contains(":")) ExternalIds.parse(id)?.season else null

        // tt / tmdb / tvdb ids carry the real season; kitsu / MAL / AniList ids name one season entry already
        val parsedId = ExternalIds.parse(id)
        val animeSeason = if (target != null) target.index + 1
            else if (anime != null && parsedId != null && parsedId.scheme !in ExternalIds.ANIME_SCHEMES) parsedId.season else null
        val pool = ArrayList<SearchResult>(searchResults)

        var bestMatch = (if (anime != null) {
            // Anime: fuzzy match against every known title (romaji, English, synonyms) and the season;
            // results of all spellings are pooled so the best one wins, not the first one over the bar
            var found = AnimeMatcher.pickBest(pool, anime, animeSeason)
            var foundScore = found?.let { AnimeMatcher.score(it, anime, animeSeason) } ?: 0.0
            if (foundScore < 0.92) {
                for (term in AnimeMatcher.searchTerms(anime, animeSeason).filter { !it.equals(title, true) }) {
                    val more = try {
                        SearchLoadCache.getSearch(cacheKey, term)
                            ?: api.search(term).also { SearchLoadCache.putSearch(cacheKey, term, it) }
                    } catch (e: Throwable) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        emptyList()
                    }
                    more.filter { m -> pool.none { it.url == m.url } }.forEach { pool += it }
                    found = AnimeMatcher.pickBest(pool, anime, animeSeason)
                    foundScore = found?.let { AnimeMatcher.score(it, anime, animeSeason) } ?: 0.0
                    if (foundScore >= 0.92) break
                }
            }
            found
        } else if (requestedSeason != null) {
            // Sites often list each season separately ("The Boys Season 2", sometimes with that
            // season's year), which strict title+year matching rejects. Accept those only when the
            // result is exactly "<title> season N" / "<title> sN" at the start — so "Season 1"
            // never matches "Season 10" and spin-offs ("The Boys Presents …") never match.
            val wanted = normalizeTitle(title)
            val seasonRx = Regex("^" + Regex.escape(wanted) + " (?:season 0*$requestedSeason|s0*$requestedSeason)(?: |$)")
            searchResults.find { r -> seasonRx.containsMatchIn(normalizeTitle(r.name)) }
                ?: pickBestMatch(searchResults, title, year)
                ?: AnimeMatcher.pickFuzzy(searchResults, title, year, requestedSeason)
        } else {
            pickBestMatch(searchResults, title, year)
        } ?: AnimeMatcher.pickFuzzy(searchResults, title, year, requestedSeason)
        ) ?: (if (anime == null) {
            // Nothing close in the first answer: type the title another way (no subtitle, no "The") and try fuzzy again
            var alt: SearchResult? = null
            for (q in AnimeMatcher.altQueries(title)) {
                val more = try {
                    SearchLoadCache.getSearch(cacheKey, q)
                        ?: api.search(q).also { SearchLoadCache.putSearch(cacheKey, q, it) }
                } catch (e: Throwable) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    emptyList()
                }
                alt = pickBestMatch(more, title, year) ?: AnimeMatcher.pickFuzzy(more, title, year, requestedSeason)
                if (alt != null) break
            }
            alt
        } else null) ?: run {
            ServerState.debug("[${api.name}] No result matches '$title'" + (year?.let { " ($it)" } ?: ""))
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No result matches '$title'")
            return emptyList()
        }

        ServerState.debug("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
        val cachedLoad = SearchLoadCache.getLoad(cacheKey, bestMatch.url)
        var mediaInfo = try {
            if (cachedLoad != null) {
                cachedLoad.info
            } else {
                val fresh = api.load(bestMatch.url)
                SearchLoadCache.putLoad(cacheKey, bestMatch.url, fresh)
                fresh
            }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[STREAM_ERROR] [${api.name}] MediaInfo load failed: ${e.message}")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Load error: ${e.message}")
            return emptyList()
        } ?: run {
            ServerState.debug("[STREAM_EMPTY] [${api.name}] MediaInfo load returned null for ${bestMatch.url}")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "MediaInfo null for match")
            return emptyList()
        }

        var mergedList = false
        // Season check by episode count: the entry must have about as many episodes as the season we want
        if (anime != null && parsedId != null) {
            val expected = ArrayList<Int>()
            // Running-number lists: a site that merges seasons lists as many episodes as the franchise has so far
            val expectedMerged = ArrayList<Int>()
            if (target != null) {
                // the entry is exact (own AniList id): its own episode count, or the running total when a site merges seasons
                anime.episodeCount?.let { expected += it }
                var cum = 0
                for (i in 0..target.index) { cum += target.counts.getOrNull(i) ?: break; if (i >= 1) expectedMerged += cum }
            } else if (parsedId.scheme in ExternalIds.ANIME_SCHEMES) {
                anime.episodeCount?.let { expected += it }
            } else {
                animeSeason?.let { sn -> tmdbSeasonEpisodeCount(parsedId, sn)?.let { expected += it } }
                if (animeSeason == null || anime.seasonHint == animeSeason) anime.episodeCount?.let { expected += it }
            }
            val running = anime.year?.let { it >= java.time.Year.now().value - 1 } ?: true
            // The entry's own AniList / MyAnimeList id, when the extension reports it: the exact season entry
            fun idVerdict(mi: MediaInfo): Boolean? {
                if (anime.aniListId != null && mi.aniListId != null) return anime.aniListId == mi.aniListId
                if (anime.malId != null && mi.malId != null) return anime.malId == mi.malId
                return null
            }
            fun fits(mi: MediaInfo): Boolean {
                idVerdict(mi)?.let { return it }
                val eps = mi.episodes.orEmpty()
                if (eps.isEmpty() || eps.any { (it.season ?: 1) > 1 }) return true
                val n = eps.mapNotNull { it.episode }.distinct().size.takeIf { it > 0 } ?: eps.size
                if (AnimeMatcher.countFits(n, expected, running, parsedId.episode)) return true
                if (expectedMerged.any { m -> n == m }) { mergedList = true; return true }
                return false
            }
            if ((expected.isNotEmpty() || idVerdict(mediaInfo) != null) && !fits(mediaInfo)) {
                ServerState.debug("[${api.name}] '${bestMatch.name}' has ${mediaInfo.episodes?.size} episodes, wanted about $expected - trying other entries")
                var replaced = false
                for (c in AnimeMatcher.rank(pool, anime, animeSeason).filter { it.url != bestMatch.url }) {
                    val mi = try {
                        SearchLoadCache.getLoad(cacheKey, c.url)?.info
                            ?: api.load(c.url)?.also { SearchLoadCache.putLoad(cacheKey, c.url, it) }
                    } catch (e: Throwable) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
                    if (mi != null && fits(mi)) { bestMatch = c; mediaInfo = mi; replaced = true; break }
                }
                if (!replaced) {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] no entry has the episode count of the wanted season ($expected)")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No entry with the wanted season's episode count")
                    return emptyList()
                }
            }
        }

        var dataUrlToLoad = mediaInfo.dataUrl
        if (anime != null) {
            val parsed = ExternalIds.parse(id)
            // TMDB numbering was translated to the AniList entry: its own episode number, or the running number on a site that merges seasons
            val epNo = target?.let { if (mergedList) it.absolute else it.local } ?: parsed?.episode
            val epList = mediaInfo.episodes
            if (!epList.isNullOrEmpty()) {
                // A later-season entry (kitsu "Season 2") numbers its own episodes from 1; a franchise page lists seasons
                val wantSeason = if (target != null) (if (mergedList) 1 else target.index + 1)
                    else anime.seasonHint?.takeIf { it > 1 } ?: parsed?.season
                var ep = if (epNo != null) AnimeEpisodes.pick(epList, wantSeason, epNo) else epList.first()
                if (ep == null && target != null) {
                    // Last resorts, so a correct entry still gives results: TMDB's own numbers, then the running number
                    ep = parsed?.season?.let { sn -> parsed.episode?.let { en -> AnimeEpisodes.pick(epList, sn, en) } }
                        ?: AnimeEpisodes.pick(epList, 1, target.absolute)
                }
                if (ep == null) {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] Episode $epNo not found")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Episode $epNo not found")
                    return emptyList()
                }
                dataUrlToLoad = ep.dataUrl
                ServerState.debug("[${api.name}] Anime episode $epNo -> '${ep.name}'")
            }
        } else if (type == "series" && id.contains(":")) {
            val parsed  = ExternalIds.parse(id)
            val season  = parsed?.season
            val episode = parsed?.episode
            if (season != null && episode != null) {
                // Anime extensions often leave the season empty: that is season 1
                val ep = mediaInfo.episodes?.find { (it.season ?: 1) == season && it.episode == episode }
                if (ep != null) {
                    dataUrlToLoad = ep.dataUrl
                    ServerState.debug("[${api.name}] Found episode S${season}E${episode}")
                } else {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] Episode S${season}E${episode} not found")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Episode S${season}E${episode} not found")
                    return emptyList()
                }
            }
        }
        ServerState.debug("[${api.name}] Loading links for $dataUrlToLoad")
        try {
            val links = api.loadLinksAll(dataUrlToLoad)
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, links.size, null)
            if (links.isNotEmpty()) {
                ServerState.debug("[STREAM_SUCCESS] [${api.name}] Resolved ${links.size} streamable link(s) for '$title'")
            } else {
                ServerState.debug("[STREAM_EMPTY] [${api.name}] 0 streamable links returned for '$title'")
            }
            return links.map { stream ->
                val newName = bestMatch.name + (if (!stream.name.isNullOrBlank()) "\n${stream.name}" else "")
                val info = (stream.info ?: com.cncverse.stremiobridge.model.StreamInfo(addonName = api.name))
                    .copy(metadataTitle = title, metadataYear = year, matchedName = bestMatch.name)
                stream.copy(name = newName, info = info)
            }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
            ServerState.error("[STREAM_ERROR] [${api.name}] Stream error for '$title': ${e.message}")
            return emptyList()
        }
    }

    /** Probes a single API wrapper with a search & loadLinks test, returning stream count. */
    suspend fun probeApi(api: MainApiWrapper, query: String = "Avatar"): Int {
        return try {
            val streams = buildGenericStreamsForApi(api, "movie", "probe_test", query, null)
            streams.size
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
            0
        }
    }

    /** Probes all active APIs in parallel (up to 5 concurrent) and updates StreamTracker. */
    suspend fun probeAllApis(query: String = "Avatar"): Map<String, Int> {
        val active = loadedApis.filter { api -> api.supportedTypes.any { it != "tv" } }
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val sem = Semaphore(5)
        coroutineScope {
            active.map { api ->
                launch {
                    sem.withPermit {
                        withTimeoutOrNull(20_000) {
                            val count = probeApi(api, query)
                            results[api.internalName] = count
                        } ?: run {
                            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Probe timed out (20s)")
                            results[api.internalName] = 0
                        }
                    }
                }
            }.joinAll()
        }
        return results
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
    /** Home-page section names declared by the API (no network). */
    val staticSectionNames: List<String> get() = emptyList()
    /** MainAPI.lang as declared by the extension. */
    val apiLang: String? get() = null
    /** Language from the repo manifest. */
    val pluginLanguage: String? get() = null
    /** False for search-only providers (no home page by design). */
    val hasHomePage: Boolean get() = true
    suspend fun getMainPageSections(): List<String>
    fun clearCache() {}

    suspend fun search(query: String): List<SearchResult>
    suspend fun getMainPage(page: Int, type: String, sectionName: String? = null): List<SearchResult>
    suspend fun load(url: String): MediaInfo?
    suspend fun loadLinks(dataUrl: String): List<StremioStream>
}

/**
 * Episodes an extension lists once per dub status (anime: "Subbed" / "Dubbed") are merged into
 * one episode whose data carries every variant; loading it returns all of them, labelled.
 */
object VariantData {
    private const val PREFIX = "cncvariants:"

    fun encode(parts: List<Pair<String, String>>): String =
        PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<List<List<String>>>(), parts.map { listOf(it.first, it.second) }
            ).toByteArray()
        )

    fun decode(data: String): List<Pair<String, String>>? {
        if (!data.startsWith(PREFIX)) return null
        return runCatching {
            val json = String(java.util.Base64.getUrlDecoder().decode(data.removePrefix(PREFIX)))
            kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.serializer<List<List<String>>>(), json)
                .filter { it.size == 2 }.map { it[0] to it[1] }
        }.getOrNull()
    }

    /** "Subbed" -> "Sub", "Dubbed" -> "Dub"; null for "None". */
    fun label(variant: String?): String? = when (variant?.trim()?.lowercase()) {
        null, "", "none" -> null
        "subbed", "sub" -> "Sub"
        "dubbed", "dub" -> "Dub"
        else -> variant.trim()
    }
}

/**
 * loadLinks for every variant a merged episode carries, each stream labelled "[Sub]" / "[Dub]",
 * and every stream tagged with this extension's display name (provider ordering matches on it).
 */
suspend fun MainApiWrapper.loadLinksAll(dataUrl: String): List<StremioStream> {
    val api = this
    fun tag(s: StremioStream, label: String?): StremioStream {
        val info = (s.info ?: com.cncverse.stremiobridge.model.StreamInfo(addonName = api.name)).let { i ->
            i.copy(providerName = api.name, linkName = if (label != null) "[$label] " + (i.linkName ?: s.title.orEmpty()) else i.linkName)
        }
        return if (label == null) s.copy(info = info)
        else s.copy(title = "[$label] " + s.title.orEmpty(), info = info)
    }
    val parts = VariantData.decode(dataUrl) ?: return loadLinks(dataUrl).map { tag(it, null) }
    return kotlinx.coroutines.coroutineScope {
        parts.map { (variant, data) ->
            async {
                try {
                    loadLinks(data).map { tag(it, VariantData.label(variant)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
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
    val posterUrl: String?,
    val description: String? = null,
    /** Air date, epoch milliseconds. */
    val releasedMs: Long? = null,
    /** CloudStream DubStatus key the episode came from ("Subbed", "Dubbed"), when the extension splits them. */
    val variant: String? = null,
)

data class CastPerson(val name: String, val character: String? = null, val photo: String? = null)

data class MediaInfo(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val description: String?,
    val year: Int?,
    val dataUrl: String,
    val episodes: List<MediaInfoEpisode>? = null,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
    /** CloudStream "tags": genres, or labels like "Live" / "Rank: 5" for live sources. */
    val genres: List<String>? = null,
    /** 0..10 */
    val rating: Double? = null,
    val cast: List<String>? = null,
    /** Same people as [cast], with photo and role where the extension gives them. */
    val castPeople: List<CastPerson>? = null,
    val runtimeMinutes: Int? = null,
    val contentRating: String? = null,
    /** AniList / MyAnimeList ids the extension reports for this entry (anime), null when it does not. */
    val aniListId: Int? = null,
    val malId: Int? = null,
)

fun SearchResult.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val encodedId = StremioIds.encode(pluginInternalName, url)
    val resolvedType = cs3TvTypeToStremio(type)
    return StremioMeta(
        id          = encodedId,
        type        = resolvedType,
        name        = name,
        poster      = PublicUrls.safeForBrowser(posterUrl),
        background  = if (isHorizontal) PublicUrls.safeForBrowser(posterUrl) else null,
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
    poster      = PublicUrls.safeForBrowser(posterUrl),
    background  = PublicUrls.safeForBrowser(backgroundUrl),
    logo        = PublicUrls.safeForBrowser(logoUrl),
    description = description,
    year        = year,
    releaseInfo = year?.toString(),
    genres      = genres,
    cast        = cast,
    appExtras   = castPeople?.takeIf { it.isNotEmpty() }?.let { people ->
        AppExtras(cast = people.map { AppCast(it.name, it.character, PublicUrls.safeForBrowser(it.photo)) })
    },
    imdbRating  = rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
    runtime     = runtimeMinutes?.let { "$it min" },
    videos      = episodes?.mapIndexed { index, ep ->
        StremioVideo(
            id       = StremioIds.encode(pluginInternalName, ep.dataUrl),
            title    = ep.name ?: "Episode ${ep.episode ?: (index + 1)}",
            released = ep.releasedMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
            season   = ep.season ?: 1,
            episode  = ep.episode ?: (index + 1),
            thumbnail= PublicUrls.safeForBrowser(ep.posterUrl ?: posterUrl),
            overview = ep.description,
        )
    }
)

expect fun Application.setupMpdProxyRoutes()

