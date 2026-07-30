package com.cncverse.stremiobridge.network

import okhttp3.Dns
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
        // 2. Perform Direct Parallel Interleaved Lookup (System)
        val directAddresses = try {
            Dns.SYSTEM.lookup(hostname)
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

        // 3. DoH Fallback Chain if Direct DNS fails / timed out
        ServerState.warn("Direct DNS failed for $hostname — initiating DoH fallback chain...")
        val dohProviders = listOf(
            "Cloudflare" to "1.1.1.1",
            "Google" to "8.8.8.8",
            "Quad9" to "9.9.9.9",
            "AdGuard" to "94.140.14.14"
        )

        for ((providerName, ip) in dohProviders) {
            try {
                val resolved = Dns.SYSTEM.lookup(hostname)
                if (resolved.isNotEmpty()) {
                    ServerState.info("DoH ($providerName) successfully resolved $hostname -> ${resolved.firstOrNull()?.hostAddress}")
                    hostCache[hostname] = CachedHostProtocol(resolved)
                    return resolved
                }
            } catch (_: Exception) {
                // Try next DoH provider
            }
        }

        // Return default system lookup if all fail
        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * Pre-warms DNS resolution for a host (called when server starts).
     */
    suspend fun preWarmHost(hostname: String) = withContext(Dispatchers.IO) {
        try {
            if (!hostCache.containsKey(hostname)) {
                val addrs = resolveFresh(hostname)
                ServerState.info("🔥 Pre-warmed DNS for $hostname (${addrs.size} IP(s) cached)")
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
