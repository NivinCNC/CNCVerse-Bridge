package com.cncverse.stremiobridge.repo

import android.content.Context

private const val PREFS_NAME = "cnc_repos"
private const val KEY_REPO_URLS = "repo_urls"
private const val SEPARATOR = "|||"

actual fun loadRepoUrls(): List<String> {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_REPO_URLS, null) ?: return emptyList()
    return raw.split(SEPARATOR).filter { it.isNotBlank() }
}

actual fun saveRepoUrls(urls: List<String>) {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_REPO_URLS, urls.joinToString(SEPARATOR)).apply()
}

private const val EXT_PREFS_NAME = "cnc_ext_settings"

actual fun loadExtensionSettings(): Map<String, String> {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(EXT_PREFS_NAME, Context.MODE_PRIVATE)
    val map = mutableMapOf<String, String>()
    prefs.all.forEach { (key, value) ->
        if (value is String) map[key] = value
    }
    return map
}

actual fun saveExtensionSettings(settings: Map<String, String>) {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(EXT_PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.clear()
    settings.forEach { (key, value) ->
        editor.putString(key, value)
    }
    editor.apply()
}

private const val KEY_REPO_CACHE = "repo_cache_json"
private val androidRepoJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

actual fun loadCachedRepoEntries(): List<com.cncverse.stremiobridge.state.RepoEntry> {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_REPO_CACHE, null) ?: return emptyList()
    return runCatching {
        androidRepoJson.decodeFromString<List<com.cncverse.stremiobridge.state.RepoEntry>>(raw)
    }.getOrDefault(emptyList())
}

actual fun saveCachedRepoEntries(entries: List<com.cncverse.stremiobridge.state.RepoEntry>) {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = runCatching { androidRepoJson.encodeToString(entries) }.getOrNull() ?: return
    prefs.edit().putString(KEY_REPO_CACHE, raw).apply()
}

private const val KEY_AVAILABLE_PLUGINS_CACHE = "available_plugins_cache_json"

actual fun loadCachedAvailablePlugins(): List<com.cncverse.stremiobridge.state.AvailablePlugin> {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_AVAILABLE_PLUGINS_CACHE, null) ?: return emptyList()
    return runCatching {
        androidRepoJson.decodeFromString<List<com.cncverse.stremiobridge.state.AvailablePlugin>>(raw)
    }.getOrDefault(emptyList())
}

actual fun saveCachedAvailablePlugins(plugins: List<com.cncverse.stremiobridge.state.AvailablePlugin>) {
    val prefs = AndroidContextHolder.appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = runCatching { androidRepoJson.encodeToString(plugins) }.getOrNull() ?: return
    prefs.edit().putString(KEY_AVAILABLE_PLUGINS_CACHE, raw).apply()
}

actual fun getExtensionSetting(key: String): String? =
    com.lagradost.cloudstream3.CloudStreamApp.getSettings()[key]

actual fun setExtensionSetting(key: String, value: String?) {
    com.lagradost.cloudstream3.CloudStreamApp.setKey(key, value)
}
