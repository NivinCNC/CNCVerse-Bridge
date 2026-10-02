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
    val poolSize: Int = 6,
    /** How long a learned "this host blocks the server IP" stays before direct is retried. */
    val blockTtlHours: Int = 12,
    /** Country used for extensions whose country can't be detected ("" = none → no proxy). */
    val defaultCountry: String = "",
)

/** Admin override for one extension. mode: auto | off | always. */
@Serializable
data class PluginGeoOverride(val mode: String = "auto", val country: String? = null)

@Serializable
private data class GeoStateFile(
    val settings: GeoProxySettings = GeoProxySettings(),
    val overrides: Map<String, PluginGeoOverride> = emptyMap(),
    /** "plugin|host" → expiry. */
    val hostBlocks: Map<String, Long> = emptyMap(),
    /** plugin → expiry: the health probe found it only works through a proxy. */
    val pluginProxy: Map<String, Long> = emptyMap(),
    val pools: Map<String, List<PooledProxySnapshot>> = emptyMap(),
)

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

/**
 * Decides, per plugin and host, whether a request goes out directly or
 * through a residential proxy of the plugin's country.
 *
 *  - unknown host: try direct; if it is refused (403/451) or the connection
 *    is cut/times out, retry through the country pool. When the proxy works
 *    the (plugin, host) pair is remembered for [GeoProxySettings.blockTtlHours]
 *    and goes proxy-first from then on.
 *  - remembered host / plugin marked by the health probe / admin "always":
 *    proxy first, direct as the last resort.
 *  - admin "off", no detectable country, or the proxy did not help either:
 *    direct only.
 */
object GeoRouter {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var settings = GeoProxySettings()
        private set
    private val overrides = ConcurrentHashMap<String, PluginGeoOverride>()
    private val hostBlocks = ConcurrentHashMap<String, Long>()
    private val pluginProxy = ConcurrentHashMap<String, Long>()
    /** "plugin|host" → until: the proxy did not help, stop retrying for a while. */
    private val noHelp = ConcurrentHashMap<String, Long>()

    private class PluginInfo(val displayName: String, val country: String?, val source: String)
    private val plugins = ConcurrentHashMap<String, PluginInfo>()

    private class Counters { val ok = AtomicLong(); val fail = AtomicLong(); val blocked = AtomicLong() }
    private val counters = ConcurrentHashMap<String, Counters>()

    private val events = java.util.ArrayDeque<GeoEvent>()

    private var stateFile: File? = null
    @Volatile private var dirty = false

