package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.server.PlatformPaths
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

/**
 * First line of the current file format: values are escaped (`\\`, `\n`, `\r`)
 * so multi-line values (string sets, JSON with newlines) survive. Files
 * without it are the old raw `key=value` lines and are read verbatim.
 */
private const val EXT_SETTINGS_HEADER = "#cnc-ext-settings v2"

private fun escapeValue(v: String): String =
    if (v.none { it == '\\' || it == '\n' || it == '\r' }) v
    else buildString(v.length + 8) {
        for (c in v) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

private fun unescapeValue(v: String): String {
    if ('\\' !in v) return v
    val sb = StringBuilder(v.length)
    var i = 0
    while (i < v.length) {
        val c = v[i]
        if (c == '\\' && i + 1 < v.length) {
            when (v[i + 1]) {
                'n' -> { sb.append('\n'); i += 2; continue }
                'r' -> { sb.append('\r'); i += 2; continue }
                '\\' -> { sb.append('\\'); i += 2; continue }
            }
        }
        sb.append(c); i++
    }
    return sb.toString()
}

private val extSettingsLock = Any()

actual fun loadExtensionSettings(): Map<String, String> = synchronized(extSettingsLock) {
    val f = extSettingsFile
    if (!f.exists()) return emptyMap()
    val lines = f.readLines()
    val escaped = lines.firstOrNull() == EXT_SETTINGS_HEADER
    val map = mutableMapOf<String, String>()
    lines.forEachIndexed { i, line ->
        if (escaped && i == 0) return@forEachIndexed
        val idx = line.indexOf('=')
        if (idx > 0) {
            val value = line.substring(idx + 1)
            map[line.substring(0, idx)] = if (escaped) unescapeValue(value) else value
        }
    }
    map
}

/**
 * Writes to a temp file and renames it over the old one, so a crash, a kill
 * or a concurrent reader never sees a truncated settings file (the old
 * in-place writeText could lose every key).
 */
actual fun saveExtensionSettings(settings: Map<String, String>) {
    // Snapshot first: the live map is written concurrently by plugin threads
    val snapshot = settings.entries.map { it.key to it.value }
    synchronized(extSettingsLock) {
        val f = extSettingsFile
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(EXT_SETTINGS_HEADER)
            for ((k, v) in snapshot) {
                if (k.isEmpty() || '=' in k || '\n' in k || '\r' in k) continue
                w.write("\n"); w.write(k); w.write("="); w.write(escapeValue(v))
            }
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
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

actual fun getExtensionSetting(key: String): String? =
    com.lagradost.cloudstream3.CloudStreamApp.getSettings()[key]

actual fun setExtensionSetting(key: String, value: String?) {
    com.lagradost.cloudstream3.CloudStreamApp.setKey(key, value)
}
