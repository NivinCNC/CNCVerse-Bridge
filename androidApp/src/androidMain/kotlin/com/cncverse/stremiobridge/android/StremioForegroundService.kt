package com.cncverse.stremiobridge.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.repo.PluginInstaller
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG          = "StremioForegroundSvc"
private const val NOTIF_ID     = 1001
private const val DEFAULT_PORT = 8080

/**
 * Android Foreground Service that:
 *  1. Loads installed repos from persistence
 *  2. Refreshes repo metadata (fetch only — no bulk download)
 *  3. Auto-updates any installed plugins that have a newer version available
 *  4. Loads installed .cs3 plugins via PathClassLoader
 *  5. Starts the Ktor Stremio HTTP server
 *  6. Shows a persistent notification with server status
 */
class StremioForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): StremioForegroundService = this@StremioForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        startForeground(NOTIF_ID, buildNotification("Starting CNCVerse Stremio Bridge…"))
        ServerState.updateStatus(ServerStatus.Starting("Initialising…"))

        serviceScope.launch {
            runCatching { startBridge() }
                .onFailure { e ->
                    Log.e(TAG, "Bridge failed", e)
                    ServerState.error("Fatal error: ${e.message}")
                    ServerState.updateStatus(ServerStatus.Error(e.message ?: "Unknown error"))
                    updateNotification("Error: ${e.message}")
                }
        }

        return START_STICKY
    }

    private suspend fun startBridge() {
        val port = DEFAULT_PORT
        ServerState.serverPort = port
        val cacheDir = filesDir.absolutePath

        // ── 1. Load installed plugins from disk ────────────────────────────
        ServerState.updateStatus(ServerStatus.Starting("Loading plugin registry…"))
        val installedPlugins = withContext(Dispatchers.IO) {
            PluginInstaller.loadInstalledPlugins(cacheDir)
        }
        RepoState.setInstalledPlugins(installedPlugins)
        // Mark all installed as Installed state
        installedPlugins.forEach { inst ->
            RepoState.setInstallState(inst.internalName, PluginInstallState.Installed)
        }
        ServerState.info("Found ${installedPlugins.size} installed plugin(s)")

        // ── 2. Load repos and kick off background refresh ──────────────────
        RepoManager.loadSavedRepos()
        ServerState.updateStatus(ServerStatus.Starting("Refreshing repos…"))
        updateNotification("Refreshing repos…")

        // Refresh repos in background (does NOT block server startup)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                RepoManager.refreshAllRepos()
                // After refresh, auto-update any outdated installed plugins
                val toUpdate = RepoState.installedPlugins.value.filter {
                    RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                }
                if (toUpdate.isNotEmpty()) {
                    ServerState.info("Auto-updating ${toUpdate.size} plugin(s)…")
                    PluginInstaller.autoUpdateInstalled(cacheDir)
                }
            }.onFailure { e ->
                ServerState.warn("Repo refresh error: ${e.message}")
            }
        }

        // ── 3. (Removed) Load installed .cs3 files ───────────────────────────────────
        // Plugins are now loaded globally by GlobalPluginManager in MainActivity on app launch.
        ServerState.updateStatus(ServerStatus.Starting("Waiting for plugins…"))
        GlobalPluginManager.isPluginsLoaded.first { it }
        val loadedInfos = ServerState.globalLoadedPlugins.value

        // ── 4. Start Ktor server ───────────────────────────────────────────
        StremioServer.start(port, cacheDir)

        // ── 5. Update state to Running ─────────────────────────────────────
        val ipAddress = getLocalIpAddress() ?: "localhost"
        val runningStatus = ServerStatus.Running(
            port          = port,
            loadedPlugins = loadedInfos,
            ipAddress     = ipAddress,
        )
        ServerState.updateStatus(runningStatus)
        updateNotification("Running on $ipAddress:$port · ${loadedInfos.count { it.apiRegistered }} plugins active")
        ServerState.info("🎬 Stremio Bridge running at http://$ipAddress:$port")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    fun stopServer() {
        StremioServer.stop()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        ServerState.updateStatus(ServerStatus.Stopped)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("CNCVerse Stremio Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notif = buildNotification(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notif)
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .firstOrNull()
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
