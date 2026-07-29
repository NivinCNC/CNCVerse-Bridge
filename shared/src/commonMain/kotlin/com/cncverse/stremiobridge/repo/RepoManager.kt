package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the user's list of repos and orchestrates fetching metadata on launch.
 * Repo URLs are persisted via [loadRepoUrls] / [saveRepoUrls].
 */
object RepoManager {

    /**
     * Load saved repos from persistence and seed the default repo if none exist.
     * Should be called once at app start (before refreshing).
     */
    fun loadSavedRepos() {
        var urls = loadRepoUrls().toMutableList()
        if (urls.isEmpty()) {
            urls = mutableListOf(DEFAULT_REPO_URL)
            saveRepoUrls(urls)
        }
        val entries = urls.map { url -> RepoEntry(url = url) }
        RepoState.setRepos(entries)
    }

    /**
     * Persists the current list of repo URLs.
     */
    private fun persistUrls() {
        saveRepoUrls(RepoState.repos.value.map { it.url })
    }

    /**
     * Adds a new repo by URL. Fetches its metadata immediately.
     * Returns the fetched [RepoEntry] or null if the URL was invalid.
     */
    suspend fun addRepo(url: String): RepoEntry? {
        val trimmed = PluginRepository.resolveShortCode(url)
        if (RepoState.repos.value.any { it.url == trimmed }) return null

        // Placeholder while loading
        val placeholder = RepoEntry(url = trimmed, isLoading = true)
        RepoState.addRepo(placeholder)

        val meta = PluginRepository.fetchRepoMeta(trimmed)
        val entry = if (meta != null) {
            RepoEntry(
                url = trimmed,
                name = meta.name,
                iconUrl = meta.iconUrl,
                description = meta.description,
                lastFetched = System.currentTimeMillis(),
            )
        } else {
            RepoEntry(url = trimmed, error = "Could not fetch repo metadata")
        }

        RepoState.updateRepo(trimmed) { entry }
        persistUrls()

        // Fetch plugins for this repo
        if (meta != null) fetchPluginsForRepo(entry, meta)
        return entry
    }

    /**
     * Removes a repo and its plugins from state. Uninstalled plugins are NOT deleted from disk.
     */
    fun removeRepo(url: String) {
        RepoState.removeRepo(url)
        persistUrls()
    }

    private val refreshMutex = Mutex()

    /**
     * Refresh all repos: fetch metadata + plugin lists.
     * Should be called on every app launch. Runs concurrently.
     */
    suspend fun refreshAllRepos() {
        if (!refreshMutex.tryLock()) {
            // Already refreshing, wait for it to finish
            refreshMutex.withLock { }
            return
        }
        try {
            RepoState.setRefreshing(true)
            coroutineScope {
                RepoState.repos.value.map { repoEntry ->
                    async(Dispatchers.IO) { refreshRepo(repoEntry) }
                }.awaitAll()
            }
        } finally {
            RepoState.setRefreshing(false)
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshRepo(repoEntry: RepoEntry) {
        RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = true, error = null) }
        val meta = PluginRepository.fetchRepoMeta(repoEntry.url)
        if (meta == null) {
            RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = false, error = "Fetch failed") }
            return
        }
        val updated = repoEntry.copy(
            name = meta.name,
            iconUrl = meta.iconUrl,
            description = meta.description,
            lastFetched = System.currentTimeMillis(),
            isLoading = false,
            error = null,
        )
        RepoState.updateRepo(repoEntry.url) { updated }
        fetchPluginsForRepo(updated, meta)
    }

    private suspend fun fetchPluginsForRepo(repoEntry: RepoEntry, meta: com.cncverse.stremiobridge.model.CncRepository) {
        val all = meta.pluginLists.flatMap { listUrl ->
            PluginRepository.fetchPluginsFromUrl(listUrl)
        }
        val wrapped = all.map { AvailablePlugin(plugin = it, repoEntry = repoEntry) }
        RepoState.mergeAvailablePlugins(wrapped, repoEntry.url)

        // Update install states: mark UpdateAvailable where version changed
        val installed = RepoState.installedPlugins.value
        wrapped.forEach { ap ->
            val inst = installed.find { it.internalName == ap.plugin.internalName }
            if (inst != null && inst.repoUrl == ap.repoEntry.url) {
                val hasNewVersion = ap.plugin.version > inst.version ||
                        (ap.plugin.fileHash != null && ap.plugin.fileHash != inst.fileHash)
                if (hasNewVersion) {
                    RepoState.setInstallState(
                        ap.plugin.internalName,
                        PluginInstallState.UpdateAvailable(ap.plugin.version)
                    )
                } else if (RepoState.getInstallState(ap.plugin.internalName) !is PluginInstallState.Installing) {
                    RepoState.setInstallState(ap.plugin.internalName, PluginInstallState.Installed)
                }
            }
        }
    }
}
