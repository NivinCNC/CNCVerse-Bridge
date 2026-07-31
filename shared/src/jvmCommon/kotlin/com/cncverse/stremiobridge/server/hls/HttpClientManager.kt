package com.cncverse.stremiobridge.server.hls

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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

    /**
     * Crea un client con proxy opzionale
     */
    fun createClient(proxyUrl: String? = null): OkHttpClient {
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
        val client = createClient(proxyUrl)
        val request = buildRequest(url, headers)

        return client.newCall(request).execute().use { response ->
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
        val client = createClient(proxyUrl)
        val request = buildRequest(url, headers)

        return client.newCall(request).execute().use { response ->
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
        val client = createClient(proxyUrl)
        val request = buildRequest(url, headers)

        val response = client.newCall(request).execute()
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
        val client = createClient(proxyUrl)
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = buildRequest(url, headers, requestBody)

        return client.newCall(request).execute().use { response ->
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
        val client = createClient(proxyUrl)
        val request = buildRequest(url, headers, method = "HEAD")

        return client.newCall(request).execute().use { response ->
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
        val requestBuilder = Request.Builder().url(url)

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
