package com.cncverse.stremiobridge.repo

/**
 * Platform-specific persistence for the user's list of repo URLs.
 * Android: SharedPreferences
 * Desktop: local JSON file
 */
expect fun loadRepoUrls(): List<String>
expect fun saveRepoUrls(urls: List<String>)

expect fun loadExtensionSettings(): Map<String, String>
expect fun saveExtensionSettings(settings: Map<String, String>)

expect fun loadCachedRepoEntries(): List<com.cncverse.stremiobridge.state.RepoEntry>
expect fun saveCachedRepoEntries(entries: List<com.cncverse.stremiobridge.state.RepoEntry>)

expect fun loadCachedAvailablePlugins(): List<com.cncverse.stremiobridge.state.AvailablePlugin>
expect fun saveCachedAvailablePlugins(plugins: List<com.cncverse.stremiobridge.state.AvailablePlugin>)

const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
