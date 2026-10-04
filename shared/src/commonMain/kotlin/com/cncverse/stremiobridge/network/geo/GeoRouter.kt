package com.cncverse.stremiobridge.network.geo

import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

@Serializable
data class GeoProxySettings(
    val enabled: Boolean = true,
    /** Use datacenter proxies when a country has no working residential ones. */
    val allowDatacenter: Boolean = false,
    /** Fall back to datacenter proxies while a country has under 3 working residential ones. */
    val datacenterFallback: Boolean = true,
    /** Minimum healthy proxies kept per country in use. */
    val poolSize: Int = 6,
    /** Upper bound per country when live streams push the pool to grow. */
    val maxPoolSize: Int = 30,
    /** Live channels one proxy may carry before the next one is used (bandwidth spread). */
    val maxStreamsPerProxy: Int = 3,
    /** How long a learned "this host blocks the server IP" stays before direct is retried. */
    val blockTtlHours: Int = 12,
    /** Country used for extensions whose country can't be detected ("" = none → no proxy). */
    val defaultCountry: String = "",
    /**
     * Host suffix → country, for hosts that are geo-locked whatever extension
     * asks for them.
     */
    val domainRules: Map<String, String> = DEFAULT_DOMAIN_RULES,
    /**
     * Private/paid proxies, one per line: `IN socks5://user:pass@host:port *50`.
     * Preferred over the public pool, never evicted. A rotating residential
     * gateway with a high `*N` capacity carries many live channels.
     */
    val customProxies: List<String> = emptyList(),
    /**
     * Default route for all "direct" traffic (server mode: Cloudflare WARP's local SOCKS,
     * socks5://127.0.0.1:40000). Blank = really direct. Falls back to direct if it is down.
     * Pool proxies never go through it — they connect straight from this server.
     */
    val warpProxy: String = System.getenv("CNC_WARP_PROXY")?.trim().orEmpty(),
    /**
     * Ultrasurf (local SOCKS, socks5://127.0.0.1:9667): India's first proxy, tried before
     * any public IN proxy and never evicted. Blank = not used.
     */
    val ultrasurfProxy: String = System.getenv("CNC_ULTRASURF_PROXY")?.trim().orEmpty(),
    /** Own Indian tunnel (socks on the Indian VPS, reverse-tunnelled to this host); tried before Ultrasurf. */
    val tunnelProxy: String = System.getenv("CNC_TUNNEL_PROXY")?.trim().orEmpty(),
    /** Hosts (and subdomains) the own tunnel carries — only these, to keep the VPS's bandwidth for Jio. */
    val tunnelDomains: List<String> = listOf("jio.com"),
    /**
     * Domain fronting through the tunnel: blocked host -> allowed host on the same CDN. The tunnel's
     * network (Sophos) resets TLS by SNI for jiotvmblive.cdn.jio.com; connecting as jiotvpllive and
     * naming the real host in the Host header gets the same content from Fastly.
     */
    val tunnelFronting: Map<String, String> = mapOf("jiotvmblive.cdn.jio.com" to "jiotvpllive.cdn.jio.com"),
    /** Host suffixes that always go through Ultrasurf (never public proxies, never skipped). */
    val ultrasurfDomains: List<String> = listOf("workers.dev", "jio.com"),
)

val DEFAULT_DOMAIN_RULES: Map<String, String> = mapOf(
    "tv.imgcdn.kim" to "IN",
    "jio.com" to "IN",
    "workers.dev" to "IN",
)

/** Admin override for one extension. mode: auto | off | always. */
@Serializable
data class PluginGeoOverride(val mode: String = "auto", val country: String? = null)

@Serializable
private data class GeoStateFile(
    val settings: GeoProxySettings = GeoProxySettings(),
    val overrides: Map<String, PluginGeoOverride> = emptyMap(),
    /** host → where it works from. An IP block belongs to the host, so every extension shares it. */
    val hostRoutes: Map<String, HostRoute> = emptyMap(),
    /** plugin → expiry: the health probe found it only works through a proxy. */
    val pluginProxy: Map<String, Long> = emptyMap(),
    val pools: Map<String, List<PooledProxySnapshot>> = emptyMap(),
)

/** A host that refuses the server IP: proxy country, expiry, and the extensions seen using it. */
@Serializable
data class HostRoute(val country: String, val until: Long, val plugins: Set<String> = emptySet())

@Serializable
data class PluginGeoStatus(
    val plugin: String,
    val displayName: String,
    val country: String? = null,
    val countrySource: String = "none",
    val detectedCountry: String? = null,
    val mode: String = "auto",
    val overrideCountry: String? = null,
    val proxiedAll: Boolean = false,
    val blockedHosts: List<String> = emptyList(),
    val proxiedOk: Long = 0,
    val proxiedFail: Long = 0,
    val directBlocked: Long = 0,
)

@Serializable
data class GeoEvent(val at: Long, val plugin: String?, val message: String)

@Serializable
data class StreamLeaseInfo(
    val key: String,
    val country: String,
    val primary: String?,
    val standby: String?,
    val idleSec: Long,
)

