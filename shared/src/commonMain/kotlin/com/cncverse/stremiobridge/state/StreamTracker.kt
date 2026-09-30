package com.cncverse.stremiobridge.state

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

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
    val enabled: Boolean = true
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
                enabled = enabled
            )
        }
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    private fun recordSingle(key: String, pluginName: String, streamCount: Int, error: String?) {
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
                s.lastError = null
            }
        }
    }

    fun record(
        pluginInternalName: String,
        internalName: String,
        pluginName: String,
        streamCount: Int,
        error: String? = null
    ) {
        recordSingle(pluginInternalName, pluginName, streamCount, error)
        if (internalName.isNotBlank() && internalName != pluginInternalName) {
            recordSingle(internalName, pluginName, streamCount, error)
        }
    }

    fun record(internalName: String, pluginName: String, streamCount: Int, error: String? = null) {
        recordSingle(internalName, pluginName, streamCount, error)
        if (internalName.contains("_")) {
            val prefix = internalName.substringBefore("_")
            if (prefix.isNotBlank()) {
                recordSingle(prefix, pluginName, streamCount, error)
            }
        }
    }

    fun getHealth(internalName: String, pluginName: String, enabled: Boolean, iconUrl: String? = null): PluginStreamHealth {
        val s = stats[internalName]
            ?: stats.entries.find { it.key.startsWith("${internalName}_") }?.value
            ?: stats.entries.find { internalName.startsWith("${it.key}_") }?.value
        return s?.toHealth(enabled, iconUrl) ?: PluginStreamHealth(
            internalName = internalName,
            pluginName = pluginName,
            iconUrl = iconUrl,
            enabled = enabled
        )
    }

    fun getAllHealth(): Map<String, Stat> = stats

    fun clear() {
        stats.clear()
    }
}
