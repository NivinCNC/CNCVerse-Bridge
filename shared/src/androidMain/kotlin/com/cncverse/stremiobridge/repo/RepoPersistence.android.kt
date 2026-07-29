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