/**
 * Decides, per plugin and host, whether a request goes out directly or
 * through a residential proxy of the right country.
 *
 *  - unknown host: try direct; if it is refused (403/450/451) or the
 *    connection is cut/times out, retry through the country pool. When the
 *    proxy works the host is remembered for [GeoProxySettings.blockTtlHours]
 *    and goes proxy-first from then on.
 *  - remembered host / plugin marked by the health probe / admin "always":
 *    proxy first, direct as the last resort.
 *  - admin "off", no detectable country, or the proxy did not help either:
 *    direct only.
 *
 * The country is the domain rule's (e.g. jio.com → IN) when the host matches
 * one — learned under "*" so every extension shares it — otherwise the
 * extension's own country.
 */
object GeoRouter {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var settings = GeoProxySettings()
        private set
    private val overrides = ConcurrentHashMap<String, PluginGeoOverride>()
    private val hostBlocks = ConcurrentHashMap<String, HostRoute>()
    private val pluginProxy = ConcurrentHashMap<String, Long>()
    /** host → until: the proxy did not help, stop retrying for a while. */
    private val noHelp = ConcurrentHashMap<String, Long>()

    private class PluginInfo(val displayName: String, val country: String?, val source: String)
    private val plugins = ConcurrentHashMap<String, PluginInfo>()

    private class Counters { val ok = AtomicLong(); val fail = AtomicLong(); val blocked = AtomicLong() }
    private val counters = ConcurrentHashMap<String, Counters>()

    private val events = java.util.ArrayDeque<GeoEvent>()

    private var stateFile: File? = null
    @Volatile private var dirty = false

    private const val NO_HELP_MS = 30 * 60_000L
    /** Shared scope key for hosts covered by a domain rule. */
    private const val ANY = "*"
    /**
     * Status codes meaning "this IP may not have it". Jio answers 450 to
     * foreign IPs, 451 is the standard code, 403 is what most sites use.
     */
    val GEO_BLOCK_CODES = setOf(403, 450, 451)
    /** Unambiguous geo/legal blocks — worth a proxy even with no other evidence. */
    val HARD_BLOCK_CODES = setOf(450, 451)
    /** CNC_GEO_DEBUG=1 logs every routing decision. */
    private val DEBUG = System.getenv("CNC_GEO_DEBUG") == "1"

    // ── Lifecycle / persistence ──────────────────────────────────────────────

    @Volatile private var initialized = false

    @Synchronized
    fun init(cacheDir: String) {
        if (initialized) return
        initialized = true
        val f = File(cacheDir, "geo_proxy.json")
        stateFile = f
        if (f.exists()) runCatching {
            val s = json.decodeFromString(GeoStateFile.serializer(), f.readText())
            settings = s.settings
            overrides.putAll(s.overrides)
            val now = System.currentTimeMillis()
            s.hostRoutes.filterValues { it.until > now }.let { hostBlocks.putAll(it) }
            s.pluginProxy.filterValues { it > now }.let { pluginProxy.putAll(it) }
            ProxyPool.restore(s.pools)
        }.onFailure { ServerState.warn("[GeoProxy] could not read geo_proxy.json: ${it.message}") }
        com.cncverse.stremiobridge.state.StreamTracker.proxiedNow = { routesViaProxy(it) }
        applySettings()
        ProxyPool.start()
        scope.launch {
            while (isActive) {
                delay(30_000)
                runCatching { expireLeases() }
                if (dirty) save()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { save() } })
    }

    private fun applySettings() {
        ProxyPool.targetSize = settings.poolSize.coerceIn(2, 20)
        ProxyPool.maxSize = settings.maxPoolSize.coerceIn(ProxyPool.targetSize, 60)
        ProxyPool.allowDatacenter = settings.allowDatacenter
        ProxyPool.datacenterFallback = settings.datacenterFallback
        ProxyPool.setCustom(settings.customProxies)
        // Pinned hosts (jio.com, workers.dev…) use the own Indian tunnel first, Ultrasurf as fallback
        ProxyPool.setBuiltin("IN", "tunnel", settings.tunnelProxy, rank = 0)
        ProxyPool.setBuiltin("IN", "ultrasurf", settings.ultrasurfProxy, rank = 1)
        warp = parseLocalProxy(settings.warpProxy)
    }

