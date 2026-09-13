package com.cncverse.stremiobridge.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.server.BridgeRuntime
import com.cncverse.stremiobridge.server.PlatformPaths
import com.cncverse.stremiobridge.server.web.AdminServer
import com.cncverse.stremiobridge.server.web.WebAdmin
import com.cncverse.stremiobridge.state.*
import com.cncverse.stremiobridge.update.GithubRelease
import com.cncverse.stremiobridge.update.OtaUpdater
import com.cncverse.stremiobridge.update.installOtaUpdate
import com.cncverse.stremiobridge.update.otaAssetExtension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Modifier
import com.cncverse.stremiobridge.shadowui.ShadowUi
import com.cncverse.stremiobridge.ui.MainScreen
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private const val DEFAULT_PORT = 8080
private val CACHE_DIR: String = PlatformPaths.cacheDir.absolutePath

fun main() = application {
    // Surface fatal errors (e.g. Compose render-thread exceptions) into the
    // app log so crashes in the packaged exe are diagnosable.
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        ServerState.error("Uncaught exception on ${thread.name}: ${e.stackTraceToString().take(800)}")
    }

    val appScope = rememberCoroutineScope()
    val pluginLoader = remember { PluginLoader() }
    var serverJob by remember { mutableStateOf<Job?>(null) }
    val windowState = rememberWindowState(width = 1000.dp, height = 780.dp)
    var settingsPluginId by remember { mutableStateOf<String?>(null) }

    var updateRelease by remember { mutableStateOf<GithubRelease?>(null) }
    var otaDownloadProgress by remember { mutableStateOf<Float?>(null) }

    // Register the loader globally before anything else runs
    GlobalPluginManager.loader = pluginLoader

    // Shared runtime wiring (also used by the web admin when CNC_WEB_ADMIN=1)
    BridgeRuntime.cacheDir = CACHE_DIR
    BridgeRuntime.appScope = appScope
    WebAdmin.appScope = appScope
    WebAdmin.preferredPort = DEFAULT_PORT

    fun startServer() {
        if (ServerState.status.value is ServerStatus.Running) return
        serverJob = appScope.launch {
            runCatching { BridgeRuntime.startBridge(DEFAULT_PORT) }
                .onFailure { e ->
                    ServerState.error("Fatal: ${e.message}")
                    ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown"))
                }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        val port = ServerState.serverPort
        BridgeRuntime.stopBridge()
        // Keep the web admin panel reachable when it is enabled
        if (WebAdmin.isEnabled) {
            appScope.launch { AdminServer.ensureRunning(port) }
        }
    }

    // ── App startup init (mirrors Android MainActivity.onCreate) ──────────
    LaunchedEffect(Unit) {
        // Pre-load repo list into state (before UI interactions)
        RepoManager.loadSavedRepos()

        // Pre-load installed plugins and register them globally so the
        // Extensions screen is browsable even before the server starts
        BridgeRuntime.forceReloadPlugins()

        // Fetch metadata and available plugins so the UI is populated
        RepoManager.refreshAllRepos()

        // Optional headless/testing convenience: start the server on launch
        if (System.getenv("CNC_AUTOSTART") == "1") startServer()

        // Testing hook: open a plugin's settings dialog on launch (gear button)
        System.getenv("CNC_TEST_GEAR")?.let {
            settingsPluginId = it
            appScope.launch(Dispatchers.IO) {
                runCatching { pluginLoader.openPluginSettings(it, null) }
            }
        }

        // Testing hook: simulate a click on the Nth clickable in the shadow UI
        // (exercises the same dispatch path as a real user click).
        System.getenv("CNC_SIMULATE_CLICK")?.toIntOrNull()?.let { clickIndex ->
            val clickDelay = System.getenv("CNC_SIMULATE_CLICK_DELAY")?.toLongOrNull() ?: 4000
            appScope.launch {
                kotlinx.coroutines.delay(clickDelay)
                runCatching {
                    var index = 0
                    android.view.ShadowClickHelper.findNthClickable { view ->
                        if (index == clickIndex) {
                            view.performClick()
                            true
                        } else {
                            index++
                            false
                        }
                    }
                }
            }
        }

        // Testing hook: capture a screenshot after the UI settles (xvfb runs).
        System.getenv("CNC_SCREENSHOT")?.let { shotPath ->
            val delayMs = System.getenv("CNC_SCREENSHOT_DELAY")?.toLongOrNull() ?: 9000
            appScope.launch {
                kotlinx.coroutines.delay(delayMs)
                runCatching {
                    val robot = java.awt.Robot()
                    val screen = Toolkit.getDefaultToolkit().screenSize
                    val image = robot.createScreenCapture(
                        java.awt.Rectangle(0, 0, screen.width.coerceAtMost(1400), screen.height.coerceAtMost(900)),
                    )
                    val out = File(shotPath)
                    out.parentFile?.mkdirs()
                    javax.imageio.ImageIO.write(image, "png", out)
                    ServerState.info("Screenshot saved: ${out.absolutePath}")
                }.onFailure { ServerState.warn("Screenshot failed: ${it.message}") }
                if (System.getenv("CNC_SCREENSHOT_EXIT") == "1") {
                    stopServer()
                    exitApplication()
                }
            }
        }

        if (WebAdmin.isEnabled) {
            ServerState.info("Web admin panel will be available at /admin once the server starts" +
                (WebAdmin.adminToken?.let { " (protected by CNC_ADMIN_TOKEN)" } ?: ""))
        }

        // OTA Update check
        appScope.launch {
            try {
                val release = OtaUpdater.checkForUpdate()
                if (release != null) {
                    updateRelease = release
                }
            } catch (e: Exception) {
                ServerState.warn("Failed to check for updates: ${e.message}")
            }
        }
    }

    Window(
        onCloseRequest = {
            stopServer()
            exitApplication()
        },
        title = "CNCVerse Bridge",
        icon = painterResource("logo.png"),
        state = windowState,
    ) {
        // Desktop windows are wide → left nav rail; shrink to bottom bar if compact
        val windowWidthClass = if (windowState.size.width < 600.dp) {
            androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
        } else {
            androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Medium
        }

        MainScreen(
            statusFlow = ServerState.status,
            logsFlow   = ServerState.logs,
            onStart    = {
                startServer()
            },
            onStop     = {
                stopServer()
            },
            onCopyUrl  = { url -> copyToClipboard(url) },
            onCopyLogs = { logText -> copyToClipboard(logText) },
            onOpenSettings = { id ->
                settingsPluginId = id
                // Run the plugin's openSettings lambda so its key reads register
                // in the schema; the dialog re-renders live as keys are discovered.
                appScope.launch(Dispatchers.IO) {
                    pluginLoader.openPluginSettings(id, null)
                }
            },
            onInstallPlugin = { ap ->
                appScope.launch {
                    BridgeRuntime.installPlugin(ap)
                }
            },
            onUninstallPlugin = { internalName ->
                appScope.launch {
                    BridgeRuntime.uninstallPlugin(internalName)
                }
            },
            onAddRepo      = { url -> RepoManager.addRepo(url) },
            onRemoveRepo   = { url -> RepoManager.removeRepo(url) },
            onRefreshRepos = { RepoManager.refreshAllRepos() },
            windowWidthClass = windowWidthClass,
        )

        // Plugin settings (gear button). Plugins with their own settings UI
        // (AlertDialog / DialogFragment code paths) render through ShadowUi —
        // the plugin's real UI running against recording stubs. Plugins without
        // UI code fall back to the schema-registry dialog.
        settingsPluginId?.let { pluginId ->
            val shadowDialogs by ShadowUi.dialogs.collectAsState()
            val sessionState by ShadowUi.sessionState.collectAsState()
            val producedDialogs by ShadowUi.sessionProducedDialogs.collectAsState()

            when {
                // Live plugin-rendered settings UI
                shadowDialogs.isNotEmpty() -> ShadowUiHost()

                // openSettings is executing on a worker thread — show a spinner
                sessionState == ShadowUi.SessionState.Running -> LoadingSettingsDialog(
                    pluginName = RepoState.installedPlugins.value
                        .firstOrNull { it.internalName == pluginId }?.displayName ?: pluginId,
                )

                // Session finished without any UI → schema fallback
                !producedDialogs -> {
                    val displayName = RepoState.installedPlugins.value
                        .firstOrNull { it.internalName == pluginId }?.displayName
                        ?: pluginId
                    PluginSettingsDialog(
                        pluginInternalName = pluginId,
                        pluginDisplayName = displayName,
                        onDismiss = {
                            settingsPluginId = null
                            appScope.launch(Dispatchers.IO) {
                                val installed = PluginInstaller.loadInstalledPlugins(CACHE_DIR)
                                val cs3Files = PluginInstaller.getInstalledFiles(CACHE_DIR)
                                GlobalPluginManager.reloadAllPlugins(installed, cs3Files)
                            }
                        },
                    )
                }

                // Plugin UI produced dialogs and the stack is now empty → done.
                else -> LaunchedEffect(pluginId) {
                    ShadowUi.endSession()
                    settingsPluginId = null
                    // Apply changed settings without restart, like the fallback path.
                    val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(CACHE_DIR) }
                    val cs3Files = withContext(Dispatchers.IO) { PluginInstaller.getInstalledFiles(CACHE_DIR) }
                    GlobalPluginManager.reloadAllPlugins(installed, cs3Files)
                }
            }
        }

        // OTA Update Dialog
        updateRelease?.let { release ->
            AlertDialog(
                onDismissRequest = {
                    if (otaDownloadProgress == null) updateRelease = null
                },
                title = { Text("Update Available") },
                text = {
                    Column {
                        Text("A new version of CNCVerse Bridge is available: ${release.tagName}")
                        if (otaDownloadProgress != null) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { otaDownloadProgress ?: 0f })
                            Text("Downloading: ${(otaDownloadProgress!! * 100).toInt()}%")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = otaDownloadProgress == null,
                        onClick = {
                            val asset = release.assets.find { it.name.endsWith(otaAssetExtension, ignoreCase = true) }
                            if (asset != null) {
                                otaDownloadProgress = 0f
                                appScope.launch {
                                    val filePath = OtaUpdater.downloadUpdate(asset.downloadUrl, asset.name) { progress ->
                                        otaDownloadProgress = progress
                                    }
                                    if (filePath != null) {
                                        installOtaUpdate(filePath)
                                    } else {
                                        ServerState.warn("Update download failed")
                                        updateRelease = null
                                        otaDownloadProgress = null
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (otaDownloadProgress != null) "Downloading..." else "Install & Restart")
                    }
                },
                dismissButton = {
                    if (otaDownloadProgress == null) {
                        Button(onClick = { updateRelease = null }) {
                            Text("Later")
                        }
                    }
                }
            )
        }
    }
}

private fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        ServerState.info("Copied to clipboard")
    } catch (e: Exception) {
        ServerState.warn("Could not copy to clipboard: ${e.message}")
    }
}

