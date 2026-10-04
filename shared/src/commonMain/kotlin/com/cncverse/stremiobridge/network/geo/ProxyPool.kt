package com.cncverse.stremiobridge.network.geo

import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A proxy endpoint. Only HTTP (CONNECT-capable) and SOCKS5 are usable from the JVM. */
@Serializable
data class ProxyEndpoint(
    val type: String,
    val host: String,
    val port: Int,
    /** Credentials of private proxies — never serialized (they live in the settings). */
    @kotlinx.serialization.Transient val username: String? = null,
    @kotlinx.serialization.Transient val password: String? = null,
) {
    val key: String get() = "$type://$host:$port"
    fun toJavaProxy(): Proxy = Proxy(
        if (type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress(host, port),
    )
    override fun toString(): String = key
}

/**
 * Proxy credentials: HTTP proxies authenticate through OkHttp's
 * proxyAuthenticator; SOCKS5 goes through the JVM-wide Authenticator, which
 * answers only for registered proxy host:port pairs.
 */
internal object ProxyCredentials {
    private val socks = ConcurrentHashMap<String, java.net.PasswordAuthentication>()
    @Volatile private var installed = false

    fun register(ep: ProxyEndpoint) {
        if (ep.username == null || ep.type != "socks5") return
        socks["${ep.host}:${ep.port}"] = java.net.PasswordAuthentication(ep.username, (ep.password ?: "").toCharArray())
        install()
    }

    @Synchronized
    private fun install() {
        if (installed) return
        installed = true
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                // Only SOCKS handshakes to a registered private proxy — never site/HTTP auth
                if (!requestingProtocol.orEmpty().startsWith("SOCKS", ignoreCase = true)) return null
                return socks["$requestingHost:$requestingPort"]
            }
        })
    }

    fun apply(builder: OkHttpClient.Builder, ep: ProxyEndpoint): OkHttpClient.Builder {
        if (ep.username != null && ep.type == "http") {
            val cred = okhttp3.Credentials.basic(ep.username, ep.password ?: "")
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", cred).build()
            }
        }
        register(ep)
        return builder
    }
}

/** A validated proxy in a country pool. */
class PooledProxy(
    val endpoint: ProxyEndpoint,
    val country: String,
    val residential: Boolean,
    val isp: String?,
    @Volatile var exitIp: String?,
    /** Admin-added private/paid proxy: preferred, never evicted (only marked down until it recovers). */
    val custom: Boolean = false,
    /** Live channels it may carry, as a multiple of maxStreamsPerProxy (rotating gateways: many). */
    val capacity: Int = 1,
    /** Built-in local tunnel (Indian VPS tunnel, Ultrasurf): tried before everything else for its country. */
    val builtin: Boolean = false,
    /** Order among built-ins: lower first (tunnel 0, Ultrasurf 1 — the fallback). */
    val rank: Int = 0,
) {
    @Volatile var latencyMs: Long = 0
    @Volatile var consecutiveFailures: Int = 0
    /** Health checks (ipify through the proxy) failed in a row — only these can remove a public proxy. */
    @Volatile var failedChecks: Int = 0
    val successes = AtomicInteger(0)
    val failures = AtomicInteger(0)
    /** Live channels currently leased to this proxy (see GeoRouter.StreamLease). */
    val activeStreams = AtomicInteger(0)
    @Volatile var lastOk: Long = 0
    @Volatile var lastChecked: Long = 0
    val addedAt: Long = System.currentTimeMillis()

    /** Lower is better: latency plus a heavy penalty per recent failure, residential preferred. */
    val score: Long get() = latencyMs + consecutiveFailures * 4_000L + (if (residential) 0 else 2_500L) - (if (custom) 10_000L else 0L) - (if (builtin) 20_000L else 0L)
}

@Serializable
data class PooledProxySnapshot(
    val endpoint: ProxyEndpoint,
    val country: String,
    val residential: Boolean,
    val isp: String? = null,
    val exitIp: String? = null,
    val latencyMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastOk: Long = 0,
    val lastChecked: Long = 0,
    val addedAt: Long = 0,
    val activeStreams: Int = 0,
    val custom: Boolean = false,
    val capacity: Int = 1,
    /** Built-in local tunnel (Ultrasurf): tried before everything else for its country. */
    val builtin: Boolean = false,
)

fun PooledProxy.snapshot() = PooledProxySnapshot(
    endpoint, country, residential, isp, exitIp, latencyMs, consecutiveFailures,
    successes.get(), failures.get(), lastOk, lastChecked, addedAt, activeStreams.get(), custom, capacity, builtin,
)

/**
 * Per-country pools of working public proxies.
 *
 * Candidates come from public lists, are classified with ip-api (country +
 * hosting flag → residential vs datacenter), then validated end-to-end: an
 * HTTPS request through the proxy must succeed and its exit IP must be in the
 * wanted country. Pools only exist for countries that something asked for,
 * are health-checked in the background and refilled when they run low.
 */
