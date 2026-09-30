package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.server.PlatformPaths
import java.io.File

private val repoUrlsFile: File
    get() = PlatformPaths.fileInConfigDir("repos.txt")

actual fun loadRepoUrls(): List<String> {
    val f = repoUrlsFile
    if (!f.exists()) return emptyList()
    return f.readLines().filter { it.isNotBlank() }
}

actual fun saveRepoUrls(urls: List<String>) {
    repoUrlsFile.writeText(urls.joinToString("\n"))
}

private val extSettingsFile: File
    get() = PlatformPaths.fileInConfigDir("ext_settings.txt")

actual fun loadExtensionSettings(): Map<String, String> {
    val f = extSettingsFile
    if (!f.exists()) return emptyMap()
    val map = mutableMapOf<String, String>()
    f.readLines().forEach { line ->
        val idx = line.indexOf('=')
        if (idx > 0) {
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            map[key] = value
        }
    }
    return map
}

actual fun saveExtensionSettings(settings: Map<String, String>) {
    extSettingsFile.writeText(settings.map { "${it.key}=${it.value}" }.joinToString("\n"))
}

private val repoCacheJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val repoCacheFile: File
    get() = PlatformPaths.fileInConfigDir("repos_cache.json")

actual fun loadCachedRepoEntries(): List<com.cncverse.stremiobridge.state.RepoEntry> {
    val f = repoCacheFile
    if (!f.exists()) return emptyList()
    return runCatching {
        repoCacheJson.decodeFromString<List<com.cncverse.stremiobridge.state.RepoEntry>>(f.readText())
    }.getOrDefault(emptyList())
}

actual fun saveCachedRepoEntries(entries: List<com.cncverse.stremiobridge.state.RepoEntry>) {
    runCatching {
        repoCacheFile.writeText(repoCacheJson.encodeToString(entries))
    }
}

private val availablePluginsCacheFile: File
    get() = PlatformPaths.fileInConfigDir("available_plugins_cache.json")

actual fun loadCachedAvailablePlugins(): List<com.cncverse.stremiobridge.state.AvailablePlugin> {
    val f = availablePluginsCacheFile
    if (!f.exists()) return emptyList()
    return runCatching {
        repoCacheJson.decodeFromString<List<com.cncverse.stremiobridge.state.AvailablePlugin>>(f.readText())
    }.getOrDefault(emptyList())
}

actual fun saveCachedAvailablePlugins(plugins: List<com.cncverse.stremiobridge.state.AvailablePlugin>) {
    runCatching {
        availablePluginsCacheFile.writeText(repoCacheJson.encodeToString(plugins))
    }
}
