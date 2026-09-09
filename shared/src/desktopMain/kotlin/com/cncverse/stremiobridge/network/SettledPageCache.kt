package com.cncverse.stremiobridge.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

data class SettledEntry(
    val url: String,
    val html: String,
    val userAgent: String,
    val timestamp: Long = System.currentTimeMillis(),
)

object SettledPageCache {
    private const val TTL_MS = 60_000L // 60 seconds TTL

    private val cache = ConcurrentHashMap<String, SettledEntry>()

    private fun normalizeUrl(url: String): String {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return url.trim().trimEnd('/')
        val scheme = httpUrl.scheme.lowercase()
        val host = httpUrl.host.lowercase()
        val port = if ((scheme == "http" && httpUrl.port == 80) || (scheme == "https" && httpUrl.port == 443)) "" else ":${httpUrl.port}"
        val encodedPath = httpUrl.encodedPath.trimEnd('/')
        val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
        return "$scheme://$host$port$encodedPath$query"
    }

    private fun stripQuery(normalizedUrl: String): String {
        return normalizedUrl.substringBefore('?')
    }

    fun put(url: String, html: String, userAgent: String) {
        if (url.isBlank() || html.isBlank()) return
        val normalized = normalizeUrl(url)
        cache[normalized] = SettledEntry(url = normalized, html = html, userAgent = userAgent)
        cleanupExpired()
    }

    fun get(url: String): SettledEntry? {
        val normalized = normalizeUrl(url)
        var entry = cache[normalized]

        // If not found by exact match, try matching without query parameters
        if (entry == null && normalized.contains('?')) {
            val pathOnly = stripQuery(normalized)
            entry = cache[pathOnly]
        }
        if (entry == null) {
            val targetPath = stripQuery(normalized)
            entry = cache.values.firstOrNull { stripQuery(it.url) == targetPath }
        }

        if (entry != null) {
            if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
                cache.remove(entry.url)
                return null
            }
            return entry
        }

        return null
    }

    fun remove(url: String) {
        cache.remove(normalizeUrl(url))
    }

    fun clear() {
        cache.clear()
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.timestamp > TTL_MS }
    }
}
