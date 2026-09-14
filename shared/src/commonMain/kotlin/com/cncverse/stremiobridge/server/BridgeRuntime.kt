package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.repo.PluginInstaller
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.state.AvailablePlugin
import com.cncverse.stremiobridge.state.PluginInstallState
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shared bridge lifecycle used by the desktop app, the headless server app and
 * the web admin API: loads the plugin registry, refreshes repos, starts/stops
 * the Ktor Stremio server and hot-reloads plugins after install/uninstall or
 * settings changes.
 */
object BridgeRuntime {

    /** Cache dir holding installed plugins (must be set by the app entrypoint). */
    @Volatile
    var cacheDir: String = ""

    /** Scope used for background work (server supervision, repo refreshes). */
    @Volatile
    var appScope: CoroutineScope? = null

    private val loadMutex = Mutex()

    /** Background hourly repo-refresh + auto-update job. Cancelled on stop. */
    private var hourlyUpdateJob: Job? = null

    private val HOURLY_INTERVAL_MS = 60L * 60L * 1000L // 1 hour

    fun getLocalIpAddress(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces().asSequence().toList()
        val preferred = interfaces.filter { iface ->
            iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                (iface.name.contains("wlan", ignoreCase = true) ||
                 iface.name.contains("eth", ignoreCase = true) ||
                 iface.name.contains("en", ignoreCase = true))
        }
        val candidates = if (preferred.isNotEmpty()) preferred else interfaces.filter { iface ->
            iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
            !iface.name.contains("p2p", ignoreCase = true) &&
            !iface.name.contains("dummy", ignoreCase = true) &&
            !iface.name.contains("tun", ignoreCase = true) &&
            !iface.name.contains("rmnet", ignoreCase = true)
        }
        candidates
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress }
            .sorted()
            .firstOrNull()
    } catch (_: Exception) { null }

    /**
     * Loads installed plugins from disk and (re)registers them in the global
     * plugin manager. Serialized by a mutex so app-startup preloads and
     * bridge starts never double-load. Skips the work when plugins are
     * already loaded.
     */
    suspend fun ensurePluginsLoaded() = loadMutex.withLock {
        if (GlobalPluginManager.isPluginsLoaded.value) return@withLock
        doReloadPlugins()
    }

    /** Unconditional plugin reload (after install/uninstall/settings changes). */
    suspend fun forceReloadPlugins() = loadMutex.withLock {
        doReloadPlugins()
    }

    private suspend fun doReloadPlugins() {
        val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(cacheDir) }
        RepoState.setInstalledPlugins(installed)
        installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }
        val cs3Files = PluginInstaller.getInstalledFiles(cacheDir)
        GlobalPluginManager.reloadAllPlugins(installed, cs3Files)
    }

    /** Install a plugin and hot-reload it into the running server. */
    suspend fun installPlugin(ap: AvailablePlugin): Boolean {
        val success = PluginInstaller.installPlugin(ap, cacheDir)
        if (success) {
            ServerState.info("Loading installed plugin '${ap.plugin.name}'…")
            forceReloadPlugins()
        }
        return success
    }

    /** Uninstall a plugin and hot-reload. */
    suspend fun uninstallPlugin(internalName: String) {
        PluginInstaller.uninstallPlugin(internalName, cacheDir)
        forceReloadPlugins()
    }

    /**
     * Mirrors the Android StremioForegroundService.startBridge flow:
     *  1. Load installed plugin registry from disk
     *  2. Refresh repos (background, non-blocking) + auto-update outdated plugins
     *  3. Wait for the global plugin load to finish
     *  4. Start the Ktor Stremio HTTP server
     */
    suspend fun startBridge(preferredPort: Int = 8080) {
        ServerState.updateStatus(ServerStatus.Starting("Loading plugin registry…"))
        val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(cacheDir) }
        RepoState.setInstalledPlugins(installed)
        installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }
        ServerState.info("Found ${installed.size} installed plugin(s)")

        RepoManager.loadSavedRepos()
        ServerState.updateStatus(ServerStatus.Starting("Refreshing repos…"))

        // Refresh repos in background (does NOT block server startup)
        appScope?.launch(Dispatchers.IO) {
            runCatching {
                RepoManager.refreshAllRepos()
                val toUpdate = RepoState.installedPlugins.value.filter {
                    RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                }
                if (toUpdate.isNotEmpty()) {
                    ServerState.info("Auto-updating ${toUpdate.size} plugin(s)…")
                    PluginInstaller.autoUpdateInstalled(cacheDir)
                    forceReloadPlugins()
                }
            }.onFailure { e ->
                ServerState.warn("Repo refresh error: ${e.message}")
            }
        }

        // Initial plugin load (no-op if already loaded at app startup)
        ServerState.updateStatus(ServerStatus.Starting("Loading plugins…"))
        runCatching { ensurePluginsLoaded() }
            .onFailure { e -> ServerState.warn("Plugin load failed: ${e.message}") }

        // Bounded safety wait for a load kicked off elsewhere
        ServerState.updateStatus(ServerStatus.Starting("Waiting for plugins…"))
        val pluginsReady = withTimeoutOrNull(30_000) {
            GlobalPluginManager.isPluginsLoaded.first { it }
        }
        if (pluginsReady == null) {
            ServerState.warn("Timed out waiting for plugin load — starting server with whatever is available")
        }
        val loadedInfos = ServerState.globalLoadedPlugins.value

        val ipAddress = getLocalIpAddress() ?: "127.0.0.1"
        CloudflaredManager.deviceIp = ipAddress
        val boundPort = StremioServer.start(preferredPort, cacheDir)

        ServerState.updateStatus(
            ServerStatus.Running(
                port          = boundPort,
                loadedPlugins = loadedInfos,
                ipAddress     = ipAddress,
            )
        )
        ServerState.info("🎬 Bridge running at http://$ipAddress:$boundPort/manifest.json")

        // Start hourly extension update checker
        startHourlyUpdateCheck()
    }

    /** Launches a background loop that refreshes repos and auto-updates plugins every hour. */
    private fun startHourlyUpdateCheck() {
        hourlyUpdateJob?.cancel()
        hourlyUpdateJob = appScope?.launch(Dispatchers.IO) {
            // First check after 1 hour
            delay(HOURLY_INTERVAL_MS)
            while (isActive) {
                runCatching {
                    ServerState.info("Hourly update check — refreshing repos…")
                    RepoManager.refreshAllRepos()
                    val toUpdate = RepoState.installedPlugins.value.filter {
                        RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                    }
                    if (toUpdate.isNotEmpty()) {
                        ServerState.info("Auto-updating ${toUpdate.size} plugin(s) found by hourly check…")
                        PluginInstaller.autoUpdateInstalled(cacheDir)
                        forceReloadPlugins()
                    } else {
                        ServerState.info("Hourly check complete — all plugins up to date")
                    }
                }.onFailure { e ->
                    ServerState.warn("Hourly update check failed: ${e.message}")
                }
                delay(HOURLY_INTERVAL_MS)
            }
        }
    }

    /** Stop the addon server and tunnel (admin fallback engine is handled by the web admin). */
    fun stopBridge() {
        hourlyUpdateJob?.cancel()
        hourlyUpdateJob = null
        StremioServer.stop()
        ServerState.updateStatus(ServerStatus.Stopped)
        ServerState.info("Server stopped by user")
    }
}
