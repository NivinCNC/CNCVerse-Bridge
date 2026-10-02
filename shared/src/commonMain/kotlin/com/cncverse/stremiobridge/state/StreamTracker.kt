package com.cncverse.stremiobridge.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** User-facing health of an extension. */
object HealthStatus {
    const val WORKING = "working"
    /** Produces streams only through a geo proxy. */
    const val PROXY = "proxy"
    /** Probed directly and through a proxy, no links either way, and no success since. */
    const val DEAD = "dead"
    const val UNKNOWN = "unknown"
}

@Serializable
data class PluginStreamHealth(
    val internalName: String,
    val pluginName: String,
    val iconUrl: String? = null,
    val totalRequests: Int = 0,
    val successRequests: Int = 0,
    val totalStreams: Int = 0,
    val lastStreamCount: Int = 0,
    val lastSuccessTime: Long = 0L,
    val lastTestedTime: Long = 0L,
    val lastError: String? = null,
    val enabled: Boolean = true,
    val status: String = HealthStatus.UNKNOWN,
    val firstSeen: Long = 0L,
    val lastProbeTime: Long = 0L,
    val lastDirectProbeStreams: Int = -1,
    val lastProxyProbeStreams: Int = -1,
    val lastProxyCountry: String? = null,
    val lastProbeNote: String? = null,
)

/** On-disk form of one [StreamTracker.Stat]. */
@Serializable
internal data class StatRecord(
    val internalName: String,
    val pluginName: String,
    val totalRequests: Int = 0,
    val successRequests: Int = 0,
    val totalStreams: Int = 0,
    val lastStreamCount: Int = 0,
    val lastSuccessTime: Long = 0L,
    val lastTestedTime: Long = 0L,
    val lastError: String? = null,
    val firstSeen: Long = 0L,
    val lastProbeTime: Long = 0L,
    val lastDirectProbeStreams: Int = -1,
    val lastProxyProbeStreams: Int = -1,
    val lastProxyCountry: String? = null,
    val lastProbeNote: String? = null,
    val lastProxySuccessTime: Long = 0L,
)

object StreamTracker {
    class Stat(
        val internalName: String,
        @Volatile var pluginName: String
    ) {
        var totalRequests: Int = 0
        var successRequests: Int = 0
        var totalStreams: Int = 0
        var lastStreamCount: Int = 0
        var lastSuccessTime: Long = 0L
        var lastTestedTime: Long = 0L
        var lastError: String? = null
        var firstSeen: Long = currentTimeMillis()
        /** Latest health probe (direct + proxy) — the "tried everything" evidence for DEAD. */
        var lastProbeTime: Long = 0L
        var lastDirectProbeStreams: Int = -1
        var lastProxyProbeStreams: Int = -1
        var lastProxyCountry: String? = null
        var lastProbeNote: String? = null
        /** Last time links came only through the proxy. */
        var lastProxySuccessTime: Long = 0L

        /**
         * - WORKING: links within the last [WORKING_WINDOW_MS], or the latest probe found links directly
         * - PROXY:   the latest probe found links only through the proxy
         * - DEAD:    the latest probe found nothing (direct and, when possible, proxy) and nothing since
         * - UNKNOWN: never probed and no recent links
         */
        fun status(now: Long = currentTimeMillis()): String {
            if (lastProbeTime > 0 && lastSuccessTime <= lastProbeTime) {
                if (lastDirectProbeStreams > 0) return HealthStatus.WORKING
                if (lastProxyProbeStreams > 0) return HealthStatus.PROXY
                // -2: a proxy run was needed but no proxy was available — not proven dead
                if (lastDirectProbeStreams == 0 && lastProxyProbeStreams != -2) return HealthStatus.DEAD
            }
            if (lastSuccessTime > 0 && now - lastSuccessTime < WORKING_WINDOW_MS) {
                return if (lastProxySuccessTime >= lastSuccessTime && lastProxySuccessTime > 0) HealthStatus.PROXY else HealthStatus.WORKING
            }
            if (lastProbeTime > 0 && lastProxyProbeStreams > 0) return HealthStatus.PROXY
            return HealthStatus.UNKNOWN
        }

        fun toHealth(enabled: Boolean, iconUrl: String? = null): PluginStreamHealth {
            return PluginStreamHealth(
                internalName = internalName,
                pluginName = pluginName,
                iconUrl = iconUrl,
                totalRequests = totalRequests,
                successRequests = successRequests,
                totalStreams = totalStreams,
                lastStreamCount = lastStreamCount,
                lastSuccessTime = lastSuccessTime,
                lastTestedTime = lastTestedTime,
                lastError = lastError,
                enabled = enabled,
                status = status(),
                firstSeen = firstSeen,
                lastProbeTime = lastProbeTime,
                lastDirectProbeStreams = lastDirectProbeStreams,
                lastProxyProbeStreams = lastProxyProbeStreams,
                lastProxyCountry = lastProxyCountry,
                lastProbeNote = lastProbeNote,
            )
        }

        internal fun toRecord() = StatRecord(
            internalName, pluginName, totalRequests, successRequests, totalStreams, lastStreamCount,
            lastSuccessTime, lastTestedTime, lastError, firstSeen, lastProbeTime, lastDirectProbeStreams,
            lastProxyProbeStreams, lastProxyCountry, lastProbeNote, lastProxySuccessTime,
        )
    }

