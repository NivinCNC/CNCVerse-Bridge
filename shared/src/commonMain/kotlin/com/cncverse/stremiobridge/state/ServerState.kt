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
            return if (!tunnel.isNullOrBlank()) {
                "$tunnel/manifest.json"
            } else {
                "${com.cncverse.stremiobridge.tunnel.DeviceIdManager.getDeviceSubdomainUrl()}/manifest.json"
            }
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

    /** Base URL as seen by the client (e.g. "http://140.238.244.130" or "http://yourdomain.com").
     *  Updated from the HTTP request Host header on every incoming request so that
     *  proxy URLs and admin display URLs reflect the public domain instead of the internal LAN IP. */
    @Volatile var publicBaseUrl: String = ""

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

    fun info(msg: String) = log(LogLevel.INFO, msg)
    fun warn(msg: String) = log(LogLevel.WARN, msg)
    fun error(msg: String) = log(LogLevel.ERROR, msg)
    fun clearLogs() { _logs.value = emptyList() }
}

expect fun currentTimeMillis(): Long
expect fun platformLog(level: LogLevel, message: String)
