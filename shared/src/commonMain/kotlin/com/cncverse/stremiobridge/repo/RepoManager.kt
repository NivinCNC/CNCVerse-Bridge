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
        val cached = runCatching { loadCachedRepoEntries() }.getOrDefault(emptyList()).associateBy { it.url }
        val entries = urls.map { url ->
            val prev = cached[url]
            if (prev != null) {
                prev.copy(isLoading = false, error = null)
            } else {
                RepoEntry(url = url, isLoading = false)
            }
        }
        RepoState.setRepos(entries)

        val cachedPlugins = runCatching { loadCachedAvailablePlugins() }.getOrDefault(emptyList())
        if (cachedPlugins.isNotEmpty()) {
            RepoState.setAvailablePlugins(cachedPlugins)
        }
    }

    /**
     * Persists the current list of repo URLs.
     */
    private fun persistUrls() {
        saveRepoUrls(RepoState.repos.value.map { it.url })
    }

    /**
     * Adds a new repo by URL. Fetches its metadata immediately.
     * @param saveGlobally If true (default) the URL is persisted to disk globally.
     *   Pass false to add a local/session-only repo that is not saved.
     * Returns the fetched [RepoEntry] or null if the URL was invalid.
     */
    suspend fun addRepo(url: String, saveGlobally: Boolean = true): RepoEntry? {
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
                isLoading = false,
            )
        } else {
            RepoEntry(url = trimmed, isLoading = false, error = "Could not fetch repo metadata")
        }

        RepoState.updateRepo(trimmed) { entry }
        if (saveGlobally) {
            persistUrls()
            saveCachedRepoEntries(RepoState.repos.value.map { it.copy(isLoading = false) })
            saveCachedAvailablePlugins(RepoState.availablePlugins.value)
        }

        // Fetch plugins for this repo
        if (meta != null) fetchPluginsForRepo(entry, meta)
        return entry
    }

    /**
     * Removes a repo and its plugins from state. When [cacheDir] is given the
     * repo's installed extensions are also deleted from disk (.cs3 + converted
     * jars) and removed from installed_plugins.json. Returns the removed
     * internal names.
     */
    fun removeRepo(url: String, cacheDir: String? = null): List<String> {
        val removed = if (!cacheDir.isNullOrEmpty()) PluginInstaller.removePluginsForRepo(url, cacheDir) else emptyList()
        RepoState.removeRepo(url)
        persistUrls()
        saveCachedRepoEntries(RepoState.repos.value.map { it.copy(isLoading = false) })
        saveCachedAvailablePlugins(RepoState.availablePlugins.value)
        return removed
    }

    private val refreshMutex = Mutex()

    /**
     * Refresh all repos: fetch metadata + plugin lists.
     * Should be called on every app launch. Runs concurrently.
     */
    suspend fun refreshAllRepos() {
        if (!refreshMutex.tryLock()) {
            // Already refreshing, wait for it to finish up to 30 seconds
            withTimeoutOrNull(30_000) { refreshMutex.withLock { } }
            return
        }
        try {
            RepoState.setRefreshing(true)
            withTimeoutOrNull(45_000) {
                supervisorScope {
                    RepoState.repos.value.map { repoEntry ->
                        async(Dispatchers.IO) { refreshRepo(repoEntry) }
                    }.awaitAll()
                }
            }
            saveCachedRepoEntries(RepoState.repos.value.map { it.copy(isLoading = false) })
        } catch (e: Exception) {
            ServerState.warn("Repo refresh error: ${e.message}")
        } finally {
            RepoState.setRefreshing(false)
            // Guarantee no repo remains stuck in isLoading state
            RepoState.repos.value.forEach { r ->
                if (r.isLoading) {
                    RepoState.updateRepo(r.url) { it.copy(isLoading = false) }
                }
            }
            if (refreshMutex.isLocked) {
                runCatching { refreshMutex.unlock() }
            }
        }
    }

    private suspend fun refreshRepo(repoEntry: RepoEntry) {
        RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = true, error = null) }
        try {
            withTimeout(30_000) {
                val meta = PluginRepository.fetchRepoMeta(repoEntry.url)
                if (meta == null) {
                    RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = false, error = "Fetch failed") }
                    return@withTimeout
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
        } catch (e: Exception) {
            ServerState.warn("Failed to refresh repo '${repoEntry.url}': ${e.message}")
            RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = false, error = e.message ?: "Timeout") }
        } finally {
            RepoState.updateRepo(repoEntry.url) { it.copy(isLoading = false) }
        }
    }

    private suspend fun fetchPluginsForRepo(repoEntry: RepoEntry, meta: com.cncverse.stremiobridge.model.CncRepository) {
        val all = meta.pluginLists.flatMap { listUrl ->
            PluginRepository.fetchPluginsFromUrl(listUrl)
        }
        val wrapped = all.map { AvailablePlugin(plugin = it, repoEntry = repoEntry) }
        RepoState.mergeAvailablePlugins(wrapped, repoEntry.url)
        saveCachedAvailablePlugins(RepoState.availablePlugins.value)

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
