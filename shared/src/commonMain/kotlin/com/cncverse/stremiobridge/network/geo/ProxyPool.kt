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

/** A public proxy endpoint. Only HTTP (CONNECT-capable) and SOCKS5 are usable from the JVM. */
@Serializable
data class ProxyEndpoint(val type: String, val host: String, val port: Int) {
    val key: String get() = "$type://$host:$port"
    fun toJavaProxy(): Proxy = Proxy(
        if (type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress(host, port),
    )
}

/** A validated proxy in a country pool. */
class PooledProxy(
    val endpoint: ProxyEndpoint,
    val country: String,
    val residential: Boolean,
    val isp: String?,
    @Volatile var exitIp: String?,
) {
    @Volatile var latencyMs: Long = 0
    @Volatile var consecutiveFailures: Int = 0
    val successes = AtomicInteger(0)
    val failures = AtomicInteger(0)
    @Volatile var lastOk: Long = 0
    @Volatile var lastChecked: Long = 0
    val addedAt: Long = System.currentTimeMillis()

    /** Lower is better: latency plus a heavy penalty per recent failure, residential preferred. */
    val score: Long get() = latencyMs + consecutiveFailures * 4_000L + (if (residential) 0 else 2_500L)
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
)

fun PooledProxy.snapshot() = PooledProxySnapshot(
    endpoint, country, residential, isp, exitIp, latencyMs, consecutiveFailures,
    successes.get(), failures.get(), lastOk, lastChecked, addedAt,
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
    @Volatile var allowDatacenter = false
    private const val MIN_HEALTHY = 3
    private const val HEALTH_INTERVAL_MS = 3 * 60_000L
    private const val SOURCE_TTL_MS = 30 * 60_000L
    private const val DEMAND_TTL_MS = 6 * 60 * 60_000L
    private const val MAX_FAILURES = 2
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

    /** Plain client for source lists and ip-api (goes out over the server's normal path). */
    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
            OkHttpClient.Builder()
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
                delay(60_000)
            }
        }
    }

    /** Marks [country] as wanted so the background loop keeps a pool for it. */
    fun demand(country: String) {
        val prev = demand.put(country, System.currentTimeMillis())
        if (prev == null) ServerState.info("[GeoProxy] pool requested for $country")
        if (healthy(country).size < MIN_HEALTHY) refillAsync(country)
    }

    fun healthy(country: String): List<PooledProxy> =
        pools[country].orEmpty().filter { it.consecutiveFailures < MAX_FAILURES }

    /**
     * Up to [n] proxies for [country], best first, spreading load across the
     * top few. Empty when the pool is still being built.
     */
    fun pick(country: String, n: Int = 2, exclude: Set<String> = emptySet()): List<PooledProxy> {
        demand(country)
        val ranked = healthy(country).filter { it.endpoint.key !in exclude }.sortedBy { it.score }
        if (ranked.isEmpty()) return emptyList()
        val head = ranked.take(3).shuffled()
        return (head + ranked.drop(3)).take(n)
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
        p.lastOk = System.currentTimeMillis()
        p.latencyMs = if (p.latencyMs == 0L) latencyMs else (p.latencyMs * 7 + latencyMs) / 8
    }

    fun reportFailure(p: PooledProxy, reason: String?) {
        p.failures.incrementAndGet()
        p.consecutiveFailures++
        if (p.consecutiveFailures >= MAX_FAILURES) evict(p, reason ?: "failures")
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
            rejected[p.endpoint.key] = System.currentTimeMillis() + 60 * 60_000L
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
                    pool += PooledProxy(s.endpoint, country, s.residential, s.isp, s.exitIp).apply {
                        latencyMs = s.latencyMs
                        lastOk = s.lastOk
                        lastChecked = 0 // force a re-check soon
                    }
                }
            }
            if (list.isNotEmpty()) demand.putIfAbsent(country, System.currentTimeMillis())
        }
    }

    fun refillAsync(country: String) {
        scope.launch { runCatching { refill(country) } }
    }

    // ── Background maintenance ───────────────────────────────────────────────

    private suspend fun maintain() {
        val now = System.currentTimeMillis()
        // Forget countries nobody asked for in a while
        demand.entries.removeIf { now - it.value > DEMAND_TTL_MS }
        rejected.entries.removeIf { it.value < now }
        pools.keys.filter { !demand.containsKey(it) }.forEach { c ->
            pools.remove(c)?.forEach { dropClient(it.endpoint) }
        }
        for (country in demand.keys) {
            val pool = pools[country].orEmpty()
            val due = pool.filter { now - it.lastChecked > HEALTH_INTERVAL_MS }
            if (due.isNotEmpty()) healthCheck(due)
            if (healthy(country).size < targetSize) {
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
                        reportFailure(p, "health check failed")
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
            val need = targetSize - healthy(country).size
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
            val residential = inCountry.filter { info[it.host]?.hosting == false }
            val ordered = residential + (if (allowDatacenter) inCountry.filter { info[it.host]?.hosting == true } else emptyList())
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
                        if (r == null) { rejected[ep.key] = System.currentTimeMillis() + 60 * 60_000L; null }
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
                val exit = exitInfo[r.exitIp]
                if (exit != null && exit.countryCode != country) {
                    rejected[ep.key] = System.currentTimeMillis() + 6 * 60 * 60_000L
                    continue
                }
                val proxyInfo = info[ep.host]
                val isResidential = (exit?.hosting ?: proxyInfo?.hosting) == false
                if (!isResidential && !allowDatacenter) continue
                if (pool.any { it.endpoint == ep }) continue
                pool += PooledProxy(ep, country, isResidential, exit?.isp ?: proxyInfo?.isp, r.exitIp).apply {
                    latencyMs = r.latencyMs
                    lastOk = System.currentTimeMillis()
                    lastChecked = lastOk
                    successes.incrementAndGet()
                }
                accepted++
            }
            ServerState.info("[GeoProxy] $country: +$accepted proxies (pool ${healthy(country).size}/$targetSize)")
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    class ProbeResult(val exitIp: String, val latencyMs: Long)

    /** HTTPS request through [ep]; returns the exit IP or null when the proxy is unusable. */
    suspend fun probeThrough(ep: ProxyEndpoint): ProbeResult? {
        val client = OkHttpClient.Builder()
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

        // SOCKS5 first (tunnels any protocol), then HTTP; de-duplicated
        return all.map { it.first }.distinctBy { it.key }.sortedBy { if (it.type == "socks5") 0 else 1 }
    }
}

/** ip-api.com batch lookups (country + hosting flag), rate-limited and cached. */
internal object IpClassifier {
    data class Info(val countryCode: String?, val hosting: Boolean?, val isp: String?, val at: Long)

    private val cache = ConcurrentHashMap<String, Info>()
    private const val TTL_MS = 24 * 60 * 60_000L
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
            .url("http://ip-api.com/batch?fields=query,status,countryCode,isp,hosting")
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
                    )
                    cache[ip] = info
                    ip to info
                }.toMap()
            }
        }.getOrNull()
    }
}
