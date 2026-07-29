package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.InstalledPlugin
import com.cncverse.stremiobridge.state.ServerState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

object GlobalPluginManager {
    var loader: PluginLoader? = null
    val isPluginsLoaded = MutableStateFlow(false)

    suspend fun reloadAllPlugins(
        installedPlugins: List<InstalledPlugin>,
        cs3Files: Map<String, File>,
    ) {
        val currentLoader = loader
        if (currentLoader == null) {
            ServerState.warn("GlobalPluginManager: loader is not initialized")
            return
        }

        // Unload existing
        currentLoader.unloadAll()
        ServerState.updateGlobalLoadedPlugins(emptyList())
        isPluginsLoaded.value = false

        if (installedPlugins.isEmpty()) {
            ServerState.info("No installed plugins to load")
            isPluginsLoaded.value = true
            return
        }

        val sitePlugins = installedPlugins.map { inst ->
            SitePlugin(
                url          = inst.localPath,
                name         = inst.displayName,
                internalName = inst.internalName,
                version      = inst.version,
                fileHash     = inst.fileHash,
                iconUrl      = inst.iconUrl,
                tvTypes      = inst.tvTypes,
                language     = inst.language,
                authors      = inst.authors,
                description  = inst.description,
            )
        }

        ServerState.info("GlobalPluginManager: Loading ${sitePlugins.size} plugins...")
        val loadedInfos = currentLoader.loadPlugins(sitePlugins, cs3Files)
        ServerState.updateGlobalLoadedPlugins(loadedInfos)
        ServerState.info("GlobalPluginManager: ${loadedInfos.size} plugins loaded successfully")
        isPluginsLoaded.value = true
    }
}
