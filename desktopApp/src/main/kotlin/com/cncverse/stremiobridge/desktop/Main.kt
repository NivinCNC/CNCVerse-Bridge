package com.cncverse.stremiobridge.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.repo.PluginRepository
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.*
import com.cncverse.stremiobridge.ui.MainScreen
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

private const val DEFAULT_PORT = 8080

fun main() = application {
    val appScope = rememberCoroutineScope()
    var serverJob: Job? = null
    val pluginLoader = remember { PluginLoader() }

    fun startServer() {
        if (ServerState.status.value is ServerStatus.Running) return
        serverJob = appScope.launch {
            runCatching { launchBridge(pluginLoader) }
                .onFailure { e ->
                    ServerState.error("Fatal: ${e.message}")
                    ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown"))
                }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        StremioServer.stop()
        pluginLoader.unloadAll()
        ServerState.updateStatus(ServerStatus.Stopped)
        ServerState.info("Server stopped by user")
    }

    Window(
        onCloseRequest = {
            stopServer()
            exitApplication()
        },
        title = "CNCVerse Stremio Bridge",
        state = WindowState(width = 640.dp, height = 780.dp),
    ) {
        MainScreen(
            statusFlow = ServerState.status,
            logsFlow   = ServerState.logs,
            onStart    = ::startServer,
            onStop     = ::stopServer,
            onCopyUrl  = { url ->
                try {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(url), null)
                    ServerState.info("Copied: $url")
                } catch (e: Exception) {
                    ServerState.warn("Could not copy to clipboard: ${e.message}")
                }
            },
            onCopyLogs = { logText ->
                try {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(logText), null)
                    ServerState.info("Copied logs to clipboard")
                } catch (e: Exception) {
                    ServerState.warn("Could not copy logs: ${e.message}")
                }
            },
        )
    }
}

private suspend fun launchBridge(pluginLoader: PluginLoader) {
    val port = DEFAULT_PORT
    ServerState.serverPort = port

    // 1. Fetch plugin list
    ServerState.updateStatus(ServerStatus.Starting("Fetching plugin list from CNC.json…"))
    val allPlugins: List<SitePlugin> = withContext(Dispatchers.IO) {
        PluginRepository.fetchAllPlugins()
    }
    ServerState.info("Found ${allPlugins.size} plugins")

    // 2. Download .cs3 files
    ServerState.updateStatus(ServerStatus.Starting("Downloading ${allPlugins.size} plugins…"))
    val cacheDir = File(System.getProperty("user.home"), ".cncverse_bridge").absolutePath
    val cs3Files = mutableMapOf<String, File>()

    withContext(Dispatchers.IO) {
        allPlugins.forEach { plugin ->
            PluginRepository.downloadPlugin(plugin, cacheDir)?.let { file ->
                cs3Files[plugin.internalName] = file
            }
        }
    }

    // 3. Convert DEX → JAR and load plugins via dex2jar + URLClassLoader
    ServerState.updateStatus(ServerStatus.Starting("Converting & loading plugins via dex2jar…"))
    val loadedInfos = withContext(Dispatchers.IO) {
        pluginLoader.loadPlugins(allPlugins, cs3Files)
    }

    // 4. Start HTTP server
    StremioServer.start(port, cacheDir)

    // 5. Update state
    val ipAddress = getLocalIpAddress() ?: "localhost"
    ServerState.updateStatus(
        ServerStatus.Running(
            port          = port,
            loadedPlugins = loadedInfos,
            ipAddress     = ipAddress,
        )
    )
    ServerState.info("🎬 Bridge running at http://$ipAddress:$port/manifest.json")

    // Open browser automatically
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI("http://localhost:$port/"))
        }
    } catch (_: Exception) {}
}

private fun getLocalIpAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress }
        .firstOrNull()
        ?.hostAddress
} catch (_: Exception) { null }
