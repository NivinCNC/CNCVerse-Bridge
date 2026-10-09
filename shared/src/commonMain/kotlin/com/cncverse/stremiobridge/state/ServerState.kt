package com.cncverse.stremiobridge.state

import com.cncverse.stremiobridge.model.SitePlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─── Server Status ────────────────────────────────────────────────────────────

sealed class ServerStatus {
    object Stopped : ServerStatus()
    data class Starting(val message: String = "Starting…") : ServerStatus()
    data class Running(
        val port: Int,
        val loadedPlugins: List<LoadedPluginInfo>,
        val ipAddress: String,
    ) : ServerStatus() {
        val pluginCount: Int get() = loadedPlugins.size
        val stremioUrl: String get() = "http://$ipAddress:$port/manifest.json"
        val localhostUrl: String get() = "http://127.0.0.1:$port/manifest.json"
        val stremioModeStremioUrl: String get() {
            val tunnel = ServerState.activeTunnelUrl.value
            val base = tunnel?.takeIf { it.isNotBlank() }
                ?: com.cncverse.stremiobridge.tunnel.TunnelSettings.publicUrl
                ?: return ""
            return "$base/manifest.json"
        }
        val stremioModeLocalhostUrl: String get() = stremioModeStremioUrl
    }
    data class Error(val message: String) : ServerStatus()
}

data class LoadedPluginInfo(
    val internalName: String,
    val displayName: String,
    val iconUrl: String?,
    val tvTypes: List<String>,
    val language: String?,
    val status: Int,
    /** True if the plugin provides a settings menu via openSettings */
    val hasSettings: Boolean = false,
    /** True if the MainAPI was successfully registered (plugin executed load()) */
    val apiRegistered: Boolean = false,
)

// ─── Log ─────────────────────────────────────────────────────────────────────

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

enum class LogLevel { INFO, WARN, ERROR }

// ─── Global State Singleton ───────────────────────────────────────────────────

object ServerState {
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val isStremioMode = MutableStateFlow(false)
    val activeTunnelUrl = MutableStateFlow<String?>(null)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _globalLoadedPlugins = MutableStateFlow<List<LoadedPluginInfo>>(emptyList())
    val globalLoadedPlugins: StateFlow<List<LoadedPluginInfo>> = _globalLoadedPlugins.asStateFlow()

    fun updateGlobalLoadedPlugins(plugins: List<LoadedPluginInfo>) {
        _globalLoadedPlugins.value = plugins
        val currentStatus = _status.value
        if (currentStatus is ServerStatus.Running) {
            _status.value = currentStatus.copy(loadedPlugins = plugins)
        }
    }

    var serverPort: Int = 8080

    /** When true, catalogs are completely omitted from all manifests (global and profile) */
    @Volatile var disableCatalogsGlobally: Boolean = false

    /** Base URL as seen by the client (e.g. "http://140.238.244.130" or "http://yourdomain.com").
     *  Updated from the HTTP request Host header on every incoming request so that
     *  proxy URLs and admin display URLs reflect the public domain instead of the internal LAN IP. */
    @Volatile var publicBaseUrl: String = ""

    /**
     * Fixed base for the bridge's own relay links (proxied video, decryption, subtitles), e.g.
     * http://140.238.244.130 - env CNC_STREAM_BASE_URL. Keeps heavy streaming traffic off the
     * public domain (a DNS/CDN provider can terminate a domain for it). Null = follow the
     * address the client used, as before.
     */
    /** Host names clients have reached this bridge by (its domain, IP, LAN names) - capped. */
    val ownHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val streamBaseUrl: String? = System.getenv("CNC_STREAM_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /** Global bridge theme accent configured by the administrator in the dev panel */
    @Volatile var globalAccentHex: String = "#8b5cf6"
    @Volatile var globalAccentGlow: String = "rgba(139, 92, 246, 0.28)"
    @Volatile var globalAccentHover: String = "#7c3aed"
    /** Global bridge base theme palette (e.g. "slate", "charcoal", "navy", "forest", "oled", "light") */
    @Volatile var globalBaseTheme: String = "slate"

    fun updateStatus(newStatus: ServerStatus) {
        _status.value = newStatus
    }

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(currentTimeMillis(), level, message)
        val current = _logs.value.takeLast(99)
        _logs.value = current + entry
        // Also emit to platform logger (adb logcat on Android)
        platformLog(level, message)
    }

    /**
     * Per-request trace (each provider's search/match/links steps, every relayed segment…):
     * thousands of lines a minute that StreamTracker already summarises. Off unless the
     * server runs with CNC_LOG_VERBOSE=1.
     */
    val verbose: Boolean = runCatching { System.getenv("CNC_LOG_VERBOSE") == "1" }.getOrDefault(false)

    fun debug(msg: String) { if (verbose) log(LogLevel.INFO, msg) }

    fun info(msg: String) = log(LogLevel.INFO, msg)
    fun warn(msg: String) = log(LogLevel.WARN, msg)
    fun error(msg: String) = log(LogLevel.ERROR, msg)
    fun clearLogs() { _logs.value = emptyList() }
}

@kotlinx.serialization.Serializable
data class AuthorCredit(
    val id: String,
    val repoUrl: String? = null,
    val authorName: String = "",
    val roleBadge: String = "Repository Author",
    val description: String = "",
    val avatarUrl: String? = null,
    val githubUrl: String? = null,
    val discordUrl: String? = null,
    val telegramUrl: String? = null,
    val donationUrl: String? = null,
    val websiteUrl: String? = null,
    val isCurated: Boolean = false,
    val pluginCount: Int? = null,
)

@kotlinx.serialization.Serializable
data class FooterCredit(
    val id: String = "",
    val label: String = "",
    val name: String = "",
    val url: String = "",
)

@kotlinx.serialization.Serializable
data class ThemeConfig(
    val accentHex: String = "#8b5cf6",
    val accentGlow: String = "rgba(139, 92, 246, 0.28)",
    val accentHover: String = "#7c3aed",
    val baseTheme: String = "slate",
)

expect fun currentTimeMillis(): Long
expect fun platformLog(level: LogLevel, message: String)
