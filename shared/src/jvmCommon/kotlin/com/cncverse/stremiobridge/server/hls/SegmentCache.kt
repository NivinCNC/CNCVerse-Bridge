package com.cncverse.stremiobridge.server.hls

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache system for segments, init segments, and MPD manifests.
 * Centralized cache management with TTL and size limits.
 */
object SegmentCache {

    // Init segment cache (raw init data)
    data class InitCacheEntry(val data: ByteArray, val timestamp: Long)
    private val initSegmentCache = ConcurrentHashMap<String, InitCacheEntry>()
    private const val INIT_CACHE_TTL_MS = 600_000L // 10 minutes
    private const val MAX_INIT_CACHE_SIZE = 100

    // Cleaned init cache (without encryption metadata)
    private val cleanedInitCache = ConcurrentHashMap<String, ByteArray>()
    private const val MAX_CLEANED_INIT_CACHE_SIZE = 100

    // Decrypted segment cache
    data class SegmentCacheEntry(val data: ByteArray, val timestamp: Long)
    private val segmentCache = ConcurrentHashMap<String, SegmentCacheEntry>()
    private const val SEGMENT_CACHE_TTL_MS = 60_000L // 60 seconds
    private const val MAX_SEGMENT_CACHE_SIZE = 150

    // MPD manifest cache
    data class MpdCacheEntry(val content: String, val timestamp: Long)
    private val mpdCache = ConcurrentHashMap<String, MpdCacheEntry>()
    private const val MPD_CACHE_TTL_MS = 1000L // 1 second for live streams
    private const val MAX_MPD_CACHE_SIZE = 50

    // Prefetch tracking
    private val prefetchingUrls = ConcurrentHashMap.newKeySet<String>()

    // ==================== Init Segment Cache ====================

    fun getInitSegment(cacheKey: String): ByteArray? {
        val now = System.currentTimeMillis()
        val cached = initSegmentCache[cacheKey] ?: return null
        return if (now - cached.timestamp < INIT_CACHE_TTL_MS) cached.data else null
    }

    fun putInitSegment(cacheKey: String, data: ByteArray) {
        initSegmentCache[cacheKey] = InitCacheEntry(data, System.currentTimeMillis())
    }

    fun hasInitSegment(cacheKey: String): Boolean {
        return initSegmentCache.containsKey(cacheKey)
    }

    // ==================== Cleaned Init Cache ====================

    fun getCleanedInit(cacheKey: String): ByteArray? {
        return cleanedInitCache[cacheKey]
    }

    fun putCleanedInit(cacheKey: String, data: ByteArray) {
        cleanedInitCache[cacheKey] = data
    }

    fun hasCleanedInit(cacheKey: String): Boolean {
        return cleanedInitCache.containsKey(cacheKey)
    }

    // ==================== Segment Cache ====================

    fun getSegment(cacheKey: String): ByteArray? {
        val now = System.currentTimeMillis()
        val cached = segmentCache[cacheKey] ?: return null
        return if (now - cached.timestamp < SEGMENT_CACHE_TTL_MS) cached.data else null
    }

    fun putSegment(cacheKey: String, data: ByteArray) {
        segmentCache[cacheKey] = SegmentCacheEntry(data, System.currentTimeMillis())
    }

    fun hasSegment(cacheKey: String): Boolean {
        return segmentCache.containsKey(cacheKey)
    }

    // ==================== MPD Cache ====================

    fun getMpd(url: String): String? {
        val now = System.currentTimeMillis()
        val cached = mpdCache[url] ?: return null
        return if (now - cached.timestamp < MPD_CACHE_TTL_MS) cached.content else null
    }

    fun putMpd(url: String, content: String) {
        // Limit cache size
        if (mpdCache.size > MAX_MPD_CACHE_SIZE) {
            val oldestKey = mpdCache.entries.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { mpdCache.remove(it) }
        }
        mpdCache[url] = MpdCacheEntry(content, System.currentTimeMillis())
    }

    fun invalidateMpd(url: String) {
        mpdCache.remove(url)
    }

    // ==================== Prefetch Tracking ====================

    fun isPrefetching(key: String): Boolean = prefetchingUrls.contains(key)

    fun markPrefetching(key: String): Boolean = prefetchingUrls.add(key)

    fun unmarkPrefetching(key: String) = prefetchingUrls.remove(key)

    // ==================== Cleanup ====================

    fun cleanup() {
        val now = System.currentTimeMillis()

        // Clean segment cache
        segmentCache.entries.removeIf { now - it.value.timestamp > SEGMENT_CACHE_TTL_MS }
        if (segmentCache.size > MAX_SEGMENT_CACHE_SIZE) {
            val oldest = segmentCache.entries.sortedBy { it.value.timestamp }
                .take(segmentCache.size - MAX_SEGMENT_CACHE_SIZE / 2)
            oldest.forEach { segmentCache.remove(it.key) }
        }

        // Clean init cache
        initSegmentCache.entries.removeIf { now - it.value.timestamp > INIT_CACHE_TTL_MS }
        if (initSegmentCache.size > MAX_INIT_CACHE_SIZE) {
            val oldest = initSegmentCache.entries.sortedBy { it.value.timestamp }
                .take(initSegmentCache.size - MAX_INIT_CACHE_SIZE / 2)
            oldest.forEach { initSegmentCache.remove(it.key) }
        }

        // Clean cleaned init cache
        if (cleanedInitCache.size > MAX_CLEANED_INIT_CACHE_SIZE) {
            cleanedInitCache.keys.take(cleanedInitCache.size - MAX_CLEANED_INIT_CACHE_SIZE / 2)
                .forEach { cleanedInitCache.remove(it) }
        }

        // Clean MPD cache
        mpdCache.entries.removeIf { now - it.value.timestamp > MPD_CACHE_TTL_MS * 5 }
        if (mpdCache.size > MAX_MPD_CACHE_SIZE) {
            val oldest = mpdCache.entries.sortedBy { it.value.timestamp }
                .take(mpdCache.size - MAX_MPD_CACHE_SIZE / 2)
            oldest.forEach { mpdCache.remove(it.key) }
        }
    }

    fun clear() {
        initSegmentCache.clear()
        cleanedInitCache.clear()
        segmentCache.clear()
        mpdCache.clear()
        prefetchingUrls.clear()
    }
}
