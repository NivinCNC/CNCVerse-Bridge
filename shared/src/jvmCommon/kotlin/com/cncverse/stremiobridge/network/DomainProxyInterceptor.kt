package com.cncverse.stremiobridge.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * OkHttp interceptor that transparently routes requests whose host matches
 * any entry in [proxyDomains] through a dedicated [proxy], leaving all other
 * requests on the default network path.
 *
 * Implementation notes:
 * - OkHttp doesn't allow changing the proxy mid-chain, so we re-issue the
 *   request on a separate proxy-enabled client, bypassing chain.proceed().
 * - Matching is host-suffix based: "tv.imgcdn.kim" also matches any subdomain.
 */
class DomainProxyInterceptor(
    /** Hostnames (or host suffixes) that should be routed through [proxy]. */
    val proxyDomains: List<String>,
    /** The proxy to use for matched requests. */
    val proxy: Proxy,
) : Interceptor {

    /** Convenience constructor for SOCKS5 proxies. */
    constructor(proxyDomains: List<String>, socks5Host: String, socks5Port: Int) : this(
        proxyDomains = proxyDomains,
        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socks5Host, socks5Port)),
    )

    // Proxy-enabled client built lazily on first match.
    // chain.call().client() is not available in all OkHttp versions, so we
    // build a standalone client with sensible defaults.
    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // Host-suffix match: "tv.imgcdn.kim" also catches "sub.tv.imgcdn.kim"
        val matched = proxyDomains.any { domain ->
            host.equals(domain, ignoreCase = true) ||
                host.endsWith(".$domain", ignoreCase = true)
        }

        if (!matched) {
            // Not a proxied domain — pass through the normal chain
            return chain.proceed(request)
        }

        // Re-issue through the dedicated proxy client, bypassing the chain.
        // (Calling chain.proceed() here would ignore our per-call proxy override.)
        return proxyClient.newCall(request).execute()
    }

    /**
     * Returns the SOCKS5 proxy URL string (e.g. "socks5://127.0.0.1:9667") if
     * [url]'s host matches any of the configured [proxyDomains], or null otherwise.
     * Used by [com.cncverse.stremiobridge.server.hls.HttpClientManager] to
     * auto-detect the proxy without requiring call-site changes.
     */
    fun proxyUrlFor(url: String): String? {
        val host = try {
            java.net.URI(url).host ?: return null
        } catch (_: Exception) {
            return null
        }
        val matched = proxyDomains.any { domain ->
            host.equals(domain, ignoreCase = true) ||
                host.endsWith(".$domain", ignoreCase = true)
        }
        if (!matched) return null
        val addr = proxy.address() as? java.net.InetSocketAddress ?: return null
        return "socks5://${addr.hostString}:${addr.port}"
    }

    companion object {
        val ULTRASURF_IN = DomainProxyInterceptor(
            proxyDomains = listOf("tv.imgcdn.kim","jio.com","workers.dev"),
            socks5Host   = "127.0.0.1",
            socks5Port   = 9667,
        )
    }
}

object UltrasurfProxySelector : java.net.ProxySelector() {

    private val ultrasurfProxy = java.net.Proxy(
        java.net.Proxy.Type.SOCKS,
        java.net.InetSocketAddress("127.0.0.1", 9667)
    )

    override fun select(uri: java.net.URI?): List<java.net.Proxy> {
        val host = uri?.host ?: return emptyList()
        val domains = DomainProxyInterceptor.ULTRASURF_IN.proxyDomains
        val matched = domains.any { domain ->
            host.equals(domain, ignoreCase = true) ||
                host.endsWith(".$domain", ignoreCase = true)
        }
        return if (matched) listOf(ultrasurfProxy) else emptyList()
    }

    override fun connectFailed(
        uri: java.net.URI?,
        sa: java.net.SocketAddress?,
        ioe: java.io.IOException?
    ) { /* no-op: let OkHttp retry/fallback handle it */ }
}