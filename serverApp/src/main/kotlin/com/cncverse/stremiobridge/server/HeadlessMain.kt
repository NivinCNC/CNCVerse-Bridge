package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.server.web.AdminServer
import com.cncverse.stremiobridge.server.web.WebAdmin
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * Headless server entrypoint — the UI-less Linux/server edition of CNCVerse
 * Bridge. Runs the same bridge engine as the desktop app, but all management
 * (server control, extensions, per-plugin settings, tunnel, logs) is exposed
 * through the web admin panel served at `/admin` on the same port as the
 * Stremio addon.
 *
 * Configuration (environment variables):
 *   CNC_PORT          — preferred HTTP port (default 8080)
 *   CNC_CACHE_DIR     — plugin cache dir (default ~/.cncverse_bridge)
 *   CNC_CONFIG_DIR    — settings/repo storage dir (default ~/.cncverse)
 *   CNC_ADMIN_TOKEN   — optional bearer/token protecting the web admin panel
 *
 * CLI: --port=NNNN overrides CNC_PORT.
 */
private const val DEFAULT_PORT = 8080

private val CACHE_DIR: String = PlatformPaths.cacheDir.absolutePath

fun main(args: Array<String>): Unit = runBlocking {
    // ── CLI & env configuration ─────────────────────────────────────────────
    val argPort = args.firstOrNull { it.startsWith("--port=") }?.removePrefix("--port=")?.toIntOrNull()
    val port = argPort
        ?: System.getenv("CNC_PORT")?.trim()?.toIntOrNull()
        ?: DEFAULT_PORT

    // ── Logging & crash visibility ───────────────────────────────────────────
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        ServerState.error("Uncaught exception on ${thread.name}: ${e.stackTraceToString().take(800)}")
    }

    // ── Runtime wiring ───────────────────────────────────────────────────────
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    BridgeRuntime.cacheDir = CACHE_DIR
    BridgeRuntime.appScope = scope
    BridgeRuntime.headlessMode = true
    WebAdmin.appScope = scope
    WebAdmin.preferredPort = port
    WebAdmin.headlessMode = true   // web admin is always on in server mode

    GlobalPluginManager.loader = PluginLoader()

    // Clean shutdown for systemd/docker stop signals
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching {
            ServerState.info("Shutdown requested — stopping server…")
            StremioServer.stop()
            AdminServer.stop()
        }
    })

    ServerState.info("CNCVerse Bridge server (headless) starting…")
    ServerState.info("Plugin cache dir: $CACHE_DIR")
    WebAdmin.adminToken?.let {
        ServerState.info("Web admin is protected by CNC_ADMIN_TOKEN — append ?token=<your token> to /admin")
    }

    // ── Boot the bridge (same flow as the desktop app) ──────────────────────
    runCatching { BridgeRuntime.startBridge(port) }
        .onFailure { e ->
            ServerState.error("Fatal startup failure: ${e.stackTraceToString().take(800)}")
            ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown"))
        }

    // If the addon server failed to start, still bring up the admin panel so
    // the operator can diagnose and retry from the browser.
    if (ServerState.status.value !is ServerStatus.Running && !StremioServer.isRunning) {
        ServerState.warn("Addon server not running — starting admin-only mode on port $port")
        AdminServer.ensureRunning(port)
    }

    val boundPort = (ServerState.status.value as? ServerStatus.Running)?.port ?: port
    val ip = BridgeRuntime.getLocalIpAddress() ?: "127.0.0.1"
    ServerState.info("🌐 Web admin panel: http://$ip:$boundPort/admin")
    ServerState.info("▶ Add to Stremio:        http://$ip:$boundPort/manifest.json")

    // Keep the process alive until cancelled (SIGTERM → shutdown hook)
    awaitCancellation()
}
