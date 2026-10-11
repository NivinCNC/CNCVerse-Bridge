package com.cncverse.stremiobridge.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.cncverse.stremiobridge.state.ServerState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptive Dual-Stack DNS Engine with Host Protocol Caching, Server Pre-Warming & DoH Fallback Chain.
 * Follows the 5-Stage Architecture:
 * 1. Cache Hit: Instantly returns the winning protocol IP list for the host.
 * 2. Parallel Interleaved Race: Races IPv4 & IPv6 together on initial request.
 * 3. Pre-Warming on Server Start: Pre-resolves DNS for installed extensions before any stream arrives.
 * 4. Mid-Request Cache Invalidation & Retry: If a cached endpoint fails mid-request, invalidates cache and retries fresh race.
 * 5. DoH Fallback Chain: Cloudflare (1.1.1.1) -> Google (8.8.8.8) -> Quad9 (9.9.9.9) -> AdGuard (94.140.14.14).
 */
object AdaptiveHostDns : Dns {

    private data class CachedHostProtocol(
        val addresses: List<InetAddress>,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMillis: Long = 15 * 60 * 1000L): Boolean =
            System.currentTimeMillis() - timestamp > ttlMillis
    }

    private val hostCache = ConcurrentHashMap<String, CachedHostProtocol>()

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Check Host Protocol Cache first
        val cached = hostCache[hostname]
        if (cached != null && !cached.isExpired()) {
            return cached.addresses
        }

        return resolveFresh(hostname)
    }

    /**
     * Resolves host via parallel interleaved race, falling back to DoH chain if direct DNS fails.
     */
    private fun resolveFresh(hostname: String): List<InetAddress> {
        if (hostname.equals("localhost", true)) return Dns.SYSTEM.lookup(hostname)
        // 2. Perform Direct Parallel Interleaved Lookup (System)
        val directAddresses = try {
            // A blocking DNS often answers 0.0.0.0 / 127.0.0.1 instead of failing: that counts as failed too
            Dns.SYSTEM.lookup(hostname).filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
        } catch (e: Exception) {
            emptyList()
        }

        if (directAddresses.isNotEmpty()) {
            val v4 = directAddresses.filterIsInstance<Inet4Address>()
            val v6 = directAddresses.filterIsInstance<Inet6Address>()

            val result = if (v4.isNotEmpty() && v6.isNotEmpty()) {
                // Interleave IPv4 and IPv6 for parallel racing [v4[0], v6[0], v4[1], v6[1]...]
                val interleaved = mutableListOf<InetAddress>()
                val maxSize = maxOf(v4.size, v6.size)
                for (i in 0 until maxSize) {
                    if (i < v4.size) interleaved.add(v4[i])
                    if (i < v6.size) interleaved.add(v6[i])
                }
                interleaved
            } else {
                directAddresses
            }

            // Cache result for fast future lookups
            hostCache[hostname] = CachedHostProtocol(result)
            return result
        }

        // 3. Real DNS-over-HTTPS (the phone's DNS is blocked or broken): ask public resolvers over HTTPS by IP
        ServerState.warn("Direct DNS failed for $hostname - asking DNS-over-HTTPS resolvers...")
        for ((providerName, endpoint) in DOH_ENDPOINTS) {
            val resolved = dohLookup(endpoint, hostname)
            if (resolved.isNotEmpty()) {
                ServerState.info("DoH ($providerName) resolved $hostname -> ${resolved.firstOrNull()?.hostAddress}")
                hostCache[hostname] = CachedHostProtocol(resolved)
                return resolved
            }
        }

        // Nothing resolved: let the caller see the system error
        return Dns.SYSTEM.lookup(hostname)
    }

    private val DOH_ENDPOINTS = listOf(
        "Cloudflare" to "https://1.1.1.1/dns-query",
        "Google" to "https://8.8.8.8/resolve",
        "Cloudflare 2" to "https://1.0.0.1/dns-query",
        "Google 2" to "https://8.8.4.4/resolve",
        "Quad9" to "https://9.9.9.9:5053/dns-query",
    )

    /** Bootstrap client: the resolvers are reached by IP, so it needs no DNS of its own. */
    private val dohClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val dohJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    private fun dohLookup(endpoint: String, hostname: String): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        for (type in listOf("A", "AAAA")) {
            try {
                val url = okhttp3.HttpUrl.Builder().scheme("https").also { b ->
                    val u = endpoint.toHttpUrl()
                    b.host(u.host).port(u.port).encodedPath(u.encodedPath)
                }.addQueryParameter("name", hostname).addQueryParameter("type", type).build()
                val req = okhttp3.Request.Builder().url(url).header("Accept", "application/dns-json").build()
                dohClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val answers = dohJson.parseToJsonElement(r.body?.string().orEmpty())
                        .let { it as? kotlinx.serialization.json.JsonObject }?.get("Answer") as? kotlinx.serialization.json.JsonArray
                        ?: return@use
                    for (a in answers) {
                        val o = a as? kotlinx.serialization.json.JsonObject ?: continue
                        val t = (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                        if (t != 1 && t != 28) continue // skip CNAME records, the address records follow
                        val data = (o["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                        // The IP literal is parsed directly, no lookup happens
                        runCatching { InetAddress.getByName(data) }.getOrNull()
                            ?.takeIf { !it.isAnyLocalAddress && !it.isLoopbackAddress }?.let { out.add(it) }
                    }
                }
            } catch (_: Exception) { /* next record type / resolver */ }
        }
        // IPv4 first: most phones' IPv6 routes to these sites are poor
        return out.sortedByDescending { it is Inet4Address }
    }

    /**
     * Pre-warms DNS resolution for a host (called when server starts).
     */
    suspend fun preWarmHost(hostname: String) = withContext(Dispatchers.IO) {
        try {
            if (!hostCache.containsKey(hostname)) {
                val addrs = resolveFresh(hostname)
                ServerState.debug("🔥 Pre-warmed DNS for $hostname (${addrs.size} IP(s) cached)")
            }
        } catch (e: Exception) {
            ServerState.warn("Pre-warm failed for $hostname: ${e.message}")
        }
    }

    /**
     * Mid-Request Fallback: Call this when a request fails mid-stream to invalidate stale cache & force fresh re-resolution.
     */
    fun invalidateAndRetry(hostname: String): List<InetAddress> {
        ServerState.warn("Invalidating stale DNS cache for $hostname and performing fresh retry...")
        hostCache.remove(hostname)
        return resolveFresh(hostname)
    }

    /**
     * Record winning protocol address when connection succeeds.
     */
    fun recordWinner(hostname: String, winnerAddress: InetAddress) {
        val current = hostCache[hostname]?.addresses ?: return
        val isV4 = winnerAddress is Inet4Address
        val reordered = current.sortedByDescending { (it is Inet4Address) == isV4 }
        hostCache[hostname] = CachedHostProtocol(reordered)
    }
}
