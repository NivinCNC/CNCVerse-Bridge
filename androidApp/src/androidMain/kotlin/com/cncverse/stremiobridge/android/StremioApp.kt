package com.cncverse.stremiobridge.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cncverse.stremiobridge.repo.AndroidContextHolder

const val NOTIF_CHANNEL_ID   = "stremio_bridge_service"
const val NOTIF_CHANNEL_NAME = "Stremio Bridge"

class StremioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        AndroidContextHolder.appContext = this
        // Shared bridge runtime (same lifecycle code as the desktop app); the scope lives
        // as long as the process so health sweeps / updates outlive the activity
        com.cncverse.stremiobridge.server.BridgeRuntime.cacheDir = filesDir.absolutePath
        com.cncverse.stremiobridge.server.BridgeRuntime.appScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while the Stremio addon server is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var appContext: Application
    }
}
