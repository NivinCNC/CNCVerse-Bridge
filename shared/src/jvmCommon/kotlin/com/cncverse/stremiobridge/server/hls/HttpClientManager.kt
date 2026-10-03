package com.cncverse.stremiobridge.server.hls

import okhttp3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.server.response.respondText

/**
 * Manager singleton per OkHttp client
 * Ottimizzato per streaming video ad alta velocità
 */
object HttpClientManager {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Connection pool ottimizzato per streaming video - come EasyProxy
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 128, // Più connessioni per parallelismo alto
        keepAliveDuration = 300,  // Keep-alive molto lungo (5 min) per riuso connessioni
        timeUnit = TimeUnit.SECONDS
    )

    // Dispatcher per massimo parallelismo - come EasyProxy
    private val dispatcher = Dispatcher().apply {
        maxRequests = 256         // Molte richieste parallele
        maxRequestsPerHost = 64   // CDN usa stesso host - serve alto parallelismo
    }

    // Client di base ottimizzato per streaming veloce con AdaptiveHostDns e FastFallback
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dns(com.cncverse.stremiobridge.network.AdaptiveHostDns)
        .fastFallback(true)
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .connectTimeout(20, TimeUnit.SECONDS)  // Connect timeout generoso per stabilità di handshake
        .readTimeout(45, TimeUnit.SECONDS)     // Read timeout generoso per segmenti video ad alto bitrate
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)     // Call timeout globale
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Pseudo-header (h_x_cnc_plugin) naming the plugin a relayed stream came from. */
    const val PLUGIN_HEADER = "X-Cnc-Plugin"

    private fun pluginOf(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals(PLUGIN_HEADER, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    /** Codes after which a stream proxy is swapped for its standby. */
    private val PROXY_RETRY_CODES = setOf(403, 407, 429, 450, 451, 502, 503, 504)

    private val geo get() = com.cncverse.stremiobridge.network.geo.GeoRouter

    /**
     * Executes a relay request.
     *
     * Hosts the geo router sends through a proxy use a per-channel lease
     * ([com.cncverse.stremiobridge.network.geo.GeoRouter.StreamLease]): one
     * proxy per live channel, channels spread over the pool by load, and a hot
     * standby that takes over within the same request when the primary fails.
     * Other hosts go direct; a geo refusal (450/451, or 403 for a domain-rule
     * host / an extension already on a proxy) teaches the router and the
     * request is retried through the pool.
     */
    private fun routed(request: Request, proxyUrl: String?, url: String?, plugin: String?): Response {
        if (proxyUrl != null) return createClient(proxyUrl, url).newCall(request).execute()
        geo.streamLease(plugin, request.url)?.let { lease ->
            val viaProxy = viaLease(lease, request)
            if (viaProxy != null && !com.cncverse.stremiobridge.network.geo.GeoRouter.geoBlocked(request.url.host, viaProxy.code)) return viaProxy
            // Every proxy refused (or none answered): last resort is the default route (WARP).
            // Its 450/451 is the clearest answer for the player (-> 451), so prefer it.
            val direct = try { defaultRouteClient().newCall(request).execute() } catch (e: java.io.IOException) {
                return viaProxy ?: throw e
            }
            val hard = com.cncverse.stremiobridge.network.geo.GeoRouter.HARD_BLOCK_CODES
            return if (viaProxy == null || !com.cncverse.stremiobridge.network.geo.GeoRouter.geoBlocked(request.url.host, direct.code) || direct.code in hard) {
                viaProxy?.close(); direct
            } else {
                direct.close(); viaProxy
            }
        }
        val direct = try {
            baseClient.newCall(request).execute()
        } catch (e: java.io.IOException) {
            val lease = geo.learnStreamBlocked(plugin, request.url, null) ?: throw e
            return viaLease(lease, request) ?: throw e
        }
        if (!com.cncverse.stremiobridge.network.geo.GeoRouter.geoBlocked(request.url.host, direct.code)) return direct
        val lease = geo.learnStreamBlocked(plugin, request.url, direct.code) ?: return direct
        val viaProxy = viaLease(lease, request) ?: return direct
        if (com.cncverse.stremiobridge.network.geo.GeoRouter.geoBlocked(request.url.host, viaProxy.code)) {
            // Proxies refused too — keep the original refusal (450/451 → the player gets 451)
            viaProxy.close()
            return direct
        }
        direct.close()
        return viaProxy
    }

    @Volatile private var defaultRoute: Pair<java.net.Proxy?, OkHttpClient>? = null

    /**
     * Pinned to the default route (WARP, or direct) — bypasses the JVM selector,
     * which would send geo-ruled hosts (jio.com…) through the proxy pool again.
     */
    private fun defaultRouteClient(): OkHttpClient {
        val w = com.cncverse.stremiobridge.network.geo.GeoRouter.warp
        defaultRoute?.takeIf { it.first == w }?.let { return it.second }
        val c = baseClient.newBuilder().proxy(w ?: Proxy.NO_PROXY).build()
        defaultRoute = w to c
        return c
    }

    private fun viaLease(
        lease: com.cncverse.stremiobridge.network.geo.GeoRouter.StreamLease,
        request: Request,
    ): Response? {
        var lastBlocked: Response? = null
        val refused = ArrayList<com.cncverse.stremiobridge.network.geo.PooledProxy>(2)
        repeat(3) {
            val p = lease.current() ?: return lastBlocked
            val started = System.currentTimeMillis()
            try {
                val resp = com.cncverse.stremiobridge.network.geo.ProxyPool
                    .clientFor(p.endpoint, streaming = true).newCall(request).execute()
                if (resp.code in PROXY_RETRY_CODES && !(resp.code == 403 && com.cncverse.stremiobridge.network.geo.GeoRouter.isToken403Host(request.url.host))) {
                    lastBlocked?.close()
                    lastBlocked = resp
                    if (resp.code == 403) refused += p
                    lease.failover(
                        p, "HTTP ${resp.code}",
                        penalize = resp.code !in com.cncverse.stremiobridge.network.geo.GeoRouter.GEO_BLOCK_CODES && resp.code != 429,
                        banForHost = resp.code in com.cncverse.stremiobridge.network.geo.GeoRouter.HARD_BLOCK_CODES,
                    )
                } else {
                    lastBlocked?.close()
                    com.cncverse.stremiobridge.network.geo.ProxyPool.reportSuccess(p, System.currentTimeMillis() - started)
                    // Another proxy got through, so the earlier 403s were about those IPs (e.g. Ultrasurf)
                    refused.forEach { com.cncverse.stremiobridge.network.geo.ProxyPool.banForHost(request.url.host, it) }
                    return resp
                }
            } catch (e: java.io.IOException) {
                lease.failover(p, e.javaClass.simpleName)
            }
        }
        return lastBlocked
    }

    /** Thrown for 450/451 so the relay can answer the player with 451 instead of a 500. */
    class GeoBlockedException(val code: Int, url: String) :
        Exception("HTTP $code: blocked for this IP (${url.take(80)})")

    private fun httpError(response: Response): Exception =
        if (response.code == 450 || response.code == 451) GeoBlockedException(response.code, response.request.url.toString())
        else Exception("HTTP ${response.code}: ${response.message}")

    fun createClient(proxyUrl: String? = null, url: String? = null): OkHttpClient {
        if (proxyUrl.isNullOrBlank()) {
            return baseClient
        }
        return try {
            val proxy = parseProxy(proxyUrl)
            baseClient.newBuilder()
                .proxy(proxy)
                .build()
        } catch (e: Exception) {
            baseClient
        }
    }

    /**
     * GET request che ritorna stringa
     */
    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String? = null
    ): String {
        val request = buildRequest(url, headers)

        return routed(request, proxyUrl, url, pluginOf(headers)).use { response ->
            if (!response.isSuccessful) {
                throw httpError(response)
            }
            response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    /**
     * GET request che ritorna byte array
     */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String? = null
    ): ByteArray {
        val request = buildRequest(url, headers)

        return routed(request, proxyUrl, url, pluginOf(headers)).use { response ->
            if (!response.isSuccessful) {
                throw httpError(response)
            }
            response.body?.bytes() ?: throw Exception("Empty response body")
        }
    }

    /**
     * Media segment fetch with a hedge for slow tunnels. Ultrasurf (Jio) often answers
     * fast but then trickles one connection at ~2 Mbps, so a 2 s segment can take 3–6 s
     * and the player stalls. If the plain fetch isn't done after [hedgeAfterMs], the same
     * segment is also fetched as parallel byte ranges (separate connections), which
     * measured 0.45–0.8 s every time; whichever finishes first wins.
     */
    suspend fun getSegmentBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String? = null,
        hedgeAfterMs: Long = 1_200L,
    ): ByteArray {
        // Detached from the caller: a losing fetch is a blocking OkHttp read that can't be
        // interrupted, and the caller must not wait for it to trickle to the end
        val primary = hedgeScope.async { runCatching { getBytes(url, headers, proxyUrl) } }
        // Fast path — and errors that come back before the hedge point propagate as before
        kotlinx.coroutines.withTimeoutOrNull(hedgeAfterMs) { primary.await() }?.let { return it.getOrThrow() }
        val hedge = hedgeScope.async { runCatching { getBytesRanged(url, headers, proxyUrl) } }
        val (first, primaryWon) = kotlinx.coroutines.selects.select<Pair<Result<ByteArray>, Boolean>> {
            primary.onAwait { it to true }
            hedge.onAwait { it to false }
        }
        if (first.isSuccess) return first.getOrThrow()
        // One side failed: the other may still make it
        val second = (if (primaryWon) hedge else primary).await()
        return second.getOrElse { throw first.exceptionOrNull()!! }
    }

    private val hedgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Fetches [url] as [parts] parallel byte ranges; falls back to the whole body if ranges aren't supported. */
    private suspend fun getBytesRanged(
        url: String,
        headers: Map<String, String>,
        proxyUrl: String?,
        parts: Int = 4,
    ): ByteArray = kotlinx.coroutines.coroutineScope {
        val firstLen = 256L * 1024
        val (first, total) = fetchRange(url, headers, proxyUrl, 0, firstLen - 1)
        if (total == null || total <= first.size) return@coroutineScope first // no range support / small file
        val rest = total - first.size
        val chunk = (rest + parts - 1) / parts
        val ranges = (0 until parts).map { i ->
            val start = first.size + i * chunk
            start to minOf(total - 1, start + chunk - 1)
        }.filter { it.first <= it.second }
        val bodies = ranges.map { (s, e) ->
            async(kotlinx.coroutines.Dispatchers.IO) { fetchRange(url, headers, proxyUrl, s, e).first }
        }.awaitAll()
        val out = java.io.ByteArrayOutputStream(total.toInt())
        out.write(first)
        bodies.forEach { out.write(it) }
        out.toByteArray().also { if (it.size.toLong() != total) throw java.io.IOException("Ranged fetch size ${it.size} != $total") }
    }

    /** One byte range; returns the bytes and the full size (null when the server ignored Range). */
    private fun fetchRange(url: String, headers: Map<String, String>, proxyUrl: String?, start: Long, end: Long): Pair<ByteArray, Long?> {
        val request = buildRequest(url, headers + ("Range" to "bytes=$start-$end"))
        return routed(request, proxyUrl, url, pluginOf(headers)).use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val body = response.body?.bytes() ?: throw Exception("Empty response body")
            if (response.code != 206) return@use body to null
            val total = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
            body to total
        }
    }

    /**
     * GET request che ritorna InputStream (per streaming)
     */
    fun getStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String? = null
    ): Response {
        val request = buildRequest(url, headers)

        val response = routed(request, proxyUrl, url, pluginOf(headers))
        if (!response.isSuccessful) {
            response.close()
            throw httpError(response)
        }
        return response
    }

    /**
     * POST request
     */
    suspend fun post(
        url: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/x-www-form-urlencoded",
        proxyUrl: String? = null
    ): String {
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = buildRequest(url, headers, requestBody)

        return routed(request, proxyUrl, url, pluginOf(headers)).use { response ->
            if (!response.isSuccessful) {
                throw httpError(response)
            }
            response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    /**
     * HEAD request per ottenere headers
     */
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String? = null
    ): Headers {
        val request = buildRequest(url, headers, method = "HEAD")

        return routed(request, proxyUrl, url, pluginOf(headers)).use { response ->
            response.headers
        }
    }

    /**
     * Build request con headers standard
     */
    private fun buildRequest(
        url: String,
        customHeaders: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        method: String = if (body != null) "POST" else "GET"
    ): Request {
        val requestBuilder = Request.Builder().url(sanitizeUrl(url))

        // Headers standard
        val headers = mutableMapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
            // NON impostiamo Accept-Encoding manualmente per lasciare che OkHttp
            // gestisca automaticamente la decompressione gzip/deflate/br
        )

        // Aggiungi headers di default basati sul dominio (se non già presenti nei custom)
        val domainHeaders = getDomainSpecificHeaders(url)
        domainHeaders.forEach { (key, value) ->
            if (!customHeaders.containsKey(key)) {
                headers[key] = value
            }
        }

        // Merge custom headers (sovrascrivono gli standard)
        headers.putAll(customHeaders)

        // Applica headers (ma NON Accept-Encoding se presente nei custom)
        headers.forEach { (key, value) ->
            // Skip Accept-Encoding per permettere a OkHttp di gestire la decompressione
            if (key.equals("Accept-Encoding", ignoreCase = true)) {
                return@forEach
            }
            // Routing hint only — never sent upstream
            if (key.equals(PLUGIN_HEADER, ignoreCase = true)) return@forEach
            requestBuilder.addHeader(key, value)
        }

        // Imposta metodo e body
        requestBuilder.method(method, body)

        return requestBuilder.build()
    }

    /**
     * Parsa proxy URL (supporta http, https, socks5)
     * Formato: protocol://[user:pass@]host:port
     */
    private fun parseProxy(proxyUrl: String): Proxy {
        val uri = java.net.URI(proxyUrl)
        val proxyType = when (uri.scheme?.lowercase()) {
            "socks5", "socks" -> Proxy.Type.SOCKS
            "http", "https" -> Proxy.Type.HTTP
            else -> throw IllegalArgumentException("Unsupported proxy type: ${uri.scheme}")
        }

        val host = uri.host ?: throw IllegalArgumentException("Missing proxy host")
        val port = if (uri.port > 0) uri.port else 1080

        return Proxy(proxyType, InetSocketAddress(host, port))
    }

    /**
     * Sanitizes a URL string so that java.net.URI (used internally by OkHttp 3.x) can parse it.
     * Only the query string portion is modified — characters illegal in URI queries
     * (*  |  {  }  [  ]  ^  `  \  space) are percent-encoded.
     * Characters that are already percent-encoded are left untouched.
     */
    private fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url          // no query — nothing to sanitize
        val base  = url.substring(0, qIdx + 1) // everything up to and including '?'
        val query = url.substring(qIdx + 1)
        val sanitized = buildString(query.length + 16) {
            var i = 0
            while (i < query.length) {
                when (val c = query[i]) {
                    // '*' is valid in query strings (RFC 3986 §3.4) and must NOT be encoded:
                    // CDN HMAC tokens (e.g. Jio __hdnea__ acl=.../path/*) are signed over the
                    // literal '*'. Re-encoding it to %2A breaks the signature → HTTP 403.
                    '|'  -> append("%7C")
                    '{'  -> append("%7B")
                    '}'  -> append("%7D")
                    '['  -> append("%5B")
                    ']'  -> append("%5D")
                    '^'  -> append("%5E")
                    '`'  -> append("%60")
                    '\\' -> append("%5C")
                    ' '  -> append("%20")
                    else -> append(c)
                }
                i++
            }
        }
        return base + sanitized
    }

    /**
     * Estrae headers h_* da query parameters
     * Esempio: h_referer=xxx → Referer: xxx
     */
    fun extractHeadersFromParams(params: Map<String, String>): Map<String, String> {
        return params
            .filterKeys { it.startsWith("h_") }
            .mapKeys { (key, _) ->
                // h_referer → Referer
                // h_user_agent → User-Agent
                key.substring(2)
                    .split("_")
                    .joinToString("-") { word ->
                        word.replaceFirstChar { it.titlecase() }
                    }
            }
    }

    /**
     * Pulisce URL rimuovendo parametri proxy
     */
    fun cleanUrl(url: String): String {
        val uri = java.net.URI(url)
        return java.net.URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            uri.path,
            null, // Rimuove query
            uri.fragment
        ).toString()
    }

    /**
     * Ritorna headers specifici per dominio
     * Necessario per CDN che richiedono Origin/Referer specifici
     * Pubblico per essere usato in ProxyRoutes per includere headers nelle URL della playlist
     */
    fun getDomainSpecificHeaders(url: String): Map<String, String> {
        val urlLower = url.lowercase()

        return when {
            // Sky Italia / NOW TV CDN
            urlLower.contains("cssott") || urlLower.contains("nowtv") ||
            urlLower.contains("sky.it") || urlLower.contains("peacocktv") -> {
                mapOf(
                    "Origin" to "https://www.nowtv.it",
                    "Referer" to "https://www.nowtv.it/"
                )
            }
            // DaddyLive / DLHD
            urlLower.contains("daddylive") || urlLower.contains("dlhd") ||
            urlLower.contains("newkso") -> {
                mapOf(
                    "Origin" to "https://daddylive.mp",
                    "Referer" to "https://daddylive.mp/"
                )
            }
            // Vavoo
            urlLower.contains("vavoo") -> {
                mapOf(
                    "Origin" to "https://vavoo.to",
                    "Referer" to "https://vavoo.to/"
                )
            }
            // Mixdrop
            urlLower.contains("mixdrop") -> {
                mapOf(
                    "Referer" to "https://mixdrop.co/"
                )
            }
            // StreamTape
            urlLower.contains("streamtape") || urlLower.contains("stape") -> {
                mapOf(
                    "Referer" to "https://streamtape.com/"
                )
            }
            // Voe
            urlLower.contains("voe.sx") || urlLower.contains("voecdn") -> {
                mapOf(
                    "Referer" to "https://voe.sx/"
                )
            }
            // Default: nessun header aggiuntivo
            else -> emptyMap()
        }
    }
}

/**
 * Upstream refused this server's IP (and every proxy tried): tell the player
 * with 451 Unavailable For Legal Reasons rather than a generic 500.
 */
internal suspend fun respondGeoBlocked(
    call: io.ktor.server.application.ApplicationCall,
    e: HttpClientManager.GeoBlockedException,
) {
    ServerState.warn("GEO_BLOCKED (${e.code}): ${e.message}")
    runCatching {
        call.response.headers.append(io.ktor.http.HttpHeaders.AccessControlAllowOrigin, "*")
        call.respondText(
            "Unavailable in this region (upstream returned ${e.code}); no working proxy for it right now.",
            io.ktor.http.ContentType.Text.Plain,
            io.ktor.http.HttpStatusCode(451, "Unavailable For Legal Reasons"),
        )
    }
}