object ProxyPool {

    // ── Tunables (admin settings feed into these via GeoRouter) ──────────────
    @Volatile var targetSize = 6
    @Volatile var maxSize = 30
    /** Per-country size raised by live-stream demand (never below [targetSize]). */
    private val dynamicTarget = ConcurrentHashMap<String, Int>()

    fun targetFor(country: String): Int = maxOf(targetSize, dynamicTarget[country] ?: 0).coerceAtMost(maxOf(maxSize, targetSize))
    @Volatile var allowDatacenter = false
    /** Use datacenter proxies only while a country has too few working residential ones. */
    @Volatile var datacenterFallback = true
    private const val MIN_HEALTHY = 3
    private const val HEALTH_INTERVAL_MS = 3 * 60_000L
    private const val SOURCE_TTL_MS = 30 * 60_000L
    private const val DEMAND_TTL_MS = 6 * 60 * 60_000L
    private const val MAX_FAILURES = 2
    /** A public proxy is removed only after this many failed health checks in a row (~1 min apart). */
    private const val DEAD_CHECKS = 3
    /** Marked-down proxies are re-checked this soon, so a hiccup costs a minute, not the proxy. */
    private const val DOWN_RECHECK_MS = 60_000L
    /** Ultrasurf / private proxies carry hundreds of requests: a few failures in a row under load must not take them out. */
    private const val MAX_FAILURES_CUSTOM = 6

