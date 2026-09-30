package com.cncverse.stremiobridge.cache

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val cacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ────────────────────────────────────────────────────────────────────────────
//  Configuration Models
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class ProviderCacheOverride(
    val enabled: Boolean = true,
    val customTtlMinutes: Long? = null
)

@Serializable
data class StreamCacheConfig(
    val enabled: Boolean = true,
    val singleFlightEnabled: Boolean = true,
    val defaultTtlMinutes: Long = 60L,            // 1 hour: many links carry undetectable short-lived tokens
    val signedSafetyBufferSeconds: Long = 60L,    // 60-second safety buffer for signed URLs
    val minCacheableTtlMinutes: Long = 5L,        // Below 5 min = ephemeral (bypass or short TTL)
    val maxRamEntries: Int = 2_000,               // entries hold whole stream lists — keep RAM bounded
    val diskPersistenceEnabled: Boolean = false,  // opt-in: stream links rarely outlive a restart anyway
    val providerOverrides: Map<String, ProviderCacheOverride> = emptyMap()
)

// ────────────────────────────────────────────────────────────────────────────
//  Cache Storage Models & Telemetry
// ────────────────────────────────────────────────────────────────────────────

@Serializable
data class CachedStreamEntry(
    val key: String,
    val streams: List<StremioStream>,
    val cachedAt: Long,
    val expiresAt: Long,
    val providerKey: String? = null,
    val hitCount: Long = 0L
)

@Serializable
data class StreamCacheStats(
    val enabled: Boolean,
    val singleFlightEnabled: Boolean,
    val hits: Long,
    val misses: Long,
    val requestsSaved: Long,
    val hitRate: Double,
    val activeRamEntries: Int,
    val totalEvicted: Long,
    val defaultTtlMinutes: Long,
    val maxRamEntries: Int,
    val lastPurgeTime: Long = 0L
)

@Serializable
data class LinkClassificationResult(
    val url: String,
    val host: String,
    val category: String,
    val isSigned: Boolean,
    val detectedExpiryTime: Long? = null,
    val remainingValidityMs: Long? = null,
    val computedTtlMs: Long,
    val isCacheable: Boolean,
    val reason: String
)

// ────────────────────────────────────────────────────────────────────────────
//  Stream Link Classifier (On-The-Fly Expiration & Security Inspector)
// ────────────────────────────────────────────────────────────────────────────

object StreamLinkClassifier {

    // Only parameters whose semantic meaning is strictly EXPIRATION TIME
    private val EXPLICIT_EXPIRY_PARAM_NAMES = setOf(
        "expires", "expire", "exp", "expiry", "validuntil", "valid_until",
        "deadline", "endtime"
    )

    // Security / session tokens that do NOT declare an expiration timestamp directly
    private val AUTH_TOKEN_PARAM_NAMES = setOf(
        "token", "st", "wssecret", "auth_token", "auth", "sign", "sig",
        "signature", "md5", "key", "v"
    )

    private val STATIC_HOST_KEYWORDS = listOf(
        "pixeldrain.com", "gofile.io", "archive.org", "1fichier.com",
        "mediafire.com", "mega.nz", "dropbox.com", "github.com",
        "raw.githubusercontent.com", "fastly.net", "jsdelivr.net",
        "r2.dev", "googleusercontent.com", "workers.dev"
    )

    private val UNIX_SECONDS_RX = Regex("""^1[6-9]\d{8}$|^2\d{9}$""")
    private val UNIX_MILLIS_RX = Regex("""^1[6-9]\d{11}$|^2\d{12}$""")
    private val HEX8_RX = Regex("""^[0-9a-fA-F]{8}$""")
    private val HOST_RX = Regex("https?://([^/:]+)")
    private val AMZ_DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(java.time.ZoneOffset.UTC)

    private val TOKENIZED_HOST_KEYWORDS = listOf(
        "hubcloud", "gdflix", "fastdl", "streamtape", "doodstream", "dood",
        "filemoon", "streamwish", "vidhide", "mixdrop", "streamhub",
        "vidsrc", "superembed", "embed", "dropload", "streamvid"
    )

