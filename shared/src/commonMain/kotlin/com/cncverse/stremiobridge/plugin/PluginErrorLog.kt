package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.state.ServerState
import java.util.concurrent.ConcurrentHashMap

/**
 * Replacement for `Throwable.printStackTrace()` inside CloudStream's `logError`
 * (libs/cloudstream-api.jar is patched to call [printStackTrace], see libs/README.md).
 *
 * Plugins hit logError for every episode with an empty/odd date, every cancelled
 * request, etc. Each printed a 15–20 line trace to stderr: ~35,000 journal lines a
 * minute, a big share of the server's CPU (synchronized stderr writes, journald,
 * rsyslog). Now each distinct error is one line, at most once a minute.
 */
object PluginErrorLog {
    private const val REPEAT_MS = 60_000L
    private val lastLogged = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun printStackTrace(t: Throwable) {
        val origin = t.stackTrace.firstOrNull { !it.className.startsWith("com.lagradost.cloudstream3.mvvm") }
        val key = t.javaClass.name + "|" + (t.message?.take(120) ?: "") + "|" + (origin?.className ?: "")
        val now = System.currentTimeMillis()
        val prev = lastLogged.put(key, now)
        if (prev != null && now - prev < REPEAT_MS) {
            lastLogged[key] = prev // keep the window anchored to the last line actually written
            return
        }
        if (lastLogged.size > 5_000) lastLogged.entries.removeIf { now - it.value > REPEAT_MS }
        val where = origin?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" } ?: ""
        ServerState.warn("[PluginError] ${t.javaClass.simpleName}: ${t.message?.take(200)}$where")
    }
}
