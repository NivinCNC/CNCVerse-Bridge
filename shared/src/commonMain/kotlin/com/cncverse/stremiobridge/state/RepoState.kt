package com.cncverse.stremiobridge.state

import com.cncverse.stremiobridge.model.SitePlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

// ─── Repo Models ─────────────────────────────────────────────────────────────

@Serializable
data class RepoEntry(
    val url: String,
    val name: String = "",
    val iconUrl: String? = null,
    val description: String? = null,
    val lastFetched: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class AvailablePlugin(
    val plugin: SitePlugin,
    val repoEntry: RepoEntry,
)

@Serializable
data class InstalledPlugin(
    val internalName: String,
    val displayName: String,
    val version: Int,
    val fileHash: String?,
    val repoUrl: String,
    val localPath: String,
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
)

sealed class PluginInstallState {
    object NotInstalled : PluginInstallState()
    data class Installing(val progress: String = "") : PluginInstallState()
    object Installed : PluginInstallState()
    data class UpdateAvailable(val newVersion: Int) : PluginInstallState()
    data class Failed(val error: String) : PluginInstallState()
}

// ─── Repo State Singleton ─────────────────────────────────────────────────────

object RepoState {
    private val _repos = MutableStateFlow<List<RepoEntry>>(emptyList())
    val repos: StateFlow<List<RepoEntry>> = _repos.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<AvailablePlugin>>(emptyList())
    val availablePlugins: StateFlow<List<AvailablePlugin>> = _availablePlugins.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledPlugin>> = _installedPlugins.asStateFlow()

    private val _pluginInstallStates = MutableStateFlow<Map<String, PluginInstallState>>(emptyMap())
    val pluginInstallStates: StateFlow<Map<String, PluginInstallState>> = _pluginInstallStates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun setRepos(repos: List<RepoEntry>) { _repos.value = repos }

    fun updateRepo(url: String, update: (RepoEntry) -> RepoEntry) {
        _repos.value = _repos.value.map { if (it.url == url) update(it) else it }
    }

    fun addRepo(repo: RepoEntry) {
        if (_repos.value.none { it.url == repo.url }) {
            _repos.value = _repos.value + repo
        }
    }

    fun removeRepo(url: String) {
        _repos.value = _repos.value.filter { it.url != url }
        // Remove plugins from removed repo
        _availablePlugins.value = _availablePlugins.value.filter { it.repoEntry.url != url }
    }

    fun mergeAvailablePlugins(plugins: List<AvailablePlugin>, repoUrl: String) {
        val others = _availablePlugins.value.filter { it.repoEntry.url != repoUrl }
        _availablePlugins.value = others + plugins
    }

    fun setInstalledPlugins(plugins: List<InstalledPlugin>) { _installedPlugins.value = plugins }

    fun markInstalled(plugin: InstalledPlugin) {
        val current = _installedPlugins.value.filter { it.internalName != plugin.internalName }
        _installedPlugins.value = current + plugin
        setInstallState(plugin.internalName, PluginInstallState.Installed)
    }

    fun markUninstalled(internalName: String) {
        _installedPlugins.value = _installedPlugins.value.filter { it.internalName != internalName }
        setInstallState(internalName, PluginInstallState.NotInstalled)
    }

    fun setInstallState(internalName: String, state: PluginInstallState) {
        _pluginInstallStates.value = _pluginInstallStates.value + (internalName to state)
    }

    fun getInstallState(internalName: String): PluginInstallState =
        _pluginInstallStates.value[internalName] ?: PluginInstallState.NotInstalled

    fun setRefreshing(refreshing: Boolean) { _isRefreshing.value = refreshing }

    fun isInstalled(internalName: String): Boolean =
        _installedPlugins.value.any { it.internalName == internalName }
}
