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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
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
            runCatching { pluginLoader.openPluginSettings(it, null) }
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

        // Plugin settings dialog (gear button) — desktop-native rendering of the
        // settings keys the plugin registered through CloudStreamApp/DataStore.
        // Must compose inside the Window: dialogs need the scene context.
        settingsPluginId?.let { pluginId ->
            val displayName = RepoState.installedPlugins.value
                .firstOrNull { it.internalName == pluginId }?.displayName
                ?: pluginId
            PluginSettingsDialog(
                pluginInternalName = pluginId,
                pluginDisplayName = displayName,
                onDismiss = {
                    settingsPluginId = null
                    // Apply changed settings without an app restart: providers
                    // re-read their prefs during load (mirrors the reference
                    // client's reload-on-close behavior)
                    appScope.launch(Dispatchers.IO) {
                        BridgeRuntime.forceReloadPlugins()
                    }
                },
            )
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