    fun classify(
        url: String?,
        customProviderTtlMinutes: Long? = null,
        defaultTtlMinutes: Long = 60L,
        signedSafetyBufferSeconds: Long = 60L,
        minCacheableTtlMinutes: Long = 5L
    ): LinkClassificationResult {
        if (url.isNullOrBlank()) {
            return LinkClassificationResult(
                url = "",
                host = "",
                category = "EMPTY_OR_TORRENT",
                isSigned = false,
                computedTtlMs = 24 * 3600 * 1000L,
                isCacheable = true,
                reason = "Non-URL media (e.g. Torrent infoHash or YouTube ID)"
            )
        }

        val trimmed = url.trim()

        if (trimmed.startsWith("magnet:", ignoreCase = true) || trimmed.startsWith("acestream:", ignoreCase = true)) {
            return LinkClassificationResult(
                url = trimmed,
                host = "p2p",
                category = "P2P_STREAM",
                isSigned = false,
                computedTtlMs = 24 * 3600 * 1000L,
                isCacheable = true,
                reason = "P2P / Magnet hash (permanent link)"
            )
        }

        val host = try {
            URI(trimmed).host?.lowercase() ?: ""
        } catch (_: Throwable) {
            HOST_RX.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase() ?: ""
        }

        val now = System.currentTimeMillis()
        val queryStr = trimmed.substringAfter('?', "")

        // Check for signed query parameters
        var detectedExpiryMs: Long? = null
        var matchedParam: String? = null
        var hasAuthToken = false
        var expiredParam: String? = null // explicit expiry already in the past → link is dead

        if (queryStr.isNotBlank()) {
            val pairs = queryStr.split("&")
            val queryMap = mutableMapOf<String, String>()
            for (pair in pairs) {
                val parts = pair.split("=", limit = 2)
                val key = parts[0].lowercase().trim()
                val value = parts.getOrNull(1)?.trim() ?: ""
                queryMap[key] = value

                if (AUTH_TOKEN_PARAM_NAMES.contains(key)) {
                    hasAuthToken = true
                }
            }

            // 1. Check for AWS S3 / Cloudflare R2 Presigned parameters: X-Amz-Date + X-Amz-Expires
            val amzDate = queryMap["x-amz-date"]
            val amzExpires = queryMap["x-amz-expires"]
            if (!amzDate.isNullOrBlank() && !amzExpires.isNullOrBlank()) {
                val expSec = amzExpires.toLongOrNull()
                if (expSec != null) {
                    try {
                        val dateMs = java.time.Instant.from(AMZ_DATE_FORMAT.parse(amzDate)).toEpochMilli()
                        val expiryEpochMs = dateMs + (expSec * 1000L)
                        if (expiryEpochMs > now) {
                            detectedExpiryMs = expiryEpochMs
                            matchedParam = "X-Amz-Date=$amzDate, X-Amz-Expires=$amzExpires"
                        } else {
                            expiredParam = "X-Amz-Date=$amzDate, X-Amz-Expires=$amzExpires"
                        }
                    } catch (_: Throwable) {}
                }
            }

            // 2. Check for explicit expiration timestamp parameters (strictly expiration keys only!)
            if (detectedExpiryMs == null) {
                for ((key, value) in queryMap) {
                    if (EXPLICIT_EXPIRY_PARAM_NAMES.contains(key)) {
                        // 1) 10-digit unix timestamp in seconds
                        if (value.matches(UNIX_SECONDS_RX)) {
                            val parsed = value.toLongOrNull()
                            if (parsed != null) {
                                val ms = parsed * 1000L
                                // These keys strictly mean "expires at", so a past value is a dead link
                                if (ms > now) {
                                    detectedExpiryMs = ms
                                    matchedParam = "$key=$value"
                                    break
                                } else expiredParam = "$key=$value"
                            }
                        }
                        // 2) 13-digit unix timestamp in milliseconds
                        if (value.matches(UNIX_MILLIS_RX)) {
                            val parsed = value.toLongOrNull()
                            if (parsed != null && parsed > now) {
                                detectedExpiryMs = parsed
                                matchedParam = "$key=$value"
                                break
                            } else if (parsed != null) expiredParam = "$key=$value"
                        }
                        // 3) Hex 8-character timestamp
                        if (value.length == 8 && value.matches(HEX8_RX)) {
                            val parsed = value.toLongOrNull(16)?.times(1000L)
                            if (parsed != null && parsed in (1_600_000_000_000L..2_500_000_000_000L) && parsed > now) {
                                detectedExpiryMs = parsed
                                matchedParam = "$key=$value (hex timestamp)"
                                break
                            }
                        }
                    }
                }
            }
        }

        val safetyBufferMs = signedSafetyBufferSeconds * 1000L
        val minCacheableMs = minCacheableTtlMinutes * 60_000L

        if (detectedExpiryMs == null && expiredParam != null) {
            return LinkClassificationResult(
                url = trimmed,
                host = host,
                category = "EXPIRED_SIGNED",
                isSigned = true,
                computedTtlMs = 0L,
                isCacheable = false,
                reason = "Signed URL already expired ($expiredParam) — never cached"
            )
        }

        // If an explicit expiration timestamp was detected:
        if (detectedExpiryMs != null) {
            val remainingMs = detectedExpiryMs - now
            val safeTtlMs = maxOf(0L, remainingMs - safetyBufferMs)
            val boundedTtlMs = if (customProviderTtlMinutes != null) {
                minOf(safeTtlMs, customProviderTtlMinutes * 60_000L)
            } else safeTtlMs

            val isCacheable = boundedTtlMs >= minCacheableMs
            val reason = if (isCacheable) {
                "Valid signed URL with $matchedParam. Safe TTL: ${boundedTtlMs / 60_000}m (buffer: ${signedSafetyBufferSeconds}s)"
            } else {
                "Signed URL remaining time (${safeTtlMs / 1000}s) is below min cache threshold (${minCacheableTtlMinutes}m)"
            }

            return LinkClassificationResult(
                url = trimmed,
                host = host,
                category = "SIGNED_URL",
                isSigned = true,
                detectedExpiryTime = detectedExpiryMs,
                remainingValidityMs = remainingMs,
                computedTtlMs = boundedTtlMs,
                isCacheable = isCacheable,
                reason = reason
            )
        }

        // Host-based heuristic classification
        if (STATIC_HOST_KEYWORDS.any { host.contains(it) }) {
            val ttl = (customProviderTtlMinutes ?: (defaultTtlMinutes * 2).coerceAtLeast(720L)) * 60_000L
            return LinkClassificationResult(
                url = trimmed,
                host = host,
                category = "STATIC_CDN",
                isSigned = false,
                computedTtlMs = ttl,
                isCacheable = true,
                reason = "Verified static host / persistent storage ($host). Long TTL applied."
            )
        }

        if (TOKENIZED_HOST_KEYWORDS.any { host.contains(it) } || hasAuthToken) {
            val tokenTtlMinutes = customProviderTtlMinutes ?: 45L
            return LinkClassificationResult(
                url = trimmed,
                host = host,
                category = "TOKENIZED_HOST",
                isSigned = true,
                computedTtlMs = tokenTtlMinutes * 60_000L,
                isCacheable = true,
                reason = "Known tokenized host or auth token present ($host). Ephemeral TTL: ${tokenTtlMinutes}m."
            )
        }

        if (trimmed.contains(".m3u8", ignoreCase = true) || trimmed.contains("/live/", ignoreCase = true) || trimmed.contains("/hls/", ignoreCase = true)) {
            val liveTtlMs = 5 * 60_000L
            return LinkClassificationResult(
                url = trimmed,
                host = host,
                category = "LIVE_STREAM",
                isSigned = false,
                computedTtlMs = liveTtlMs,
                isCacheable = true,
                reason = "HLS live stream or dynamic playlist. Short 5m TTL."
            )
        }

        // Default Direct Stream
        val baseTtl = (customProviderTtlMinutes ?: defaultTtlMinutes) * 60_000L
        return LinkClassificationResult(
            url = trimmed,
            host = host,
            category = "DIRECT_STREAM",
            isSigned = false,
            computedTtlMs = baseTtl,
            isCacheable = true,
            reason = "Standard stream link without detectable signature. TTL: ${baseTtl / 60_000}m."
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  StreamCacheManager: High Concurrency LRU Memory + L2 Disk Persistence
// ────────────────────────────────────────────────────────────────────────────

object StreamCacheManager {

    /**
     * Shared fetches run plugin code, so they use the server's bounded plugin
     * pool (never Dispatchers.Default, which only has one thread per core).
     * Lazy: StremioServer owns the dispatcher.
     */
    private val fetchScope by lazy {
        CoroutineScope(com.cncverse.stremiobridge.server.StremioServer.pluginDispatcher + SupervisorJob())
    }
    /** Housekeeping (purge / disk flush) — cheap, kept off the plugin pool. */
    private val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val memoryCache = ConcurrentHashMap<String, CachedStreamEntry>()

    /** An in-flight shared fetch plus how many requests are currently waiting on it. */
    private class Flight(val deferred: Deferred<List<StremioStream>>) {
        val waiters = java.util.concurrent.atomic.AtomicInteger(0)
    }
    private val inFlight = ConcurrentHashMap<String, Flight>()

    /** Hard cap for one shared fetch; it is also cancelled once no request is waiting for it. */
    private const val MAX_FETCH_MS = 60_000L

    private val isDirty = AtomicBoolean(false)
    @Volatile private var cacheDirFile: File? = null
    @Volatile private var configFile: File? = null
    @Volatile private var dataFile: File? = null
    private val diskLock = Any()

    // High performance atomic telemetry counters
    val hits = AtomicLong(0L)
    val misses = AtomicLong(0L)
    val requestsSaved = AtomicLong(0L)
    val totalEvicted = AtomicLong(0L)
    private val lastPurgeTimestamp = AtomicLong(0L)

    @Volatile var config: StreamCacheConfig = StreamCacheConfig()
        private set

    private var cleanupJob: Job? = null
    private var persistenceJob: Job? = null

    fun init(cacheDir: String) {
        val dir = File(cacheDir)
        if (!dir.exists()) dir.mkdirs()
        cacheDirFile = dir
        configFile = File(dir, "stream_cache_config.json")
        dataFile = File(dir, "stream_cache.json")

        loadConfig()
        loadDiskCache()

        // Background periodic purge and flush jobs
        cleanupJob?.cancel()
        cleanupJob = maintenanceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // Purge expired every 5 minutes
                purgeExpired()
            }
        }

        persistenceJob?.cancel()
        persistenceJob = maintenanceScope.launch {
            while (isActive) {
                delay(30 * 1000L) // Debounced flush every 30 seconds
                if (isDirty.getAndSet(false) && config.diskPersistenceEnabled) {
                    saveToDiskNow()
                }
            }
        }

        ServerState.info("StreamCacheManager initialized: ${memoryCache.size} active entries in RAM.")
    }

    private fun loadConfig() {
        val file = configFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                config = cacheJson.decodeFromString<StreamCacheConfig>(json)
                ServerState.info("Loaded StreamCacheConfig from disk.")
            } catch (e: Throwable) {
                ServerState.warn("Failed to load StreamCacheConfig: ${e.message}")
            }
        }
    }

    fun updateConfig(newConfig: StreamCacheConfig) {
        config = newConfig
        val file = configFile ?: return
        try {
            file.writeText(cacheJson.encodeToString(newConfig))
            ServerState.info("Updated and saved StreamCacheConfig.")
        } catch (e: Throwable) {
            ServerState.warn("Failed to save StreamCacheConfig: ${e.message}")
        }
    }

    private fun loadDiskCache() {
        if (!config.diskPersistenceEnabled) return
        val file = dataFile ?: return
        if (!file.exists()) return

        try {
            // Streamed decode: never materialise the whole file as one String
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            val list = file.inputStream().buffered().use {
                cacheJson.decodeFromStream<List<CachedStreamEntry>>(it)
            }
            val now = System.currentTimeMillis()
            var loaded = 0
            list.forEach { entry ->
                if (entry.expiresAt > now) {
                    memoryCache[entry.key] = entry
                    loaded++
                }
            }
            ServerState.info("StreamCacheManager: Restored $loaded valid stream entries from disk.")
        } catch (e: Throwable) {
            ServerState.warn("Failed to restore stream_cache.json: ${e.message}")
        }
    }

    /**
     * Streams the live entries straight to disk (no whole-cache String in
     * memory), then atomically replaces the file. Serialized so the periodic
     * flush and shutdown can't write the same temp file concurrently.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun saveToDiskNow() = synchronized(diskLock) {
        val file = dataFile ?: return@synchronized
        try {
            val now = System.currentTimeMillis()
            val active = memoryCache.values.filter { it.expiresAt > now }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().buffered().use { cacheJson.encodeToStream(active, it) }
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Throwable) {
            ServerState.warn("Failed to save stream_cache.json: ${e.message}")
        }
    }

    /**
     * Retrieves cached streams if present and unexpired (O(1) non-blocking).
     */
    fun get(key: String): List<StremioStream>? {
        if (!config.enabled) return null
        val entry = memoryCache[key] ?: return null
        val now = System.currentTimeMillis()

        if (now > entry.expiresAt) {
            memoryCache.remove(key)
            totalEvicted.incrementAndGet()
            isDirty.set(true)
            return null
        }

        hits.incrementAndGet()
        return entry.streams
    }

    /**
     * Inspects, filters, and puts streams into the cache with safe expiration.
     */
    fun put(key: String, streams: List<StremioStream>, providerKey: String? = null) {
        if (!config.enabled || streams.isEmpty()) return

        val (validStreams, computedTtlMs) = filterAndClassifyStreams(streams, providerKey)
        if (validStreams.isEmpty() || computedTtlMs <= 0L) return

        val now = System.currentTimeMillis()
        val expiresAt = now + computedTtlMs

        // Evict if exceeding max RAM capacity
        if (memoryCache.size >= config.maxRamEntries) {
            evictExcessEntries()
        }

        val entry = CachedStreamEntry(
            key = key,
            streams = validStreams,
            cachedAt = now,
            expiresAt = expiresAt,
            providerKey = providerKey,
            hitCount = 0L
        )

        memoryCache[key] = entry
        isDirty.set(true)
    }

    /**
     * High-concurrency Single-Flight Request Deduplication:
     * If 1,000+ users request the same stream at the same second, only 1 scrape job
     * is executed. The other 999 wait and share the exact same result.
     */
    suspend fun getOrFetch(
        key: String,
        providerKey: String? = null,
        fetcher: suspend () -> List<StremioStream>
    ): List<StremioStream> = getOrFetchResult(key, providerKey) { FetchResult(fetcher()) }

    /** A fetch outcome; [cacheable] = false serves the streams without caching them (e.g. partial results). */
    class FetchResult(val streams: List<StremioStream>, val cacheable: Boolean = true)

    suspend fun getOrFetchResult(
        key: String,
        providerKey: String? = null,
        fetcher: suspend () -> FetchResult
    ): List<StremioStream> {
        if (!config.enabled) return fetcher().streams

        // 1. Fast L1 Memory lookup
        val cached = get(key)
        if (cached != null) return cached

        // 2. If single-flight is disabled, scrape directly
        if (!config.singleFlightEnabled) {
            misses.incrementAndGet()
            val fetched = fetcher()
            if (fetched.cacheable) put(key, fetched.streams, providerKey)
            return fetched.streams
        }

        // 3. Single-flight request coalescing. The shared job is time-limited on
        //    its own (callers' timeouts only stop *their* wait) and is cancelled
        //    when the last waiting request goes away, so abandoned scrapes never
        //    keep running in the background.
        var isLeader = false
        val flight = inFlight.compute(key) { _, existing ->
            if (existing != null && existing.deferred.isActive) {
                existing
            } else {
                isLeader = true
                lateinit var self: Flight
                val deferred = fetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val result = withTimeout(MAX_FETCH_MS) { fetcher() }
                        if (result.cacheable) put(key, result.streams, providerKey)
                        result.streams
                    } finally {
                        inFlight.remove(key, self) // only our own entry, never a newer leader's
                    }
                }
                self = Flight(deferred)
                self
            }
        }!!
        flight.waiters.incrementAndGet()
        flight.deferred.start()

        if (!isLeader) requestsSaved.incrementAndGet() else misses.incrementAndGet()

        return try {
            flight.deferred.await()
        } finally {
            if (flight.waiters.decrementAndGet() == 0 && flight.deferred.isActive) {
                flight.deferred.cancel()
                inFlight.remove(key, flight)
            }
        }
    }

    /**
     * Inspects every stream, strips dead/expired URLs, and calculates safe TTL.
     */
    fun filterAndClassifyStreams(
        streams: List<StremioStream>,
        providerKey: String? = null
    ): Pair<List<StremioStream>, Long> {
        val override = providerKey?.let { config.providerOverrides[it] }
        if (override != null && !override.enabled) {
            // Provider cache explicitly disabled by admin
            return Pair(emptyList(), 0L)
        }

        val customTtl = override?.customTtlMinutes
        val validList = mutableListOf<StremioStream>()
        var minComputedTtlMs = Long.MAX_VALUE

        for (stream in streams) {
            val url = stream.url
            if (url.isNullOrBlank()) {
                // InfoHash or YouTube ID: permanent
                validList.add(stream)
                val torrentTtlMs = (customTtl ?: (config.defaultTtlMinutes * 2)) * 60_000L
                minComputedTtlMs = minOf(minComputedTtlMs, torrentTtlMs)
                continue
            }

            val classification = StreamLinkClassifier.classify(
                url = url,
                customProviderTtlMinutes = customTtl,
                defaultTtlMinutes = config.defaultTtlMinutes,
                signedSafetyBufferSeconds = config.signedSafetyBufferSeconds,
                minCacheableTtlMinutes = config.minCacheableTtlMinutes
            )

            // CRITICAL: NEVER drop freshly scraped streams from playback!
            // The user must always be able to click and play what was scraped.
            validList.add(stream)

            if (!classification.isCacheable || classification.computedTtlMs <= 0L) {
                // A signed link that is expired or about to expire: caching this list
                // would hand users a dead link later — serve it now, but don't cache.
                return Pair(streams, 0L)
            }
            minComputedTtlMs = minOf(minComputedTtlMs, classification.computedTtlMs)
        }

        val effectiveTtlMs = if (validList.isEmpty()) {
            0L
        } else if (minComputedTtlMs == Long.MAX_VALUE) {
            (customTtl ?: config.defaultTtlMinutes) * 60_000L
        } else {
            minComputedTtlMs
        }

        return Pair(validList, effectiveTtlMs)
    }

    private fun evictExcessEntries() {
        val now = System.currentTimeMillis()
        var removed = 0

        // Pass 1: Remove all expired
        val iter = memoryCache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (now > entry.expiresAt) {
                iter.remove()
                removed++
            }
        }

        // Pass 2: If still overflowing, evict oldest 20%
        if (memoryCache.size >= config.maxRamEntries) {
            val sorted = memoryCache.entries.sortedBy { it.value.cachedAt }
            val toRemove = (memoryCache.size * 0.2).toInt().coerceAtLeast(1)
            for (i in 0 until toRemove) {
                if (i < sorted.size) {
                    memoryCache.remove(sorted[i].key)
                    removed++
                }
            }
        }

        totalEvicted.addAndGet(removed.toLong())
        isDirty.set(true)
    }

    fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        var purged = 0
        val iter = memoryCache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (now > entry.expiresAt) {
                iter.remove()
                purged++
            }
        }
        if (purged > 0) {
            totalEvicted.addAndGet(purged.toLong())
            lastPurgeTimestamp.set(now)
            isDirty.set(true)
            ServerState.info("[CACHE] Purged $purged expired stream entries from RAM.")
        }
        return purged
    }

    fun clearAll() {
        memoryCache.clear()
        inFlight.clear()
        dataFile?.delete()
        isDirty.set(false)
        ServerState.info("[CACHE] Flushed all stream caches.")
    }

    fun inspectLink(url: String): LinkClassificationResult {
        return StreamLinkClassifier.classify(
            url = url,
            defaultTtlMinutes = config.defaultTtlMinutes,
            signedSafetyBufferSeconds = config.signedSafetyBufferSeconds,
            minCacheableTtlMinutes = config.minCacheableTtlMinutes
        )
    }

    fun getStats(): StreamCacheStats {
        val h = hits.get()
        val m = misses.get()
        val total = h + m
        val rate = if (total == 0L) 0.0 else (h.toDouble() / total.toDouble()) * 100.0

        return StreamCacheStats(
            enabled = config.enabled,
            singleFlightEnabled = config.singleFlightEnabled,
            hits = h,
            misses = m,
            requestsSaved = requestsSaved.get(),
            hitRate = (rate * 10.0).toLong() / 10.0,
            activeRamEntries = memoryCache.size,
            totalEvicted = totalEvicted.get(),
            defaultTtlMinutes = config.defaultTtlMinutes,
            maxRamEntries = config.maxRamEntries,
            lastPurgeTime = lastPurgeTimestamp.get()
        )
    }

    fun shutdown() {
        cleanupJob?.cancel()
        persistenceJob?.cancel()
        if (isDirty.get()) {
            saveToDiskNow()
        }
    }
}
