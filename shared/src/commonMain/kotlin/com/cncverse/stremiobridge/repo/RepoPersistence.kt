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

const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