/** Spinner shown while the plugin's settings UI code executes on a worker thread. */
@Composable
private fun LoadingSettingsDialog(pluginName: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("$pluginName Settings") },
        text = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                Text("Opening $pluginName settings…")
            }
        },
    )
}


/**
 * Mirrors the Android StremioForegroundService.startBridge flow:
 *  1. Load installed plugin registry from disk
 *  2. Refresh repos (background, non-blocking) + auto-update outdated plugins
 *  3. Wait for GlobalPluginManager to finish loading plugins
 *  4. Start the Ktor Stremio HTTP server
 */
private suspend fun startBridge(appScope: CoroutineScope) {
    ServerState.updateStatus(ServerStatus.Starting("Loading plugin registry…"))
    val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(CACHE_DIR) }
    RepoState.setInstalledPlugins(installed)
    installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }
    ServerState.info("Found ${installed.size} installed plugin(s)")

    RepoManager.loadSavedRepos()
    ServerState.updateStatus(ServerStatus.Starting("Refreshing repos…"))

    // Refresh repos in background (does NOT block server startup)
    appScope.launch(Dispatchers.IO) {
        runCatching {
            RepoManager.refreshAllRepos()
            val toUpdate = RepoState.installedPlugins.value.filter {
                RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
            }
            if (toUpdate.isNotEmpty()) {
                ServerState.info("Auto-updating ${toUpdate.size} plugin(s)…")
                PluginInstaller.autoUpdateInstalled(CACHE_DIR)
            }
        }.onFailure { e ->
            ServerState.warn("Repo refresh error: ${e.message}")
        }
    }

    // Wait for the global plugin load kicked off at app start (bounded, so a
    // failed load can't wedge the server in "Waiting for plugins" forever)
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
    val boundPort = StremioServer.start(DEFAULT_PORT, CACHE_DIR)

    ServerState.updateStatus(
        ServerStatus.Running(
            port          = boundPort,
            loadedPlugins = loadedInfos,
            ipAddress     = ipAddress,
        )
    )
    ServerState.info("🎬 Bridge running at http://$ipAddress:$boundPort/manifest.json")

}

private fun getLocalIpAddress(): String? = try {
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
