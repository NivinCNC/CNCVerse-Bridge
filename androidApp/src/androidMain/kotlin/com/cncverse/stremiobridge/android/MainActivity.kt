package com.cncverse.stremiobridge.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cncverse.stremiobridge.server.BridgeRuntime
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.state.*
import com.cncverse.stremiobridge.ui.MainScreen
import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.plugin.PluginLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : com.lagradost.cloudstream3.MainActivity() {

    private var serviceBound = false
    private var bridgeService: StremioForegroundService? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridgeService = (binder as StremioForegroundService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bridgeService = null
            serviceBound = false
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startBridgeService()
        else Toast.makeText(this, "Notification permission needed for foreground service", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        com.cncverse.stremiobridge.plugin.PluginUIContext.currentActivity = this
    }

    override fun onPause() {
        super.onPause()
        if (com.cncverse.stremiobridge.plugin.PluginUIContext.currentActivity == this) {
            com.cncverse.stremiobridge.plugin.PluginUIContext.currentActivity = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge display for true AMOLED experience
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Prevent crashes on the UI thread caused by missing plugin methods
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try {
                    android.os.Looper.loop()
                } catch (e: Throwable) {
                    if (e is NoSuchMethodError || e is NoClassDefFoundError || e is LinkageError) {
                        ServerState.warn("Plugin attempted to use a missing method/class on UI thread: ${e.message}")
                    } else {
                        ServerState.warn("Caught unhandled UI exception: ${e.message}")
                    }
                }
            }
        }

        // Bind to service if already running
        Intent(this, StremioForegroundService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Pre-load repo list into state (before setContent so UI sees initial data)
        RepoManager.loadSavedRepos()

        // Pre-load installed plugins so the Extensions screen is browsable before the server starts
        activityScope.launch(Dispatchers.IO) {
            if (GlobalPluginManager.loader == null) {
                GlobalPluginManager.loader = PluginLoader(applicationContext)
            }
            BridgeRuntime.ensurePluginsLoaded()

            // Fetch metadata and available plugins immediately so the UI is populated
            RepoManager.refreshAllRepos()
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            MainScreen(
                statusFlow          = ServerState.status,
                logsFlow            = ServerState.logs,
                onStart             = ::onStartPressed,
                onStop              = ::onStopPressed,
                onCopyUrl           = { url -> copyToClipboard("Stremio URL", url) },
                onCopyLogs          = { logText -> copyToClipboard("Logs", logText) },
                onOpenSettings      = { id -> GlobalPluginManager.loader?.openPluginSettings(id, this@MainActivity) },
                // The running server picks up reloaded plugins through StremioServer.loadedApis
                onInstallPlugin     = { ap -> BridgeRuntime.installPlugin(ap) },
                onUninstallPlugin   = { internalName -> BridgeRuntime.uninstallPlugin(internalName) },
                onAddRepo           = { url -> RepoManager.addRepo(url) },
                onRemoveRepo        = { url -> activityScope.launch(Dispatchers.IO) { BridgeRuntime.removeRepo(url) } },
                onRefreshRepos      = { RepoManager.refreshAllRepos() },
                windowWidthClass    = windowSizeClass.widthSizeClass,
            )
        }
    }

    private fun onStartPressed() {
        if (ServerState.status.value is ServerStatus.Running) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!notifGranted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startBridgeService()
    }

    private fun onStopPressed() {
        bridgeService?.stopServer()
        stopService(Intent(this, StremioForegroundService::class.java))
        if (ServerState.status.value !is ServerStatus.Stopped) BridgeRuntime.stopBridge()
    }

    private fun startBridgeService() {
        com.cncverse.stremiobridge.plugin.PluginUIContext.currentActivity = this
        val intent = Intent(this, StremioForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun isSubscribed(): Boolean {
        val settings = com.cncverse.stremiobridge.repo.loadExtensionSettings()
        val mode = settings["KEY_MODE"]
        val token = settings["KEY_LICENSE_TOKEN"]
        val expiresAt = settings["KEY_EXPIRES_AT"]?.toLongOrNull() ?: 0L
        val nowSeconds = System.currentTimeMillis() / 1000
        return mode == "subscription" && token != null && (expiresAt == 0L || nowSeconds < expiresAt)
    }

    private fun shouldBlockIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action == Intent.ACTION_VIEW && isSubscribed()) {
            val url = intent.dataString ?: return false
            if (url.contains("omg10.com")) {
                ServerState.info("Blocked ad URL via subscription")
                return true
            }
        }
        return false
    }

    override fun startActivity(intent: Intent?) {
        if (shouldBlockIntent(intent)) return
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (shouldBlockIntent(intent)) return
        super.startActivity(intent, options)
    }
}
