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
import com.cncverse.stremiobridge.server.BridgeRuntime
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
        if (ServerState.status.value is ServerStatus.Running && StremioServer.isRunning) {
            Log.i(TAG, "Server is already running, skipping restart.")
            val ipAddress = getLocalIpAddress() ?: "localhost"
            val loadedCount = ServerState.globalLoadedPlugins.value.count { it.apiRegistered }
            startForeground(NOTIF_ID, buildNotification("Running on $ipAddress:${ServerState.serverPort} · $loadedCount plugins active"))
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Starting CNCVerse Bridge…"))
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
        updateNotification("Starting CNCVerse Bridge…")
        // Same lifecycle as the desktop app: registry → repo refresh + auto-update →
        // plugins → Ktor server, plus health tracking, nightly maintenance and pre-warm
        BridgeRuntime.startBridge(DEFAULT_PORT)
        val running = ServerState.status.value as? ServerStatus.Running ?: return
        updateNotification("Running on ${running.ipAddress}:${running.port} · ${running.loadedPlugins.count { it.apiRegistered }} plugins active")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    fun stopServer() {
        BridgeRuntime.stopBridge()
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
            .setContentTitle("CNCVerse Bridge")
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
        } catch (e: Exception) {
            null
        }
    }
}