    private const val NO_HELP_MS = 30 * 60_000L
    private val BLOCK_CODES = setOf(403, 451)

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
            s.hostBlocks.filterValues { it > now }.let { hostBlocks.putAll(it) }
            s.pluginProxy.filterValues { it > now }.let { pluginProxy.putAll(it) }
            ProxyPool.restore(s.pools)
        }.onFailure { ServerState.warn("[GeoProxy] could not read geo_proxy.json: ${it.message}") }
        com.cncverse.stremiobridge.state.StreamTracker.proxiedNow = { routesViaProxy(it) }
        applySettings()
        ProxyPool.start()
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (dirty) save()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { save() } })
    }

    private fun applySettings() {
        ProxyPool.targetSize = settings.poolSize.coerceIn(2, 20)
        ProxyPool.allowDatacenter = settings.allowDatacenter
    }

    @Synchronized
    fun save() {
        val f = stateFile ?: return
        dirty = false
        val now = System.currentTimeMillis()
        val state = GeoStateFile(
            settings = settings,
            overrides = HashMap(overrides),
            hostBlocks = hostBlocks.filterValues { it > now },
            pluginProxy = pluginProxy.filterValues { it > now },
            pools = ProxyPool.snapshot().mapValues { (_, l) -> l.filter { it.consecutiveFailures == 0 } },
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
        settings = s.copy(
            poolSize = s.poolSize.coerceIn(2, 20),
            blockTtlHours = s.blockTtlHours.coerceIn(1, 24 * 14),
            defaultCountry = s.defaultCountry.trim().uppercase().take(2),
        )
        applySettings()
        save()
    }

    fun setOverride(plugin: String, o: PluginGeoOverride?) {
        if (o == null || (o.mode == "auto" && o.country.isNullOrBlank())) overrides.remove(plugin)
        else overrides[plugin] = o.copy(country = o.country?.trim()?.uppercase()?.takeIf { it.length == 2 })
        if (o?.mode == "off") clearLearned(plugin)
        countryFor(plugin)?.let { if (o?.mode == "always") ProxyPool.demand(it) }
        save()
    }

    /** Forgets everything learned about [plugin] (blocked hosts, probe result). */
    fun clearLearned(plugin: String) {
        hostBlocks.keys.removeIf { it.startsWith("$plugin|") }
        noHelp.keys.removeIf { it.startsWith("$plugin|") }
        pluginProxy.remove(plugin)
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

    fun countryFor(plugin: String): String? {
        overrides[plugin]?.country?.let { return it }
        plugins[plugin]?.country?.let { return it }
        return settings.defaultCountry.takeIf { it.length == 2 }
    }

    private fun countrySource(plugin: String): String = when {
        overrides[plugin]?.country != null -> "admin"
        plugins[plugin]?.country != null -> plugins[plugin]!!.source
        settings.defaultCountry.length == 2 -> "default"
        else -> "none"
    }

    private fun activeCountries(): Set<String> {
        val now = System.currentTimeMillis()
        val ps = hostBlocks.filterValues { it > now }.keys.map { it.substringBefore('|') } +
            pluginProxy.filterValues { it > now }.keys +
            overrides.filterValues { it.mode == "always" }.keys
        return ps.mapNotNull { countryFor(it) }.toSet()
    }

    // ── Decisions ────────────────────────────────────────────────────────────

    enum class Decision { DIRECT, DIRECT_THEN_PROXY, PROXY_FIRST }

    fun decide(plugin: String?, mode: RouteMode, host: String): Pair<Decision, String?> {
        if (!settings.enabled || plugin == null) {
            return if (mode == RouteMode.FORCE_PROXY && plugin != null && countryFor(plugin) != null)
                Decision.PROXY_FIRST to countryFor(plugin) else Decision.DIRECT to null
        }
        if (mode == RouteMode.FORCE_DIRECT) return Decision.DIRECT to null
        val o = overrides[plugin]
        if (o?.mode == "off") return Decision.DIRECT to null
        val country = countryFor(plugin) ?: return Decision.DIRECT to null
        val now = System.currentTimeMillis()
        if (mode == RouteMode.FORCE_PROXY || o?.mode == "always" ||
            (pluginProxy[plugin] ?: 0L) > now ||
            (hostBlocks["$plugin|$host"] ?: 0L) > now
        ) return Decision.PROXY_FIRST to country
        if ((noHelp["$plugin|$host"] ?: 0L) > now) return Decision.DIRECT to null
        return Decision.DIRECT_THEN_PROXY to country
    }

    /** True when any plugin is currently routed through a proxy (cheap gate for the ProxySelector). */
    fun hasProxyRoutes(): Boolean =
        hostBlocks.isNotEmpty() || pluginProxy.isNotEmpty() || overrides.values.any { it.mode == "always" }

    private fun markBlocked(plugin: String, host: String) {
        val until = System.currentTimeMillis() + settings.blockTtlHours * 3_600_000L
        if (hostBlocks.put("$plugin|$host", until) == null) {
            event(plugin, "$host blocks the server IP — routing via ${countryFor(plugin)} proxy")
        }
        counters.getOrPut(plugin) { Counters() }.blocked.incrementAndGet()
        markDirty()
    }

    private fun markNoHelp(plugin: String, host: String) {
        noHelp["$plugin|$host"] = System.currentTimeMillis() + NO_HELP_MS
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
    internal fun routesViaProxy(plugin: String): Boolean = usesProxy(plugin)

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
     * Sends [req] through up to two pooled proxies of [country]. Returns the
     * first response that isn't a proxy-level failure, or null.
     */
    fun viaPool(plugin: String?, country: String, req: Request): Response? {
        val tried = HashSet<String>()
        repeat(2) {
            val p = ProxyPool.pick(country, 1, tried).firstOrNull() ?: return null
            tried += p.endpoint.key
            val started = System.currentTimeMillis()
            try {
                val resp = ProxyPool.clientFor(p.endpoint).newCall(req).execute()
                // 407 / gateway errors come from the proxy itself, not the site
                if (resp.code == 407 || resp.code == 502 || resp.code == 504) {
                    resp.close()
                    ProxyPool.reportFailure(p, "HTTP ${resp.code}")
                } else {
                    ProxyPool.reportSuccess(p, System.currentTimeMillis() - started)
                    plugin?.let { counters.getOrPut(it) { Counters() }.ok.incrementAndGet() }
                    return resp
                }
            } catch (e: IOException) {
                ProxyPool.reportFailure(p, e.javaClass.simpleName)
            }
            plugin?.let { counters.getOrPut(it) { Counters() }.fail.incrementAndGet() }
        }
        return null
    }

    /**
     * OkHttp application interceptor for the plugin clients. Must sit after
     * CloudflareKiller and the fixed-domain Ultrasurf interceptor.
     */
    object ProxyInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val req = chain.request()
            val tag = NetContext.tagFor(chain.call())
            val plugin = tag?.plugin ?: NetContext.currentPlugin()
            val mode = tag?.mode ?: NetContext.currentMode()
            val host = req.url.host
            val (decision, country) = decide(plugin, mode, host)
            when (decision) {
                Decision.DIRECT -> return chain.proceed(req)

                Decision.PROXY_FIRST -> {
                    if (retryable(req)) viaPool(plugin, country!!, req)?.let { return it }
                    return chain.proceed(req)
                }

                Decision.DIRECT_THEN_PROXY -> {
                    if (!retryable(req)) return chain.proceed(req)
                    val c = country!!
                    val p = plugin!!
                    val direct = try {
                        chain.proceed(req)
                    } catch (e: IOException) {
                        if (!blockish(e)) throw e
                        when (val r = retryViaProxy(p, c, host, req)) {
                            null -> throw e
                            else -> return r
                        }
                    }
                    if (direct.code !in BLOCK_CODES || direct.header("cf-mitigated") != null) return direct
                    val viaProxy = retryViaProxy(p, c, host, req) ?: return direct
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
        private fun retryViaProxy(plugin: String, country: String, host: String, req: Request): Response? {
            if (ProxyPool.healthy(country).isEmpty()) {
                ProxyPool.demand(country)
                return null
            }
            val viaProxy = viaPool(plugin, country, req)
            if (viaProxy != null && viaProxy.code < 400) {
                markBlocked(plugin, host)
                return viaProxy
            }
            viaProxy?.close()
            markNoHelp(plugin, host)
            return null
        }
    }

    /**
     * JVM-wide ProxySelector: covers OkHttpClients that plugins build
     * themselves (no interceptor of ours). Only proxy-first routes apply here —
     * there is no response to inspect, so nothing is learned at this level.
     */
    class Selector(private val delegate: java.net.ProxySelector) : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): List<java.net.Proxy> {
            val fixed = delegate.select(uri)
            if (!fixed.isNullOrEmpty() && fixed.any { it.type() != java.net.Proxy.Type.DIRECT }) return fixed
            val host = uri?.host ?: return listOf(java.net.Proxy.NO_PROXY)
            val mode = NetContext.currentMode()
            if (!hasProxyRoutes() && mode != RouteMode.FORCE_PROXY) return listOf(java.net.Proxy.NO_PROXY)
            val plugin = NetContext.resolvePlugin() ?: return listOf(java.net.Proxy.NO_PROXY)
            val (decision, country) = decide(plugin, mode, host)
            if (decision != Decision.PROXY_FIRST || country == null) return listOf(java.net.Proxy.NO_PROXY)
            val picked = ProxyPool.pick(country, 2).map { it.endpoint.toJavaProxy() }
            return picked + java.net.Proxy.NO_PROXY
        }

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: IOException?) {
            val addr = sa as? java.net.InetSocketAddress ?: return
            ProxyPool.reportConnectFailed(addr.hostString, addr.port)
            delegate.connectFailed(uri, sa, ioe)
        }
    }

    // ── Live streams: sticky proxy with a hot standby ────────────────────────

    /**
     * A relayed live stream keeps using one proxy (CDNs often pin tokens to
     * the IP) with a second, already-validated proxy on standby. When the
     * primary fails the standby takes over immediately and a new standby is
     * picked.
     */
    class StickySession(val country: String) {
        @Volatile var primary: PooledProxy? = null
        @Volatile var standby: PooledProxy? = null
        @Volatile var lastUsed = System.currentTimeMillis()

        @Synchronized
        fun current(): PooledProxy? {
            lastUsed = System.currentTimeMillis()
            val healthy = ProxyPool.healthy(country).map { it.endpoint.key }.toSet()
            if (primary?.endpoint?.key !in healthy) { primary = standby?.takeIf { it.endpoint.key in healthy }; standby = null }
            if (primary == null) primary = ProxyPool.pick(country, 1).firstOrNull()
            if (standby == null || standby?.endpoint?.key !in healthy || standby == primary) {
                standby = ProxyPool.pick(country, 1, setOfNotNull(primary?.endpoint?.key)).firstOrNull()
            }
            return primary
        }

        @Synchronized
        fun failover(failed: PooledProxy, reason: String) {
            ProxyPool.reportFailure(failed, reason)
            if (primary == failed) {
                primary = standby
                standby = null
                ServerState.info("[GeoProxy] stream failover ${failed.endpoint.key} → ${primary?.endpoint?.key ?: "none"} ($reason)")
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, StickySession>()

    /** Session for a relayed stream of [plugin] from [host], or null when it should go direct. */
    fun streamSession(plugin: String?, host: String): StickySession? {
        if (plugin.isNullOrBlank()) return null
        val (decision, country) = decide(plugin, RouteMode.AUTO, host)
        if (decision != Decision.PROXY_FIRST || country == null) return null
        val now = System.currentTimeMillis()
        if (sessions.size > 200) sessions.entries.removeIf { now - it.value.lastUsed > 15 * 60_000L }
        return sessions.getOrPut("$plugin|$host") { StickySession(country) }
    }

    /** True when some of [plugin]'s traffic already goes through a proxy. */
    private fun usesProxy(plugin: String): Boolean {
        val now = System.currentTimeMillis()
        return (pluginProxy[plugin] ?: 0L) > now || overrides[plugin]?.mode == "always" ||
            hostBlocks.any { it.key.startsWith("$plugin|") && it.value > now }
    }

    /**
     * The direct relay of [plugin]'s stream from [host] was refused. When the
     * plugin already needs a proxy elsewhere, its CDN is treated as blocked
     * too and a session is returned; otherwise null (keep the direct result).
     */
    fun learnStreamHostBlocked(plugin: String, host: String): StickySession? {
        if (!settings.enabled || overrides[plugin]?.mode == "off" || !usesProxy(plugin)) return null
        val country = countryFor(plugin) ?: return null
        if (ProxyPool.healthy(country).isEmpty()) { ProxyPool.demand(country); return null }
        markBlocked(plugin, host)
        return streamSession(plugin, host)
    }

    fun activeSessions(): Map<String, Pair<String?, String?>> =
        sessions.filterValues { System.currentTimeMillis() - it.lastUsed < 15 * 60_000L }
            .mapValues { (_, s) -> s.primary?.endpoint?.key to s.standby?.endpoint?.key }

    // ── Admin view ───────────────────────────────────────────────────────────

    fun pluginStatuses(): List<PluginGeoStatus> {
        val now = System.currentTimeMillis()
        val names = plugins.keys + overrides.keys
        return names.map { p ->
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
                blockedHosts = hostBlocks.filter { it.key.startsWith("$p|") && it.value > now }.keys.map { it.substringAfter('|') },
                proxiedOk = c?.ok?.get() ?: 0,
                proxiedFail = c?.fail?.get() ?: 0,
                directBlocked = c?.blocked?.get() ?: 0,
            )
        }.sortedWith(compareByDescending<PluginGeoStatus> { it.proxiedAll || it.blockedHosts.isNotEmpty() || it.mode != "auto" }.thenBy { it.displayName.lowercase() })
    }

    fun recentEvents(): List<GeoEvent> = synchronized(events) { events.toList() }
}