    /** Links within this window count as "working" without a probe. */
    const val WORKING_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    private val stats = ConcurrentHashMap<String, Stat>()

    private var file: File? = null
    @Volatile private var dirty = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Set by the geo router: true when the current request ran through a proxy for this plugin. */
    @Volatile var proxiedNow: ((pluginInternalName: String) -> Boolean)? = null

    private fun recordSingle(key: String, pluginName: String, streamCount: Int, error: String?, viaProxy: Boolean) {
        val s = stats.computeIfAbsent(key) { Stat(key, pluginName) }
        s.pluginName = pluginName
        synchronized(s) {
            s.totalRequests++
            s.lastTestedTime = currentTimeMillis()
            s.lastStreamCount = streamCount
            s.lastError = error
            if (streamCount > 0) {
                s.successRequests++
                s.totalStreams += streamCount
                s.lastSuccessTime = s.lastTestedTime
                if (viaProxy) s.lastProxySuccessTime = s.lastSuccessTime
                s.lastError = null
            }
        }
        dirty = true
    }

    fun record(
        pluginInternalName: String,
        internalName: String,
        pluginName: String,
        streamCount: Int,
        error: String? = null
    ) {
        val viaProxy = streamCount > 0 && runCatching { proxiedNow?.invoke(pluginInternalName) }.getOrNull() == true
        recordSingle(pluginInternalName, pluginName, streamCount, error, viaProxy)
        if (internalName.isNotBlank() && internalName != pluginInternalName) {
            recordSingle(internalName, pluginName, streamCount, error, viaProxy)
        }
    }

    fun record(internalName: String, pluginName: String, streamCount: Int, error: String? = null) {
        recordSingle(internalName, pluginName, streamCount, error, false)
        if (internalName.contains("_")) {
            val prefix = internalName.substringBefore("_")
            if (prefix.isNotBlank()) {
                recordSingle(prefix, pluginName, streamCount, error, false)
            }
        }
    }