    private fun failLimit(p: PooledProxy) = if (p.custom) MAX_FAILURES_CUSTOM else MAX_FAILURES
    private const val VALIDATE_CONCURRENCY = 24
    private const val MAX_VALIDATE_PER_ROUND = 240

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pools = ConcurrentHashMap<String, CopyOnWriteArrayList<PooledProxy>>()
    private val demand = ConcurrentHashMap<String, Long>()
    private val refillLocks = ConcurrentHashMap<String, Mutex>()
    private val lastRefill = ConcurrentHashMap<String, Long>()
    /** Endpoints that failed validation recently — skipped for a while (key → until). */
    private val rejected = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Plain client for source lists and ip-api. */
    private val directClient: OkHttpClient by lazy {
        // Straight from this server (never via WARP): proxy lists, ip-api
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Shared connection pool for every per-proxy client (routes differ, so no cross-talk). */
    private val proxyConnectionPool = okhttp3.ConnectionPool(64, 60, TimeUnit.SECONDS)
    private val proxyClients = ConcurrentHashMap<String, OkHttpClient>()

    /** Client that sends everything through [ep]. Cached per endpoint. */
    fun clientFor(ep: ProxyEndpoint, streaming: Boolean = false): OkHttpClient {
        val key = ep.key + (if (streaming) "#s" else "")
        return proxyClients.getOrPut(key) {
            ProxyCredentials.apply(OkHttpClient.Builder(), ep)
                .proxy(ep.toJavaProxy())
                .connectionPool(proxyConnectionPool)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(if (streaming) 30 else 20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(if (streaming) 60 else 40, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    private fun dropClient(ep: ProxyEndpoint) {
        proxyClients.remove(ep.key)
        proxyClients.remove(ep.key + "#s")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            delay(20_000)
            while (isActive) {
                runCatching { maintain() }.onFailure { ServerState.warn("[GeoProxy] maintenance error: ${it.message}") }
                delay(30_000)
            }
        }
    }

    /** Marks [country] as wanted so the background loop keeps a pool for it. */
    fun demand(country: String) {
        val prev = demand.put(country, System.currentTimeMillis())
        if (prev == null) ServerState.info("[GeoProxy] pool requested for $country")
        if (healthy(country).size < MIN_HEALTHY) refillAsync(country)
    }

    /** Still in its pool and not marked down. */
    fun isUsable(p: PooledProxy): Boolean =
        p.consecutiveFailures < failLimit(p) && pools[p.country]?.contains(p) == true

    fun healthy(country: String): List<PooledProxy> =
        pools[country].orEmpty().filter { it.consecutiveFailures < failLimit(it) }

    /**
     * Up to [n] proxies for [country], best first, spreading load across the
     * top few. Empty when the pool is still being built.
     */
    /** Usable built-in tunnels (Ultrasurf), whatever country pool holds them. */
    private fun builtins(): List<PooledProxy> =
        pools.values.flatten().filter { it.builtin && it.consecutiveFailures < failLimit(it) }.sortedBy { it.rank }

    fun pick(country: String, n: Int = 2, exclude: Set<String> = emptySet(), host: String? = null, residentialOnly: Boolean = false): List<PooledProxy> {
        // Hosts pinned to Ultrasurf (workers.dev…) never use anything else
        if (host != null && GeoRouter.ultrasurfOnly(host)) return builtins().filter { it.endpoint.key !in exclude }.take(n)
        demand(country)
        val banned = host?.let { bannedFor(it) }.orEmpty()
        val ranked = healthy(country)
            .filter { it.endpoint.key !in exclude && it.endpoint.key !in banned && (!residentialOnly || it.residential || it.custom) }
            .sortedBy { it.score }
        if (ranked.isEmpty()) return emptyList()
        // Built-in tunnels (Ultrasurf) always first; spread the rest over the best three
        val (builtin, rest) = ranked.partition { it.builtin }
        val head = rest.take(3).shuffled()
        return (builtin.sortedBy { it.rank } + head + rest.drop(3)).take(n)
    }

    /**
     * Proxy for a live channel: under its stream cap first, then residential
     * (CDNs like Jio refuse datacenter IPs even in-country), then the one
     * carrying the fewest channels, then latency.
     */
    fun leastLoaded(country: String, exclude: Set<String>, host: String? = null, cap: Int = Int.MAX_VALUE): PooledProxy? {
        if (host != null && GeoRouter.ultrasurfOnly(host)) return builtins().firstOrNull { it.endpoint.key !in exclude }
        demand(country)
        val banned = host?.let { bannedFor(it) }.orEmpty()
        return healthy(country)
            .filter { it.endpoint.key !in exclude && it.endpoint.key !in banned }
            .minWithOrNull(
                compareBy<PooledProxy> { cap != Int.MAX_VALUE && it.activeStreams.get() >= cap.toLong() * it.capacity }
                    .thenBy { !it.builtin }
                    .thenBy { it.rank }
                    .thenBy { !it.custom }
                    .thenBy { !it.residential }
                    .thenBy { it.activeStreams.get().toDouble() / it.capacity }
                    .thenBy { it.score }
            )
    }

    // ── Per-host bans: a proxy whose IP a given site refuses ─────────────────

    private val hostBans = ConcurrentHashMap<String, Long>()
    private const val HOST_BAN_MS = 6 * 60 * 60_000L

    /** [p] got a geo refusal from [host]; don't use it for that host for a while. */
    fun banForHost(host: String, p: PooledProxy) {
        if (p.builtin && GeoRouter.ultrasurfOnly(host)) return // pinned: always Ultrasurf
        hostBans["$host|${p.endpoint.key}"] = System.currentTimeMillis() + HOST_BAN_MS
        if (hostBans.size > 5_000) {
            val now = System.currentTimeMillis()
            hostBans.entries.removeIf { it.value < now }
        }
    }

    private fun bannedFor(host: String): Set<String> {
        if (hostBans.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        val prefix = "$host|"
        return hostBans.entries.filter { it.key.startsWith(prefix) && it.value > now }.mapTo(HashSet()) { it.key.substring(prefix.length) }
    }

    /**
     * Grows the [country] pool so [liveChannels] fit at [perProxy] each, plus
     * two spare (standbys / failover headroom), capped at [maxSize].
     */
    fun ensureCapacity(country: String, liveChannels: Int, perProxy: Int) {
        val per = perProxy.coerceAtLeast(1)
        // Private proxies take their share first (a rotating gateway may carry most of it)
        val privateSlots = healthy(country).filter { it.custom }.sumOf { it.capacity.toLong() * per }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val publicChannels = (liveChannels - privateSlots).coerceAtLeast(0)
        val wanted = ((publicChannels + per - 1) / per) + 2
        val prev = dynamicTarget[country] ?: 0
        if (wanted > prev) {
            dynamicTarget[country] = wanted
            if (healthy(country).size < targetFor(country)) {
                ServerState.info("[GeoProxy] $country: $liveChannels live channel(s) → growing pool to ${targetFor(country)}")
                refillAsync(country)
            }
        } else if (wanted < prev - 2) {
            dynamicTarget[country] = wanted // shrink lazily; extra proxies just age out
        }
    }

    /** Like [pick], but waits (up to [timeoutMs]) for a pool to be built — for health probes. */
    suspend fun pickAwait(country: String, n: Int = 2, timeoutMs: Long = 90_000): List<PooledProxy> {
        pick(country, n).takeIf { it.isNotEmpty() }?.let { return it }
        withTimeoutOrNull(timeoutMs) {
            refill(country)
            while (healthy(country).isEmpty()) delay(2_000)
        }
        return pick(country, n)
    }

    fun reportSuccess(p: PooledProxy, latencyMs: Long) {
        p.successes.incrementAndGet()
        p.consecutiveFailures = 0
        p.failedChecks = 0
        p.lastOk = System.currentTimeMillis()
        p.latencyMs = if (p.latencyMs == 0L) latencyMs else (p.latencyMs * 7 + latencyMs) / 8
    }

    fun reportFailure(p: PooledProxy, reason: String?) {
        p.failures.incrementAndGet()
        p.consecutiveFailures++
        // Request failures only mark a proxy down: a site resetting one connection doesn't mean
        // the proxy is dead. It stays in the pool and the health check decides (see healthCheck).
        if (p.consecutiveFailures == failLimit(p)) {
            ServerState.info("[GeoProxy] ${p.country}: ${p.endpoint.key} marked down (${reason ?: "failures"}) — re-checking")
        }
    }

    private fun reportCheckFailed(p: PooledProxy) {
        p.failures.incrementAndGet()
        p.failedChecks++
        p.consecutiveFailures = maxOf(p.consecutiveFailures + 1, failLimit(p))
        if (!p.custom && p.failedChecks >= DEAD_CHECKS) evict(p, "dead: $DEAD_CHECKS health checks failed")
    }

    /** Called by the JVM ProxySelector when a connect through a pooled proxy fails. */
    fun reportConnectFailed(host: String, port: Int) {
        pools.values.forEach { list ->
            list.firstOrNull { it.endpoint.host == host && it.endpoint.port == port }
                ?.let { reportFailure(it, "connect failed") }
        }
    }

    fun findPooled(host: String, port: Int): PooledProxy? =
        pools.values.asSequence().flatten().firstOrNull { it.endpoint.host == host && it.endpoint.port == port }

    private fun evict(p: PooledProxy, reason: String) {
        val list = pools[p.country] ?: return
        if (list.remove(p)) {
            dropClient(p.endpoint)
            rejected[p.endpoint.key] = System.currentTimeMillis() + 30 * 60_000L
            ServerState.info("[GeoProxy] ${p.country}: dropped ${p.endpoint.key} ($reason) — ${healthy(p.country).size} left")
            if (healthy(p.country).size < MIN_HEALTHY) refillAsync(p.country)
        }
    }

    fun removeProxy(country: String, key: String): Boolean {
        val p = pools[country]?.firstOrNull { it.endpoint.key == key } ?: return false
        evict(p, "removed by admin")
        return true
    }

    fun countries(): Set<String> = pools.keys + demand.keys

    fun snapshot(): Map<String, List<PooledProxySnapshot>> =
        countries().associateWith { c -> pools[c].orEmpty().sortedBy { it.score }.map { it.snapshot() } }

    fun demandSince(country: String): Long? = demand[country]
    fun isRefilling(country: String): Boolean = refillLocks[country]?.isLocked == true
    fun lastRefillAt(country: String): Long? = lastRefill[country]

    /** Restores pools saved before a restart; they are re-checked on the next health pass. */
    fun restore(saved: Map<String, List<PooledProxySnapshot>>) {
        saved.forEach { (country, list) ->
            val pool = pools.getOrPut(country) { CopyOnWriteArrayList() }
            list.forEach { s ->
                if (pool.none { it.endpoint == s.endpoint }) {
                    val res = s.residential && IpClassifier.Info(null, false, s.isp, 0).residential
                    pool += PooledProxy(s.endpoint, country, res, s.isp, s.exitIp).apply {
                        latencyMs = s.latencyMs
                        lastOk = s.lastOk
                        lastChecked = 0 // force a re-check soon
                    }
                }
            }
            if (list.isNotEmpty()) demand.putIfAbsent(country, System.currentTimeMillis())
        }
    }

    private fun residentialHealthy(country: String) = healthy(country).count { it.residential }

    data class CustomProxy(val country: String, val endpoint: ProxyEndpoint, val capacity: Int)

    /**
     * Parses one admin line: `IN socks5://user:pass@host:port *50` — country,
     * proxy URL (http or socks5, credentials optional) and an optional
     * capacity (`*N`, live channels as a multiple of maxStreamsPerProxy).
     * Returns null for an invalid line.
     */
    fun parseCustom(line: String): CustomProxy? {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts[0].startsWith("#")) return null
        val country = parts.firstOrNull { Regex("^[A-Za-z]{2}$").matches(it) }?.uppercase() ?: return null
        val url = parts.firstOrNull { "://" in it } ?: return null
        val capacity = parts.firstOrNull { Regex("^[*x]\\d+$").matches(it) }?.drop(1)?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val type = when (uri.scheme?.lowercase()) {
            "socks5", "socks5h", "socks" -> "socks5"
            "http", "https" -> "http"
            else -> return null
        }
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val userInfo = uri.rawUserInfo?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val user = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
        val pass = userInfo?.substringAfter(':', "")
        return CustomProxy(country, ProxyEndpoint(type, host, port, user, pass), capacity)
    }

    /**
     * Installs (or removes, with a blank [url]) a built-in local tunnel such as
     * Ultrasurf as the first proxy of [country]: preferred over everything,
     * never evicted, effectively unlimited live-channel capacity.
     */
    fun setBuiltin(country: String, label: String, url: String, rank: Int = 0) {
        val list = pools.getOrPut(country) { CopyOnWriteArrayList() }
        val parsed = parseCustom("$country $url")?.endpoint
        // Several built-ins can coexist (tunnel + Ultrasurf); each label manages only its own entry
        list.filter { it.builtin && it.isp == label && (it.endpoint != parsed || it.rank != rank) }
            .forEach { list.remove(it); dropClient(it.endpoint) }
        if (parsed == null || list.any { it.builtin && it.isp == label && it.endpoint == parsed }) return
        list.add(0, PooledProxy(parsed, country, residential = true, isp = label, exitIp = null,
            custom = true, capacity = 1000, builtin = true, rank = rank).apply { lastChecked = 0 })
        demand.putIfAbsent(country, System.currentTimeMillis())
    }

    /** Replaces the admin-added private proxies (from the geo settings). */
    fun setCustom(lines: List<String>) {
        val wanted = lines.mapNotNull { parseCustom(it) }
        val wantedKeys = wanted.map { it.country + "|" + it.endpoint.key }.toSet()
        // Drop removed ones
        pools.forEach { (country, list) ->
            list.filter { it.custom && !it.builtin && (country + "|" + it.endpoint.key) !in wantedKeys }.forEach {
                list.remove(it); dropClient(it.endpoint)
            }
        }
        for (c in wanted) {
            val list = pools.getOrPut(c.country) { CopyOnWriteArrayList() }
            val existing = list.firstOrNull { it.endpoint.key == c.endpoint.key }
            if (existing != null && existing.custom && existing.endpoint == c.endpoint && existing.capacity == c.capacity) continue
            existing?.let { list.remove(it); dropClient(it.endpoint) }
            list += PooledProxy(c.endpoint, c.country, residential = true, isp = "private", exitIp = null,
                custom = true, capacity = c.capacity).apply { lastChecked = 0 } // checked on the next pass
            demand.putIfAbsent(c.country, System.currentTimeMillis())
        }
    }

    private val lastRefillRequest = ConcurrentHashMap<String, Long>()

    /** Background refill, at most once a minute per country (every request may ask). */
    fun refillAsync(country: String) {
        if (isRefilling(country)) return
        val now = System.currentTimeMillis()
        var go = false
        lastRefillRequest.compute(country) { _, prev ->
            if (prev == null || now - prev >= 60_000L) { go = true; now } else prev
        }
        if (go) refillNow(country)
    }

    private fun refillNow(country: String) {
        scope.launch { runCatching { refill(country) } }
    }

    // ── Background maintenance ───────────────────────────────────────────────

    private suspend fun maintain() {
        val now = System.currentTimeMillis()
        // Forget countries nobody asked for in a while
        // Countries with private proxies are always kept
        pools.forEach { (c, l) -> if (l.any { it.custom }) demand[c] = now }
        demand.entries.removeIf { now - it.value > DEMAND_TTL_MS }
        rejected.entries.removeIf { it.value < now }
        // TheSpeedX lists have no country: look them up a slice at a time (ip-api free tier
        // allows ~1500 IPs/min) so their proxies join the right country pools
        if (demand.isNotEmpty()) {
            runCatching { ProxySources.refreshSpeedX(directClient) }
            val slice = ProxySources.unclassifiedSpeedX(300)
            if (slice.isNotEmpty()) runCatching { IpClassifier.classify(slice, directClient, json) }
        }
        // Proxies are kept until they die, even for countries nobody asked for lately or once
        // they vanish from the public lists — only failed health checks remove them.
        for (country in (pools.keys + demand.keys).toSet()) {
            val pool = pools[country].orEmpty()
            // Down proxies are re-checked soon (custom tunnels every 30 s) so they come back fast
            val due = pool.filter {
                val down = it.consecutiveFailures >= failLimit(it)
                now - it.lastChecked > when { down && it.custom -> 30_000L; down -> DOWN_RECHECK_MS; else -> HEALTH_INTERVAL_MS }
            }
            if (due.isNotEmpty()) healthCheck(due)
            if (demand.containsKey(country) && healthy(country).size < targetFor(country)) {
                val last = lastRefill[country] ?: 0L
                val backoff = if (healthy(country).size < MIN_HEALTHY) 2 * 60_000L else 10 * 60_000L
                if (now - last > backoff) refill(country)
            }
        }
    }

    private suspend fun healthCheck(list: List<PooledProxy>) {
        val sem = Semaphore(12)
        list.map { p ->
            scope.async {
                sem.withPermit {
                    p.lastChecked = System.currentTimeMillis()
                    val r = probeThrough(p.endpoint)
                    if (r != null) {
                        p.exitIp = r.exitIp
                        reportSuccess(p, r.latencyMs)
                    } else {
                        reportCheckFailed(p)
                    }
                }
            }
        }.awaitAll()
    }

    /** Builds/tops up the pool for [country]. Serialized per country. */
    suspend fun refill(country: String) {
        val lock = refillLocks.getOrPut(country) { Mutex() }
        if (lock.isLocked) return
        lock.withLock {
            lastRefill[country] = System.currentTimeMillis()
            val pool = pools.getOrPut(country) { CopyOnWriteArrayList() }
            val need = targetFor(country) - healthy(country).size
            if (need <= 0) return

            val have = pool.map { it.endpoint.key }.toSet()
            val now = System.currentTimeMillis()
            val candidates = ProxySources.candidates(country, directClient, json)
                .filter { it.key !in have && (rejected[it.key] ?: 0L) < now }
            if (candidates.isEmpty()) {
                ServerState.warn("[GeoProxy] $country: no proxy candidates found in public lists")
                return
            }
            // Classify proxy IPs (country + residential) before spending time on validation
            val info = IpClassifier.classify(candidates.map { it.host }.distinct(), directClient, json)
            val inCountry = candidates.filter { info[it.host]?.countryCode == country }
            val residential = inCountry.filter { info[it.host]?.residential == true }
            // Residential first; datacenter IPs only when allowed outright or as a fallback while residential runs short
            val dcAllowed = allowDatacenter || (datacenterFallback && residentialHealthy(country) < MIN_HEALTHY)
            val ordered = residential + (if (dcAllowed) inCountry.filter { info[it.host]?.residential != true } else emptyList())
            ServerState.info(
                "[GeoProxy] $country: ${candidates.size} candidates, ${inCountry.size} in-country, " +
                    "${residential.size} residential — validating"
            )
            if (ordered.isEmpty()) return

            val sem = Semaphore(VALIDATE_CONCURRENCY)
            val added = AtomicInteger(0)
            val results = ordered.take(MAX_VALIDATE_PER_ROUND).map { ep ->
                scope.async {
                    if (added.get() >= need) return@async null
                    sem.withPermit {
                        if (added.get() >= need) return@withPermit null
                        val r = probeThrough(ep)
                        if (r == null) { rejected[ep.key] = System.currentTimeMillis() + 15 * 60_000L; null }
                        else { added.incrementAndGet(); ep to r }
                    }
                }
            }.awaitAll().filterNotNull()

            // The exit IP decides the country the target site sees — verify it
            val exitInfo = IpClassifier.classify(
                results.map { it.second.exitIp }.filter { it !in info }.distinct(), directClient, json,
            ) + info
            var accepted = 0
            for ((ep, r) in results) {
                if (accepted >= need) break // parallel validation can overshoot the target
                val exit = exitInfo[r.exitIp]
                if (exit != null && exit.countryCode != country) {
                    rejected[ep.key] = System.currentTimeMillis() + 6 * 60 * 60_000L
                    continue
                }
                val proxyInfo = info[ep.host]
                val isResidential = (exit ?: proxyInfo)?.residential == true
                if (!isResidential && !dcAllowed) continue
                if (pool.any { it.endpoint == ep }) continue
                pool += PooledProxy(ep, country, isResidential, exit?.isp ?: proxyInfo?.isp, r.exitIp).apply {
                    latencyMs = r.latencyMs
                    lastOk = System.currentTimeMillis()
                    lastChecked = lastOk
                    successes.incrementAndGet()
                }
                accepted++
            }
            ServerState.info("[GeoProxy] $country: +$accepted proxies (pool ${healthy(country).size}/${targetFor(country)})")
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    class ProbeResult(val exitIp: String, val latencyMs: Long)

    /** HTTPS request through [ep]; returns the exit IP or null when the proxy is unusable. */
    suspend fun probeThrough(ep: ProxyEndpoint): ProbeResult? {
        val client = ProxyCredentials.apply(OkHttpClient.Builder(), ep)
            .proxy(ep.toJavaProxy())
            .connectionPool(proxyConnectionPool)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val started = System.currentTimeMillis()
        return runCatching {
            client.newCall(Request.Builder().url("https://api.ipify.org?format=json").build()).execute().use { resp ->
                if (resp.code != 200) return@use null
                val ip = json.parseToJsonElement((resp.body?.string() ?: "")).jsonObject["ip"]?.jsonPrimitive?.contentOrNull
                    ?: return@use null
                ProbeResult(ip, System.currentTimeMillis() - started)
            }
        }.getOrNull()
    }
}

/** Public proxy lists (fetched over the server's own connection, cached). */
internal object ProxySources {
    private class Cached(val at: Long, val items: List<Pair<ProxyEndpoint, String?>>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 30 * 60_000L

    private fun fetch(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    private fun normType(raw: String?): String? = when (raw?.lowercase()?.trim()) {
        "http", "https" -> "http"
        "socks5", "socks5h" -> "socks5"
        else -> null
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun cached(key: String, load: () -> List<Pair<ProxyEndpoint, String?>>): List<Pair<ProxyEndpoint, String?>> {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < TTL_MS }?.let { return it.items }
        val items = runCatching(load).getOrDefault(emptyList())
        if (items.isNotEmpty()) cache[key] = Cached(now, items)
        return items.ifEmpty { cache[key]?.items.orEmpty() }
    }

    /** Candidates hinted for [country] (the hint is re-verified by ip-api). */
    fun candidates(country: String, client: OkHttpClient, json: Json): List<ProxyEndpoint> {
        val all = mutableListOf<Pair<ProxyEndpoint, String?>>()

        all += cached("proxifly:$country") {
            val body = fetch(client, "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/$country/data.json")
                ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val type = normType(str(o["protocol"])) ?: return@mapNotNull null
                val ip = str(o["ip"]) ?: return@mapNotNull null
                val port = o["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                // HTTP proxies must support CONNECT for https sites
                if (type == "http" && o["https"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
                ProxyEndpoint(type, ip, port) to country
            }
        }

        all += cached("proxyscrape:$country") {
            val body = fetch(
                client,
                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=" +
                    country.lowercase() + "&proxy_format=protocolipport&format=json",
            ) ?: return@cached emptyList()
            val arr = json.parseToJsonElement(body).jsonObject["proxies"] as? JsonArray ?: return@cached emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if (o["alive"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
                val type = normType(str(o["protocol"])) ?: return@mapNotNull null
                val ip = str(o["ip"]) ?: return@mapNotNull null
                val port = o["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                ProxyEndpoint(type, ip, port) to country
            }
        }

        all += cached("vakhov") {
            val body = fetch(client, "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/proxylist.json")
                ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.flatMap { el ->
                val o = el as? JsonObject ?: return@flatMap emptyList()
                val ip = str(o["ip"]) ?: return@flatMap emptyList()
                val port = str(o["port"])?.toIntOrNull() ?: return@flatMap emptyList()
                val cc = str(o["country_code"])
                buildList {
                    if (str(o["socks5"]) == "1") add(ProxyEndpoint("socks5", ip, port) to cc)
                    if (str(o["http"]) == "1" && str(o["ssl"]) == "1") add(ProxyEndpoint("http", ip, port) to cc)
                }
            }
        }.filter { it.second == country }

        all += cached("monosans") {
            val body = fetch(client, "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies.json")
                ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val type = normType(str(o["protocol"])) ?: return@mapNotNull null
                val host = str(o["host"]) ?: return@mapNotNull null
                val port = o["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val cc = runCatching {
                    str(o["geolocation"]?.jsonObject?.get("country")?.jsonObject?.get("iso_code"))
                }.getOrNull()
                ProxyEndpoint(type, host, port) to cc
            }
        }.filter { it.second == country }

        // ProxyScrape's GitHub mirror, per country
        all += cached("proxyscrape-gh:$country") {
            val body = fetch(client, "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/" +
                country.lowercase() + "/data.json") ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val type = normType(str(o["protocol"])) ?: return@mapNotNull null
                val ip = str(o["ip"]) ?: return@mapNotNull null
                val port = o["port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                ProxyEndpoint(type, ip, port) to country
            }
        }

        // proxyfreeonly.com — one big list for all countries (cached)
        all += cached("proxyfreeonly") {
            val body = fetch(client, "https://proxyfreeonly.com/api/free-proxy-list?limit=500&page=1&sortBy=lastChecked&sortType=desc")
                ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.flatMap { el ->
                val o = el as? JsonObject ?: return@flatMap emptyList()
                val ip = str(o["ip"]) ?: return@flatMap emptyList()
                val port = str(o["port"])?.toIntOrNull() ?: return@flatMap emptyList()
                val cc = str(o["country"])
                (o["protocols"] as? JsonArray).orEmpty().mapNotNull { p -> normType(str(p))?.let { ProxyEndpoint(it, ip, port) to cc } }
            }
        }.filter { it.second == country }

        // hproxy.com, per country (recently verified only)
        all += cached("hproxy:$country") {
            val body = fetch(client, "https://hproxy.com/v1/proxy-list?format=json&recent=true&country=$country&limit=5000")
                ?: return@cached emptyList()
            json.parseToJsonElement(body).jsonArray.flatMap { el ->
                val o = el as? JsonObject ?: return@flatMap emptyList()
                if (str(o["status"]) != null && str(o["status"]) != "alive") return@flatMap emptyList()
                val ip = str(o["ip"]) ?: return@flatMap emptyList()
                val port = o["port"]?.jsonPrimitive?.intOrNull ?: return@flatMap emptyList()
                (o["protocols"] as? JsonArray).orEmpty().mapNotNull { p -> normType(str(p))?.let { ProxyEndpoint(it, ip, port) to country } }
            }
        }

        // TheSpeedX lists carry no country: use the ones the background classifier already placed here
        all += speedX().filter { IpClassifier.cached(it.host)?.countryCode == country }.map { it to country }

        // SOCKS5 first (tunnels any protocol), then HTTP; de-duplicated
        return all.map { it.first }.distinctBy { it.key }.sortedBy { if (it.type == "socks5") 0 else 1 }
    }

    // ── TheSpeedX/PROXY-List: large, refreshed often, no country info ──────────
    @Volatile private var speedXList: List<ProxyEndpoint> = emptyList()
    @Volatile private var speedXAt = 0L

    fun speedX(): List<ProxyEndpoint> = speedXList

    /** Re-downloads the TheSpeedX HTTP + SOCKS5 lists every 30 min. */
    fun refreshSpeedX(client: OkHttpClient) {
        val now = System.currentTimeMillis()
        if (now - speedXAt < TTL_MS) return
        speedXAt = now
        val base = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/"
        val out = ArrayList<ProxyEndpoint>()
        for ((file, type) in listOf("socks5.txt" to "socks5", "http.txt" to "http")) {
            val body = fetch(client, base + file) ?: continue
            body.lineSequence().mapNotNull { line ->
                val t = line.trim(); val i = t.lastIndexOf(':')
                if (i <= 0) null else t.substring(i + 1).toIntOrNull()?.let { ProxyEndpoint(type, t.substring(0, i), it) }
            }.forEach { out += it }
        }
        if (out.isNotEmpty()) {
            speedXList = out.distinctBy { it.key }
            ServerState.info("[GeoProxy] TheSpeedX lists: ${speedXList.size} proxies")
        }
    }

    /** Hosts from TheSpeedX not looked up yet (for the background classifier). */
    fun unclassifiedSpeedX(limit: Int): List<String> =
        speedXList.asSequence().map { it.host }.distinct().filter { IpClassifier.cached(it) == null }.take(limit).toList()
}

/** ip-api.com batch lookups (country + hosting flag), rate-limited and cached. */
internal object IpClassifier {
    data class Info(val countryCode: String?, val hosting: Boolean?, val isp: String?, val at: Long, val org: String? = null) {
        /**
         * ip-api's hosting flag misses many clouds (Cloudflare WARP exits, Google Cloud,
         * Indian DCs like CtrlS) — the ISP/org/AS name settles it.
         */
        val residential: Boolean get() = hosting == false && !DATACENTER_NAME.containsMatchIn(listOfNotNull(isp, org).joinToString(" "))
    }

    private val DATACENTER_NAME = Regex(
        "cloudflare|oracle|amazon|aws|google|microsoft|azure|digitalocean|vultr|choopa|linode|akamai|ovh|hetzner|" +
            "contabo|ctrls|alibaba|tencent|huawei cloud|leaseweb|m247|datacamp|cdn77|zenlayer|hostinger|hosting|" +
            "data ?cent|datacenter|server|cloud|vps|colo|ipxo|g-core|gcore|scaleway|ionos|godaddy|netcup|kamatera",
        RegexOption.IGNORE_CASE,
    )

    private val cache = ConcurrentHashMap<String, Info>()
    private const val TTL_MS = 24 * 60 * 60_000L

    /** Cached lookup only (no network). */
    fun cached(ip: String): Info? = cache[ip]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
    private val rate = Mutex()
    @Volatile private var nextAllowed = 0L

    suspend fun classify(ips: List<String>, client: OkHttpClient, json: Json): Map<String, Info> {
        val now = System.currentTimeMillis()
        val out = HashMap<String, Info>()
        val missing = ips.filter { ip ->
            val c = cache[ip]
            if (c != null && now - c.at < TTL_MS) { out[ip] = c; false } else true
        }
        for (chunk in missing.chunked(100)) {
            val got = batch(chunk, client, json) ?: break
            out += got
        }
        if (cache.size > 20_000) cache.entries.removeIf { now - it.value.at > TTL_MS }
        return out
    }

    private suspend fun batch(ips: List<String>, client: OkHttpClient, json: Json): Map<String, Info>? = rate.withLock {
        // Free tier: 15 batch requests per minute
        val wait = nextAllowed - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        nextAllowed = System.currentTimeMillis() + 4_500
        val body = json.encodeToString(JsonArray.serializer(), JsonArray(ips.map { JsonPrimitive(it) }))
        val req = Request.Builder()
            .url("http://ip-api.com/batch?fields=query,status,countryCode,isp,org,as,hosting")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    nextAllowed = System.currentTimeMillis() + 60_000
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                val now = System.currentTimeMillis()
                json.parseToJsonElement((resp.body?.string() ?: "")).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val ip = o["query"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (o["status"]?.jsonPrimitive?.contentOrNull != "success") return@mapNotNull null
                    val info = Info(
                        o["countryCode"]?.jsonPrimitive?.contentOrNull,
                        o["hosting"]?.jsonPrimitive?.booleanOrNull,
                        o["isp"]?.jsonPrimitive?.contentOrNull,
                        now,
                        listOfNotNull(o["org"]?.jsonPrimitive?.contentOrNull, o["as"]?.jsonPrimitive?.contentOrNull).joinToString(" "),
                    )
                    cache[ip] = info
                    ip to info
                }.toMap()
            }
        }.getOrNull()
    }
}
