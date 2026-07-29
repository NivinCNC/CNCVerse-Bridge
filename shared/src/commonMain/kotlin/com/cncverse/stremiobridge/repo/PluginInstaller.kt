package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.*
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val installedJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Handles install/uninstall/update of individual plugins.
 * Installed plugin metadata is persisted as installed_plugins.json in the cache dir.
 */
object PluginInstaller {

    private fun installedFile(cacheDir: String) =
        File(cacheDir, "installed_plugins.json")

    /** Load persisted installed plugin list from disk. */
    fun loadInstalledPlugins(cacheDir: String): List<InstalledPlugin> {
        val f = installedFile(cacheDir)
        if (!f.exists()) return emptyList()
        return try {
            installedJson.decodeFromString<List<InstalledPlugin>>(f.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Persist the current installed plugin list to disk. */
    private fun saveInstalledPlugins(cacheDir: String) {
        try {
            installedFile(cacheDir).writeText(
                installedJson.encodeToString(RepoState.installedPlugins.value)
            )
        } catch (_: Exception) {}
    }

    /** Install a plugin: download its .cs3 file and mark it as installed. */
    suspend fun installPlugin(ap: AvailablePlugin, cacheDir: String): Boolean {
        val plugin = ap.plugin
        RepoState.setInstallState(plugin.internalName, PluginInstallState.Installing("Downloading…"))
        ServerState.info("Installing '${plugin.name}'…")

        val file = PluginRepository.downloadPlugin(plugin, cacheDir)
        return if (file != null) {
            val installed = InstalledPlugin(
                internalName = plugin.internalName,
                displayName  = plugin.name,
                version      = plugin.version,
                fileHash     = plugin.fileHash,
                repoUrl      = ap.repoEntry.url,
                localPath    = file.absolutePath,
                iconUrl      = plugin.iconUrl,
                tvTypes      = plugin.tvTypes ?: emptyList(),
                language     = plugin.language,
                authors      = plugin.authors,
                description  = plugin.description,
            )
            RepoState.markInstalled(installed)
            saveInstalledPlugins(cacheDir)
            ServerState.info("Installed '${plugin.name}' v${plugin.version}")
            true
        } else {
            RepoState.setInstallState(plugin.internalName, PluginInstallState.Failed("Download failed"))
            false
        }
    }

    /** Uninstall a plugin: remove the .cs3 file from disk and clear install record. */
    suspend fun uninstallPlugin(internalName: String, cacheDir: String) = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "plugins")
        dir.listFiles { f -> f.nameWithoutExtension == internalName.replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase() }
            ?.firstOrNull()?.delete()
        RepoState.markUninstalled(internalName)
        saveInstalledPlugins(cacheDir)
        ServerState.info("Uninstalled '$internalName'")
    }

    /**
     * Auto-updates any installed plugin that has UpdateAvailable state.
     * Downloads the new version and replaces the cached file.
     */
    suspend fun autoUpdateInstalled(cacheDir: String) {
        val available = RepoState.availablePlugins.value
        val installed = RepoState.installedPlugins.value
        val toUpdate = installed.filter { inst ->
            RepoState.getInstallState(inst.internalName) is PluginInstallState.UpdateAvailable
        }

        toUpdate.forEach { inst ->
            val ap = available.find { it.plugin.internalName == inst.internalName && it.repoEntry.url == inst.repoUrl } ?: return@forEach
            ServerState.info("Auto-updating '${inst.displayName}' to v${ap.plugin.version}…")
            installPlugin(ap, cacheDir)
        }
    }

    /** Returns locally available .cs3 files for all installed plugins. */
    fun getInstalledFiles(cacheDir: String): Map<String, File> {
        val dir = File(cacheDir, "plugins")
        if (!dir.exists()) return emptyMap()
        return RepoState.installedPlugins.value.mapNotNull { inst ->
            val f = File(inst.localPath)
            if (f.exists()) inst.internalName to f else null
        }.toMap()
    }
}