    /**
     * Stores a health probe outcome for an API and its plugin. [proxyStreams]
     * is -1 when no proxy run was possible (no country / pool).
     */
    fun recordProbe(
        pluginInternalName: String,
        internalName: String,
        pluginName: String,
        directStreams: Int,
        proxyStreams: Int,
        proxyCountry: String?,
        note: String?,
    ) {
        val now = currentTimeMillis()
        for (key in setOf(pluginInternalName, internalName).filter { it.isNotBlank() }) {
            val s = stats.computeIfAbsent(key) { Stat(key, pluginName) }
            synchronized(s) {
                if (key == pluginInternalName && internalName != pluginInternalName && s.lastProbeTime >= now - 60_000) {
                    // Several APIs of one plugin: the plugin keeps the best result of this sweep
                    s.lastDirectProbeStreams = maxOf(s.lastDirectProbeStreams, directStreams)
                    s.lastProxyProbeStreams = maxOf(s.lastProxyProbeStreams, proxyStreams)
                    if (proxyCountry != null) s.lastProxyCountry = proxyCountry
                    if (directStreams > 0 || proxyStreams > 0) s.lastProbeNote = note
                } else {
                    s.lastDirectProbeStreams = directStreams
                    s.lastProxyProbeStreams = proxyStreams
                    s.lastProxyCountry = proxyCountry
                    s.lastProbeNote = note
                }
                s.lastProbeTime = now
                s.lastTestedTime = now
            }
        }
        dirty = true
    }

    private fun find(internalName: String): Stat? =
        stats[internalName]
            ?: stats.entries.find { it.key.startsWith("${internalName}_") }?.value
            ?: stats.entries.find { internalName.startsWith("${it.key}_") }?.value

    fun getHealth(internalName: String, pluginName: String, enabled: Boolean, iconUrl: String? = null): PluginStreamHealth {
        return find(internalName)?.toHealth(enabled, iconUrl) ?: PluginStreamHealth(
            internalName = internalName,
            pluginName = pluginName,
            iconUrl = iconUrl,
            enabled = enabled
        )
    }

    fun statusOf(internalName: String): String = find(internalName)?.status() ?: HealthStatus.UNKNOWN

    fun statOf(internalName: String): Stat? = find(internalName)

    /** Starts the "installed since" clock for plugins seen for the first time. */
    fun ensureSeen(internalName: String, pluginName: String) {
        if (stats.containsKey(internalName)) return
        stats.computeIfAbsent(internalName) { Stat(internalName, pluginName) }
        dirty = true
    }

    fun forget(internalNames: Collection<String>) {
        internalNames.forEach { n -> stats.keys.removeIf { it == n || it.startsWith("${n}_") } }
        dirty = true
    }

    fun getAllHealth(): Map<String, Stat> = stats

    fun clear() {
        stats.clear()
        dirty = true
    }

    // ── Persistence (stream_health.json) ─────────────────────────────────────

    fun init(cacheDir: String) {
        val f = File(cacheDir, "stream_health.json")
        if (file != null) return
        file = f
        if (f.exists()) runCatching {
            json.decodeFromString<List<StatRecord>>(f.readText()).forEach { r ->
                stats[r.internalName] = Stat(r.internalName, r.pluginName).apply {
                    totalRequests = r.totalRequests; successRequests = r.successRequests
                    totalStreams = r.totalStreams; lastStreamCount = r.lastStreamCount
                    lastSuccessTime = r.lastSuccessTime; lastTestedTime = r.lastTestedTime
                    lastError = r.lastError; firstSeen = if (r.firstSeen > 0) r.firstSeen else currentTimeMillis()
                    lastProbeTime = r.lastProbeTime; lastDirectProbeStreams = r.lastDirectProbeStreams
                    lastProxyProbeStreams = r.lastProxyProbeStreams; lastProxyCountry = r.lastProxyCountry
                    lastProbeNote = r.lastProbeNote; lastProxySuccessTime = r.lastProxySuccessTime
                }
            }
        }.onFailure { ServerState.warn("Could not read stream_health.json: ${it.message}") }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { saveNow() } })
    }

    /** Writes if anything changed (called periodically and on shutdown). */
    fun saveIfDirty() { if (dirty) saveNow() }

    @Synchronized
    fun saveNow() {
        val f = file ?: return
        dirty = false
        val records = stats.values.map { s -> synchronized(s) { s.toRecord() } }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(records))
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { ServerState.warn("Could not save stream_health.json: ${it.message}") }
    }
}