    @Synchronized
    fun save() {
        val f = stateFile ?: return
        dirty = false
        val now = System.currentTimeMillis()
        val state = GeoStateFile(
            settings = settings,
            overrides = HashMap(overrides),
            hostRoutes = hostBlocks.filterValues { it.until > now },
            pluginProxy = pluginProxy.filterValues { it > now },
            pools = ProxyPool.snapshot().mapValues { (_, l) -> l.filter { it.consecutiveFailures == 0 && !it.custom } },
        )
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(GeoStateFile.serializer(), state))
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { ServerState.warn("[GeoProxy] save failed: ${it.message}") }
    }

    private fun markDirty() { dirty = true }

    fun updateSettings(s: GeoProxySettings) {
        val rules = s.domainRules
            .mapKeys { it.key.trim().lowercase().removePrefix("*.").removePrefix(".") }
            .mapValues { it.value.trim().uppercase() }
            .filter { (k, v) -> k.isNotEmpty() && '.' in k && Regex("^[A-Z]{2}$").matches(v) }
        settings = s.copy(
            poolSize = s.poolSize.coerceIn(2, 20),
            maxPoolSize = s.maxPoolSize.coerceIn(s.poolSize.coerceIn(2, 20), 60),
            maxStreamsPerProxy = s.maxStreamsPerProxy.coerceIn(1, 50),
            blockTtlHours = s.blockTtlHours.coerceIn(1, 24 * 14),
            defaultCountry = s.defaultCountry.trim().uppercase().take(2),
            domainRules = rules,
            ultrasurfDomains = s.ultrasurfDomains.map { it.trim().lowercase().removePrefix("*.").removePrefix(".") }
                .filter { it.isNotEmpty() && '.' in it }.distinct(),
        )
        applySettings()
        save()
    }

    fun setOverride(plugin: String, o: PluginGeoOverride?) {
        if (o == null || (o.mode == "auto" && o.country.isNullOrBlank())) overrides.remove(plugin)
        else overrides[plugin] = o.copy(country = normalizeCountries(o.country))
        if (o?.mode == "off") clearLearned(plugin)
        // Build every listed country's pool, so the choice between them has something to pick from
        overrideCountries(plugin).forEach { ProxyPool.demand(it) }
        countryFor(plugin)?.let { if (o?.mode == "always") ProxyPool.demand(it) }
        save()
    }

    /** Forgets everything learned about [plugin] (blocked hosts, probe result). */
    fun clearLearned(plugin: String) {
        // Hosts only this extension used are forgotten; shared ones just lose it as a user
        hostBlocks.entries.removeIf { (_, r) -> r.plugins == setOf(plugin) }
        hostBlocks.replaceAll { _, r -> if (plugin in r.plugins) r.copy(plugins = r.plugins - plugin) else r }
        pluginProxy.remove(plugin)
        markDirty()
    }

    /** Forgets a learned host (admin). */
    fun clearHost(host: String) {
        hostBlocks.remove(host)
        noHelp.remove(host)
        markDirty()
    }

    // ── Plugin → country ─────────────────────────────────────────────────────

    data class PluginGeoInfo(
        val internalName: String,
        val displayName: String,
        val names: List<String>,
        val sectionNames: List<String>,
        val languages: List<String?>,
    )

    /** Called after every plugin (re)load. */
    fun registerPlugins(list: List<PluginGeoInfo>) {
        val fresh = list.associate { p ->
            val r = CountryResolver.resolve(p.names, p.sectionNames, p.languages)
            p.internalName to PluginInfo(p.displayName, r.country, r.source)
        }
        plugins.keys.retainAll(fresh.keys)
        plugins.putAll(fresh)
        // Keep pools warm for countries that already have learned routes
        activeCountries().forEach { ProxyPool.demand(it) }
    }

    fun detectedCountry(plugin: String): String? = plugins[plugin]?.country

    fun overrideMode(plugin: String): String = overrides[plugin]?.mode ?: "auto"

    /** "th, id vn" → "TH,ID,VN"; null when no valid 2-letter code is given. */
    private fun normalizeCountries(raw: String?): String? =
        raw?.split(',', ' ', ';', '/')?.map { it.trim().uppercase() }?.filter { it.length == 2 && it.all(Char::isLetter) }
            ?.distinct()?.joinToString(",")?.takeIf { it.isNotEmpty() }

    private fun overrideCountries(plugin: String): List<String> =
        overrides[plugin]?.country?.split(',')?.filter { it.length == 2 }.orEmpty()

    fun countryFor(plugin: String): String? {
        // An admin list ("TH,ID,VN") means any of them: use the one with the most working
        // proxies right now (the first listed on a tie, or while no pool is built yet)
        val listed = overrideCountries(plugin)
        if (listed.size == 1) return listed[0]
        if (listed.size > 1) return listed.maxWith(compareBy<String> { ProxyPool.healthy(it).size }.thenBy { -listed.indexOf(it) })
        plugins[plugin]?.country?.let { return it }
        return settings.defaultCountry.takeIf { it.length == 2 }
    }

    /**
     * The request to send through the own tunnel for [request]: for a fronted host, the URL uses
     * the allowed host (TLS SNI) and the Host header names the real one. Null = send as is.
     */
    fun frontedForTunnel(request: okhttp3.Request): okhttp3.Request? {
        val real = request.url.host
        val front = settings.tunnelFronting[real.lowercase()] ?: return null
        return request.newBuilder()
            .url(request.url.newBuilder().host(front).build())
            .header("Host", real)
            .build()
    }

    /** Hosts the own Indian tunnel may carry (settings.tunnelDomains, suffix match). */
    fun tunnelHost(host: String): Boolean {
        val h = host.lowercase()
        return settings.tunnelProxy.isNotBlank() && settings.tunnelDomains.any { d -> h == d || h.endsWith(".$d") }
    }

    /** Hosts pinned to Ultrasurf (settings.ultrasurfDomains, suffix match). */
    fun ultrasurfOnly(host: String): Boolean {
        val h = host.lowercase()
        return (settings.ultrasurfProxy.isNotBlank() || settings.tunnelProxy.isNotBlank()) &&
            settings.ultrasurfDomains.any { d -> h == d || h.endsWith(".$d") }
    }

    /** Country of the domain rule matching [host] (suffix match), if any. */
    fun ruleCountry(host: String): String? {
        val h = host.lowercase()
        if (ultrasurfOnly(h)) return "IN"
        return settings.domainRules.entries.firstOrNull { (d, _) -> h == d || h.endsWith(".$d") }?.value
    }


    private fun countrySource(plugin: String): String = when {
        overrides[plugin]?.country != null -> "admin"
        plugins[plugin]?.country != null -> plugins[plugin]!!.source
        settings.defaultCountry.length == 2 -> "default"
        else -> "none"
    }

    private fun activeCountries(): Set<String> {
        val now = System.currentTimeMillis()
        val fromBlocks = hostBlocks.values.filter { it.until > now }.map { it.country }
        val ps = pluginProxy.filterValues { it > now }.keys + overrides.filterValues { it.mode == "always" }.keys
        return (fromBlocks + ps.mapNotNull { countryFor(it) }).toSet()
    }

    // ── Decisions ────────────────────────────────────────────────────────────

    enum class Decision { DIRECT, DIRECT_THEN_PROXY, PROXY_FIRST }

    fun decide(plugin: String?, mode: RouteMode, host: String): Pair<Decision, String?> {
        if (mode == RouteMode.FORCE_DIRECT) return Decision.DIRECT to null
        val rule = ruleCountry(host)
        if (!settings.enabled) {
            val c = rule ?: plugin?.let { countryFor(it) } ?: (if (mode == RouteMode.FORCE_PROXY) "IN" else null)
            return if (mode == RouteMode.FORCE_PROXY && c != null) Decision.PROXY_FIRST to c else Decision.DIRECT to null
        }
        val o = plugin?.let { overrides[it] }
        if (o?.mode == "off") return Decision.DIRECT to null
        val now = System.currentTimeMillis()
        // A host known to refuse the server IP is proxied for every extension, from where it worked
        val learned = hostBlocks[host]?.takeIf { it.until > now }
        // A forced-proxy probe of an extension with no known country tries India (Ultrasurf first)
        val country = learned?.country ?: rule ?: plugin?.let { countryFor(it) }
            ?: (if (mode == RouteMode.FORCE_PROXY) "IN" else null) ?: return Decision.DIRECT to null
        // Domain-rule hosts (jio.com, tv.imgcdn.kim…) are known to need it: proxy first (Ultrasurf for IN)
        if (learned != null || rule != null || mode == RouteMode.FORCE_PROXY || o?.mode == "always" ||
            (plugin != null && (pluginProxy[plugin] ?: 0L) > now)
        ) return Decision.PROXY_FIRST to country
        if ((noHelp[host] ?: 0L) > now) return Decision.DIRECT to null
        return Decision.DIRECT_THEN_PROXY to country
    }

    /**
     * Hosts whose 403 means "token expired", not "IP refused": Jio answers 450 to a
     * blocked IP and 403 to a stale __hdnea__ token. Proxy/route switching can't fix
     * an expired token — the 403 goes straight back so the caller refreshes it.
     */
    private val TOKEN_403_HOSTS = listOf("jio.com")

    fun isToken403Host(host: String): Boolean {
        val h = host.lowercase()
        return TOKEN_403_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Does [code] from [host] mean this IP is refused (so another route may help)? */
    fun geoBlocked(host: String, code: Int): Boolean =
        code in HARD_BLOCK_CODES || (code == 403 && !isToken403Host(host))

    /** WARP's local SOCKS endpoint (default route), or null. */
    @Volatile var warp: java.net.Proxy? = null
        private set

    /** "socks5://host:port" / "http://host:port" → Proxy, or null when blank/invalid. */
    fun parseLocalProxy(url: String?): java.net.Proxy? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        val type = when (uri.scheme?.lowercase()) {
            "socks5", "socks5h", "socks" -> java.net.Proxy.Type.SOCKS
            "http", "https" -> java.net.Proxy.Type.HTTP
            else -> return null
        }
        return java.net.Proxy(type, java.net.InetSocketAddress.createUnresolved(host, port))
    }

    /** Loopback / private / link-local hosts and local names — never routed anywhere. */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost")) return true
        if (!(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) || ':' in h)) return false
        val a = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false
        return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress
    }

    /** True when anything is currently routed through a proxy (cheap gate for the ProxySelector). */
    fun hasProxyRoutes(): Boolean =
        hostBlocks.isNotEmpty() || pluginProxy.isNotEmpty() || overrides.values.any { it.mode == "always" }

    private fun markBlocked(plugin: String?, host: String, country: String) {
        val until = System.currentTimeMillis() + settings.blockTtlHours * 3_600_000L
        val prev = hostBlocks[host]
        hostBlocks[host] = HostRoute(country, until, (prev?.plugins ?: emptySet()) + setOfNotNull(plugin))
        if (prev == null || prev.until < System.currentTimeMillis()) {
            event(plugin, "$host blocks the server IP — routing via $country proxy (all extensions)")
        }
        counters.getOrPut(plugin ?: ANY) { Counters() }.blocked.incrementAndGet()
        markDirty()
    }

    /** Direct connection failures per host (count, window start); see retryViaProxy. */
    private val directStrikes = ConcurrentHashMap<String, Pair<Int, Long>>()
    private const val STRIKES_TO_BLOCK = 3
    private const val STRIKE_WINDOW_MS = 10 * 60_000L

    /** Counts a direct connection failure for [host]; true once there have been enough recently. */
    private fun directStrike(host: String): Boolean {
        val now = System.currentTimeMillis()
        val (n, _) = directStrikes.compute(host) { _, prev ->
            if (prev == null || now - prev.second > STRIKE_WINDOW_MS) 1 to now else (prev.first + 1) to prev.second
        }!!
        if (n >= STRIKES_TO_BLOCK) { directStrikes.remove(host); return true }
        if (directStrikes.size > 5_000) directStrikes.entries.removeIf { now - it.value.second > STRIKE_WINDOW_MS }
        return false
    }

    private fun markNoHelp(host: String) {
        noHelp[host] = System.currentTimeMillis() + NO_HELP_MS
    }

    /** Result of a health probe: the plugin only produces streams through a proxy. */
    fun markPluginNeedsProxy(plugin: String, needs: Boolean) {
        if (needs) {
            val until = System.currentTimeMillis() + settings.blockTtlHours.coerceAtLeast(24) * 3_600_000L
            if (pluginProxy.put(plugin, until) == null) event(plugin, "works only through a ${countryFor(plugin)} proxy")
        } else {
            pluginProxy.remove(plugin)
        }
        markDirty()
    }

    /** Health tracking asks whether a success came through a proxy. */
    /**
     * Did [plugin]'s last requests actually go through a proxy? Health uses it to tell
     * "works only via proxy" from "works". It used to be "has any blocked host / proxy flag",
     * so one blocked image host made every success of the plugin count as a proxy success.
     */
    internal fun routesViaProxy(plugin: String): Boolean =
        (lastProxiedAt[plugin] ?: 0L) > System.currentTimeMillis() - 60_000L

    /** plugin → last time one of its requests was sent through a pool proxy. */
    private val lastProxiedAt = ConcurrentHashMap<String, Long>()

    /**
     * Hosts on a fixed domain rule (workers.dev → Ultrasurf, jio.com…) are proxied by choice,
     * not because the plugin is blocked here — they don't make it a "works only via proxy" one.
     */
    private fun markProxied(plugin: String?, host: String) {
        if (plugin == null || ruleCountry(host) != null || ultrasurfOnly(host)) return
        lastProxiedAt[plugin] = System.currentTimeMillis()
    }

    /** Requests of a forced-proxy health probe that no proxy could carry (per plugin). */
    private val probeMisses = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun resetProbeMisses(plugin: String) { probeMisses.remove(plugin) }
    fun probeMisses(plugin: String): Int = probeMisses[plugin]?.get() ?: 0

    fun needsProxy(plugin: String): Boolean = (pluginProxy[plugin] ?: 0L) > System.currentTimeMillis()

    private fun event(plugin: String?, message: String) {
        ServerState.info("[GeoProxy] " + (plugin?.let { "[$it] " } ?: "") + message)
        synchronized(events) {
            events.addFirst(GeoEvent(System.currentTimeMillis(), plugin, message))
            while (events.size > 200) events.removeLast()
        }
    }

    // ── Request execution through the pool ───────────────────────────────────

    private fun retryable(req: Request): Boolean = req.body?.isOneShot() != true

    private fun blockish(e: IOException): Boolean =
        e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException ||
            e is SSLException || (e is SocketException) ||
            e.message?.contains("reset", ignoreCase = true) == true ||
            e.message?.contains("timeout", ignoreCase = true) == true

    /**
     * Sends [req] through up to three pooled proxies of [country]. A proxy
     * whose answer is itself a geo block (or a proxy-level error) is skipped
     * for the next one. Returns the first usable response, the last blocked
     * one when nothing better came back, or null when no proxy answered.
     */
    fun viaPool(plugin: String?, country: String, req: Request, residentialOnly: Boolean = false): Response? {
        val tried = HashSet<String>()
        var lastBlocked: Response? = null
        val refused = ArrayList<PooledProxy>(2)
        repeat(3) {
            val p = ProxyPool.pick(country, 1, tried, req.url.host, residentialOnly).firstOrNull() ?: return lastBlocked
            tried += p.endpoint.key
            val started = System.currentTimeMillis()
            try {
                val resp = ProxyPool.clientFor(p.endpoint).newCall(req).execute()
                when {
                    // 407 / gateway errors come from the proxy itself, not the site
                    resp.code == 407 || resp.code == 502 || resp.code == 504 -> {
                        resp.close()
                        ProxyPool.reportFailure(p, "HTTP ${resp.code}")
                    }
                    // This proxy's IP is refused by this site (e.g. datacenter range) — ban it for this host, try another
                    geoBlocked(req.url.host, resp.code) -> {
                        lastBlocked?.close()
                        lastBlocked = resp
                        if (resp.code in HARD_BLOCK_CODES || resp.header("cf-mitigated") != null) ProxyPool.banForHost(req.url.host, p)
                        else refused += p
                        ProxyPool.reportSuccess(p, System.currentTimeMillis() - started)
                    }
                    else -> {
                        lastBlocked?.close()
                        ProxyPool.reportSuccess(p, System.currentTimeMillis() - started)
                        counters.getOrPut(plugin ?: ANY) { Counters() }.ok.incrementAndGet()
                        markProxied(plugin, req.url.host)
                        // The site answers from here, so a 403 from the earlier proxy was about its IP
                        // (e.g. Ultrasurf on tv.imgcdn.kim) — skip it for this host for a while
                        refused.forEach { ProxyPool.banForHost(req.url.host, it) }
                        return resp
                    }
                }
            } catch (e: IOException) {
                ProxyPool.reportFailure(p, e.javaClass.simpleName)
            }
            counters.getOrPut(plugin ?: ANY) { Counters() }.fail.incrementAndGet()
        }
        // Nothing worked: the own Indian tunnel as a last resort (residential-only sites)
        ProxyPool.lastResortTunnel(country, req.url.host)?.let { t ->
            val started = System.currentTimeMillis()
            try {
                val sendReq = frontedForTunnel(req) ?: req
                val client = if (sendReq !== req) ProxyPool.frontingClientFor(t.endpoint) else ProxyPool.clientFor(t.endpoint)
                val resp = client.newCall(sendReq).execute()
                if (resp.code != 407 && resp.code != 502 && resp.code != 504 && !geoBlocked(req.url.host, resp.code)) {
                    lastBlocked?.close()
                    ProxyPool.reportSuccess(t, System.currentTimeMillis() - started)
                    counters.getOrPut(plugin ?: ANY) { Counters() }.ok.incrementAndGet()
                    event(plugin, "${req.url.host}: no proxy got through — used the India tunnel (last resort)")
                    return resp
                }
                resp.close()
            } catch (e: IOException) {
                ProxyPool.reportFailure(t, e.javaClass.simpleName)
            }
        }
        return lastBlocked
    }

    /**
     * OkHttp application interceptor for the plugin clients (after
     * CloudflareKiller). Every plugin request passes through here.
     */
    object ProxyInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val req = chain.request()
            val tag = NetContext.tagFor(chain.call())
            val plugin = tag?.plugin ?: NetContext.currentPlugin()
            val mode = tag?.mode ?: NetContext.currentMode()
            val host = req.url.host
            val (decision, country) = decide(plugin, mode, host)
            if (DEBUG) ServerState.info("[GeoProxy:debug] intercept host=$host plugin=$plugin mode=$mode tagged=${tag != null} -> $decision $country")
            when (decision) {
                Decision.DIRECT -> return chain.proceed(req)

                Decision.PROXY_FIRST -> {
                    // Health probes only trust residential/private proxies (datacenter refusals prove nothing)
                    val probing = mode == RouteMode.FORCE_PROXY
                    val r = if (retryable(req)) viaPool(plugin, country!!, req, residentialOnly = probing) else null
                    if (probing && (r == null || geoBlocked(req.url.host, r.code) || r.header("cf-mitigated") != null)) {
                        // The "via proxy" probe did not really get through a proxy here
                        plugin?.let { probeMisses.getOrPut(it) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
                    }
                    r?.let { return it }
                    return chain.proceed(req)
                }

                Decision.DIRECT_THEN_PROXY -> {
                    if (!retryable(req)) return chain.proceed(req)
                    val c = country!!
                    val direct = try {
                        chain.proceed(req)
                    } catch (e: IOException) {
                        if (!blockish(e)) throw e
                        when (val r = retryViaProxy(plugin, c, host, req, fromError = true)) {
                            null -> throw e
                            else -> return r
                        }
                    }
                    // A Cloudflare challenge (403 + cf-mitigated) counts too: datacenter IPs get challenged where
                    // residential ones pass, and the proxy answer is only kept if it is not a challenge itself
                    if (!geoBlocked(host, direct.code)) return direct
                    val viaProxy = retryViaProxy(plugin, c, host, req) ?: return direct
                    direct.close()
                    return viaProxy
                }
            }
        }

        /**
         * The direct attempt was refused. With no pool yet nothing can be
         * verified: request one for [country] (built in the background) and
         * keep the direct result; later requests retry once it exists.
         */
        private fun retryViaProxy(plugin: String?, country: String, host: String, req: Request, fromError: Boolean = false): Response? {
            if (ProxyPool.healthy(country).isEmpty()) {
                ProxyPool.demand(country)
                return null
            }
            val viaProxy = viaPool(plugin, country, req)
            if (viaProxy != null && viaProxy.code < 400 && viaProxy.header("cf-mitigated") == null) {
                // A refusal (403/45x) proves the block at once. A timeout or reset may just be a
                // bad moment (server overloaded, WARP hiccup) — that once put MovieBox's API and
                // Cinemeta behind proxies for 12 h — so it takes a few in a row to mark the host.
                if (!fromError || directStrike(host)) markBlocked(plugin, host, country)
                return viaProxy
            }
            viaProxy?.close()
            markNoHelp(host)
            return null
        }
    }

    /**
     * JVM-wide ProxySelector: covers OkHttpClients that plugins build
     * themselves (no interceptor of ours). Only proxy-first routes apply here —
     * there is no response to inspect, so nothing is learned at this level.
     */
    class Selector(private val delegate: java.net.ProxySelector?) : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): List<java.net.Proxy> {
            geoSelect(uri)?.let { return it }
            // Default route: WARP (falls back to direct when WARP is down). Local
            // services (FlareSolverr, our own relay, LAN) always go direct.
            val w = warp
            if (w != null && uri?.host?.let { isLocalHost(it) } == false) return listOf(w, java.net.Proxy.NO_PROXY)
            val fallback = runCatching { delegate?.select(uri) }.getOrNull()
            return if (fallback.isNullOrEmpty()) listOf(java.net.Proxy.NO_PROXY) else fallback
        }

        private fun geoSelect(uri: java.net.URI?): List<java.net.Proxy>? {
            val host = uri?.host ?: return null
            val mode = NetContext.currentMode()
            if (!hasProxyRoutes() && mode != RouteMode.FORCE_PROXY && ruleCountry(host) == null) return null
            val plugin = NetContext.resolvePlugin()
            if (plugin == null && ruleCountry(host) == null) return null
            val (decision, country) = decide(plugin, mode, host)
            if (decision != Decision.PROXY_FIRST || country == null) return null
            val picked = ProxyPool.pick(country, 2, host = host, residentialOnly = mode == RouteMode.FORCE_PROXY).map { it.endpoint.toJavaProxy() }
            if (picked.isEmpty()) {
                if (mode == RouteMode.FORCE_PROXY && plugin != null) probeMisses.getOrPut(plugin) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                return null
            }
            markProxied(plugin, host)
            return picked + listOfNotNull(warp) + java.net.Proxy.NO_PROXY
        }

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: IOException?) {
            val addr = sa as? java.net.InetSocketAddress ?: return
            ProxyPool.reportConnectFailed(addr.hostString, addr.port)
            runCatching { delegate?.connectFailed(uri, sa, ioe) }
        }
    }

    // ── Live streams: load-balanced leases with a hot standby ────────────────

    /**
     * One relayed live channel (all its viewers share it — the segment cache
     * fetches each segment once). It keeps one proxy (CDN tokens are often
     * pinned to an IP) with a different, already-validated proxy on standby.
     * Channels are spread over the pool by load, so many simultaneous streams
     * never pile onto one proxy's bandwidth; the pool grows with the number of
     * live channels.
     */
    class StreamLease(val key: String, val country: String, val host: String) {
        @Volatile var primary: PooledProxy? = null
            private set
        @Volatile var standby: PooledProxy? = null
            private set
        @Volatile var lastUsed = System.currentTimeMillis()

        @Volatile private var lastRankCheck = 0L

        @Synchronized
        fun current(): PooledProxy? {
            val now = System.currentTimeMillis()
            lastUsed = now
            // Hot path (every segment of every viewer): keep a working pair without rescanning the pool
            val p = primary
            val s = standby
            if (p != null && s != null && ProxyPool.isUsable(p) && ProxyPool.isUsable(s)) {
                // Pinned hosts: go back to a better-ranked built-in (the tunnel) once it works again
                if (p.builtin && p.rank > 0 && now - lastRankCheck > 15_000L) {
                    lastRankCheck = now
                    val best = ProxyPool.leastLoaded(country, emptySet(), host)
                    if (best != null && best.builtin && best.rank < p.rank) {
                        ServerState.info("[GeoProxy] stream $key: back to ${best.endpoint.key} (preferred)")
                        setPrimary(best)
                        standby = p
                        return best
                    }
                }
                return p
            }
            val healthy = ProxyPool.healthy(country).map { it.endpoint.key }.toSet()
            if (primary != null && primary!!.endpoint.key !in healthy) {
                setPrimary(standby?.takeIf { it.endpoint.key in healthy })
                standby = null
            }
            val cap = settings.maxStreamsPerProxy
            if (primary == null) setPrimary(ProxyPool.leastLoaded(country, emptySet(), host, cap))
            if (standby == null || standby!!.endpoint.key !in healthy || standby == primary) {
                standby = ProxyPool.leastLoaded(country, setOfNotNull(primary?.endpoint?.key), host, cap)
            }
            return primary
        }

        private fun setPrimary(p: PooledProxy?) {
            if (p === primary) return
            primary?.activeStreams?.decrementAndGet()
            p?.activeStreams?.incrementAndGet()
            primary = p
        }

        @Synchronized
        fun failover(failed: PooledProxy, reason: String, penalize: Boolean = true, banForHost: Boolean = false) {
            // A TLS handshake failing through a built-in tunnel is that network filtering this one
            // hostname (BSNL blocks jiotvmblive.cdn.jio.com by SNI; jiotvpllive works on the same
            // IPs): ban it for this host only and keep the tunnel healthy for every other host.
            val hostFiltered = failed.builtin && reason == "SSLHandshakeException"
            // A site-level refusal (expired token, 403) is not the proxy's fault — switch without penalty
            if (penalize && !hostFiltered) ProxyPool.reportFailure(failed, reason)
            // 450/451 = this site refuses that IP outright — don't hand it to this host again for a while
            if (banForHost || hostFiltered) ProxyPool.banForHost(host, failed)
            if (primary === failed) {
                val next = standby?.takeIf { it !== failed }
                standby = null
                setPrimary(next)
                ServerState.info("[GeoProxy] stream $key: ${failed.endpoint.key} → ${next?.endpoint?.key ?: "none"} ($reason)")
            }
        }

        @Synchronized
        fun release() {
            setPrimary(null)
            standby = null
        }
    }

    private val leases = ConcurrentHashMap<String, StreamLease>()
    private const val LEASE_IDLE_MS = 2 * 60_000L

    /** Live channel key: CDN host + the channel's directory (segments/renditions share it). */
    fun channelKey(url: okhttp3.HttpUrl): String {
        val segs = url.pathSegments
        val dir = if (segs.size > 1) segs.dropLast(1).take(4).joinToString("/") else ""
        return url.host + "/" + dir
    }

    /**
     * Lease for a relayed stream, or null when it should go direct.
     * [plugin] may be null for streams on domain-rule hosts.
     */
    fun streamLease(plugin: String?, url: okhttp3.HttpUrl): StreamLease? {
        val (decision, country) = decide(plugin, RouteMode.AUTO, url.host)
        if (decision != Decision.PROXY_FIRST || country == null) return null
        val key = channelKey(url)
        leases[key]?.let { return it }
        val lease = leases.computeIfAbsent(key) { StreamLease(key, country, url.host) }
        ProxyPool.ensureCapacity(country, leasesFor(country), settings.maxStreamsPerProxy)
        return lease
    }

    private fun leasesFor(country: String) = leases.values.count { it.country == country }

    private fun expireLeases() {
        val now = System.currentTimeMillis()
        leases.entries.removeIf { (_, l) ->
            (now - l.lastUsed > LEASE_IDLE_MS).also { if (it) l.release() }
        }
    }

    /** True when some of [plugin]'s traffic already goes through a proxy. */
    private fun usesProxy(plugin: String): Boolean {
        val now = System.currentTimeMillis()
        return (pluginProxy[plugin] ?: 0L) > now || overrides[plugin]?.mode == "always" ||
            hostBlocks.values.any { plugin in it.plugins && it.until > now }
    }

    /**
     * The direct relay of a stream from [url] was refused with [code] (null =
     * connection failure). A hard geo block (450/451), a domain-rule host, or
     * a plugin that already needs a proxy elsewhere marks the host blocked and
     * returns a lease; otherwise null (keep the direct result).
     */
    fun learnStreamBlocked(plugin: String?, url: okhttp3.HttpUrl, code: Int?): StreamLease? {
        if (!settings.enabled || (plugin != null && overrides[plugin]?.mode == "off")) return null
        val host = url.host
        val convincing = code in HARD_BLOCK_CODES || ruleCountry(host) != null || (plugin != null && usesProxy(plugin))
        if (!convincing) return null
        val country = ruleCountry(host) ?: plugin?.let { countryFor(it) } ?: return null
        if (ProxyPool.healthy(country).isEmpty()) { ProxyPool.demand(country); return null }
        markBlocked(plugin, host, country)
        return streamLease(plugin, url)
    }

    fun activeLeases(): List<StreamLeaseInfo> {
        val now = System.currentTimeMillis()
        return leases.values.map {
            StreamLeaseInfo(it.key, it.country, it.primary?.endpoint?.key, it.standby?.endpoint?.key, (now - it.lastUsed) / 1000)
        }.sortedBy { it.key }
    }

    // ── Admin view ───────────────────────────────────────────────────────────

    fun pluginStatuses(): List<PluginGeoStatus> {
        val now = System.currentTimeMillis()
        val names = plugins.keys + overrides.keys
        val shared = PluginGeoStatus(
            plugin = ANY,
            displayName = "Learned blocked hosts (all extensions)",
            country = null,
            countrySource = "rules",
            blockedHosts = hostBlocks.filter { it.value.until > now }.map { it.key + " (" + it.value.country + ")" }.sorted(),
            proxiedOk = counters[ANY]?.ok?.get() ?: 0,
            proxiedFail = counters[ANY]?.fail?.get() ?: 0,
            directBlocked = counters[ANY]?.blocked?.get() ?: 0,
        )
        return listOf(shared) + names.map { p ->
            val c = counters[p]
            PluginGeoStatus(
                plugin = p,
                displayName = plugins[p]?.displayName ?: p,
                country = countryFor(p),
                countrySource = countrySource(p),
                detectedCountry = plugins[p]?.country,
                mode = overrides[p]?.mode ?: "auto",
                overrideCountry = overrides[p]?.country,
                proxiedAll = (pluginProxy[p] ?: 0L) > now,
                blockedHosts = hostBlocks.filter { p in it.value.plugins && it.value.until > now }.keys.sorted(),
                proxiedOk = c?.ok?.get() ?: 0,
                proxiedFail = c?.fail?.get() ?: 0,
                directBlocked = c?.blocked?.get() ?: 0,
            )
        }.sortedWith(compareByDescending<PluginGeoStatus> { it.proxiedAll || it.blockedHosts.isNotEmpty() || it.mode != "auto" }.thenBy { it.displayName.lowercase() })
    }

    fun recentEvents(): List<GeoEvent> = synchronized(events) { events.toList() }
}
