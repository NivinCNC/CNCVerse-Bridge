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
     * Reused across all profiles so manifests never block on re-fetching sections.
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
    val profiles: MutableMap<String, ProfileRecord> = ConcurrentHashMap()
    private var profilesFile: File? = null

    /** Persisted, per-profile preferences and bookkeeping. */
    @Serializable
    data class ProfileRecord(
        /** Globally-enabled extensions this profile has turned off. */
        val disabled: Set<String> = emptySet(),
        /** Globally-disabled extensions this profile has explicitly opted into. */
        val enabledOverrides: Set<String> = emptySet(),
        /** When true, catalog defs are omitted from manifest (streams & search only). */
        val disableCatalogs: Boolean = false,
        val disabledCatalogs: Set<String> = emptySet(),
        /** User-chosen allowed resolutions (e.g. ["2160p", "1080p", "720p"]). Empty = all allowed. */
        val allowedResolutions: Set<String> = emptySet(),
        /** When true, exclude CAM / TeleSync / Screener recordings. */
        val excludeCam: Boolean = false,
        /** Maximum streams to return per quality tier (0 = unlimited). */
        val maxStreamsPerResolution: Int = 0,
        /** Hide subtitles: streams go out without subtitle tracks and /subtitles answers empty. */
        val hideSubtitles: Boolean = false,
        /** How streams are grouped: default | provider | quality. */
        val groupBy: String = "default",
        /** Order inside a group: default | size (largest first, unknown size last). */
        val sortBy: String = "default",
        /** Streams with a KNOWN file size outside [minSizeGb, maxSizeGb] are dropped; 0 = no bound. */
        val minSizeGb: Double = 0.0,
        val maxSizeGb: Double = 0.0,
        /** Extension names in priority order: earlier ones come first inside each group. */
        val providerOrder: List<String> = emptyList(),
        /** The profile's own stream formatter choice (null = follow the server's formatter). */
        val formatter: com.cncverse.stremiobridge.format.ProfileFormatter? = null,
        /** User-chosen profile name, shown in the Stremio addon title ("CNCVerse Bridge · Kids"). */
        val displayName: String = "",
        val createdAt: Long = 0,
        val lastSeen: Long = 0,
    )

    /** Request body for POST /api/profile/{id}/set. */
    @Serializable
    data class ProfileSetRequest(
        val disabledExtensions: List<String> = emptyList(),
        /** Globally-disabled extensions the profile wants in its manifest. */
        val enabledExtensions: List<String> = emptyList(),
        // Optional: fields a caller omits keep the profile's saved value (the
        // configure page's bulk extension saves don't send catalog/quality prefs).
        val disableCatalogs: Boolean? = null,
        val disabledCatalogs: List<String>? = null,
        val allowedResolutions: List<String>? = null,
        val excludeCam: Boolean? = null,
        val maxStreamsPerResolution: Int? = null,
    )

    /** Request body for POST /api/profile/{id}/playback. */
    @Serializable
    data class ProfilePlaybackRequest(
        val hideSubtitles: Boolean = false,
        val groupBy: String = "default",
        val sortBy: String = "default",
        val minSizeGb: Double = 0.0,
        val maxSizeGb: Double = 0.0,
        val providerOrder: List<String> = emptyList(),
    )

    @Serializable
    data class ProfileQualityPreferencesRequest(
        val allowedResolutions: List<String> = emptyList(),
        val excludeCam: Boolean = false,
        val maxStreamsPerResolution: Int = 0,
    )

    @Serializable
    data class ProfileCatalogToggleRequest(
        val disableCatalogs: Boolean = false
    )

    @Serializable
    data class ProfilePluginToggleRequest(
        val internalName: String = ""
    )

    val credits: java.util.concurrent.CopyOnWriteArrayList<com.cncverse.stremiobridge.state.AuthorCredit> = java.util.concurrent.CopyOnWriteArrayList()
    private var creditsFile: File? = null
    private var themeConfigFile: File? = null

    private fun loadCredits() {
        val file = creditsFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                val list = serverJson.decodeFromString<List<com.cncverse.stremiobridge.state.AuthorCredit>>(json)
                credits.clear()
                credits.addAll(list)
            } catch (e: Exception) {
                ServerState.warn("Failed to load credits.json: ${e.message}")
            }
        }
    }

    internal fun saveCredits(list: List<com.cncverse.stremiobridge.state.AuthorCredit>) {
        credits.clear()
        credits.addAll(list)
        val file = creditsFile ?: return
        try {
            file.writeText(serverJson.encodeToString(list))
        } catch (e: Exception) {
            ServerState.warn("Failed to save credits.json: ${e.message}")
        }
    }

    // ── Footer Credits (Dynamic, configurable by admin, persisted to disk) ─────
    private val footerCredits = mutableListOf<com.cncverse.stremiobridge.state.FooterCredit>()
    private var footerCreditsFile: File? = null

    private fun loadFooterCredits() {
        val file = footerCreditsFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                val list = serverJson.decodeFromString<List<com.cncverse.stremiobridge.state.FooterCredit>>(json)
                footerCredits.clear()
                footerCredits.addAll(list)
            } catch (e: Exception) {
                ServerState.warn("Failed to load footer_credits.json: ${e.message}")
            }
        }
    }

    internal fun saveFooterCredits(list: List<com.cncverse.stremiobridge.state.FooterCredit>) {
        footerCredits.clear()
        footerCredits.addAll(list)
        val file = footerCreditsFile ?: return
        try {
            file.writeText(serverJson.encodeToString(list))
        } catch (e: Exception) {
            ServerState.warn("Failed to save footer_credits.json: ${e.message}")
        }
    }

    fun getFooterCredits(): List<com.cncverse.stremiobridge.state.FooterCredit> {
        return footerCredits.toList()
    }

    fun buildFooterHtml(): String {
        val list = getFooterCredits()
        if (list.isEmpty()) return ""
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return list.joinToString(""" <span class="footer-sep">&middot;</span> """) { item ->
            val linkHtml = if (item.url.isNotBlank()) {
                """<a href="${esc(item.url)}" target="_blank" rel="noopener" class="footer-author-link" style="font-weight:700;">${esc(item.name)}</a>"""
            } else {
                """<span class="footer-author-name" style="font-weight:700;">${esc(item.name)}</span>"""
            }
            if (item.label.isNotBlank()) {
                """<span>${esc(item.label)} $linkHtml</span>"""
            } else {
                """<span>$linkHtml</span>"""
            }
        }
    }

    /**
     * Returns the live credits list:
     *  1. Team & Contributor entries explicitly created by admin (isCurated=true, no repoUrl).
     *  2. Installed repositories (auto-populated with live data + admin overrides).
     *  3. Any other custom curated repo maintainer entries.
     */
    fun getCreditsList(): List<com.cncverse.stremiobridge.state.AuthorCredit> {
        val knownRepos = com.cncverse.stremiobridge.state.RepoState.repos.value
        val savedList = credits.toList()

        fun normKey(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val m = Regex("(?:raw\\.githubusercontent|github)\\.com/([^/]+)/([^/]+)").find(url)
            if (m != null) return "${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase().removeSuffix(".git")}"
            return url.trim().lowercase().removeSuffix("/")
        }

        val savedByRepoUrl = mutableMapOf<String, com.cncverse.stremiobridge.state.AuthorCredit>()
        for (c in savedList) {
            if (!c.repoUrl.isNullOrBlank()) {
                savedByRepoUrl[c.repoUrl] = c
                val k = normKey(c.repoUrl)
                if (k.isNotBlank()) savedByRepoUrl.putIfAbsent(k, c)
            }
        }

        val result = mutableListOf<com.cncverse.stremiobridge.state.AuthorCredit>()

        // 1. Team / contributor entries explicitly configured by admin
        val teamEntries = savedList.filter { it.isCurated && (it.repoUrl.isNullOrBlank()) }
        result.addAll(teamEntries)

        // 2. Installed repos (auto-populated)
        val installedUrls = knownRepos.map { it.url }.toSet()
        val installedKeys = knownRepos.map { normKey(it.url) }.filter { it.isNotBlank() }.toSet()

        for (repo in knownRepos) {
            if (repo.url.isBlank()) continue
            val ghMatch = Regex("(?:raw\\.githubusercontent|github)\\.com/([^/]+)/([^/]+)").find(repo.url)
            val ghUser = ghMatch?.groupValues?.get(1) ?: ""
            val ghRepo = ghMatch?.groupValues?.get(2)?.removeSuffix(".git") ?: ""
            val liveAuthorName = repo.name.ifBlank { ghUser.ifBlank { repo.url.substringAfterLast("/").ifBlank { "Unknown Repo" } } }
            val liveAvatar = PublicUrls.safeForBrowser(repo.iconUrl) ?: (if (ghUser.isNotBlank()) "https://github.com/$ghUser.png" else null)
            val liveGhLink = if (ghUser.isNotBlank() && ghRepo.isNotBlank()) "https://github.com/$ghUser/$ghRepo" else null
            val id = "repo_" + kotlin.math.abs(repo.url.hashCode())

            val saved = savedByRepoUrl[repo.url] ?: savedByRepoUrl[normKey(repo.url)]
            val rawGh = saved?.githubUrl?.takeUnless { it.isBlank() } ?: liveGhLink
            val cleanGh = if (rawGh != null && rawGh.contains("raw.githubusercontent.com")) {
                val m = Regex("raw\\.githubusercontent\\.com/([^/]+)/([^/]+)").find(rawGh)
                if (m != null) "https://github.com/${m.groupValues[1]}/${m.groupValues[2].removeSuffix(".git")}" else rawGh
            } else rawGh

            val pCount = com.cncverse.stremiobridge.state.RepoState.availablePlugins.value
                .count { it.repoEntry.url == repo.url }

            result.add(com.cncverse.stremiobridge.state.AuthorCredit(
                id = saved?.id ?: id,
                repoUrl = repo.url,
                authorName = saved?.authorName?.takeUnless { it.isBlank() } ?: liveAuthorName,
                avatarUrl = saved?.avatarUrl?.takeUnless { it.isBlank() } ?: liveAvatar,
                githubUrl = cleanGh,
                roleBadge = saved?.roleBadge?.takeUnless { it.isBlank() } ?: "Installed Repository",
                description = saved?.description?.takeUnless { it.isBlank() } ?: (repo.description ?: "Community CloudStream repository."),
                discordUrl = saved?.discordUrl?.trim()?.takeUnless { it.isBlank() },
                telegramUrl = saved?.telegramUrl?.trim()?.takeUnless { it.isBlank() },
                donationUrl = saved?.donationUrl?.trim()?.takeUnless { it.isBlank() },
                websiteUrl = saved?.websiteUrl?.trim()?.takeUnless { it.isBlank() },
                isCurated = false,
                pluginCount = if (pCount > 0) pCount else null
            ))
        }

        // 3. Extra curated repo entries
        val curatedExtras = savedList.filter { c ->
            c.isCurated && !c.repoUrl.isNullOrBlank() && !installedUrls.contains(c.repoUrl) && !installedKeys.contains(normKey(c.repoUrl))
        }
        result.addAll(curatedExtras)

        return result
    }

    /** Present ⇔ the admin turned catalogs off globally (survives restarts). */
    private var catalogsOffMarker: File? = null

    internal fun saveGlobalCatalogSetting() {
        val f = catalogsOffMarker ?: return
        runCatching { if (ServerState.disableCatalogsGlobally) f.writeText("1") else f.delete() }
            .onFailure { ServerState.warn("Failed to save global catalog setting: ${it.message}") }
    }

    private fun loadThemeConfig() {
        val file = themeConfigFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                val cfg = serverJson.decodeFromString<com.cncverse.stremiobridge.state.ThemeConfig>(json)
                ServerState.globalAccentHex = safeColor(cfg.accentHex, ServerState.globalAccentHex)
                ServerState.globalAccentGlow = safeColor(cfg.accentGlow, ServerState.globalAccentGlow)
                ServerState.globalAccentHover = safeColor(cfg.accentHover, ServerState.globalAccentHover)
                ServerState.globalBaseTheme = safeBaseTheme(cfg.baseTheme)
            } catch (e: Exception) {
                ServerState.warn("Failed to load theme_config.json: ${e.message}")
            }
        }
    }

    // Theme values are interpolated into the admin page's JS and the public
    // index/configure pages' CSS — only plain colours and known theme names pass.
    private val HEX_COLOR_RX = Regex("^#[0-9a-fA-F]{3,8}$")
    private val RGBA_COLOR_RX = Regex("^rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*(,\\s*(0|1|0?\\.\\d+)\\s*)?\\)$")
    private val BASE_THEMES = setOf("slate", "charcoal", "navy", "forest", "oled", "light")

    private fun safeColor(value: String?, fallback: String): String {
        val v = value?.trim().orEmpty()
        return if (HEX_COLOR_RX.matches(v) || RGBA_COLOR_RX.matches(v)) v else fallback
    }

    private fun safeBaseTheme(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in BASE_THEMES } ?: "slate"

    internal fun saveThemeConfig(rawHex: String, rawGlow: String, rawHover: String, rawBaseTheme: String = "slate") {
        val hex = safeColor(rawHex, ServerState.globalAccentHex)
        val glow = safeColor(rawGlow, ServerState.globalAccentGlow)
        val hover = safeColor(rawHover, ServerState.globalAccentHover)
        val baseTheme = safeBaseTheme(rawBaseTheme)
        ServerState.globalAccentHex = hex
        ServerState.globalAccentGlow = glow
        ServerState.globalAccentHover = hover
        ServerState.globalBaseTheme = baseTheme
        val file = themeConfigFile ?: return
        try {
            val cfg = com.cncverse.stremiobridge.state.ThemeConfig(hex, glow, hover, baseTheme)
            file.writeText(serverJson.encodeToString(cfg))
        } catch (e: Exception) {
            ServerState.warn("Failed to save theme_config.json: ${e.message}")
        }
    }

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

    private val profileSavePending = AtomicBoolean(false)

    /**
     * Debounced save: profiles.json can be MBs, and manifest fetches from many
     * profiles would otherwise re-serialize the whole map on every request.
     * Coalesces all changes within a 5 s window into one write.
     */
    private fun saveProfiles() {
        if (profilesFile == null) return
        if (!profileSavePending.compareAndSet(false, true)) return
        manifestRefreshScope.launch(Dispatchers.IO) {
            delay(5_000)
            profileSavePending.set(false)
            writeProfilesNow()
        }
    }

    private val profilesWriteLock = Any()

    /** (fetchedAt, body) of the last successful /api/community-stats upstream response. */
    @Volatile private var communityStatsCache: Pair<Long, String>? = null

    /**
     * Writes profiles.json atomically. Serialized so concurrent writers can't
     * interleave in the temp file; the rename replaces the file in one step so
     * a crash mid-write never leaves a truncated profiles.json behind.
     * Request handlers must use the debounced [saveProfiles], not this.
     */
    private fun writeProfilesNow() = synchronized(profilesWriteLock) {
        val file = profilesFile ?: return@synchronized
        try {
            val map: Map<String, ProfileRecord> = HashMap(profiles)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(serverJson.encodeToString(map))
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
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

    // ── Per-profile extension limit ───────────────────────────────────────────

    /**
     * A profile may have at most this many extensions switched on. Profiles that opted into
     * every installed extension (~530) made each stream request search hundreds of providers,
     * which is what kept the server's CPU pinned.
     */
    val MAX_PROFILE_EXTENSIONS = 50

    /**
     * One card per loaded source, exactly as the profile page lists them (/api/extensions):
     * id = unambiguousSlug(api) ?: internalName; [globallyOn] = switched on for everyone.
     * The limit counts these, so the page, the save check and the trim always agree.
     */
    private class ExtCard(val id: String, val globallyOn: Boolean, val statKey: String)

    private fun extCards(): List<ExtCard> =
        loadedApis.map { ExtCard(unambiguousSlug(it) ?: it.internalName, !isGloballyDisabled(it), it.internalName) }
            .distinctBy { it.id }

    /** Cards a profile with these choices has switched on (same rule as the page's isExtActive). */
    private fun activeCards(disabled: Set<String>, overrides: Set<String>, cards: List<ExtCard>): List<ExtCard> =
        cards.filter { if (it.globallyOn) it.id !in disabled else it.id in overrides }

    fun enabledExtensionCount(profileId: String): Int {
        val rec = profiles[profileId] ?: ProfileRecord()
        return activeCards(rec.disabled, rec.enabledOverrides, extCards()).size
    }

    /** Would these choices go over the limit? */
    fun exceedsExtensionLimit(disabled: Set<String>, overrides: Set<String>): Boolean =
        activeCards(disabled, overrides, extCards()).size > MAX_PROFILE_EXTENSIONS

    /** Would switching [internalName] on take this profile over the limit? */
    fun wouldExceedOnEnable(profileId: String, internalName: String): Boolean {
        val rec = profiles[profileId] ?: ProfileRecord()
        val cards = extCards()
        val active = activeCards(rec.disabled, rec.enabledOverrides, cards)
        if (active.any { it.id == internalName }) return false // switching it off
        return active.size >= MAX_PROFILE_EXTENSIONS
    }

    /**
     * Trims every profile over [MAX_PROFILE_EXTENSIONS] to its best extensions — the ones
     * that delivered the most streams — and switches the rest off. Returns profiles trimmed.
     */
    fun enforceProfileExtensionLimit(): Int {
        val cards = extCards()
        var trimmed = 0
        for ((pid, rec) in profiles.toMap()) {
            val active = activeCards(rec.disabled, rec.enabledOverrides, cards)
            if (active.size <= MAX_PROFILE_EXTENSIONS) continue
            val keep = active.sortedByDescending { com.cncverse.stremiobridge.state.StreamTracker.statOf(it.statKey)?.totalStreams ?: 0 }
                .take(MAX_PROFILE_EXTENSIONS).map { it.id }.toSet()
            val drop = active.filter { it.id !in keep }
            val newOverrides = rec.enabledOverrides - drop.filter { !it.globallyOn }.map { it.id }.toSet()
            val newDisabled = rec.disabled + drop.filter { it.globallyOn }.map { it.id }
            profiles[pid] = rec.copy(disabled = newDisabled, enabledOverrides = newOverrides)
            trimmed++
        }
        if (trimmed > 0) saveProfiles()
        ServerState.info("[Profiles] extension limit $MAX_PROFILE_EXTENSIONS: trimmed $trimmed profile(s)")
        return trimmed
    }

    private fun limitError(profileId: String): String =
        "{\"error\":\"limit\",\"message\":\"A profile can have at most $MAX_PROFILE_EXTENSIONS extensions switched on. Switch one off first.\"," +
            "\"max\":$MAX_PROFILE_EXTENSIONS,\"enabled\":${enabledExtensionCount(profileId)}}"

    /** Replaces the whole selection for a profile (bulk select / clear all). */
    fun setProfileSelections(
        profileId: String,
        disabled: Set<String>,
        enabledOverrides: Set<String>,
        disableCatalogs: Boolean? = null,
        disabledCatalogs: Set<String>? = null,
        allowedResolutions: Set<String>? = null,
        excludeCam: Boolean? = null,
        maxStreamsPerResolution: Int? = null,
    ) {
        val now = currentTimeMillis()
        val existing = profiles[profileId]
        // copy(): every other field (formatter, name, playback settings...) carries over untouched
        profiles[profileId] = (existing ?: ProfileRecord(createdAt = now)).copy(
            disabled = disabled,
            enabledOverrides = enabledOverrides,
            disableCatalogs = disableCatalogs ?: existing?.disableCatalogs ?: false,
            disabledCatalogs = disabledCatalogs ?: existing?.disabledCatalogs ?: emptySet(),
            allowedResolutions = allowedResolutions ?: existing?.allowedResolutions ?: emptySet(),
            excludeCam = excludeCam ?: existing?.excludeCam ?: false,
            maxStreamsPerResolution = maxStreamsPerResolution ?: existing?.maxStreamsPerResolution ?: 0,
            lastSeen = now,
        )
        saveProfiles()
    }

    /** Updates a profile's filtering & playback settings (file size, subtitles, grouping, sorting, provider order). */
    fun updateProfilePlayback(profileId: String, r: ProfilePlaybackRequest) {
        val now = currentTimeMillis()
        val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
        val max = r.maxSizeGb.coerceIn(0.0, 1000.0)
        val min = r.minSizeGb.coerceIn(0.0, 1000.0).let { if (max > 0 && it > max) max else it }
        profiles[profileId] = existing.copy(
            hideSubtitles = r.hideSubtitles,
            groupBy = r.groupBy.takeIf { it in setOf("default", "provider", "quality") } ?: "default",
            sortBy = r.sortBy.takeIf { it in setOf("default", "size") } ?: "default",
            minSizeGb = min,
            maxSizeGb = max,
            providerOrder = r.providerOrder.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 80 }
                .distinct().take(MAX_PROFILE_EXTENSIONS + 10),
            lastSeen = now,
        )
        saveProfiles()
    }

    /** Updates quality preferences for a user profile. */
    fun updateProfileQualityPreferences(
        profileId: String,
        allowedResolutions: Set<String>,
        excludeCam: Boolean,
        maxStreamsPerResolution: Int,
    ) {
        val now = currentTimeMillis()
        val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
        profiles[profileId] = existing.copy(
            allowedResolutions = allowedResolutions,
            excludeCam = excludeCam,
            maxStreamsPerResolution = maxStreamsPerResolution,
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
        val disableCatalogs = rec?.disableCatalogs ?: false
        val disabledJson = disabled.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val overridesJson = overrides.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val disabledCatalogs = rec?.disabledCatalogs ?: emptySet()
        val disabledCatalogsJson = disabledCatalogs.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val allowedResolutions = rec?.allowedResolutions ?: emptySet()
        val allowedResolutionsJson = allowedResolutions.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val excludeCam = rec?.excludeCam ?: false
        val maxStreamsPerResolution = rec?.maxStreamsPerResolution ?: 0
        val extra = (if (nowEnabled != null) ",\"nowEnabled\":$nowEnabled" else "") +
            ",\"maxExtensions\":$MAX_PROFILE_EXTENSIONS,\"enabledCount\":${enabledExtensionCount(profileId)}" +
            ",\"displayName\":" + serverJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(rec?.displayName ?: "")) +
            ",\"formatter\":" + serverJson.encodeToString(
                com.cncverse.stremiobridge.format.ProfileFormatter.serializer(),
                rec?.formatter ?: com.cncverse.stremiobridge.format.ProfileFormatter(),
            )
        return "{\"profileId\":\"" + profileId + "\",\"disabledExtensions\":[" + disabledJson +
            "],\"enabledExtensions\":[" + overridesJson +
            "],\"disableCatalogs\":" + disableCatalogs +
            ",\"disabledCatalogs\":[" + disabledCatalogsJson + "]" +
            ",\"allowedResolutions\":[" + allowedResolutionsJson + "]" +
            ",\"excludeCam\":" + excludeCam +
            ",\"maxStreamsPerResolution\":" + maxStreamsPerResolution +
            ",\"hideSubtitles\":" + (rec?.hideSubtitles ?: false) +
            ",\"groupBy\":\"" + (rec?.groupBy ?: "default") + "\",\"sortBy\":\"" + (rec?.sortBy ?: "default") + "\"" +
            ",\"minSizeGb\":" + (rec?.minSizeGb ?: 0.0) + ",\"maxSizeGb\":" + (rec?.maxSizeGb ?: 0.0) +
            ",\"providerOrder\":" + serverJson.encodeToString(
                kotlinx.serialization.serializer<List<String>>(),
                rec?.providerOrder ?: emptyList(),
            ) +
            ",\"createdAt\":" + (rec?.createdAt ?: 0) + ",\"lastSeen\":" + (rec?.lastSeen ?: 0) + extra + "}"
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

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort
        if (cacheDir != null) {
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
            profilesFile = File(cacheDir, "profiles.json")
            loadProfiles()
            creditsFile = File(cacheDir, "credits.json")
            loadCredits()
            footerCreditsFile = File(cacheDir, "footer_credits.json")
            loadFooterCredits()
            themeConfigFile = File(cacheDir, "theme_config.json")
            loadThemeConfig()
            catalogsOffMarker = File(cacheDir, "catalogs_disabled_globally")
            ServerState.disableCatalogsGlobally = catalogsOffMarker?.exists() == true
            com.cncverse.stremiobridge.format.StreamFormatter.init(cacheDir)
            com.cncverse.stremiobridge.cache.StreamCacheManager.init(cacheDir)
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
        if (profileSavePending.getAndSet(false)) writeProfilesNow()
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        com.cncverse.stremiobridge.cache.StreamCacheManager.shutdown()
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
        setupWebAdminRoutes()
        routing {
            // 📺 User-facing index page 📺
            get("/") {
                call.respondText(buildIndexHtml(), ContentType.Text.Html)
            }
            get("/configure") {
                call.respondText(buildIndexHtml(), ContentType.Text.Html)
            }
            // Profile manifest is served from /u/{profileId}/manifest.json, so
            // Stremio resolves the configure page at /u/{profileId}/configure.
            get("/u/{profileId}/configure") {
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
                // Public endpoint: serve a 5-minute cached copy so it can't be used to
                // hammer the upstream (or this server's bandwidth) with one request per hit.
                val cached = communityStatsCache
                if (cached != null && currentTimeMillis() - cached.first < 5 * 60_000L) {
                    return@get call.respondText(cached.second, ContentType.Application.Json)
                }
                try {
                    val res = httpClient.get("https://cncverse.pages.dev/api/stats") {
                        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    }
                    val body = res.bodyAsText()
                    if (res.status.isSuccess()) communityStatsCache = currentTimeMillis() to body
                    call.respondText(body, ContentType.Application.Json)
                } catch (e: Exception) {
                    cached?.let { return@get call.respondText(it.second, ContentType.Application.Json) }
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

            post("/api/profile/{profileId}/catalogs") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val disable = try {
                    serverJson.decodeFromString<ProfileCatalogToggleRequest>(rawText).disableCatalogs
                } catch (_: Throwable) {
                    rawText.contains("\"disableCatalogs\":true") || rawText.contains("\"disableCatalogs\": true")
                }
                val now = currentTimeMillis()
                val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
                profiles[profileId] = existing.copy(disableCatalogs = disable, lastSeen = now)
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // ── User-side stream formatter ───────────────────────────────────

            // Presets + variables for the configure page's formatter editor
            // Links to an extension's own server on this machine (Re:ANIME: http://127.0.0.1:41949/…)
            get("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }
            head("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@head call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }

            get("/api/formatter") {
                call.respond(com.cncverse.stremiobridge.format.StreamFormatter.catalog())
            }

            // Live preview of templates against sample streams (nothing is saved)
            post("/api/formatter/preview") {
                val body = runCatching {
                    serverJson.decodeFromString<com.cncverse.stremiobridge.format.ProfileFormatter>(call.receiveText())
                }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val (n, d) = if (body.mode == "preset") {
                    val p = com.cncverse.stremiobridge.format.StreamFormatter.PRESETS.find { it.id == body.presetId }
                        ?: return@post call.respond(com.cncverse.stremiobridge.format.FormatterPreviewResult(false, "Unknown preset"))
                    p.nameTemplate to p.descriptionTemplate
                } else body.nameTemplate to body.descriptionTemplate
                call.respond(com.cncverse.stremiobridge.format.StreamFormatter.preview(n, d))
            }

            // Save this profile's formatter choice
            post("/api/profile/{profileId}/formatter") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val body = runCatching {
                    serverJson.decodeFromString<com.cncverse.stremiobridge.format.ProfileFormatter>(call.receiveText())
                }.getOrNull() ?: return@post call.respondText(
                    "{\"error\":\"invalid formatter body\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                val cleaned = try {
                    com.cncverse.stremiobridge.format.StreamFormatter.validateProfile(body)
                } catch (e: com.cncverse.stremiobridge.format.TemplateException) {
                    return@post call.respondText(
                        "{\"error\":" + serverJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
                            kotlinx.serialization.json.JsonPrimitive(e.message ?: "Invalid template")) + "}",
                        ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
                val now = currentTimeMillis()
                val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
                profiles[profileId] = existing.copy(formatter = cleaned.takeUnless { it.mode == "default" }, lastSeen = now)
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Rename this profile (shown in the Stremio addon title)
            post("/api/profile/{profileId}/name") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val raw = runCatching {
                    serverJson.parseToJsonElement(call.receiveText()).jsonObject["displayName"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                val name = raw.filter { !it.isISOControl() }.trim().take(40)
                val now = currentTimeMillis()
                val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
                profiles[profileId] = existing.copy(displayName = name, lastSeen = now)
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Toggle an extension on/off for this profile
            post("/api/profile/{profileId}/toggle") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val internalName = try {
                    serverJson.decodeFromString<ProfilePluginToggleRequest>(rawText).internalName
                } catch (_: Throwable) {
                    Regex("\"internalName\"\\s*:\\s*\"([^\"]+)\"").find(rawText)?.groupValues?.get(1) ?: ""
                }
                if (internalName.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
                if (wouldExceedOnEnable(profileId, internalName)) {
                    return@post call.respondText(limitError(profileId), ContentType.Application.Json, HttpStatusCode.Conflict)
                }
                val nowEnabled = toggleProfilePlugin(profileId, internalName)
                saveProfiles()
                call.respondText(profileJson(profileId, nowEnabled), ContentType.Application.Json)
            }

            // Replace the whole disabled set for this profile (Select all / Clear all)
            post("/api/profile/{profileId}/set") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val body = try {
                    serverJson.decodeFromString<ProfileSetRequest>(rawText)
                } catch (e: Exception) {
                    return@post call.respondText(
                        "{\"error\":\"expected a disabledExtensions string array\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
                if (exceedsExtensionLimit(body.disabledExtensions.toSet(), body.enabledExtensions.toSet())) {
                    return@post call.respondText(limitError(profileId), ContentType.Application.Json, HttpStatusCode.Conflict)
                }
                setProfileSelections(
                    profileId,
                    disabled = body.disabledExtensions.toSet(),
                    enabledOverrides = body.enabledExtensions.toSet(),
                    disableCatalogs = body.disableCatalogs,
                    disabledCatalogs = body.disabledCatalogs?.toSet(),
                    allowedResolutions = body.allowedResolutions?.toSet(),
                    excludeCam = body.excludeCam,
                    maxStreamsPerResolution = body.maxStreamsPerResolution,
                )
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Save user quality filter preferences
            post("/api/profile/{profileId}/quality") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val body = try {
                    serverJson.decodeFromString<ProfileQualityPreferencesRequest>(rawText)
                } catch (e: Exception) {
                    return@post call.respondText(
                        "{\"error\":\"invalid quality preferences body\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
                updateProfileQualityPreferences(
                    profileId,
                    allowedResolutions = body.allowedResolutions.toSet(),
                    excludeCam = body.excludeCam,
                    maxStreamsPerResolution = body.maxStreamsPerResolution,
                )
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Filtering & playback: file size, subtitles, grouping, sorting, provider order
            post("/api/profile/{profileId}/playback") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val body = try {
                    serverJson.decodeFromString<ProfilePlaybackRequest>(rawText)
                } catch (e: Exception) {
                    return@post call.respondText(
                        "{\"error\":\"invalid playback settings body\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
                updateProfilePlayback(profileId, body)
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }

            // Toggle per-plugin catalog on/off for this profile
            post("/api/profile/{profileId}/toggle-catalog") {
                val profileId = call.parameters["profileId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val rawText = try { call.receiveText() } catch (_: Throwable) { "" }
                val internalName = try {
                    serverJson.decodeFromString<ProfilePluginToggleRequest>(rawText).internalName
                } catch (_: Throwable) {
                    Regex("\"internalName\"\\s*:\\s*\"([^\"]+)\"").find(rawText)?.groupValues?.get(1) ?: ""
                }
                if (internalName.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
                val now = currentTimeMillis()
                val existing = profiles.getOrPut(profileId) { ProfileRecord(createdAt = now, lastSeen = now) }
                val cur = existing.disabledCatalogs.toMutableSet()
                if (cur.contains(internalName)) cur.remove(internalName) else cur.add(internalName)
                profiles[profileId] = existing.copy(disabledCatalogs = cur, lastSeen = now)
                saveProfiles()
                call.respondText(profileJson(profileId), ContentType.Application.Json)
            }


            // Returns loaded extensions for the user page
            get("/api/extensions") {
                val installed = com.cncverse.stremiobridge.state.RepoState.installedPlugins.value
                val knownRepos = com.cncverse.stremiobridge.state.RepoState.repos.value
                // Encoded with kotlinx (not string concatenation) so descriptions containing
                // backslashes, tabs or control characters can't produce invalid JSON.
                val arr = kotlinx.serialization.json.buildJsonArray {
                loadedApis.forEach { api ->
                    val name = api.name
                    val id = unambiguousSlug(api) ?: api.internalName
                    val plugin = installed.find { it.internalName == api.pluginInternalName || it.internalName == api.internalName }
                    val rawRepoUrl = plugin?.repoUrl ?: ""
                    // Canonicalize: prefer the URL as stored in the known repos list so that
                    // refs/heads variants don't create phantom repo groups in the UI.
                    val knownRepo = knownRepos.find { it.url == rawRepoUrl }
                        ?: knownRepos.find { normalizeGhUrl(it.url) == normalizeGhUrl(rawRepoUrl) }
                    val repoUrl = knownRepo?.url ?: rawRepoUrl
                    val repoName = knownRepo?.name?.takeIf { it.isNotBlank() }
                        ?: run {
                            val slug = rawRepoUrl.substringAfterLast("/").substringBefore(".json").ifBlank { "CNCVerse" }
                            slug.replace(Regex("[-_]"), " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        }
                    val lang = plugin?.language?.takeIf { it.isNotBlank() } ?: "en"
                    // Prefer repo-declared tvTypes (from the plugin metadata JSON) over
                    // api.supportedTypes at runtime, since runtime types can be buggy
                    // (e.g. all returning "Others" due to a CS3 issue). The repo manifest
                    // always has the correct declared types — this is the "direct TVtype".
                    val types = if (!plugin?.tvTypes.isNullOrEmpty()) plugin!!.tvTypes.distinct() else api.supportedTypes.distinct()
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("internalName", kotlinx.serialization.json.JsonPrimitive(id))
                        put("name", kotlinx.serialization.json.JsonPrimitive(name))
                        put("enabled", kotlinx.serialization.json.JsonPrimitive(!isGloballyDisabled(api)))
                        put("repoUrl", kotlinx.serialization.json.JsonPrimitive(repoUrl))
                        put("repoName", kotlinx.serialization.json.JsonPrimitive(repoName))
                        put("lang", kotlinx.serialization.json.JsonPrimitive(lang))
                        put("iconUrl", kotlinx.serialization.json.JsonPrimitive(PublicUrls.safeForBrowser(plugin?.iconUrl).orEmpty()))
                        put("description", kotlinx.serialization.json.JsonPrimitive(plugin?.description?.replace("\n", " ").orEmpty()))
                        put("types", kotlinx.serialization.json.JsonArray(types.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        // working | proxy | dead | unknown — from real traffic + the nightly probe
                        val stat = com.cncverse.stremiobridge.state.StreamTracker.statOf(api.internalName)
                            ?: com.cncverse.stremiobridge.state.StreamTracker.statOf(api.pluginInternalName)
                        put("health", kotlinx.serialization.json.JsonPrimitive(stat?.status() ?: com.cncverse.stremiobridge.state.HealthStatus.UNKNOWN))
                        put("lastSuccess", kotlinx.serialization.json.JsonPrimitive(stat?.lastSuccessTime ?: 0L))
                        put("lastChecked", kotlinx.serialization.json.JsonPrimitive(stat?.lastProbeTime ?: 0L))
                    })
                }
                }
                call.respondText(serverJson.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr), ContentType.Application.Json)
            }

            // ── Repos (user-facing) ───────────────────────────────────────────

            // Repos installed on this server, with extension counts and states
            get("/api/repos") {
                val sb = StringBuilder("[")
                com.cncverse.stremiobridge.state.RepoState.repos.value.forEachIndexed { i, repo ->
                    if (i > 0) sb.append(",")
                    val name = repo.name.ifBlank { repo.url }.replace("\"", "\\\"")
                    val url = repo.url.replace("\"", "\\\"")
                    val icon = PublicUrls.safeForBrowser(repo.iconUrl)?.replace("\"", "\\\"") ?: ""
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

            get("/api/credits") {
                call.respondText(serverJson.encodeToString(getCreditsList()), ContentType.Application.Json)
            }

            get("/api/footer-credits") {
                call.respondText(serverJson.encodeToString(getFooterCredits()), ContentType.Application.Json)
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
        val rec = profileId?.let { profiles[it] }
        if (rec?.disableCatalogs == true || ServerState.disableCatalogsGlobally) {
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

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, null, profileId) }
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

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, genre, profileId) }
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

    private suspend fun ApplicationCall.respondMeta(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val meta = withContext(pluginDispatcher) { buildMeta(type, id, profileId) }
        if (meta != null) {
            respond(StremioMetaResponse(meta))
        } else {
            respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun ApplicationCall.respondStreams(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val streams = withContext(pluginDispatcher) { buildStreams(type, id, profileId) }
        val sorted = sortStreamsByQuality(streams)

        // Filter streams according to user profile quality preferences (if requested with a profile)
        val filtered = if (profileId != null) {
            profiles[profileId]?.let { arrangeStreams(filterStreamsByProfile(sorted, it), it) } ?: sorted
        } else {
            sorted
        }

        // A profile's own formatter (preset / custom / off) wins; otherwise the server's.
        val formatted = com.cncverse.stremiobridge.format.StreamFormatter.applyFor(
            filtered,
            com.cncverse.stremiobridge.format.StreamFormatter.contextFromId(type, id),
            profileId?.let { profiles[it]?.formatter },
        )
        // "Hide subtitles": no subtitle tracks go out (after formatting, so the description stays truthful)
        val out = (if (profileId != null && profiles[profileId]?.hideSubtitles == true) formatted.map { it.copy(subtitles = null) } else formatted)
            .map { withStreamBase(it) }
            .map { LocalRelay.rewriteStream(it) }
            .map { s -> s.subtitles?.let { subs -> s.copy(subtitles = subs.map { it.copy(lang = com.cncverse.stremiobridge.format.SubtitleLangs.normalize(it.lang)) }) } ?: s }
        respond(StremioStreamResponse(out))
    }

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

    private suspend fun ApplicationCall.respondSubtitles(profileId: String?) {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        if (profileId != null && profiles[profileId]?.hideSubtitles == true) {
            respond(StremioSubtitleResponse(emptyList()))
            return
        }
        val streams = withContext(pluginDispatcher) { buildStreams(type, id, profileId) }
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
        val rec = profiles[profileId] ?: return globallyDisabled
        val ids = profileMatchIds(api)
        return if (globallyDisabled) {
            ids.none { rec.enabledOverrides.contains(it) } ||
                ids.any { rec.disabled.contains(it) }
        } else {
            ids.any { rec.disabled.contains(it) }
        }
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

    /**
     * No longer a timer: reloading every home page every 30 minutes (opened or not) was a big
     * CPU spike for nothing. Pages load once at startup / after a plugin reload and are then
     * refreshed only when requested (see buildCatalog). Kept so callers stay unchanged.
     */
    fun startPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
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
     * Builds the Stremio manifest.
     * Serves instantly from [providerCatalogCache], reusing provider definitions across all profiles.
     * @param profileId If non-null, excludes extensions the profile has disabled.
     */
    suspend fun buildManifest(profileId: String? = null): StremioManifest {
        val activeApis = loadedApis.filter { !isPluginBlocked(it, profileId) }
        val types = listOf("movie", "series", "other", "tv")

        val rec = profileId?.let { profiles[it] }

        val catalogs = if (rec?.disableCatalogs == true || ServerState.disableCatalogsGlobally) {
            emptyList()
        } else {
            activeApis.filter { api -> rec == null || !profileMatchIds(api).any { rec.disabledCatalogs.contains(it) } }.flatMap { api ->
                providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
            }.distinctBy { it.id }
        }

        val hasCatalogs = catalogs.isNotEmpty() && !(rec?.disableCatalogs ?: false) && !ServerState.disableCatalogsGlobally
        val effectiveCatalogs = if (hasCatalogs) catalogs else emptyList()

        // Unique manifest id per profile so the personal addon can be
        // installed alongside the global one in Stremio without replacing it.
        val profileSuffix = profileId
            ?.replace(Regex("[^a-zA-Z0-9]"), "")
            ?.take(24)
            ?.ifBlank { null }

        // Dynamic version hash so Stremio client invalidates manifest cache on config changes
        val configHash = (
            (if (rec?.disableCatalogs == true || ServerState.disableCatalogsGlobally) 1 else 0) * 397 xor
            (rec?.displayName?.hashCode() ?: 0) * 7 xor
            (rec?.disabledCatalogs?.hashCode() ?: 0) * 31 xor
            (rec?.disabled?.hashCode() ?: 0) * 17 xor
            (rec?.enabledOverrides?.hashCode() ?: 0) xor
            effectiveCatalogs.size
        ).let { kotlin.math.abs(it) % 10000 }

        return StremioManifest(
            id          = if (profileSuffix == null) "com.cncverse.stremiobridge"
                          else "com.cncverse.stremiobridge." + profileSuffix,
            version     = "1.0.$configHash",
            name        = if (profileId == null) "CNCVerse Bridge"
                          else "CNCVerse Bridge \u00B7 " + (profiles[profileId]?.displayName?.takeIf { it.isNotBlank() } ?: "Profile"),
            description = "Cloudstream plugin bridge for Stremio \u2014 Developed by NivinCNC",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Bridge/refs/heads/main/logo.png",
            types       = types,
            resources   = if (hasCatalogs) listOf("catalog", "meta", "stream", "subtitles") else listOf("meta", "stream", "subtitles"),
            catalogs    = effectiveCatalogs,
            behaviorHints = BehaviorHints(configurable = true),
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
        type: String, id: String, search: String?, skip: Int, genre: String?, profileId: String? = null
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
        if (isPluginBlocked(api, profileId)) return emptyList()
        val rec = profileId?.let { profiles[it] }
        if (rec != null && (rec.disableCatalogs || profileMatchIds(api).any { rec.disabledCatalogs.contains(it) })) return emptyList()

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

    private suspend fun buildMeta(type: String, id: String, profileId: String? = null): StremioMeta? {
        val (pluginKey, dataUrl) = StremioIds.decode(id) ?: return null
        // Item ids carry the source's display-name slug, which several extensions can share
        // (SKTech and LivXow both have "📺 SUN NXT"). Taking the first match could pick a
        // copy that is disabled for this user → 404 and a detail page that never loads.
        // Try every enabled match instead: exact keys first, then the name slug.
        val candidates = (loadedApis.filter { apiKey(it) == pluginKey } +
            loadedApis.filter { nameSlug(it.name) == pluginKey } +
            loadedApis.filter { it.internalName == pluginKey })
            .distinct()
            .filter { !isPluginBlocked(it, profileId) }
        for (api in candidates) {
            val meta = try {
                // Opening the same detail page again used to re-scrape the site every time
                val key = apiKey(api)
                val info = SearchLoadCache.getLoad(key, dataUrl)?.info
                    ?: kotlinx.coroutines.withTimeoutOrNull(META_LOAD_TIMEOUT_MS) { api.load(dataUrl) }
                        ?.also { SearchLoadCache.putLoad(key, dataUrl, it) }
                info?.toStremiMeta(nameSlug(api.name), type)
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

    /** Sort rank from the stream's own resolution (never the release/page title). */
    private fun streamQualityRank(stream: StremioStream): Int =
        when (detectStreamResolution(stream)) {
            "2160p" -> 5
            "1080p" -> 4
            "720p" -> 3
            "480p" -> 2
            "360p" -> 1
            else -> 0
        }

    private fun sortStreamsByQuality(streams: List<StremioStream>): List<StremioStream> =
        streams.sortedByDescending { streamQualityRank(it) }

    /** Detects standard resolution tier: 2160p, 1080p, 720p, 480p, 360p, or other. */
    fun detectStreamResolution(stream: StremioStream): String =
        // Same rule as the formatter: reported quality, else the link's own name —
        // never the matched release title (multi-quality pack pages say "4K 1080p 720p").
        when (com.cncverse.stremiobridge.format.StreamVariables.resolutionOf(stream)) {
            "4320p", "2160p" -> "2160p"
            "1440p", "1080p" -> "1080p"
            "720p" -> "720p"
            "576p", "480p" -> "480p"
            "360p" -> "360p"
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
    fun arrangeStreams(streams: List<StremioStream>, profile: ProfileRecord): List<StremioStream> {
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
    fun filterStreamsByProfile(streams: List<StremioStream>, profile: ProfileRecord): List<StremioStream> {
        var result = streams

        // 1. Exclude CAM / Screener rips
        if (profile.excludeCam) {
            result = result.filter { !isCamStream(it) }
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
    private val STREAM_DEADLINE_MS = 45_000L
    /** Answer with what has loaded after this long; the rest load into the cache until STREAM_DEADLINE_MS. */
    private val STREAM_SOFT_DEADLINE_MS = 8_000L
    private val PROVIDER_TIMEOUT_MS = 38_000L
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

    private suspend fun buildStreams(type: String, id: String, profileId: String? = null): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val matchingApis = loadedApis.filter { api ->
                val slug = apiKey(api)
                slug == pluginKey || nameSlug(api.name) == pluginKey || api.internalName == pluginKey
            }.filter { !isPluginBlocked(it, profileId) }

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
                                var links = api.loadLinksAll(dataUrl)
                                // Catalog items opened straight from a row (defaultVideoId) carry the
                                // item URL, not the load() data — e.g. Netflix mirrors need the title
                                // that only load() adds. Resolve it once and retry.
                                if (links.isEmpty() && !api.supportedTypes.all { it == "tv" }) {
                                    val info = runCatching { api.load(dataUrl) }.getOrNull()
                                    val resolved = info?.let { mi ->
                                        mi.dataUrl.takeIf { it.isNotBlank() && it != dataUrl }
                                            ?: mi.episodes?.firstOrNull()?.dataUrl
                                    }
                                    if (resolved != null && resolved != dataUrl) {
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
            .filter { api -> !isPluginBlocked(api, profileId) && api.supportedTypes.any { it != "tv" } }
            .map { it.internalName }.sorted().joinToString(",").hashCode()
        val aggCacheKey = "stream:generic:$type:$id:$activeSig"

        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetchResult(aggCacheKey, null) {
            try {
                val resolved = resolveExternal(type, ext)
                if (resolved == null) {
                    ServerState.warn("Media resolve failed: no title/year found for $id")
                    return@getOrFetchResult com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(emptyList())
                }
                val (title, year) = resolved
                ServerState.debug("Media resolve success: title='$title', year=$year")

                // Exclude live-TV-only extensions from generic TMDB VOD searches —
                // they don't carry on-demand movie/series content.
                val activePlugins = loadedApis.filter { api ->
                    !isPluginBlocked(api, profileId) &&
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
                                    val streams = buildGenericStreamsForApi(api, type, id, title, year)
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
                }

                // Answer when everything is done, or once STREAM_SOFT_DEADLINE_MS has passed and
                // there is something to show; with nothing yet, wait up to STREAM_DEADLINE_MS.
                withTimeoutOrNull(STREAM_DEADLINE_MS) {
                    while (jobs.any { it.isActive }) {
                        if (accumulated.isNotEmpty() && System.currentTimeMillis() - started >= STREAM_SOFT_DEADLINE_MS) break
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
        year: Int?
    ): List<StremioStream> {
        if (id.startsWith("probe_")) {
            return doBuildGenericStreamsForApi(api, type, id, title, year)
        }
        val providerCacheKey = "stream:provider:${api.internalName}:$type:$id"
        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetch(providerCacheKey, api.internalName) {
            doBuildGenericStreamsForApi(api, type, id, title, year)
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
        year: Int?
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
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Search error: ${e.message}")
            return emptyList()
        }
        ServerState.debug("[${api.name}] Found ${searchResults.size} results")

        val requestedSeason = if (type == "series" && id.contains(":")) {
            id.split(":").getOrNull(1)?.toIntOrNull()
        } else null

        val bestMatch = if (requestedSeason != null) {
            // Sites often list each season separately ("The Boys Season 2", sometimes with that
            // season's year), which strict title+year matching rejects. Accept those only when the
            // result is exactly "<title> season N" / "<title> sN" at the start — so "Season 1"
            // never matches "Season 10" and spin-offs ("The Boys Presents …") never match.
            val wanted = normalizeTitle(title)
            val seasonRx = Regex("^" + Regex.escape(wanted) + " (?:season 0*$requestedSeason|s0*$requestedSeason)(?: |$)")
            searchResults.find { r -> seasonRx.containsMatchIn(normalizeTitle(r.name)) }
                ?: pickBestMatch(searchResults, title, year)
        } else {
            pickBestMatch(searchResults, title, year)
        } ?: run {
            ServerState.debug("[${api.name}] No result matches '$title'" + (year?.let { " ($it)" } ?: ""))
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No result matches '$title'")
            return emptyList()
        }

        ServerState.debug("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
        val cachedLoad = SearchLoadCache.getLoad(cacheKey, bestMatch.url)
        val mediaInfo = try {
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

        var dataUrlToLoad = mediaInfo.dataUrl
        if (type == "series" && id.contains(":")) {
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
                    .copy(metadataTitle = title, metadataYear = year)
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
        return """
<!DOCTYPE html>
<html lang="en" data-base-theme="${ServerState.globalBaseTheme}">
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
:root, [data-base-theme="slate"] {
  --bg: #0f172a;
  --surface: #1e293b;
  --surface-active: #334155;
  --surface-card: #1e293b;
  --surface-card-hover: #243248;
  --border: #334155;
  --border-focus: #475569;
  --border-active: ${ServerState.globalAccentHex};
  --text: #ffffff;
  --text-sub: #cbd5e1;
  --text-dim: #94a3b8;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #1e293b;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --cyan: #06b6d4;
  --cyan-bg: rgba(6, 182, 212, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --red: #f43f5e;
  --card-radius: 14px;
}
[data-base-theme="charcoal"] {
  --bg: #18181b;
  --surface: #27272a;
  --surface-active: #3f3f46;
  --surface-card: #27272a;
  --surface-card-hover: #323236;
  --border: #3f3f46;
  --border-focus: #52525b;
  --border-active: ${ServerState.globalAccentHex};
  --text: #ffffff;
  --text-sub: #d4d4d8;
  --text-dim: #a1a1aa;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #27272a;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --cyan: #06b6d4;
  --cyan-bg: rgba(6, 182, 212, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --red: #f43f5e;
}
[data-base-theme="navy"] {
  --bg: #0d1117;
  --surface: #161b22;
  --surface-active: #21262d;
  --surface-card: #161b22;
  --surface-card-hover: #1c2128;
  --border: #30363d;
  --border-focus: #484f58;
  --border-active: ${ServerState.globalAccentHex};
  --text: #ffffff;
  --text-sub: #cbd5e1;
  --text-dim: #94a3b8;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #161b22;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --cyan: #06b6d4;
  --cyan-bg: rgba(6, 182, 212, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --red: #f43f5e;
}
[data-base-theme="forest"] {
  --bg: #0c1512;
  --surface: #13221d;
  --surface-active: #1a2f28;
  --surface-card: #13221d;
  --surface-card-hover: #172a24;
  --border: #223c33;
  --border-focus: #2f5246;
  --border-active: ${ServerState.globalAccentHex};
  --text: #ffffff;
  --text-sub: #c7eedd;
  --text-dim: #8ecbb0;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #13221d;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --cyan: #06b6d4;
  --cyan-bg: rgba(6, 182, 212, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --red: #f43f5e;
}
[data-base-theme="oled"] {
  --bg: #000000;
  --surface: #0a0a0a;
  --surface-active: #141414;
  --surface-card: #0f0f0f;
  --surface-card-hover: #161616;
  --border: #242424;
  --border-focus: #383838;
  --border-active: ${ServerState.globalAccentHex};
  --text: #ffffff;
  --text-sub: #e5e5e5;
  --text-dim: #a3a3a3;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #0f0f0f;
  --green: #10b981;
  --green-bg: rgba(16, 185, 129, 0.14);
  --cyan: #06b6d4;
  --cyan-bg: rgba(6, 182, 212, 0.14);
  --amber: #f59e0b;
  --amber-bg: rgba(245, 158, 11, 0.14);
  --red: #f43f5e;
}
[data-base-theme="light"], [data-theme="light"] {
  --bg: #f8fafc;
  --surface: #ffffff;
  --surface-active: #f1f5f9;
  --surface-card: #ffffff;
  --surface-card-hover: #f8fafc;
  --border: #e2e8f0;
  --border-focus: #cbd5e1;
  --border-active: ${ServerState.globalAccentHex};
  --text: #0f172a;
  --text-sub: #334155;
  --text-dim: #64748b;
  --accent: ${ServerState.globalAccentHex};
  --accent-hover: ${ServerState.globalAccentHover};
  --accent-glow: ${ServerState.globalAccentGlow};
  --code-bg: #f8fafc;
  --green: #059669;
  --green-bg: rgba(5, 150, 105, 0.08);
  --cyan: #0284c7;
  --cyan-bg: rgba(2, 132, 199, 0.08);
  --amber: #d97706;
  --amber-bg: rgba(217, 119, 6, 0.08);
  --red: #f43f5e;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { overflow-x: hidden; max-width: 100vw; }
body {
  background: var(--bg);
  color: var(--text);
  font: 14px/1.5 'Inter', system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  padding: 1.25rem 1rem 4rem;
  min-height: 100vh;
  -webkit-font-smoothing: antialiased;
}
@media (max-width: 680px) {
  body { padding: 12px 10px 4rem; }
}
.container {
  max-width: 42rem;
  width: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
  min-width: 0;
}

/* Header */
.hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.2rem 0;
}
.brand-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}
.logo-box {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--accent-glow);
  border: 1px solid rgba(139, 92, 246, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
}
.btn-icon-hdr {
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
  text-decoration: none;
}
.btn-icon-hdr:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
  color: var(--accent);
}
.brand-title {
  font-size: 1.35rem;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.4px;
}
.hdr-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

/* Left burger + page views (Home / Profiles / Quality / Formatter) */
.hdr-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.hdr-profile {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 46vw;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-muted, var(--text));
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.hdr-profile:hover { border-color: var(--border-focus); color: var(--accent); }
.view-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 1.05rem;
  font-weight: 800;
  color: var(--text);
  margin: 2px 0 -4px;
}
.view-hidden { display: none !important; }
.drawer-btn.active {
  background: var(--accent-glow);
  border-color: rgba(139, 92, 246, 0.45);
  color: var(--accent);
}
/* Health badges + dead filter on provider cards */
.hb {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 999px;
  line-height: 1.3;
}
.hb-dead { background: rgba(239, 68, 68, 0.15); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }
.hb-proxy { background: rgba(59, 130, 246, 0.15); color: #60a5fa; border: 1px solid rgba(59, 130, 246, 0.4); }
.p-card.is-dead { opacity: 0.62; }
.dead-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

/* Slide-Over Drawer Menu */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 600;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.22s ease;
}
.drawer-backdrop.open {
  opacity: 1;
  pointer-events: auto;
}
.drawer {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 290px;
  max-width: 85vw;
  background: var(--surface);
  border-right: 1px solid var(--border);
  box-shadow: 8px 0 32px rgba(0, 0, 0, 0.5);
  z-index: 650;
  display: flex;
  flex-direction: column;
  padding: 16px 14px;
  gap: 12px;
  transform: translateX(-100%);
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  box-sizing: border-box;
}
.drawer.open {
  transform: translateX(0);
}
.drawer-hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.drawer-title {
  font-size: 14px;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.2px;
}
.drawer-close {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  padding: 6px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}
.drawer-close:hover {
  background: var(--surface-active);
  color: var(--text);
}
.drawer-menu {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  flex: 1;
}
.drawer-section-label {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: var(--text-dim);
  padding: 6px 4px 2px;
}
.drawer-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 9px;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.15s ease;
  width: 100%;
  box-sizing: border-box;
  font-family: inherit;
}
.drawer-btn:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
  color: var(--accent);
}

/* Donation Goal Bar (pengu.uk top bar) */
.goal-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;
}
.goal-card:hover {
  border-color: var(--border-focus);
  transform: translateY(-1px);
}
.goal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13.5px;
  font-weight: 700;
  gap: 10px;
}
.goal-text { color: var(--text); font-weight: 700; }
.goal-pct { color: #f43f5e; font-weight: 800; font-size: 13px; }
.goal-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.goal-track {
  flex: 1;
  height: 8px;
  background: rgba(148, 163, 184, 0.15);
  border-radius: 999px;
  overflow: hidden;
}
.goal-fill {
  height: 100%;
  background: linear-gradient(90deg, #f43f5e 0%, var(--accent) 100%);
  border-radius: 999px;
  transition: width 0.35s ease;
}
.goal-heart-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: rgba(244, 63, 94, 0.12);
  border: 1px solid rgba(244, 63, 94, 0.28);
  color: #f43f5e;
  flex-shrink: 0;
  transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
  box-shadow: 0 1px 4px rgba(244, 63, 94, 0.15);
}
.goal-card:hover .goal-heart-btn {
  transform: scale(1.18);
  background: rgba(244, 63, 94, 0.22);
  border-color: rgba(244, 63, 94, 0.55);
  box-shadow: 0 0 12px rgba(244, 63, 94, 0.45);
}

/* Stat Cards (3 stacked metric cards matching pengu.uk) */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
}
.stat-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
}
@media (max-width: 520px) {
  .stat-card { padding: 9px 10px; }
}
.stat-lbl {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-dim);
}
.stat-val {
  font-size: 24px;
  font-weight: 800;
  color: var(--text);
  line-height: 1.15;
}
@media (max-width: 520px) {
  .stat-val { font-size: 19px; }
}
.stat-sub {
  font-size: 11.5px;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* THE HERO CTA INSTALL CARD (Centerpiece matching pengu.uk) */
.install-hero-card {
  background: var(--surface-card);
  border: 1.5px solid var(--border);
  border-radius: var(--card-radius);
  padding: 18px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 13px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.14);
}
.install-hero-hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.install-hero-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-dim);
}
.install-mode-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--accent-glow);
  color: var(--accent);
  border: 1px solid rgba(92, 112, 214, 0.3);
}

.install-status-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.install-ready-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.install-sync-status {
  font-size: 12px;
  font-weight: 600;
  color: var(--green);
  display: flex;
  align-items: center;
  gap: 5px;
}

/* THE MASSIVE PRIMARY CTA BUTTON */
.btn-hero-install {
  width: 100%;
  height: 48px;
  background: var(--accent);
  color: #ffffff !important;
  font-size: 15px;
  font-weight: 700;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-decoration: none;
  transition: all 0.18s ease;
  box-shadow: 0 4px 16px var(--accent-glow);
}
.btn-hero-install:hover {
  background: var(--accent-hover);
  transform: translateY(-1px);
  box-shadow: 0 6px 22px var(--accent-glow);
}
.btn-hero-install:active { transform: translateY(0); }

/* SECONDARY BUTTON ROW: Copy Addon URL (Wide) + Let Devs Choose Button on side */
.install-btn-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.btn-hero-copy {
  flex: 1;
  height: 42px;
  background: var(--surface);
  border: 1.5px solid var(--border);
  border-radius: 9px;
  color: var(--text);
  font-size: 13.5px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  transition: all 0.15s ease;
}
.btn-hero-copy:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
}
.btn-clear {
  flex: 0 0 auto;
  min-width: 120px;
  padding: 0 12px;
  height: 42px;
  background: var(--surface);
  border: 1.5px solid var(--border);
  border-radius: 9px;
  color: var(--text-sub);
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.btn-clear:hover {
  background: var(--surface-active);
  color: var(--accent);
  border-color: var(--accent);
}

/* Exclude Catalogs Switch Card */
.catalog-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  padding: 12px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.catalog-title {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--text);
  display: flex;
  align-items: center;
  gap: 6px;
}
.catalog-desc {
  font-size: 11.5px;
  color: var(--text-dim);
  margin-top: 2px;
}
.switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
  flex-shrink: 0;
}
.switch input { opacity: 0; width: 0; height: 0; }
.slider {
  position: absolute;
  cursor: pointer;
  inset: 0;
  background-color: var(--code-bg);
  border: 1.5px solid var(--border);
  transition: 0.2s;
  border-radius: 999px;
}
.slider:before {
  position: absolute;
  content: "";
  height: 14px;
  width: 14px;
  left: 2px;
  bottom: 2.5px;
  background-color: var(--text-dim);
  transition: 0.2s;
  border-radius: 50%;
}
input:checked + .slider {
  background-color: var(--accent);
  border-color: var(--accent);
}
input:checked + .slider:before {
  transform: translateX(18px);
  background-color: #ffffff;
}

/* SOURCES SECTION (Image 2 style) */
.sources-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}
.sources-section-hdr {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.sources-big-title {
  font-size: 20px;
  font-weight: 800;
  color: var(--text);
  letter-spacing: -0.3px;
}
.sources-subtitle {
  font-size: 12.5px;
  color: var(--text-dim);
}
.sources-actions-row {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.sources-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-sub);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  padding: 5px 11px;
  border-radius: 7px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
  line-height: 1.2;
}
.sources-btn:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
  color: var(--text);
}
.sources-btn:active {
  transform: scale(0.97);
}
.sources-btn.primary {
  border-color: rgba(99, 102, 241, 0.35);
  color: var(--accent);
}
.sources-btn.primary:hover {
  background: var(--accent-glow);
  border-color: var(--accent);
  color: var(--accent);
}
.sources-btn.selected-btn {
  background: var(--surface);
  border-color: rgba(99, 102, 241, 0.3);
  color: var(--text);
}
.sources-btn.selected-btn b {
  color: var(--accent);
  font-weight: 800;
}
.sources-btn.selected-btn.all-active {
  background: var(--accent-glow);
  border-color: var(--accent);
  color: var(--accent);
}

/* Providers Wrapper Card */
.providers-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.providers-top-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  gap: 8px;
  flex-wrap: wrap;
}
.providers-sub-info {
  font-size: 11px;
  color: var(--text-dim);
  font-weight: 500;
  text-transform: none;
  letter-spacing: normal;
}
.providers-sub-info b {
  color: var(--text-sub);
}
.providers-count-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  color: var(--green);
  background: var(--green-bg);
  border: 1px solid rgba(16, 185, 129, 0.25);
  padding: 3px 9px;
  border-radius: 999px;
  text-transform: none;
  transition: all 0.15s ease;
}
.count-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

/* Presets Pill Bar (Wrapped, No Horizontal Scroll) */
.presets-pill-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 2px;
}
.preset-pill {
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-sub);
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 999px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.preset-pill:hover {
  background: var(--surface-active);
  color: var(--text);
  border-color: var(--border-focus);
}
.preset-pill.active {
  background: var(--accent-glow);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 700;
}

/* Filter controls */
.filters-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-select {
  flex: 1;
  min-width: 130px;
  height: 33px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  padding: 0 10px;
  outline: none;
  cursor: pointer;
}
.filter-select:focus { border-color: var(--accent); }

.search-input-wrap {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
}
.search-icon-svg {
  position: absolute;
  left: 10px;
  color: var(--text-dim);
  pointer-events: none;
}
.search-input {
  width: 100%;
  font-size: 12.5px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  padding: 7px 10px 7px 32px;
  outline: none;
}
.search-input:focus { border-color: var(--accent); }

/* Sources — responsive auto-fill grid */
.sources-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 6px;
  width: 100%;
}

/* Individual Provider Card - horizontal list row */
.p-card {
  background: var(--surface);
  border: 1.5px solid var(--border);
  border-radius: 12px;
  padding: 10px 14px;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  user-select: none;
  min-width: 0;
}
.p-card:hover {
  border-color: var(--border-focus);
  background: var(--surface-hover, var(--surface));
}
.p-card.selected {
  border-color: var(--accent);
  background: var(--surface-active);
  box-shadow: 0 2px 10px var(--accent-glow);
}
/* Left icon block */
.p-card-icon-col {
  flex-shrink: 0;
}
/* Middle info block */
.p-card-info-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.p-top-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.p-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.p-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  object-fit: contain;
  flex-shrink: 0;
  background: var(--bg2);
}
.p-icon-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

/* Selection circle on the right (like pengu.uk) */
.p-chk-circle {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1.5px solid var(--border-focus);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 20px;
  transition: all 0.12s ease;
  background: transparent;
  color: transparent;
  font-size: 10px;
  font-weight: 800;
}
.p-card.selected .p-chk-circle {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

/* Card metadata line (like 4K · 1080p · Wide-Library) */
.p-meta-line {
  font-size: 10.5px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.p-desc-line {
  font-size: 11px;
  color: var(--text-dim);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 1px;
  opacity: 0.75;
}
.p-res-highlight {
  color: var(--cyan);
  font-weight: 700;
}
.p-repo-badge {
  color: var(--text-sub);
}
.p-lang-badge {
  color: var(--amber);
  font-weight: 700;
  text-transform: uppercase;
}

/* Per-Provider Catalog Controls */
.p-top-right {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.p-cat-pill {
  font-family: inherit;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.3px;
  padding: 2px 6px;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1;
  transition: all 0.15s ease;
  user-select: none;
  min-height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
}
.p-cat-pill.on {
  background: var(--green-bg);
  color: var(--green);
  border-color: rgba(16, 185, 129, 0.3);
}
.p-cat-pill.on:hover {
  background: rgba(16, 185, 129, 0.25);
  border-color: var(--green);
}
.p-cat-pill.off {
  background: rgba(245, 158, 11, 0.15);
  color: var(--amber);
  border-color: rgba(245, 158, 11, 0.35);
}
.p-cat-pill.off:hover {
  background: rgba(245, 158, 11, 0.25);
  border-color: var(--amber);
}
.p-cat-pill.disabled {
  background: var(--surface-active);
  color: var(--text-dim);
  border-color: var(--border);
  cursor: not-allowed;
  opacity: 0.6;
}

/* Repos & Credits Modals */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 400;
  padding: 16px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease;
}
.modal-overlay.open { opacity: 1; pointer-events: auto; }
.modal-box {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  width: 100%;
  max-width: 480px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 85vh;
  overflow-y: auto;
}
.modal-hdr { display: flex; align-items: center; justify-content: space-between; }
.modal-title { font-size: 15px; font-weight: 700; color: var(--text); }
.modal-close { background: none; border: none; color: var(--text-dim); cursor: pointer; padding: 4px; display: flex; }
.repos-list { display: flex; flex-direction: column; gap: 8px; }
.repo-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 9px 11px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

/* Authors & Open Source Credits Hub */
.credits-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.author-card {
  background: var(--surface-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color 0.15s ease;
}
.author-card:hover {
  border-color: var(--border-focus);
}
.author-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.author-info-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.author-avatar {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  object-fit: cover;
  background: var(--surface-active);
  flex-shrink: 0;
  border: 1px solid var(--border);
}
.author-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.author-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--accent-glow);
  color: var(--accent);
  border: 1px solid rgba(139, 92, 246, 0.3);
  flex-shrink: 0;
}
.author-desc {
  font-size: 11.5px;
  color: var(--text-dim);
  line-height: 1.4;
}
.author-links-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.author-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  padding: 4px 9px;
  border-radius: 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-sub);
  text-decoration: none;
  transition: all 0.15s ease;
}
.author-link-btn:hover {
  background: var(--surface-active);
  border-color: var(--border-focus);
  color: var(--text);
}
.author-link-btn.gh:hover { color: #f1f4fa; border-color: #64748b; background: rgba(255, 255, 255, 0.08); }
.author-link-btn.dc { color: #5865f2; border-color: rgba(88, 101, 242, 0.28); background: rgba(88, 101, 242, 0.06); }
.author-link-btn.dc:hover { color: #fff; border-color: #5865f2; background: #5865f2; }
.author-link-btn.tg { color: #229ed9; border-color: rgba(34, 158, 217, 0.28); background: rgba(34, 158, 217, 0.06); }
.author-link-btn.tg:hover { color: #fff; border-color: #229ed9; background: #229ed9; }
.author-link-btn.heart { color: #f43f5e; border-color: rgba(244, 63, 94, 0.28); background: rgba(244, 63, 94, 0.05); }
.author-link-btn.heart:hover { color: #fff; border-color: #f43f5e; background: #f43f5e; }

/* Profile switcher + user formatter cards */
.u-card { background: var(--surface-card); border: 1px solid var(--border); border-radius: var(--card-radius); padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; }
.u-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.u-select, .u-input { background: var(--surface); border: 1.5px solid var(--border); border-radius: 7px; color: var(--text); font-size: 12.5px; font-weight: 600; padding: 6px 10px; outline: none; }
.u-select { cursor: pointer; min-width: 160px; }
.u-btn { background: var(--surface); border: 1.5px solid var(--border); border-radius: 7px; color: var(--text); font-size: 12px; font-weight: 700; padding: 6px 11px; cursor: pointer; }
.u-btn:hover { border-color: var(--accent); }
.u-btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
.u-btn.danger:hover { border-color: #f43f5e; color: #f43f5e; }
.u-tpl { width: 100%; box-sizing: border-box; background: var(--surface); border: 1.5px solid var(--border); border-radius: 8px; color: var(--text); font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; padding: 8px 10px; resize: vertical; white-space: pre; overflow-x: auto; outline: none; }
.u-tpl:focus, .u-input:focus, .u-select:focus { border-color: var(--accent); }
.u-label { font-size: 11px; font-weight: 700; color: var(--text-sub); text-transform: uppercase; letter-spacing: .03em; }
.u-sample { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 9px 11px; }
.u-sample-kind { font-size: 10px; color: var(--text-sub); text-transform: uppercase; letter-spacing: .04em; margin-bottom: 4px; }
.u-sample-name { font-weight: 700; font-size: 12.5px; white-space: pre-wrap; word-break: break-word; margin-bottom: 4px; }
.u-sample-desc { font-size: 12px; white-space: pre-wrap; word-break: break-word; opacity: .85; }
.u-vars { display: flex; flex-wrap: wrap; gap: 5px; }
.u-vars code { cursor: pointer; font-size: 11px; padding: 2px 6px; border-radius: 5px; background: var(--surface); border: 1px solid var(--border); }
.u-vars code:hover { border-color: var(--accent); }
.u-err { color: #f43f5e; background: rgba(244, 63, 94, 0.08); border-radius: 7px; padding: 7px 10px; font-size: 12px; white-space: pre-wrap; }
.u-tag { font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px; background: var(--green-bg); color: var(--green); border: 1px solid rgba(16,185,129,0.25); }

/* Toast */
.toast {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%) translateY(20px);
  background: var(--surface-card);
  border: 1px solid var(--border-focus);
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  padding: 8px 16px;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  opacity: 0;
  pointer-events: none;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 500;
  white-space: nowrap;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
.empty { grid-column: 1 / -1; text-align: center; padding: 2rem 1rem; color: var(--text-dim); font-size: 12.5px; }

/* Page Footer */
.page-footer {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-dim);
  text-align: center;
  flex-wrap: wrap;
}
.footer-author-link { color: var(--text); font-weight: 600; text-decoration: none; border-bottom: 1px dashed var(--accent); }
</style>
</head>
<body>

<div class="container">
  <!-- HEADER -->
  <header class="hdr">
    <div class="hdr-left">
      <button class="btn-icon-hdr btn-burger" onclick="toggleDrawer(true)" title="Menu &amp; pages">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
      </button>
      <div class="brand-wrap" onclick="showView('home')" style="cursor:pointer;">
        <div class="logo-box">
          <img src="/logo.png" alt="CNCVerse" style="width:32px;height:32px;border-radius:8px;object-fit:contain;" onerror="this.style.display='none'">
        </div>
        <h1 class="brand-title">CNCVerse Bridge</h1>
      </div>
    </div>
    <div class="hdr-actions">
      <button class="hdr-profile" id="hdr-profile" onclick="showView('profiles')" title="Active profile — switch or manage profiles">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
        <span id="hdr-profile-name">Profile</span>
      </button>
    </div>
  </header>

  <!-- DONATION GOAL BAR (pengu.uk top bar - click to donate) -->
  <a class="goal-card" id="goal-card" data-view="home" title="Click to view &amp; support CNCVerse Community Goal" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
    <div class="goal-top">
      <div class="goal-text" id="goal-text">&#36;0 raised of &#36;100 goal</div>
      <div class="goal-pct" id="goal-pct">0%</div>
    </div>
    <div class="goal-bar-row">
      <div class="goal-track">
        <div class="goal-fill" id="goal-fill" style="width: 0%;"></div>
      </div>
      <span class="goal-heart-btn" title="Support CNCVerse Community Goal">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
      </span>
    </div>
  </a>

  <!-- 4 METRIC STATS CARDS (REPOSITORIES, SOURCES, QUALITIES, SYNC) -->
  <div class="stats-grid" data-view="home">
    <div class="stat-card" onclick="openReposModal()" style="cursor:pointer;" title="Click to view installed repositories &amp; sources">
      <div class="stat-lbl">REPOSITORIES</div>
      <div class="stat-val" id="st-repos-count">0</div>
      <div class="stat-sub">Installed Repos</div>
    </div>
    <div class="stat-card" onclick="scrollToSources()" style="cursor:pointer;" title="Click to view &amp; customize Sources">
      <div class="stat-lbl">SOURCES</div>
      <div class="stat-val" id="st-stream-count">0</div>
      <div class="stat-sub" id="st-total-count">0 available</div>
    </div>
    <div class="stat-card">
      <div class="stat-lbl">QUALITIES</div>
      <div class="stat-val">4K &rarr; 360p</div>
      <div class="stat-sub">Ultra HD to SD</div>
    </div>
    <div class="stat-card">
      <div class="stat-lbl">SYNC</div>
      <div class="stat-val" id="stat-mode-val">Dev Choice</div>
      <div class="stat-sub" id="stat-sync-sub">Auto-sync on</div>
    </div>
  </div>

  <!-- PROFILE SWITCHER (several profiles on one device, each its own addon) -->
  <div class="u-card" id="profile-card" data-view="profiles">
    <div class="u-row" style="justify-content:space-between;">
      <div>
        <div class="catalog-title" style="font-size:14px;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
          <span>Profiles</span>
          <span class="u-tag" id="profile-count-tag">1 profile</span>
        </div>
        <div class="catalog-desc">Each profile is its own addon with its own sources, catalogs, quality and formatter. Install several side by side in Stremio.</div>
      </div>
    </div>
    <div class="u-row">
      <select class="u-select" id="profile-select" onchange="switchProfile(this.value)" title="Switch profile"></select>
      <button class="u-btn" onclick="createProfile()">+ New</button>
      <button class="u-btn" onclick="renameProfile()">Rename</button>
      <button class="u-btn danger" onclick="removeProfile()">Remove</button>
    </div>
  </div>

  <!-- THE MAIN HERO INSTALL CARD (No manifest url displayed, embedded inside buttons) -->
  <div class="install-hero-card" data-view="home">
    <div class="install-hero-hdr">
      <div class="install-hero-tag">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="6 3 20 12 6 21 6 3"/></svg>
        <span>INSTALL CNCVERSE BRIDGE</span>
      </div>
      <span class="install-mode-badge" id="addon-mode-badge">Dev Choice (Default)</span>
    </div>

    <div class="install-status-row">
      <div class="install-ready-title" id="install-ready-title">Developer's choice ready to install.</div>
      <div class="install-sync-status">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
        <span id="sync-status-text">All changes auto-sync</span>
      </div>
    </div>

    <!-- MAIN CTA BUTTON -->
    <a id="install-btn" class="btn-hero-install" href="#">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="6 3 20 12 6 21 6 3"/></svg>
      <span id="install-btn-text">Install CNCVerse Addon</span>
    </a>

    <!-- SECONDARY BUTTON ROW: Copy Addon URL -->
    <div class="install-btn-row">
      <button class="btn-hero-copy" onclick="copyCurrentManifest(this)" title="Copy manifest URL to clipboard">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
        <span class="copy-lbl">Copy Addon URL</span>
      </button>
    </div>
  </div>

  <!-- SHOW CATALOGS SWITCH CARD (Positive logic & Clear Phrasing) -->
  <div class="catalog-card" data-view="home">
    <div>
      <div class="catalog-title">
        <span>Show Catalogs in Stremio</span>
        <span id="catalog-mode-tag" style="font-size:10px; font-weight:700; padding:1px 6px; border-radius:4px; background:var(--green-bg); color:var(--green); border:1px solid rgba(16,185,129,0.25);">Catalogs Active</span>
      </div>
      <div class="catalog-desc">
        Enable discover rows &amp; catalog shelves in Stremio. Turn off for Streams Only mode (faster loading).
      </div>
    </div>
    <label class="switch" title="Toggle Stremio catalogs">
      <input type="checkbox" id="chk-enable-catalogs" checked onchange="toggleEnableCatalogs(this.checked)">
      <span class="slider"></span>
    </label>
  </div>

  <!-- STREAM QUALITY PREFERENCES CARD -->
  <div class="quality-card" data-view="quality" style="background:var(--surface-card); border:1px solid var(--border); border-radius:var(--card-radius); padding:14px 16px; display:flex; flex-direction:column; gap:12px;">
    <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px;">
      <div>
        <div class="catalog-title" style="font-size:14px;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>
          <span>Stream Quality Filters</span>
          <span id="quality-filter-tag" style="font-size:10px; font-weight:700; padding:1px 6px; border-radius:4px; background:var(--green-bg); color:var(--green); border:1px solid rgba(16,185,129,0.25);">All Qualities</span>
        </div>
        <div class="catalog-desc">
          Choose which video qualities Stremio will display. Unchecked qualities will be filtered out.
        </div>
      </div>
      <div style="display:flex; align-items:center; gap:8px;">
        <span style="font-size:12px; font-weight:700; color:var(--text-sub);">Max per tier:</span>
        <select id="sel-max-streams" onchange="onQualityChange()" style="background:var(--surface); border:1.5px solid var(--border); border-radius:7px; color:var(--text); font-size:12px; font-weight:600; padding:4px 8px; outline:none; cursor:pointer;">
          <option value="0">All streams</option>
          <option value="1">1 link (Fastest)</option>
          <option value="2">2 links (Clean)</option>
          <option value="3">3 links</option>
          <option value="5">5 links</option>
        </select>
      </div>
    </div>

    <!-- Quality Checkbox Pills -->
    <div style="display:flex; flex-wrap:wrap; gap:8px;">
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--text); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-2160p" value="2160p" checked onchange="onQualityChange()" style="accent-color:var(--accent); cursor:pointer;">
        <span>4K (2160p)</span>
      </label>
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--text); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-1080p" value="1080p" checked onchange="onQualityChange()" style="accent-color:var(--accent); cursor:pointer;">
        <span>1080p FHD</span>
      </label>
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--text); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-720p" value="720p" checked onchange="onQualityChange()" style="accent-color:var(--accent); cursor:pointer;">
        <span>720p HD</span>
      </label>
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--text); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-480p" value="480p" checked onchange="onQualityChange()" style="accent-color:var(--accent); cursor:pointer;">
        <span>480p / SD</span>
      </label>
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--text); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-360p" value="360p" checked onchange="onQualityChange()" style="accent-color:var(--accent); cursor:pointer;">
        <span>360p</span>
      </label>
      <label style="cursor:pointer; display:inline-flex; align-items:center; gap:6px; background:var(--surface); border:1.5px solid var(--border); border-radius:8px; padding:6px 12px; font-size:12.5px; font-weight:700; color:var(--red); user-select:none; transition:all 0.15s ease;">
        <input type="checkbox" id="q-exclude-cam" checked onchange="onQualityChange()" style="accent-color:var(--red); cursor:pointer;">
        <span>Block CAM / Screeners</span>
      </label>
    </div>
  </div>

  <!-- FILTERING & PLAYBACK (per profile) -->
  <div class="u-card" id="playback-card" data-view="quality">
    <div>
      <div class="catalog-title" style="font-size:14px;"><span>Filtering &amp; Playback</span></div>
      <div class="catalog-desc">File size limits, subtitles, grouping, sorting and provider order for this profile. Streams whose size is not known are never hidden by the size limits.</div>
    </div>
    <div style="display:flex;flex-wrap:wrap;gap:14px;align-items:flex-end;">
      <label style="display:flex;flex-direction:column;gap:4px;font-size:11px;font-weight:700;color:var(--text-sub);">Min size (GB)
        <input id="pb-min-gb" type="number" min="0" step="0.1" placeholder="Any" onchange="onPlaybackChange()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12.5px;font-weight:600;padding:6px 9px;outline:none;width:110px;">
      </label>
      <label style="display:flex;flex-direction:column;gap:4px;font-size:11px;font-weight:700;color:var(--text-sub);">Max size (GB)
        <input id="pb-max-gb" type="number" min="0" step="0.1" placeholder="Any" onchange="onPlaybackChange()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12.5px;font-weight:600;padding:6px 9px;outline:none;width:110px;">
      </label>
      <label style="display:flex;flex-direction:column;gap:4px;font-size:11px;font-weight:700;color:var(--text-sub);">Group streams by
        <select id="pb-group" onchange="onPlaybackChange()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12.5px;font-weight:600;padding:6px 9px;outline:none;">
          <option value="default">Default</option>
          <option value="provider">Provider</option>
          <option value="quality">Quality</option>
        </select>
      </label>
      <label style="display:flex;flex-direction:column;gap:4px;font-size:11px;font-weight:700;color:var(--text-sub);">Sort streams
        <select id="pb-sort" onchange="onPlaybackChange()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12.5px;font-weight:600;padding:6px 9px;outline:none;">
          <option value="default">Default</option>
          <option value="size">Size (largest first)</option>
        </select>
      </label>
      <label style="cursor:pointer;display:inline-flex;align-items:center;gap:6px;background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12.5px;font-weight:600;padding:6px 9px;outline:none;">
        <input type="checkbox" id="pb-hide-subs" onchange="onPlaybackChange()" style="accent-color:var(--accent);cursor:pointer;">
        <span>Hide subtitles</span>
      </label>
    </div>
    <div>
      <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;">
        <div class="catalog-title" style="font-size:13px;"><span>Provider order</span></div>
        <button onclick="resetProviderOrder()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12px;font-weight:700;padding:5px 11px;cursor:pointer;">Reset</button>
      </div>
      <div class="catalog-desc">Inside each quality group, providers appear in this order. Only your switched-on movie &amp; series sources are listed.</div>
      <div id="provider-order" style="display:flex;flex-direction:column;gap:6px;margin-top:8px;max-height:340px;overflow:auto;"></div>
    </div>
  </div>

  <!-- SOURCE STATUS (live health of the sources) -->
  <div class="u-card" id="status-card" data-view="status">
    <div class="u-row" style="justify-content:space-between;gap:8px;">
      <div>
        <div class="catalog-title" style="font-size:14px;"><span>Source status</span></div>
        <div class="catalog-desc">Live health of your sources, from the server's own checks.</div>
      </div>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
        <label style="cursor:pointer;display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:700;color:var(--text-sub);">
          <input type="checkbox" id="status-all" onchange="renderStatusView()" style="accent-color:var(--accent);cursor:pointer;">
          <span>All installed sources</span>
        </label>
        <button onclick="loadStatusView()" style="background:var(--surface);border:1.5px solid var(--border);border-radius:7px;color:var(--text);font-size:12px;font-weight:700;padding:5px 11px;cursor:pointer;">Refresh</button>
      </div>
    </div>
    <div id="status-summary" style="display:flex;gap:10px;flex-wrap:wrap;"></div>
    <div id="status-filters" style="display:flex;gap:6px;flex-wrap:wrap;"></div>
    <div id="status-list" style="display:flex;flex-direction:column;gap:6px;"></div>
  </div>

  <!-- USER STREAM FORMATTER (per profile) -->
  <div class="u-card" id="formatter-card" data-view="formatter">
    <div class="u-row" style="justify-content:space-between;">
      <div>
        <div class="catalog-title" style="font-size:14px;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/></svg>
          <span>Stream Formatter</span>
          <span class="u-tag" id="fmt-mode-tag">Server default</span>
        </div>
        <div class="catalog-desc">Choose how stream names and details look in Stremio for this profile &mdash; pick a preset or write your own.</div>
      </div>
    </div>
    <div class="u-row">
      <select class="u-select" id="ufmt-mode" onchange="onUserFormatterModeChange()"></select>
      <button class="u-btn primary" onclick="saveUserFormatter()">Save</button>
      <span id="ufmt-status" style="font-size:12px;"></span>
    </div>
    <div id="ufmt-custom" style="display:none; flex-direction:column; gap:8px;">
      <div class="u-row">
        <span class="u-label">Start from</span>
        <select class="u-select" id="ufmt-start" onchange="copyPresetIntoCustom(this.value)"></select>
      </div>
      <label class="u-label" for="ufmt-name">Name template</label>
      <textarea class="u-tpl" id="ufmt-name" rows="2" spellcheck="false" oninput="scheduleUserFormatterPreview()"></textarea>
      <label class="u-label" for="ufmt-desc">Description template <span style="text-transform:none;font-weight:500;">(blank lines are removed)</span></label>
      <textarea class="u-tpl" id="ufmt-desc" rows="7" spellcheck="false" oninput="scheduleUserFormatterPreview()"></textarea>
      <details>
        <summary style="cursor:pointer;font-size:12px;font-weight:700;color:var(--text-sub);">Variables &amp; syntax</summary>
        <div class="u-vars" id="ufmt-vars" style="margin-top:8px;"></div>
        <div style="font-size:11.5px;line-height:1.7;color:var(--text-sub);margin-top:8px;">
          Click a variable to insert it. Modifiers chain with <code>::</code> &mdash; exists, length, join('sep'), default('text'), replace('a','b'), upper, lower, title, truncate(n), bytes2.
          Conditions: <code>{stream.resolution::=2160p["4K"||"HD"]}</code>. Compare with = != &gt; &gt;= &lt; &lt;= ~ (contains).
        </div>
      </details>
    </div>
    <div>
      <div class="u-label" style="margin-bottom:6px;">Preview</div>
      <div id="ufmt-preview" style="display:flex; flex-direction:column; gap:6px;"></div>
    </div>
  </div>

  <!-- SOURCES SECTION -->
  <div class="sources-section" id="sources-section" data-view="home">
    <div class="providers-card">
      <div class="providers-top-line">
        <div>
          <h2 class="sources-big-title" style="font-size:16px; margin:0 0 2px 0; display:flex; align-items:center; gap:7px;">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
            <span>Sources &amp; Providers</span>
          </h2>
          <p class="sources-subtitle" style="margin:0; font-size:11.5px;">Select providers and resolutions. Presets configure sources instantly.</p>
        </div>
        <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
          <div class="providers-count-pill" id="providers-count-pill">
            <span class="count-dot"></span>
            <span id="providers-count-label">0 of 0 active</span>
          </div>
          <button class="sources-btn" id="btn-select-all" onclick="toggleSelectAll()" title="Toggle selection of all sources">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            <span>Toggle All</span>
          </button>
          <button class="sources-btn" onclick="setAll(false)" title="Clear all selected providers">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            <span>Clear All</span>
          </button>
        </div>
      </div>

      <!-- Presets Pill Bar (Wrapped, No Horizontal Scroll) -->
      <div class="presets-pill-bar" id="presets-row">
        <button class="preset-pill active" data-preset="dev_choice" onclick="applyPreset('dev_choice', this)">Developer's Choice</button>
        <button class="preset-pill" data-preset="all" onclick="applyPreset('all', this)">All Sources</button>
        <button class="preset-pill" data-preset="movies_tv" onclick="applyPreset('movies_tv', this)">Movies &amp; Series</button>
        <button class="preset-pill" data-preset="live_tv" onclick="applyPreset('live_tv', this)">Live TV</button>
        <button class="preset-pill" data-preset="live_sports" onclick="applyPreset('live_sports', this)">Live Sports</button>
        <button class="preset-pill" data-preset="anime" onclick="applyPreset('anime', this)">Anime</button>
      </div>

      <!-- Filter Dropdowns (Scales dynamically to any number of repos, types & languages) -->
      <div class="filters-row">
        <select class="filter-select" id="repo-filter" onchange="onRepoFilterChange(this.value)">
          <option value="">All Repositories</option>
        </select>
        <select class="filter-select" id="type-filter" onchange="onTypeFilterChange(this.value)">
          <option value="">All Content Types</option>
        </select>
        <label class="dead-toggle" title="Dead = tested directly and through a proxy, no links (re-checked every night)"><input type="checkbox" id="hide-dead" onchange="onHideDeadChange(this.checked)"> Hide dead <span id="dead-count" class="hb hb-dead" style="display:none"></span></label>
        <select class="filter-select" id="lang-filter" onchange="onLangFilterChange(this.value)">
          <option value="">All Languages</option>
        </select>
      </div>

      <!-- Real-time Search Input -->
      <div class="search-input-wrap">
        <svg class="search-icon-svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" class="search-input" id="ext-search" placeholder="Search providers by name, repo, language, or tags..." oninput="renderCards()">
      </div>

      <!-- 2-Column Sources Grid (Strict 2 columns on mobile matching pengu.uk) -->
      <div class="sources-grid" id="sources-grid">
        <div class="empty">Loading provider sources...</div>
      </div>
    </div>
  </div>

  <!-- FOOTER -->
  <footer class="page-footer" id="page-footer" style="${if (getFooterCredits().isEmpty()) "display:none;" else ""}">
    ${buildFooterHtml()}
  </footer>
</div>

<!-- INSTALLED REPOSITORIES MODAL -->
<div class="modal-overlay" id="repos-modal" onclick="if(event.target===this)closeReposModal()">
  <div class="modal-box" style="max-width:580px;">
    <div class="modal-hdr">
      <div class="modal-title" style="display:flex;align-items:center;gap:8px;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        <span>Installed Repositories</span>
        <span id="repos-count-badge" style="font-size:11px; padding:2px 8px; border-radius:999px; background:var(--accent-glow); color:var(--accent); border:1px solid rgba(139,92,246,0.3); font-weight:700;">0 Repos</span>
      </div>
      <button class="modal-close" onclick="closeReposModal()" title="Close">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div style="font-size:12px; color:var(--text-sub); line-height:1.5; padding:10px 13px; background:rgba(139,92,246,0.06); border:1px solid rgba(139,92,246,0.18); border-radius:9px; display:flex; align-items:flex-start; gap:9px;">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="#f43f5e" style="flex-shrink:0; margin-top:2px;"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
      <div>All media streaming is powered by open-source CloudStream repository maintainers. <strong style="color:var(--text);">Please check out their original repositories and support them!</strong></div>
    </div>
    <div class="credits-list" id="repos-list">
      <!-- Dynamically filled by renderReposModal() -->
    </div>
  </div>
</div>

<!-- DEVELOPER & CONTRIBUTOR CREDITS MODAL -->
<div class="modal-overlay" id="credits-modal" onclick="if(event.target===this)closeCreditsModal()">
  <div class="modal-box" style="max-width:540px;">
    <div class="modal-hdr">
      <div class="modal-title" style="display:flex;align-items:center;gap:8px;">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        <span>Developer Credits</span>
      </div>
      <button class="modal-close" onclick="closeCreditsModal()" title="Close">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div style="font-size:12px; color:var(--text-sub); line-height:1.5; padding:10px 13px; background:rgba(139,92,246,0.06); border:1px solid rgba(139,92,246,0.18); border-radius:9px;">
      The core architects and developers behind CNCVerse Bridge.
    </div>
    <div class="credits-list" id="credits-list">
      <!-- Dynamically filled by renderCreditsModal() -->
    </div>
  </div>
</div>

<!-- SLIDE-OVER DRAWER -->
<div class="drawer-backdrop" id="drawer-backdrop" onclick="toggleDrawer(false)"></div>
<aside class="drawer" id="drawer">
  <div class="drawer-hdr">
    <div style="display:flex;align-items:center;gap:8px;">
      <div class="logo-box" style="width:28px;height:28px;">
        <img src="/logo.png" alt="CNCVerse" style="width:28px;height:28px;border-radius:6px;object-fit:contain;" onerror="this.style.display='none'">
      </div>
      <span class="drawer-title">CNCVerse Bridge</span>
    </div>
    <button class="drawer-close" onclick="toggleDrawer(false)" title="Close Menu">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
  </div>
  <div class="drawer-menu">
    <div class="drawer-section-label">PAGES</div>
    <button class="drawer-btn" data-page="home" onclick="showView('home'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
        <span>Home &amp; sources</span>
      </div>
    </button>
    <button class="drawer-btn" data-page="profiles" onclick="showView('profiles'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        <span>Profiles</span>
      </div>
    </button>
    <button class="drawer-btn" data-page="quality" onclick="showView('quality'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="15" rx="2"/><polyline points="17 2 12 7 7 2"/></svg>
        <span>Stream filters</span>
      </div>
    </button>
    <button class="drawer-btn" data-page="formatter" onclick="showView('formatter'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/></svg>
        <span>Stream formatter</span>
      </div>
    </button>

    <button class="drawer-btn" data-page="status" onclick="showView('status'); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
        <span>Source status</span>
      </div>
    </button>

    <div class="drawer-section-label" style="margin-top:10px;">COMMUNITY &amp; SOURCES</div>
    <button class="drawer-btn" onclick="openReposModal(); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        <span>Installed Repositories</span>
      </div>
    </button>
    <button class="drawer-btn" onclick="openCreditsModal(); toggleDrawer(false);">
      <div style="display:flex;align-items:center;gap:10px;color:var(--accent);">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        <span>Developer Credits</span>
      </div>
    </button>
    <a class="drawer-btn" href="https://t.me/cncverse" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 0 0-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.95-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.74-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.36.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .38z"/></svg>
        <span>Telegram Community</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
    <a class="drawer-btn" href="https://discord.gg/djuu5s2b8e" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M20.32 4.37A19.8 19.8 0 0 0 15.4 2.84a.07.07 0 0 0-.08.04c-.21.38-.45.87-.61 1.25a18.3 18.3 0 0 0-5.49 0 12.6 12.6 0 0 0-.62-1.25.08.08 0 0 0-.08-.04 19.7 19.7 0 0 0-4.92 1.53.07.07 0 0 0-.03.03C.53 9.05-.32 13.58.1 18.06a.08.08 0 0 0 .03.06 19.9 19.9 0 0 0 6 3.03.08.08 0 0 0 .08-.03c.46-.63.87-1.3 1.23-1.99a.08.08 0 0 0-.04-.11 13.1 13.1 0 0 1-1.87-.89.08.08 0 0 1 0-.13l.37-.29a.07.07 0 0 1 .08-.01c3.93 1.79 8.18 1.79 12.06 0a.07.07 0 0 1 .08.01l.37.29a.08.08 0 0 1 0 .13c-.6.35-1.22.65-1.87.89a.08.08 0 0 0-.04.11c.36.7.78 1.36 1.23 1.99a.08.08 0 0 0 .08.03 19.8 19.8 0 0 0 6.01-3.03.08.08 0 0 0 .03-.05c.5-5.18-.84-9.68-3.55-13.66a.06.06 0 0 0-.03-.03zM8.02 15.33c-1.18 0-2.16-1.09-2.16-2.42 0-1.33.96-2.42 2.16-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.33-.96 2.42-2.16 2.42zm7.97 0c-1.18 0-2.15-1.09-2.15-2.42 0-1.33.95-2.42 2.15-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.33-.95 2.42-2.16 2.42z"/></svg>
        <span>Discord Community</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
    <a class="drawer-btn" href="https://cncverse.pages.dev" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;color:#f43f5e;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
        <span style="font-weight:700;">Donate &amp; Support Goal</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>

    <div class="drawer-section-label" style="margin-top:10px;">PREFERENCES &amp; TOOLS</div>
    <button class="drawer-btn" onclick="toggleTheme();">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
        <span>Toggle Dark / Light</span>
      </div>
    </button>
    <a class="drawer-btn" href="/admin" target="_blank" rel="noopener">
      <div style="display:flex;align-items:center;gap:10px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>
        <span>Admin Panel</span>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
    </a>
  </div>
  <div style="margin-top:auto; font-size:11px; color:var(--text-dim); text-align:center; padding-top:12px; border-top:1px solid var(--border);">
    <div>CNCVerse Bridge v2.5</div>
    <div>Stremio Addon Gateway</div>
  </div>
</aside>

<div class="toast" id="toast"></div>

<script>
"use strict";

/* Theme Handling */
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

/* Page views: Home / Profiles / Quality / Formatter (hash-routed, back button works) */
var VIEWS = ["home", "profiles", "quality", "formatter", "status"];
var currentView = "home";
function showView(name, fromHash) {
  if (VIEWS.indexOf(name) < 0) name = "home";
  currentView = name;
  var nodes = document.querySelectorAll("[data-view]");
  for (var i = 0; i < nodes.length; i++) {
    nodes[i].classList.toggle("view-hidden", nodes[i].getAttribute("data-view") !== name);
  }
  var btns = document.querySelectorAll(".drawer-btn[data-page]");
  for (var j = 0; j < btns.length; j++) {
    btns[j].classList.toggle("active", btns[j].getAttribute("data-page") === name);
  }
  if (!fromHash) {
    var h = name === "home" ? "" : "#" + name;
    if ((window.location.hash || "") !== h) {
      try { history.pushState(null, "", window.location.pathname + window.location.search + h); } catch (e) { window.location.hash = h; }
    }
  }
  if (name === "status") loadStatusView();
  if (name === "quality") { try { renderProviderOrder(); } catch (e) {} }
  window.scrollTo(0, 0);
}
function viewFromHash() { return (window.location.hash || "").replace("#", "") || "home"; }
window.addEventListener("popstate", function() { showView(viewFromHash(), true); });
window.addEventListener("hashchange", function() { showView(viewFromHash(), true); });

function updateHeaderProfile() {
  var label = document.getElementById("hdr-profile-name");
  if (!label) return;
  var name = "";
  try {
    var list = readProfileList();
    for (var i = 0; i < list.length; i++) if (list[i].id === pid) name = list[i].name || list[i].id;
  } catch (e) {}
  label.textContent = name || "Profile";
}

/* Slide-Over Drawer */
function toggleDrawer(open) {
  var d = document.getElementById("drawer");
  var b = document.getElementById("drawer-backdrop");
  if (!d || !b) return;
  if (open) {
    d.classList.add("open");
    b.classList.add("open");
  } else {
    d.classList.remove("open");
    b.classList.remove("open");
  }
}

/* Per-Plugin Catalog Toggle */
function toggleCatalog(internalName) {
  ensureProfileCustomized();
  if (pData._dc.has(internalName)) {
    pData._dc.delete(internalName);
    toast("Catalog enabled for this source");
  } else {
    pData._dc.add(internalName);
    toast("Catalog hidden (Streams active)");
  }
  saveProfileState();
}

/* Profile & Storage */
var pid = (function(){
  var m = window.location.pathname.match(/\/u\/([^\/]+)/);
  if (m && m[1]) {
    localStorage.setItem("stremio_profile_id", m[1]);
    localStorage.setItem("cnc_customized_" + m[1], "true");
    return m[1];
  }
  var k = "stremio_profile_id";
  var s = localStorage.getItem(k);
  if (!s || s.length < 8) {
    s = "p_" + Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 6);
    localStorage.setItem(k, s);
  }
  return s;
})();

var exts = [];
var repos = [];
var pData = {
  profileId: pid,
  disabledExtensions: [],
  enabledExtensions: [],
  disableCatalogs: false,
  disabledCatalogs: [],
  _d: new Set(),
  _e: new Set(),
  _dc: new Set()
};

var currentRepo = "";
var currentType = "";
var currentLang = "";
var currentManifestUrl = "";

var LANG_MAP = {
  "en": "English",
  "hi": "Hindi",
  "ta": "Tamil",
  "te": "Telugu",
  "ml": "Malayalam",
  "kn": "Kannada",
  "bn": "Bengali",
  "mr": "Marathi",
  "es": "Spanish",
  "fr": "French",
  "de": "German",
  "it": "Italian",
  "pt": "Portuguese",
  "ru": "Russian",
  "ar": "Arabic",
  "ja": "Japanese",
  "ko": "Korean",
  "zh": "Chinese",
  "multi": "Multi",
  "universal": "Universal"
};

/* Unified Mode Check */
function isProfileCustomized() {
  if (localStorage.getItem("cnc_customized_" + pid) === "true") return true;
  if (pData._d.size > 0 || pData._e.size > 0 || pData._dc.size > 0 || pData.disableCatalogs) return true;
  return false;
}

function markProfileCustomized() {
  var wasCustom = isProfileCustomized();
  localStorage.setItem("cnc_customized_" + pid, "true");
  if (!wasCustom) {
    toast("Switched to Personal Profile");
  }
}

function ensureProfileCustomized() {
  if (!isProfileCustomized()) {
    markProfileCustomized();
    pData._d.clear();
    pData._e.clear();
    pData._dc.clear();
  }
}

/* A profile can have at most MAX_EXT extensions switched on (the server enforces it too). */
var MAX_EXT = 50;
function activeExtCount() { return exts.filter(function(e){ return isExtActive(e); }).length; }
function limitToast() { toast("A profile can have at most " + MAX_EXT + " extensions. Switch one off first."); }

function isExtActive(e) {
  if (!isProfileCustomized()) {
    // Default Developer's Choice: all globally enabled extensions
    return !!e.enabled;
  }
  if (e.enabled) {
    return !pData._d.has(e.internalName);
  } else {
    return pData._e.has(e.internalName);
  }
}

/* Numbered Order Map for Selected Indicators */
function getActiveOrderMap() {
  var activeList = exts.filter(function(e){ return isExtActive(e); });
  var map = {};
  for (var i = 0; i < activeList.length; i++) {
    map[activeList[i].internalName] = i + 1;
  }
  return map;
}

/* URLs & Manifests (Permanent profile URL for seamless live sync) */
function updateUrls() {
  var h = window.location.host;
  var proto = window.location.protocol;
  var isCustom = isProfileCustomized();

  var manifestPath = "/u/" + encodeURIComponent(pid) + "/manifest.json";
  currentManifestUrl = proto + "//" + h + manifestPath;
  var stremioUrl = currentManifestUrl.replace(/^https?:\/\//, "stremio://");

  var installBtn = document.getElementById("install-btn");
  if (installBtn) installBtn.href = stremioUrl;

  var installBtnText = document.getElementById("install-btn-text");
  if (installBtnText) {
    installBtnText.textContent = isCustom ? "Install Personal Addon" : "Install CNCVerse Addon";
  }

  var webBtn = document.getElementById("drawer-web-btn");
  if (webBtn) webBtn.href = "https://web.stremio.com/#/addons?addon=" + encodeURIComponent(currentManifestUrl);

  var modeBadge = document.getElementById("addon-mode-badge");
  var readyTitle = document.getElementById("install-ready-title");
  var syncStatusText = document.getElementById("sync-status-text");
  var statModeVal = document.getElementById("stat-mode-val");

  if (modeBadge) {
    if (isCustom) {
      modeBadge.textContent = "Personal Profile";
      modeBadge.style.color = "var(--green)";
      modeBadge.style.background = "var(--green-bg)";
      modeBadge.style.borderColor = "rgba(16,185,129,0.25)";
    } else {
      modeBadge.textContent = "Dev Choice (Default)";
      modeBadge.style.color = "var(--accent)";
      modeBadge.style.background = "var(--accent-glow)";
      modeBadge.style.borderColor = "rgba(139,92,246,0.3)";
    }
  }
  if (readyTitle) {
    readyTitle.textContent = isCustom ? "Personal configuration ready." : "Developer's choice ready to install.";
  }
  if (syncStatusText) {
    syncStatusText.textContent = isCustom ? "All changes auto-sync to your addon" : "Default configuration active (auto-sync enabled)";
  }
  if (statModeVal) {
    statModeVal.innerHTML = isCustom ? "Saved &#10003;" : "Dev Choice";
  }
}

function copyCurrentManifest(btn) {
  var url = currentManifestUrl || (window.location.protocol + "//" + window.location.host + "/u/" + encodeURIComponent(pid) + "/manifest.json");

  function onDone() {
    if (btn) {
      var lbl = btn.querySelector(".copy-lbl");
      if (lbl) {
        var orig = lbl.textContent;
        lbl.textContent = "Copied!";
        btn.style.color = "var(--green)";
        btn.style.borderColor = "var(--green)";
        setTimeout(function(){
          lbl.textContent = orig;
          btn.style.color = "";
          btn.style.borderColor = "";
        }, 1800);
      }
    }
    toast(isProfileCustomized() ? "Copied Personal Addon URL!" : "Copied CNCVerse Addon URL!");
  }

  function fallbackCopy(url) {
    var ta = document.createElement("textarea");
    ta.value = url; ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.focus(); ta.select();
    try { document.execCommand("copy"); onDone(); } catch(e) {}
    document.body.removeChild(ta);
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url).then(onDone).catch(function(){ fallbackCopy(url); });
  } else {
    fallbackCopy(url);
  }
}

/* Feature 3: Positive Catalogs Toggle */
function toggleEnableCatalogs(enable) {
  ensureProfileCustomized();
  pData.disableCatalogs = !enable;
  updateCatalogStatusUI();
  updateUrls();

  fetch("/api/profile/" + encodeURIComponent(pid) + "/catalogs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ disableCatalogs: !enable })
  }).then(function(r){ return r.json(); })
    .then(function(p){
      pData.disableCatalogs = p.disableCatalogs || false;
      updateCatalogStatusUI();
      updateUrls();
      toast(enable ? "Catalogs enabled in Stremio" : "Catalogs hidden (Streams Only mode)");
    })
    .catch(function(e){ toast("Error: " + e.message); });
}

function updateCatalogStatusUI() {
  var chk = document.getElementById("chk-enable-catalogs");
  if (chk) chk.checked = !pData.disableCatalogs;

  var tag = document.getElementById("catalog-mode-tag");
  if (tag) {
    if (pData.disableCatalogs) {
      tag.textContent = "Streams Only";
      tag.style.background = "var(--amber-bg)";
      tag.style.color = "var(--amber)";
      tag.style.borderColor = "rgba(245, 158, 11, 0.25)";
    } else {
      tag.textContent = "Catalogs Active";
      tag.style.background = "var(--green-bg)";
      tag.style.color = "var(--green)";
      tag.style.borderColor = "rgba(16, 185, 129, 0.25)";
    }
  }
}

/* Feature 4: Curated Presets */
/* Live-only: every type it reports is Live. ("TvSeries" contains "tv" — a substring test pulled
   ~250 movie/series extensions into the Live TV preset.) */
function isLiveOnly(e) {
  var ts = e.types || [];
  if (!ts.length) return false;
  for (var i = 0; i < ts.length; i++) {
    var t = String(ts[i]).toLowerCase();
    if (t !== "live" && t !== "livestream") return false;
  }
  return true;
}

function applyPreset(preset, btn) {
  if (preset === "dev_choice") {
    resetToDevChoice();
    return;
  }

  ensureProfileCustomized();

  var targetInternals = [];

  if (preset === "all") {
    targetInternals = exts.map(function(e){ return e.internalName; });
    toast("All sources enabled");
  } else if (preset === "live_sports") {
    targetInternals = exts.filter(function(e) {
      if (!isLiveOnly(e)) return false;
      var n = (e.name + " " + e.internalName).toLowerCase();
      return /sport|streamed|daddy|event|cric|match|football|fifa|ipl|f1|ufc|wwe/.test(n);
    }).map(function(e){ return e.internalName; });
    toast("Live Sports applied");
  } else if (preset === "live_tv") {
    targetInternals = exts.filter(function(e) { return isLiveOnly(e); }).map(function(e){ return e.internalName; });
    toast("Live TV applied");
  } else if (preset === "movies_tv") {
    targetInternals = exts.filter(function(e) {
      var types = (e.types || []).join(" ").toLowerCase();
      return types.indexOf("movie") >= 0 || types.indexOf("series") >= 0;
    }).map(function(e){ return e.internalName; });
    toast("Movies & Series applied");
  } else if (preset === "anime") {
    targetInternals = exts.filter(function(e) {
      var types = (e.types || []).join(" ").toLowerCase();
      var n = (e.name + " " + e.internalName).toLowerCase();
      return types.indexOf("anime") >= 0 || n.indexOf("anime") >= 0 || n.indexOf("gogo") >= 0 || n.indexOf("ani") >= 0;
    }).map(function(e){ return e.internalName; });
    toast("Anime applied");
  }
  if (targetInternals.length > MAX_EXT) {
    targetInternals = targetInternals.slice(0, MAX_EXT);
    toast("Limited to " + MAX_EXT + " extensions per profile");
  }

  pData._d.clear();
  pData._e.clear();

  exts.forEach(function(e){
    var want = targetInternals.indexOf(e.internalName) >= 0;
    if (e.enabled) {
      if (!want) pData._d.add(e.internalName);
    } else {
      if (want) pData._e.add(e.internalName);
    }
  });

  var buttons = document.querySelectorAll("#presets-row .preset-pill");
  buttons.forEach(function(b){ b.classList.remove("active"); });
  if (btn) btn.classList.add("active");

  saveProfileState();
}

function resetToDevChoice() {
  localStorage.removeItem("cnc_customized_" + pid);
  pData._d.clear();
  pData._e.clear();
  pData._dc.clear();
  pData.disableCatalogs = false;

  var buttons = document.querySelectorAll("#presets-row .preset-pill");
  buttons.forEach(function(b){ b.classList.remove("active"); });
  var devBtn = document.querySelector('[data-preset="dev_choice"]');
  currentRepo = "";
  currentType = "";
  currentLang = "";
  var rSel = document.getElementById("repo-filter"); if (rSel) rSel.value = "";
  var tSel = document.getElementById("type-filter"); if (tSel) tSel.value = "";
  var lSel = document.getElementById("lang-filter"); if (lSel) lSel.value = "";
  var sInp = document.getElementById("ext-search"); if (sInp) sInp.value = "";

  updateCatalogStatusUI();
  updateMetrics();
  renderCards();
  updateUrls();

  toast("Reset to Developer's Choice");

  // Clear profile overrides on server
  fetch("/api/profile/" + encodeURIComponent(pid) + "/set", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ disabledExtensions: [], enabledExtensions: [], disabledCatalogs: [], disableCatalogs: false })
  }).catch(function(){});
}

function onRepoFilterChange(val) {
  currentRepo = val || "";
  renderCards();
}

function onTypeFilterChange(val) {
  currentType = val || "";
  renderCards();
}

function onLangFilterChange(val) {
  currentLang = val || "";
  renderCards();
}

function setAll(enable) {
  ensureProfileCustomized();
  var matching = getFilteredExtensions();
  var changed = 0, skipped = 0;
  var free = MAX_EXT - activeExtCount();
  matching.forEach(function(e){
    if (enable) {
      if (isExtActive(e)) return;
      if (free <= 0) { skipped++; return; } // the per-profile limit
      pData._d.delete(e.internalName);
      if (!e.enabled) pData._e.add(e.internalName);
      free--;
      changed++;
    } else {
      pData._e.delete(e.internalName);
      if (e.enabled) pData._d.add(e.internalName);
      changed++;
    }
  });
  var devBtn = document.querySelector('[data-preset="dev_choice"]');
  if (devBtn) devBtn.classList.remove("active");
  if (enable && skipped > 0) {
    toast("Enabled " + changed + " — limit of " + MAX_EXT + " extensions per profile reached");
  } else {
    toast(enable ? ("Enabled " + changed + " sources") : ("Cleared " + changed + " sources"));
  }
  saveProfileState();
}

function toggleSelectAll() {
  var matching = getFilteredExtensions();
  if (!matching.length) return;
  var allSelected = matching.every(function(e){ return isExtActive(e); });
  setAll(!allSelected);
}

function toggleCard(name) {
  name = decodeURIComponent(name.replace(/%27/g, "'"));
  var ext = exts.find(function(e){ return e.internalName === name; });
  if (!ext) return;

  ensureProfileCustomized();

  var willBeActive = !isExtActive(ext);
  if (willBeActive && activeExtCount() >= MAX_EXT) { limitToast(); return; }
  if (ext.enabled) {
    if (willBeActive) pData._d.delete(name); else pData._d.add(name);
  } else {
    if (willBeActive) pData._e.add(name); else pData._e.delete(name);
  }

  var devBtn = document.querySelector('[data-preset="dev_choice"]');
  if (devBtn) devBtn.classList.remove("active");

  renderCards();
  updateMetrics();
  updateUrls();
  saveProfileDebounced();
}

var saveTimer = null;
function saveProfileDebounced() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(function(){ saveProfileState(); }, 350);
}

function saveProfileState() {
  updateMetrics();
  renderCards();
  updateUrls();

  var payload = {
    disabledExtensions: Array.from(pData._d),
    enabledExtensions: Array.from(pData._e),
    disabledCatalogs: Array.from(pData._dc),
    disableCatalogs: pData.disableCatalogs
  };

  fetch("/api/profile/" + encodeURIComponent(pid) + "/set", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  }).then(function(r){
      if (r.status === 409) {
        // Over the per-profile limit: show why and go back to what is saved
        return r.json().then(function(err){ toast(err.message || "Extension limit reached"); loadProfile(); return null; });
      }
      return r.json();
    })
    .then(function(p){
      if (!p) return;
      pData.disabledExtensions = p.disabledExtensions || [];
      pData.enabledExtensions = p.enabledExtensions || [];
      pData.disabledCatalogs = p.disabledCatalogs || [];
      pData._d = new Set(pData.disabledExtensions);
      pData._e = new Set(pData.enabledExtensions);
      pData._dc = new Set(pData.disabledCatalogs);
      pData.disableCatalogs = p.disableCatalogs || false;
      updateMetrics();
      renderCards();
      updateUrls();
    })
    .catch(function(e){ toast("Sync error: " + e.message); });
}

/* Dead extensions: probed directly and via proxy, no links. Users can hide them. */
var hideDead = false;
try { hideDead = localStorage.getItem("cnc_hide_dead") === "1"; } catch (e) {}
function onHideDeadChange(on) {
  hideDead = !!on;
  try { localStorage.setItem("cnc_hide_dead", hideDead ? "1" : "0"); } catch (e) {}
  renderCards();
}
function syncDeadToggle() {
  var box = document.getElementById("hide-dead");
  if (box) box.checked = hideDead;
  var n = exts.filter(function(e) { return e.health === "dead"; }).length;
  var badge = document.getElementById("dead-count");
  if (badge) { badge.textContent = n; badge.style.display = n ? "" : "none"; }
}
function timeAgoShort(ts) {
  var s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

function getFilteredExtensions() {
  var q = (document.getElementById("ext-search") ? document.getElementById("ext-search").value : "").trim().toLowerCase();

  return exts.filter(function(e){
    if (q) {
      var n = (e.name || "").toLowerCase();
      var iname = (e.internalName || "").toLowerCase();
      var r = (e.repoName || "").toLowerCase();
      var l = (e.lang || "").toLowerCase();
      var lname = (LANG_MAP[l] || "").toLowerCase();
      var types = (e.types || []).join(" ").toLowerCase();

      var match = (n.indexOf(q) >= 0) || (iname.indexOf(q) >= 0) || (r.indexOf(q) >= 0) || (l.indexOf(q) >= 0) || (lname.indexOf(q) >= 0) || (types.indexOf(q) >= 0);
      if (!match) return false;
    }

    if (currentRepo) {
      if (e.repoName !== currentRepo && e.repoUrl !== currentRepo) return false;
    }

    if (currentType) {
      var hasType = (e.types || []).some(function(t){ return (t||"").toLowerCase() === currentType.toLowerCase(); });
      if (!hasType) return false;
    }

    if (hideDead && e.health === "dead") return false;

    if (currentLang) {
      var eLang = (e.lang || "en").toLowerCase();
      if (eLang !== currentLang.toLowerCase()) return false;
    }

    return true;
  });
}

/* Feature 6: Render Cards Matching pengu.uk 2-column cards */
function renderCards() {
  var custEl = document.getElementById("sources-grid");
  if (!custEl) return;

  var filtered = getFilteredExtensions();
  var orderMap = getActiveOrderMap();
  updateMetrics();
  syncDeadToggle();

  if (!filtered.length) {
    custEl.innerHTML = '<div class="empty">No providers match your search or filter.</div>';
    return;
  }

  custEl.innerHTML = filtered.map(function(e){
    var isSelected = isExtActive(e);

    // metadata text (e.g. Movies, Series · Repo · Lang). No resolution badge:
    // extensions don't declare a max quality, so a fixed "4K" was wrong for most.
    var parts = [];
    if (e.types && e.types.length) {
      parts.push('<span class="p-types-badge">' + esc(e.types.join(", ")) + '</span>');
    }
    if (e.repoName) {
      parts.push('<span class="p-repo-badge">' + esc(e.repoName) + '</span>');
    }
    var langStr = (e.lang || "en").toUpperCase();
    parts.push('<span class="p-lang-badge">' + esc(langStr) + '</span>');
    if (e.health === "dead") {
      parts.push('<span class="hb hb-dead" title="No links found directly or through a proxy' + (e.lastChecked ? " (checked " + timeAgoShort(e.lastChecked) + ")" : "") + '">&#10005; Dead</span>');
    } else if (e.health === "proxy") {
      parts.push('<span class="hb hb-proxy" title="Geo-blocked for the server; served through a regional proxy">&#127760; Via proxy</span>');
    }
    if (!e.enabled) {
      parts.push('<span style="color:var(--amber); font-weight:700;">Off</span>');
    }

    var orderNum = isSelected ? (orderMap[e.internalName] || "&#10003;") : "";
    var checkHtml = isSelected
      ? ('<div class="p-chk-circle">' + orderNum + '</div>')
      : '<div class="p-chk-circle"></div>';

    var catBadge = "";
    if (isSelected) {
      if (pData.disableCatalogs) {
        catBadge = '<span class="p-cat-pill disabled" title="All catalogs are globally disabled">CAT OFF</span>';
      } else if (pData._dc.has(e.internalName)) {
        catBadge = '<button type="button" class="p-cat-pill off" onclick="event.stopPropagation(); toggleCatalog(\'' + esc(e.internalName).replace(/\'/g, "%27") + '\')" title="Catalog hidden from Stremio. Click to enable.">CAT OFF</button>';
      } else {
        catBadge = '<button type="button" class="p-cat-pill on" onclick="event.stopPropagation(); toggleCatalog(\'' + esc(e.internalName).replace(/\'/g, "%27") + '\')" title="Catalog active in Stremio. Click to hide.">CAT ON</button>';
      }
    }

    var rightHtml = '<div class="p-top-right">' + catBadge + checkHtml + '</div>';

    var iconHtml = e.iconUrl
      ? '<img class="p-icon" src="' + esc(e.iconUrl) + '" alt="" onerror="this.style.display=\'none\'">'
      : '<span class="p-icon-letter">' + esc((e.name||"?").charAt(0).toUpperCase()) + '</span>';

    return '<div class="p-card ' + (isSelected ? "selected" : "") + (e.health === "dead" ? " is-dead" : "") + '" onclick="toggleCard(\'' + esc(e.internalName).replace(/\'/g, "%27") + '\')">' +
      '<div class="p-card-icon-col">' + iconHtml + '</div>' +
      '<div class="p-card-info-col">' +
        '<div class="p-top-line">' +
          '<span class="p-name" title="' + esc(e.name) + '">' + esc(e.name) + '</span>' +
          rightHtml +
        '</div>' +
        '<div class="p-meta-line">' + parts.join(" &middot; ") + '</div>' +
        (e.description ? '<div class="p-desc-line">' + esc(e.description) + '</div>' : '') +
      '</div>' +
    '</div>';
  }).join("");
}

function populateDropdownFilters() {
  // Repo Dropdown
  var repoSelect = document.getElementById("repo-filter");
  if (repoSelect && document.activeElement !== repoSelect) {
    var repoMap = {};
    exts.forEach(function(e){
      var r = e.repoName || "Installed";
      repoMap[r] = (repoMap[r] || 0) + 1;
    });

    var totalRepoCount = (repos && repos.length) ? repos.length : Object.keys(repoMap).length;
    var rHtml = '<option value="">All Repositories (' + totalRepoCount + ' repos &middot; ' + exts.length + ' sources)</option>';
    Object.keys(repoMap).sort().forEach(function(r){
      var sel = (currentRepo === r) ? ' selected' : '';
      rHtml += '<option value="' + esc(r) + '"' + sel + '>' + esc(r) + ' (' + repoMap[r] + ' sources)</option>';
    });
    if (repoSelect.innerHTML !== rHtml) repoSelect.innerHTML = rHtml;
  }

  // Type / Category Dropdown
  var typeSelect = document.getElementById("type-filter");
  if (typeSelect && document.activeElement !== typeSelect) {
    var typeMap = {};
    exts.forEach(function(e){
      (e.types || []).forEach(function(t){
        if (t) typeMap[t] = (typeMap[t] || 0) + 1;
      });
    });

    var totalTypeCount = Object.keys(typeMap).length;
    var tHtml = '<option value="">All Content Types (' + totalTypeCount + ' types)</option>';
    Object.keys(typeMap).sort().forEach(function(t){
      var sel = (currentType === t) ? ' selected' : '';
      tHtml += '<option value="' + esc(t) + '"' + sel + '>' + esc(t) + ' (' + typeMap[t] + ' sources)</option>';
    });
    if (typeSelect.innerHTML !== tHtml) typeSelect.innerHTML = tHtml;
  }

  // Lang Dropdown
  var langSelect = document.getElementById("lang-filter");
  if (langSelect && document.activeElement !== langSelect) {
    var langMap = {};
    exts.forEach(function(e){
      var l = (e.lang || "en").toLowerCase();
      langMap[l] = (langMap[l] || 0) + 1;
    });

    var totalLangCount = Object.keys(langMap).length;
    var lHtml = '<option value="">All Languages (' + totalLangCount + ')</option>';
    Object.keys(langMap).sort().forEach(function(l){
      var sel = (currentLang === l) ? ' selected' : '';
      var disp = LANG_MAP[l] || l.toUpperCase();
      lHtml += '<option value="' + esc(l) + '"' + sel + '>' + esc(disp) + ' (' + langMap[l] + ' sources)</option>';
    });
    if (langSelect.innerHTML !== lHtml) langSelect.innerHTML = lHtml;
  }
}

function scrollToSources() {
  if (currentView !== "home") showView("home");
  var el = document.getElementById("sources-section");
  if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
}

function updateMetrics() {
  if (currentView === "quality") { try { renderProviderOrder(); } catch (e) {} }
  var activeCount = exts.filter(function(e){ return isExtActive(e); }).length;
  var totalSources = exts.length;

  var repoMap = {};
  exts.forEach(function(e){
    var r = e.repoName || "Installed";
    repoMap[r] = true;
  });
  var totalRepos = (repos && repos.length) ? repos.length : Object.keys(repoMap).length;

  // Top stat cards
  var reposStat = document.getElementById("st-repos-count");
  if (reposStat) reposStat.textContent = totalRepos;

  var streamCount = document.getElementById("st-stream-count");
  if (streamCount) streamCount.textContent = activeCount;

  var totalCount = document.getElementById("st-total-count");
  if (totalCount) totalCount.textContent = totalSources + " available";

  // Selected Action Button
  var btnSelCount = document.getElementById("btn-selected-count");
  if (btnSelCount) btnSelCount.textContent = activeCount + " / " + MAX_EXT;

  var btnSelectAll = document.getElementById("btn-select-all");
  if (btnSelectAll) {
    if (activeCount === totalSources && totalSources > 0) {
      btnSelectAll.classList.add("all-active");
    } else {
      btnSelectAll.classList.remove("all-active");
    }
  }

  // Providers Card Top Line
  var countLabel = document.getElementById("providers-count-label");
  if (countLabel) {
    countLabel.textContent = activeCount + " of " + totalSources + " active";
  }

  var countPill = document.getElementById("providers-count-pill");
  if (countPill) {
    if (activeCount === 0) {
      countPill.style.color = "var(--text-dim)";
      countPill.style.background = "var(--surface-active)";
      countPill.style.borderColor = "var(--border)";
    } else {
      countPill.style.color = "var(--green)";
      countPill.style.background = "var(--green-bg)";
      countPill.style.borderColor = "rgba(16, 185, 129, 0.25)";
    }
  }
}

/* Data Loaders */
function loadExts() {
  fetch("/api/extensions")
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(data){
      exts = data || [];
      populateDropdownFilters();
      updateMetrics();
      renderCards();
      updateUrls();
    })
    .catch(function(){});
}

function loadRepos() {
  fetch("/api/repos")
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(data){
      repos = data || [];
      populateDropdownFilters();
      updateMetrics();
      // The credits modal is rendered when opened; re-rendering it from this
      // 15 s refresh made the open Developer Credits view reload and jump.
    })
    .catch(function(){});
}

/* ── Multiple profiles on one device ─────────────────────────────────────────
   The device keeps a list of its profiles in localStorage; each profile is a
   separate server-side profile (own manifest URL, sources, catalogs, quality
   and formatter), so several can be installed side by side in Stremio. */
var PROFILES_KEY = "cnc_profiles";

function writeProfileList(list) {
  try { localStorage.setItem(PROFILES_KEY, JSON.stringify(list)); } catch (e) {}
}

function readProfileList() {
  var list = [];
  try { list = JSON.parse(localStorage.getItem(PROFILES_KEY) || "[]") || []; } catch (e) { list = []; }
  list = list.filter(function(p) { return p && typeof p.id === "string" && p.id.length >= 8; });
  if (!list.some(function(p) { return p.id === pid; })) {
    list.push({ id: pid, name: list.length ? ("Profile " + (list.length + 1)) : "Main" });
    writeProfileList(list);
  }
  return list;
}

function newProfileId() {
  return "p_" + Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 6);
}

function renderProfileBar() {
  var list = readProfileList();
  var sel = document.getElementById("profile-select");
  if (sel) {
    sel.innerHTML = list.map(function(p) {
      return '<option value="' + esc(p.id) + '"' + (p.id === pid ? ' selected' : '') + '>' + esc(p.name || p.id) + '</option>';
    }).join("");
  }
  var tag = document.getElementById("profile-count-tag");
  if (tag) tag.textContent = list.length + (list.length === 1 ? " profile" : " profiles");
  updateHeaderProfile();
}

function switchProfile(id) {
  if (!id || id === pid) return;
  localStorage.setItem("stremio_profile_id", id);
  window.location.href = "/u/" + encodeURIComponent(id) + "/configure";
}

function syncProfileName(id, name) {
  return fetch("/api/profile/" + encodeURIComponent(id) + "/name", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ displayName: name })
  }).catch(function() {});
}

function createProfile() {
  var list = readProfileList();
  var name = prompt("Name for the new profile (e.g. Kids, Movies, Living room):", "Profile " + (list.length + 1));
  if (name === null) return;
  name = String(name).trim().slice(0, 40) || ("Profile " + (list.length + 1));
  var id = newProfileId();
  list.push({ id: id, name: name });
  writeProfileList(list);
  syncProfileName(id, name).then(function() { switchProfile(id); });
}

function renameProfile() {
  var list = readProfileList();
  var cur = list.filter(function(p) { return p.id === pid; })[0];
  var name = prompt("Rename this profile:", cur ? cur.name : "");
  if (name === null) return;
  name = String(name).trim().slice(0, 40);
  if (!name) return;
  list.forEach(function(p) { if (p.id === pid) p.name = name; });
  writeProfileList(list);
  renderProfileBar();
  syncProfileName(pid, name).then(function() { toast("Profile renamed"); });
}

function removeProfile() {
  var list = readProfileList();
  if (list.length <= 1) { toast("This is your only profile — create another one first"); return; }
  if (!confirm("Remove this profile from this device? If it is installed in Stremio, uninstall it there too.")) return;
  list = list.filter(function(p) { return p.id !== pid; });
  writeProfileList(list);
  switchProfile(list[0].id);
}

/* Keep the device's profile name and the server's (shown in Stremio) in sync. */
function applyServerProfileName(p) {
  var list = readProfileList();
  var cur = list.filter(function(x) { return x.id === pid; })[0];
  if (!cur) return;
  if (p.displayName && p.displayName !== cur.name) {
    cur.name = p.displayName;
    writeProfileList(list);
    renderProfileBar();
  } else if (!p.displayName && cur.name) {
    syncProfileName(pid, cur.name);
  }
}

/* ── User stream formatter (per profile) ─────────────────────────────────── */
var ufmtCatalog = null;
var ufmtState = { mode: "default", presetId: "", nameTemplate: "", descriptionTemplate: "" };
var ufmtPreviewTimer = null;
var ufmtLastFocus = null;

document.addEventListener("focusin", function(ev) {
  var t = ev.target;
  if (t && (t.id === "ufmt-name" || t.id === "ufmt-desc")) ufmtLastFocus = t;
});

function loadFormatterCatalog() {
  fetch("/api/formatter")
    .then(function(r) { return r.json(); })
    .then(function(c) { ufmtCatalog = c; renderUserFormatterControls(); })
    .catch(function() {});
}

function renderUserFormatterControls() {
  if (!ufmtCatalog) return;
  var opts = '<option value="default">Server default' + (ufmtCatalog.serverFormatterEnabled ? '' : ' (original names)') + '</option>' +
    '<option value="off">Original names (no formatting)</option>';
  ufmtCatalog.presets.forEach(function(p) {
    opts += '<option value="preset:' + esc(p.id) + '">Preset: ' + esc(p.title) + '</option>';
  });
  opts += '<option value="custom">Custom (write your own)</option>';
  var sel = document.getElementById("ufmt-mode");
  sel.innerHTML = opts;
  sel.value = ufmtState.mode === "preset" ? ("preset:" + ufmtState.presetId) : ufmtState.mode;
  if (!sel.value) sel.value = "default";

  var start = '<option value="">— copy a preset to edit —</option>';
  ufmtCatalog.presets.forEach(function(p) { start += '<option value="' + esc(p.id) + '">' + esc(p.title) + '</option>'; });
  document.getElementById("ufmt-start").innerHTML = start;

  var vars = document.getElementById("ufmt-vars");
  vars.innerHTML = ufmtCatalog.variables.map(function(v) {
    return '<code data-var="' + esc(v) + '">{' + esc(v) + '}</code>';
  }).join("");
  vars.onclick = function(ev) {
    var v = ev.target && ev.target.getAttribute ? ev.target.getAttribute("data-var") : null;
    if (v) insertFormatterVariable("{" + v + "}");
  };

  document.getElementById("ufmt-name").value = ufmtState.nameTemplate || "";
  document.getElementById("ufmt-desc").value = ufmtState.descriptionTemplate || "";
  updateUserFormatterUI();
}

function insertFormatterVariable(text) {
  var ta = ufmtLastFocus || document.getElementById("ufmt-desc");
  var s = ta.selectionStart == null ? ta.value.length : ta.selectionStart;
  var e = ta.selectionEnd == null ? s : ta.selectionEnd;
  ta.value = ta.value.slice(0, s) + text + ta.value.slice(e);
  ta.focus();
  ta.setSelectionRange(s + text.length, s + text.length);
  scheduleUserFormatterPreview();
}

function currentUserFormatter() {
  var v = document.getElementById("ufmt-mode").value || "default";
  if (v.indexOf("preset:") === 0) return { mode: "preset", presetId: v.slice(7), nameTemplate: "", descriptionTemplate: "" };
  if (v === "custom") {
    return { mode: "custom", presetId: "",
      nameTemplate: document.getElementById("ufmt-name").value,
      descriptionTemplate: document.getElementById("ufmt-desc").value };
  }
  return { mode: v, presetId: "", nameTemplate: "", descriptionTemplate: "" };
}

function updateUserFormatterUI() {
  var f = currentUserFormatter();
  document.getElementById("ufmt-custom").style.display = f.mode === "custom" ? "flex" : "none";
  var tag = document.getElementById("fmt-mode-tag");
  if (tag) {
    tag.textContent = f.mode === "default" ? "Server default" : f.mode === "off" ? "Original names" : f.mode === "custom" ? "Custom" : "Preset";
  }
  previewUserFormatter();
}

function onUserFormatterModeChange() {
  var f = currentUserFormatter();
  // Switching to Custom with empty boxes: start from the first preset
  if (f.mode === "custom" && !document.getElementById("ufmt-name").value &&
      !document.getElementById("ufmt-desc").value && ufmtCatalog && ufmtCatalog.presets.length) {
    copyPresetIntoCustom(ufmtCatalog.presets[0].id);
  }
  updateUserFormatterUI();
}

function copyPresetIntoCustom(id) {
  if (!id || !ufmtCatalog) return;
  var p = ufmtCatalog.presets.filter(function(x) { return x.id === id; })[0];
  if (!p) return;
  document.getElementById("ufmt-name").value = p.nameTemplate;
  document.getElementById("ufmt-desc").value = p.descriptionTemplate;
  document.getElementById("ufmt-start").value = "";
  scheduleUserFormatterPreview();
}

function scheduleUserFormatterPreview() {
  clearTimeout(ufmtPreviewTimer);
  ufmtPreviewTimer = setTimeout(previewUserFormatter, 350);
}

function previewUserFormatter() {
  var box = document.getElementById("ufmt-preview");
  if (!box) return;
  var f = currentUserFormatter();
  if (f.mode === "off" || (f.mode === "default" && ufmtCatalog && !ufmtCatalog.serverFormatterEnabled)) {
    box.innerHTML = '<div style="font-size:12px;color:var(--text-sub);">Streams keep the names their extensions give them.</div>';
    return;
  }
  if (f.mode === "default") {
    box.innerHTML = '<div style="font-size:12px;color:var(--text-sub);">Uses the server&#39;s formatter, as set by the admin.</div>';
    return;
  }
  fetch("/api/formatter/preview", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(f) })
    .then(function(r) { return r.json(); })
    .then(function(res) {
      if (!res.ok) { box.innerHTML = '<div class="u-err">' + esc(res.error || "Invalid template") + '</div>'; return; }
      box.innerHTML = (res.samples || []).map(function(s) {
        return '<div class="u-sample"><div class="u-sample-kind">' + esc(s.label) + '</div>' +
          '<div class="u-sample-name">' + esc(s.name) + '</div>' +
          '<div class="u-sample-desc">' + esc(s.description) + '</div></div>';
      }).join("");
    })
    .catch(function() { box.innerHTML = '<div class="u-err">Preview unavailable</div>'; });
}

function saveUserFormatter() {
  var f = currentUserFormatter();
  var st = document.getElementById("ufmt-status");
  fetch("/api/profile/" + encodeURIComponent(pid) + "/formatter", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(f)
  })
    .then(function(r) { return r.json().then(function(j) { return { ok: r.ok, body: j }; }); })
    .then(function(res) {
      if (!res.ok) { st.innerHTML = '<span style="color:#f43f5e;">' + esc(res.body.error || "Could not save") + '</span>'; return; }
      ufmtState = res.body.formatter || f;
      st.innerHTML = '<span style="color:var(--green);">Saved</span>';
      toast("Formatter saved — Stremio shows it on the next stream list");
      setTimeout(function() { st.innerHTML = ""; }, 3000);
    })
    .catch(function(e) { st.innerHTML = '<span style="color:#f43f5e;">' + esc(e.message) + '</span>'; });
}

function applyUserFormatterFromProfile(p) {
  if (!p || !p.formatter) return;
  ufmtState = p.formatter;
  if (ufmtCatalog) renderUserFormatterControls();
}

function loadProfile() {
  fetch("/api/profile/" + encodeURIComponent(pid))
    .then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function(p){
      pData.profileId = pid;
      pData.disabledExtensions = p.disabledExtensions || [];
      pData.enabledExtensions = p.enabledExtensions || [];
      pData.disabledCatalogs = p.disabledCatalogs || [];
      pData._d = new Set(pData.disabledExtensions);
      pData._e = new Set(pData.enabledExtensions);
      pData._dc = new Set(pData.disabledCatalogs);
      // Load the saved catalog toggle too — without this the page always assumed
      // "catalogs on" after a refresh and the next save overwrote the real setting.
      pData.disableCatalogs = p.disableCatalogs || false;
      pData.allowedResolutions = p.allowedResolutions || [];
      pData.excludeCam = p.excludeCam || false;
      pData.maxStreamsPerResolution = p.maxStreamsPerResolution || 0;

      applyQualityPreferences(p);
      applyPlaybackPreferences(p);
      applyUserFormatterFromProfile(p);
      applyServerProfileName(p);

      if (pData._d.size > 0 || pData._e.size > 0 || pData._dc.size > 0 || pData.disableCatalogs) {
        localStorage.setItem("cnc_customized_" + pid, "true");
        var devBtn = document.querySelector('[data-preset="dev_choice"]');
        if (devBtn) devBtn.classList.remove("active");
      }

      updateCatalogStatusUI();
      updateMetrics();
      renderCards();
      updateUrls();
    })
    .catch(function(){});
}

function applyQualityPreferences(p) {
  var allowed = p.allowedResolutions || [];
  ['2160p', '1080p', '720p', '480p', '360p'].forEach(function(res) {
    var cb = document.getElementById('q-' + res);
    if (cb) {
      cb.checked = (allowed.length === 0) || (allowed.indexOf(res) !== -1);
    }
  });
  var camCb = document.getElementById('q-exclude-cam');
  if (camCb) camCb.checked = (p.excludeCam !== false);
  var maxSel = document.getElementById('sel-max-streams');
  if (maxSel) maxSel.value = String(p.maxStreamsPerResolution || 0);
  updateQualityBadge();
}

function updateQualityBadge() {
  var allowed = [];
  ['2160p', '1080p', '720p', '480p', '360p'].forEach(function(res) {
    var cb = document.getElementById('q-' + res);
    if (cb && cb.checked) allowed.push(res);
  });
  var tag = document.getElementById('quality-filter-tag');
  if (!tag) return;
  if (allowed.length === 5) {
    tag.textContent = "All Qualities";
    tag.style.background = "var(--green-bg)";
    tag.style.color = "var(--green)";
    tag.style.borderColor = "rgba(16,185,129,0.25)";
  } else if (allowed.length === 0) {
    tag.textContent = "None Selected";
    tag.style.background = "var(--red-bg)";
    tag.style.color = "var(--red)";
    tag.style.borderColor = "rgba(244,63,94,0.25)";
  } else {
    tag.textContent = allowed.join(', ');
    tag.style.background = "var(--cyan-bg)";
    tag.style.color = "var(--cyan)";
    tag.style.borderColor = "rgba(6,182,212,0.25)";
  }
}

function onQualityChange() {
  var allowed = [];
  ['2160p', '1080p', '720p', '480p', '360p'].forEach(function(res) {
    var cb = document.getElementById('q-' + res);
    if (cb && cb.checked) allowed.push(res);
  });
  var excludeCam = document.getElementById('q-exclude-cam') ? document.getElementById('q-exclude-cam').checked : false;
  var maxStreams = parseInt(document.getElementById('sel-max-streams') ? document.getElementById('sel-max-streams').value : '0', 10);

  updateQualityBadge();

  fetch("/api/profile/" + encodeURIComponent(pid) + "/quality", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      allowedResolutions: allowed,
      excludeCam: excludeCam,
      maxStreamsPerResolution: maxStreams
    })
  }).then(function(r){ return r.json(); })
    .then(function(p){
      pData.allowedResolutions = p.allowedResolutions || [];
      pData.excludeCam = p.excludeCam || false;
      pData.maxStreamsPerResolution = p.maxStreamsPerResolution || 0;
      updateUrls();
    }).catch(function(e){ console.error("Quality save error", e); });
}

/* ---- Filtering & playback (per profile) ---- */
function applyPlaybackPreferences(p) {
  pData.hideSubtitles = !!p.hideSubtitles;
  pData.groupBy = p.groupBy || "default";
  pData.sortBy = p.sortBy || "default";
  pData.minSizeGb = p.minSizeGb || 0;
  pData.maxSizeGb = p.maxSizeGb || 0;
  pData.providerOrder = p.providerOrder || [];
  var el = document.getElementById("pb-hide-subs"); if (el) el.checked = pData.hideSubtitles;
  el = document.getElementById("pb-group"); if (el) el.value = pData.groupBy;
  el = document.getElementById("pb-sort"); if (el) el.value = pData.sortBy;
  el = document.getElementById("pb-min-gb"); if (el) el.value = pData.minSizeGb > 0 ? pData.minSizeGb : "";
  el = document.getElementById("pb-max-gb"); if (el) el.value = pData.maxSizeGb > 0 ? pData.maxSizeGb : "";
  renderProviderOrder();
}

function onPlaybackChange() {
  function num(id) {
    var el = document.getElementById(id);
    var v = el ? parseFloat(el.value) : 0;
    return (isFinite(v) && v > 0) ? v : 0;
  }
  var hs = document.getElementById("pb-hide-subs");
  var g = document.getElementById("pb-group");
  var so = document.getElementById("pb-sort");
  pData.hideSubtitles = hs ? hs.checked : false;
  pData.groupBy = g ? g.value : "default";
  pData.sortBy = so ? so.value : "default";
  pData.minSizeGb = num("pb-min-gb");
  pData.maxSizeGb = num("pb-max-gb");
  savePlayback();
}

var playbackTimer = null;
function savePlayback() {
  if (playbackTimer) clearTimeout(playbackTimer);
  playbackTimer = setTimeout(function() {
    fetch("/api/profile/" + encodeURIComponent(pid) + "/playback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        hideSubtitles: !!pData.hideSubtitles,
        groupBy: pData.groupBy || "default",
        sortBy: pData.sortBy || "default",
        minSizeGb: pData.minSizeGb || 0,
        maxSizeGb: pData.maxSizeGb || 0,
        providerOrder: pData.providerOrder || []
      })
    }).then(function(r){ return r.json(); })
      .then(function(p){
        // The server tidies the values (min cannot exceed max…): show what is saved
        var el = document.getElementById("pb-min-gb"); if (el) el.value = p.minSizeGb > 0 ? p.minSizeGb : "";
        el = document.getElementById("pb-max-gb"); if (el) el.value = p.maxSizeGb > 0 ? p.maxSizeGb : "";
        pData.minSizeGb = p.minSizeGb || 0;
        pData.maxSizeGb = p.maxSizeGb || 0;
      }).catch(function(e){ console.error("Playback save error", e); });
  }, 300);
}

function activeProviderNames() {
  var names = [];
  exts.forEach(function(e) {
    if (isExtActive(e) && !isLiveOnly(e) && names.indexOf(e.name) < 0) names.push(e.name);
  });
  return names;
}

function orderedProviders() {
  var active = activeProviderNames();
  var out = [];
  (pData.providerOrder || []).forEach(function(n) { if (active.indexOf(n) >= 0 && out.indexOf(n) < 0) out.push(n); });
  active.forEach(function(n) { if (out.indexOf(n) < 0) out.push(n); });
  return out;
}

function renderProviderOrder() {
  var box = document.getElementById("provider-order");
  if (!box) return;
  var list = orderedProviders();
  box.innerHTML = "";
  if (!list.length) { box.textContent = "Switch on some movie / series sources to order them."; return; }
  list.forEach(function(name, i) {
    var row = document.createElement("div");
    row.style.cssText = "display:flex;align-items:center;gap:8px;padding:5px 8px;border:1px solid var(--border);border-radius:8px;background:var(--surface);font-size:12.5px;font-weight:600;";
    var label = document.createElement("span");
    label.style.flex = "1";
    label.textContent = (i + 1) + ". " + name;
    function mk(txt, delta) {
      var b = document.createElement("button");
      b.textContent = txt;
      b.style.cssText = "background:var(--surface);border:1px solid var(--border);border-radius:6px;color:var(--text);cursor:pointer;padding:2px 9px;font-size:13px;font-weight:700;";
      b.onclick = function() { moveProvider(i, delta); };
      return b;
    }
    row.appendChild(label);
    row.appendChild(mk("\u2191", -1));
    row.appendChild(mk("\u2193", 1));
    box.appendChild(row);
  });
}

function moveProvider(i, delta) {
  var list = orderedProviders();
  var j = i + delta;
  if (j < 0 || j >= list.length) return;
  var t = list[i]; list[i] = list[j]; list[j] = t;
  pData.providerOrder = list;
  renderProviderOrder();
  savePlayback();
}

function resetProviderOrder() {
  pData.providerOrder = [];
  renderProviderOrder();
  savePlayback();
}

/* ---- Source status ---- */
var statusData = [];
var statusFilter = "all";
function agoText(ms) {
  if (!ms) return "never";
  var sec = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (sec < 60) return sec + "s ago";
  if (sec < 3600) return Math.round(sec / 60) + "m ago";
  if (sec < 86400) return Math.round(sec / 3600) + "h ago";
  return Math.round(sec / 86400) + "d ago";
}
function statusKind(e) {
  var h = e.health || "unknown";
  if (h === "working" || h === "proxy") return "up";
  if (h === "dead") return "down";
  return "unchecked";
}
function loadStatusView() {
  fetch("/api/extensions").then(function(r){ return r.json(); }).then(function(list) {
    statusData = list || [];
    renderStatusView();
  }).catch(function() {
    var el = document.getElementById("status-list");
    if (el) el.textContent = "Could not load the source status.";
  });
}
function setStatusFilter(f) { statusFilter = f; renderStatusView(); }
function renderStatusView() {
  var sum = document.getElementById("status-summary");
  var fil = document.getElementById("status-filters");
  var list = document.getElementById("status-list");
  if (!sum || !fil || !list) return;
  var allBox = document.getElementById("status-all");
  var showAll = allBox ? allBox.checked : false;
  var rows = statusData.filter(function(e) { return showAll || isExtActive(e); });
  var counts = { up: 0, down: 0, unchecked: 0 };
  rows.forEach(function(e) { counts[statusKind(e)]++; });

  sum.innerHTML = "";
  [["Sources", rows.length, "var(--text)"], ["Up", counts.up, "var(--green)"], ["Down", counts.down, "var(--red)"], ["Unchecked", counts.unchecked, "var(--text-sub)"]].forEach(function(c) {
    var box = document.createElement("div");
    box.style.cssText = "min-width:90px;padding:8px 12px;border:1px solid var(--border);border-radius:9px;background:var(--surface);";
    var n = document.createElement("div");
    n.style.cssText = "font-size:20px;font-weight:800;color:" + c[2] + ";";
    n.textContent = c[1];
    var l = document.createElement("div");
    l.style.cssText = "font-size:11px;font-weight:700;color:var(--text-sub);";
    l.textContent = c[0];
    box.appendChild(n); box.appendChild(l);
    sum.appendChild(box);
  });

  fil.innerHTML = "";
  [["all", "All"], ["up", "Up"], ["down", "Down"], ["unchecked", "Unchecked"]].forEach(function(f) {
    var b = document.createElement("button");
    b.textContent = f[1];
    var on = statusFilter === f[0];
    b.style.cssText = "border-radius:7px;font-size:12px;font-weight:700;padding:4px 12px;cursor:pointer;border:1.5px solid " + (on ? "var(--accent)" : "var(--border)") + ";background:" + (on ? "var(--accent)" : "var(--surface)") + ";color:" + (on ? "#fff" : "var(--text)") + ";";
    b.onclick = function() { setStatusFilter(f[0]); };
    fil.appendChild(b);
  });

  var order = { down: 0, unchecked: 1, up: 2 };
  rows = rows.filter(function(e) { return statusFilter === "all" || statusKind(e) === statusFilter; });
  rows.sort(function(a, b) {
    var d = order[statusKind(a)] - order[statusKind(b)];
    return d !== 0 ? d : String(a.name).toLowerCase().localeCompare(String(b.name).toLowerCase());
  });
  list.innerHTML = "";
  if (!rows.length) { list.textContent = "Nothing to show."; return; }
  rows.forEach(function(e) {
    var kind = statusKind(e);
    var row = document.createElement("div");
    row.style.cssText = "display:flex;align-items:center;gap:10px;padding:7px 10px;border:1px solid var(--border);border-radius:8px;background:var(--surface);font-size:12.5px;";
    var name = document.createElement("span");
    name.style.cssText = "flex:1;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
    name.textContent = e.name;
    var pill = document.createElement("span");
    var color = kind === "up" ? "var(--green)" : (kind === "down" ? "var(--red)" : "var(--text-sub)");
    pill.style.cssText = "font-size:10.5px;font-weight:800;padding:2px 8px;border-radius:5px;border:1px solid " + color + ";color:" + color + ";";
    pill.textContent = kind === "up" ? (e.health === "proxy" ? "UP · PROXY" : "UP") : (kind === "down" ? "DOWN" : "UNCHECKED");
    var when = document.createElement("span");
    when.style.cssText = "font-size:11px;color:var(--text-sub);min-width:150px;text-align:right;";
    when.textContent = "worked " + agoText(e.lastSuccess) + " · checked " + agoText(e.lastChecked);
    row.appendChild(name); row.appendChild(pill); row.appendChild(when);
    list.appendChild(row);
  });
}

function loadStats() {
  fetch("/api/community-stats")
    .then(function(r){ if(!r.ok) throw new Error("Proxy error"); return r.json(); })
    .catch(function(){ return fetch("https://cncverse.pages.dev/api/stats").then(function(r){ return r.json(); }); })
    .then(function(data){
      if (!data) return;
      var numRaised = (data.totalUsd !== undefined && data.totalUsd !== null) ? Number(data.totalUsd) :
                      ((data.total_raised !== undefined && data.total_raised !== null) ? Number(data.total_raised) :
                      ((data.totalRaised !== undefined && data.totalRaised !== null) ? Number(data.totalRaised) : 0));
      var numTarget = (data.targetGoalUsd !== undefined && data.targetGoalUsd !== null) ? Number(data.targetGoalUsd) :
                      ((data.monthly_target !== undefined && data.monthly_target !== null) ? Number(data.monthly_target) :
                      ((data.monthlyTarget !== undefined && data.monthlyTarget !== null) ? Number(data.monthlyTarget) : 100));
      var pct = (data.percent !== undefined && data.percent !== null) ? Math.round(Number(data.percent)) :
                (numTarget > 0 ? Math.round((numRaised / numTarget) * 100) : 0);
      var raisedStr = (numRaised % 1 === 0) ? numRaised.toString() : numRaised.toFixed(2);
      var targetStr = (numTarget % 1 === 0) ? numTarget.toString() : numTarget.toFixed(2);
      var dollar = String.fromCharCode(36);

      var pctEl = document.getElementById("goal-pct");
      if (pctEl) pctEl.textContent = pct + "%";

      var textEl = document.getElementById("goal-text");
      if (textEl) textEl.innerHTML = dollar + raisedStr + " raised of " + dollar + targetStr + " goal";

      var fillEl = document.getElementById("goal-fill");
      if (fillEl) fillEl.style.width = Math.min(100, Math.max(0, pct)) + "%";
    })
    .catch(function(){});
}

/* ── Installed Repositories Modal ─────────────────────────────────────── */
function openReposModal() {
  renderReposModal();
  var m = document.getElementById("repos-modal");
  if (m) m.classList.add("open");
}
function closeReposModal() {
  var m = document.getElementById("repos-modal");
  if (m) m.classList.remove("open");
}
function renderReposModal() {
  var container = document.getElementById("repos-list");
  if (!container) return;
  container.innerHTML = '<div class="empty"><span class="loader"></span> Loading repositories...</div>';

  fetch("/api/credits")
    .then(function(r) { return r.json(); })
    .catch(function() { return []; })
    .then(function(creditList) {
      var repoMaintainers = (creditList || []).filter(function(c) {
        return !c.isCurated || (c.repoUrl && c.repoUrl !== "");
      });
      // Fallback to repos array if credits endpoint returned nothing for repos
      var list = repoMaintainers.length ? repoMaintainers : repos.map(function(r){
        return {
          authorName: r.name,
          repoUrl: r.url,
          avatarUrl: r.iconUrl,
          description: r.description,
          pluginCount: r.pluginCount
        };
      });

      var countEl = document.getElementById("repos-count-badge");
      if (countEl) countEl.textContent = list.length + (list.length === 1 ? " Repo" : " Repos");

      if (!list.length) {
        container.innerHTML = '<div class="empty">No repositories installed.</div>';
        return;
      }

      var cards = list.map(function(c) {
        var links = [];
        var cleanGh = (c.githubUrl || "").trim();
        var ghMatch = cleanGh.match(/(?:raw\.githubusercontent|github)\.com\/([^\/]+)\/([^\/]+)/i);
        var repoRootUrl = ghMatch ? ("https://github.com/" + ghMatch[1] + "/" + ghMatch[2].replace(/\.git$/, "")) : cleanGh;

        if (repoRootUrl) {
          links.push('<a class="author-link-btn gh" href="' + esc(repoRootUrl) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/></svg><span>GitHub</span></a>');
        }
        if (c.repoUrl && c.repoUrl.trim() !== repoRootUrl && c.repoUrl.trim() !== cleanGh) {
          links.push('<a class="author-link-btn" href="' + esc(c.repoUrl.trim()) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg><span>Repository</span></a>');
        }
        if (c.discordUrl && c.discordUrl.trim()) {
          var cleanDc = c.discordUrl.trim();
          var dcLink = cleanDc.indexOf("http") === 0 ? cleanDc : ("https://discord.com/users/" + cleanDc);
          var dcLabel = cleanDc.indexOf("http") === 0 ? "Discord" : ("@" + cleanDc);
          var dcMatch = cleanDc.match(/(?:discord\.com\/users\/|discordapp\.com\/users\/)([^\/\?]+)/i);
          if (dcMatch) dcLabel = "@" + dcMatch[1];
          links.push('<a class="author-link-btn dc" href="' + esc(dcLink) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994.021-.041.001-.09-.041-.106a13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.929 1.793 8.18 1.793 12.061 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.894.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.078.078 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/></svg><span>' + esc(dcLabel) + '</span></a>');
        }
        if (c.telegramUrl && c.telegramUrl.trim()) {
          links.push('<a class="author-link-btn tg" href="' + esc(c.telegramUrl.trim()) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 0 0-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.95-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.74-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.36.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .38z"/></svg><span>Telegram</span></a>');
        }
        if (c.donationUrl && c.donationUrl.trim()) {
          links.push('<a class="author-link-btn heart" href="' + esc(c.donationUrl.trim()) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg><span>Support</span></a>');
        }
        if (c.websiteUrl && c.websiteUrl.trim()) {
          links.push('<a class="author-link-btn" href="' + esc(c.websiteUrl.trim()) + '" target="_blank" rel="noopener"><span>Website</span></a>');
        }

        var repoMatch = repos.find(function(r) {
          return (r.url && c.repoUrl && r.url.trim().toLowerCase() === c.repoUrl.trim().toLowerCase());
        });
        var sourceCount = (c.pluginCount !== undefined && c.pluginCount !== null) ? c.pluginCount :
                          (repoMatch ? (repoMatch.pluginCount || 0) : null);

        var avatarSrc = c.avatarUrl || (repoMatch ? repoMatch.iconUrl : "");
        var avatarHtml = avatarSrc
          ? '<img class="author-avatar" src="' + esc(avatarSrc) + '" alt="" onerror="this.src=\'/logo.png\'">'
          : '<div class="author-avatar" style="display:flex;align-items:center;justify-content:center;font-weight:800;color:var(--accent);">' + esc((c.authorName || "?").charAt(0).toUpperCase()) + '</div>';

        var badgesHtml = '';
        if (sourceCount !== null && sourceCount > 0) {
          badgesHtml += '<span class="author-badge" style="background:var(--surface-active);border-color:var(--border);color:var(--accent);font-weight:700;">' + sourceCount + ' sources</span>';
        }

        return '<div class="author-card">' +
          '<div class="author-top">' +
            '<div class="author-info-left">' +
              avatarHtml +
              '<div style="min-width:0;">' +
                '<div class="author-name">' + esc(c.authorName) + '</div>' +
                '<div style="font-size:10.5px;color:var(--text-dim);">' + esc(c.roleBadge || "Installed Repository") + '</div>' +
              '</div>' +
            '</div>' +
            '<div style="display:flex;align-items:center;gap:6px;flex-shrink:0;">' + badgesHtml + '</div>' +
          '</div>' +
          (c.description ? '<div class="author-desc">' + esc(c.description) + '</div>' : '') +
          (links.length ? '<div class="author-links-row">' + links.join("") + '</div>' : '') +
        '</div>';
      });

      container.innerHTML = cards.join("");
    });
}

/* ── Developer Credits Modal ──────────────────────────────────────────── */
function openCreditsModal() {
  renderCreditsModal();
  var m = document.getElementById("credits-modal");
  if (m) m.classList.add("open");
}
function closeCreditsModal() {
  var m = document.getElementById("credits-modal");
  if (m) m.classList.remove("open");
}
function renderCreditsModal() {
  var container = document.getElementById("credits-list");
  if (!container) return;
  container.innerHTML = '<div class="empty"><span class="loader"></span> Loading developer credits...</div>';

  fetch("/api/credits")
    .then(function(r) { return r.json(); })
    .catch(function() { return []; })
    .then(function(list) {
      var coreTeam = (list || []).filter(function(c) {
        return c.isCurated && (!c.repoUrl || c.repoUrl === "");
      });

      if (!coreTeam.length) {
        container.innerHTML = '<div class="empty">No team credits found.</div>';
        return;
      }

      var cards = coreTeam.map(function(c) {
        var links = [];
        var cleanGh = (c.githubUrl || "").trim();
        if (cleanGh) {
          links.push('<a class="author-link-btn gh" href="' + esc(cleanGh) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/></svg><span>GitHub</span></a>');
        }
        if (c.discordUrl && c.discordUrl.trim()) {
          var cleanDc = c.discordUrl.trim();
          var dcLink = cleanDc.indexOf("http") === 0 ? cleanDc : ("https://discord.com/users/" + cleanDc);
          var dcLabel = cleanDc.indexOf("http") === 0 ? "Discord" : ("@" + cleanDc);
          var dcMatch = cleanDc.match(/(?:discord\.com\/users\/|discordapp\.com\/users\/)([^\/\?]+)/i);
          if (dcMatch) dcLabel = "@" + dcMatch[1];
          links.push('<a class="author-link-btn dc" href="' + esc(dcLink) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994.021-.041.001-.09-.041-.106a13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.929 1.793 8.18 1.793 12.061 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.894.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.078.078 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/></svg><span>' + esc(dcLabel) + '</span></a>');
        }
        if (c.telegramUrl && c.telegramUrl.trim()) {
          links.push('<a class="author-link-btn tg" href="' + esc(c.telegramUrl.trim()) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 0 0-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.95-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.74-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.36.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .38z"/></svg><span>Telegram</span></a>');
        }
        if (c.donationUrl && c.donationUrl.trim()) {
          links.push('<a class="author-link-btn heart" href="' + esc(c.donationUrl.trim()) + '" target="_blank" rel="noopener"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg><span>Support</span></a>');
        }
        if (c.websiteUrl && c.websiteUrl.trim()) {
          links.push('<a class="author-link-btn" href="' + esc(c.websiteUrl.trim()) + '" target="_blank" rel="noopener"><span>Website</span></a>');
        }

        var avatarSrc = c.avatarUrl || "/logo.png";
        var avatarHtml = '<img class="author-avatar" src="' + esc(avatarSrc) + '" alt="" onerror="this.src=\'/logo.png\'">';
        var badgesHtml = c.roleBadge ? ('<span class="author-badge" style="background:var(--surface-active);border-color:var(--border);color:var(--accent);font-weight:700;">' + esc(c.roleBadge) + '</span>') : '';

        return '<div class="author-card" style="border-color: rgba(139, 92, 246, 0.35); background: linear-gradient(180deg, var(--surface-card) 0%, rgba(139, 92, 246, 0.04) 100%);">' +
          '<div class="author-top">' +
            '<div class="author-info-left">' +
              avatarHtml +
              '<div style="min-width:0;">' +
                '<div class="author-name">' + esc(c.authorName) + '</div>' +
                '<div style="font-size:10.5px;color:var(--text-dim);">' + esc(c.roleBadge || "Core Developer") + '</div>' +
              '</div>' +
            '</div>' +
            '<div style="display:flex;align-items:center;gap:6px;flex-shrink:0;">' + badgesHtml + '</div>' +
          '</div>' +
          (c.description ? '<div class="author-desc">' + esc(c.description) + '</div>' : '') +
          (links.length ? '<div class="author-links-row">' + links.join("") + '</div>' : '') +
        '</div>';
      });

      container.innerHTML = cards.join("");
    });
}

function esc(s) {
  return String(s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
function toast(msg) {
  var t = document.getElementById("toast");
  if (!t) return;
  t.textContent = msg;
  t.classList.add("show");
  if (t._timer) clearTimeout(t._timer);
  t._timer = setTimeout(function(){ t.classList.remove("show"); }, 2200);
}

function loadFooterCredits() {
  fetch("/api/footer-credits")
    .then(function(r) { return r.json(); })
    .catch(function() { return []; })
    .then(function(items) {
      var el = document.getElementById("page-footer");
      if (!el) return;
      if (!items || !items.length) {
        el.innerHTML = "";
        el.style.display = "none";
        return;
      }
      el.style.display = "flex";
      var parts = items.map(function(item) {
        var linkHtml = item.url ? ('<a href="' + esc(item.url) + '" target="_blank" rel="noopener" class="footer-author-link" style="font-weight:700;">' + esc(item.name) + '</a>') : ('<span class="footer-author-name" style="font-weight:700;">' + esc(item.name) + '</span>');
        return item.label ? ('<span>' + esc(item.label) + ' ' + linkHtml + '</span>') : ('<span>' + linkHtml + '</span>');
      });
      el.innerHTML = parts.join(' <span class="footer-sep">&middot;</span> ');
    });
}

updateUrls();
showView(viewFromHash(), true);
loadExts();
loadRepos();
renderProfileBar();
loadFormatterCatalog();
loadProfile();
loadStats();
loadFooterCredits();

setInterval(function(){ loadExts(); loadRepos(); loadFooterCredits(); }, 15000);
setInterval(function(){ loadStats(); }, 60000);
</script>
</body>
</html>

"""
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

/**
 * Platform hook for the web admin panel routes. Mounted inside the main addon
 * server engine when enabled (desktop with CNC_WEB_ADMIN=1, always on in the
 * headless server app); a no-op on Android.
 */
expect fun Application.setupWebAdminRoutes()

