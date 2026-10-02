package com.cncverse.stremiobridge.server.hls

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.network.DomainProxyInterceptor

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

    private val PROXY_RETRY_CODES = setOf(403, 407, 429, 451, 502, 503, 504)

    /**
     * Executes a relay request. Streams of plugins that are routed through a
     * geo proxy use a sticky proxy with a hot standby (see
     * [com.cncverse.stremiobridge.network.geo.GeoRouter.StickySession]): a
     * failing proxy is swapped for the standby within the same request. If the
     * direct fetch of a proxied plugin's CDN is refused, the CDN host is
     * learned as blocked and retried through the pool.
     */
    private fun routed(request: Request, proxyUrl: String?, url: String?, plugin: String?): Response {
        if (proxyUrl != null || plugin == null || url?.let { DomainProxyInterceptor.ULTRASURF_IN.proxyUrlFor(it) } != null) {
            return createClient(proxyUrl, url).newCall(request).execute()
        }
        val host = request.url.host
        val geo = com.cncverse.stremiobridge.network.geo.GeoRouter
        geo.streamSession(plugin, host)?.let { session ->
            viaSession(session, request)?.let { return it }
            return baseClient.newCall(request).execute()
        }
        // Not routed yet: direct first; a refusal on a plugin that already needs a
        // proxy elsewhere means its CDN is geo-blocked too.
        val direct = try {
            baseClient.newCall(request).execute()
        } catch (e: java.io.IOException) {
            val session = geo.learnStreamHostBlocked(plugin, host) ?: throw e
            return viaSession(session, request) ?: throw e
        }
        if (direct.code != 403 && direct.code != 451) return direct
        val session = geo.learnStreamHostBlocked(plugin, host) ?: return direct
        val viaProxy = viaSession(session, request) ?: return direct
        direct.close()
        return viaProxy
    }

    private fun viaSession(
        session: com.cncverse.stremiobridge.network.geo.GeoRouter.StickySession,
        request: Request,
    ): Response? {
        repeat(3) {
            val p = session.current() ?: return null
            val started = System.currentTimeMillis()
            try {
                val resp = com.cncverse.stremiobridge.network.geo.ProxyPool
                    .clientFor(p.endpoint, streaming = true).newCall(request).execute()
                if (resp.code in PROXY_RETRY_CODES) {
                    resp.close()
                    session.failover(p, "HTTP ${resp.code}")
                } else {
                    com.cncverse.stremiobridge.network.geo.ProxyPool.reportSuccess(p, System.currentTimeMillis() - started)
                    return resp
                }
            } catch (e: java.io.IOException) {
                session.failover(p, e.javaClass.simpleName)
            }
        }
        return null
    }

    fun createClient(proxyUrl: String? = null, url: String? = null): OkHttpClient {
        val resolvedProxy = proxyUrl
            ?: url?.let { DomainProxyInterceptor.ULTRASURF_IN.proxyUrlFor(it) }

        if (resolvedProxy.isNullOrBlank()) {
            return baseClient
        }
        return try {
            val proxy = parseProxy(resolvedProxy)
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
                throw Exception("HTTP ${response.code}: ${response.message}")
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
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            response.body?.bytes() ?: throw Exception("Empty response body")
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
            throw Exception("HTTP ${response.code}: ${response.message}")
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
                throw Exception("HTTP ${response.code}: ${response.message}")
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
