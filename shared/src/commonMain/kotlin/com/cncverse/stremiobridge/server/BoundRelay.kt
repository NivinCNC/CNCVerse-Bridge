package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.network.geo.GeoRouter
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Some HLS hosts (MultiMovies' GD Mirror: hanerix, morencius, acek-cdn… links carrying `asn=13335`)
 * sign each link for the network that unlocked it. The bridge unlocks through WARP, so the link
 * works only from WARP: the phone gets 403 whatever headers it sends. CloudStream does not hit this
 * because the phone unlocks the link itself.
 *
 * Such sites are found by asking for the playlist twice (directly and through WARP); when only WARP
 * gets it, the site's links are played through /proxy/bound, which fetches the playlist and every
 * segment through WARP. Relay links are signed, so this never becomes an open proxy.
 */
object BoundRelay {
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val BUDGET_MS = 3_000L

    /** site (last two host labels) -> (bound to WARP, checkedAt) */
    private val verdicts = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val probeLimit = Semaphore(8)

    private val key: ByteArray by lazy {
        val f = runCatching { java.io.File(System.getProperty("user.home"), ".cncverse_bridge/relay.key") }.getOrNull()
        runCatching { f?.takeIf { it.length() == 32L }?.readBytes() }.getOrNull()
            ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it); runCatching { f?.writeBytes(it) } }
    }

    private fun clientVia(proxy: java.net.Proxy) = okhttp3.OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var warpClient: Pair<java.net.Proxy, okhttp3.OkHttpClient>? = null
    private fun warp(): okhttp3.OkHttpClient? {
        val w = GeoRouter.warp ?: return null
        warpClient?.takeIf { it.first == w }?.let { return it.second }
        return clientVia(w).also { warpClient = w to it }
    }
    private val directClient by lazy {
        clientVia(java.net.Proxy.NO_PROXY).newBuilder().readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    }

    internal fun siteOf(host: String): String = host.lowercase().split('.').takeLast(2).joinToString(".")

    private val PLAYLIST_HINT = Regex("[.](m3u8|txt)([?#]|$)", RegexOption.IGNORE_CASE)

    private fun isHls(s: StremioStream): Boolean {
        val url = s.url ?: return false
        if (s.externalUrl != null || s.infoHash != null) return false
        val type = s.info?.linkType?.uppercase()
        return type == "M3U8" || (type == null && PLAYLIST_HINT.containsMatchIn(url))
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    private fun cached(site: String): Boolean? {
        val (bound, at) = verdicts[site] ?: return null
        if (System.currentTimeMillis() - at > TTL_MS) { verdicts.remove(site); return null }
        return bound
    }

    private fun headersOf(s: StremioStream): Map<String, String> = s.behaviorHints?.proxyHeaders?.request.orEmpty()

    private fun fetch(client: okhttp3.OkHttpClient, url: String, headers: Map<String, String>): Pair<Int, String>? = runCatching {
        val b = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        client.newCall(b.build()).execute().use { r ->
            val head = r.body?.source()?.let { src -> src.request(64); src.buffer.snapshot().utf8().take(64) }.orEmpty()
            r.code to head
        }
    }.getOrNull()

    /** True when only WARP can open the playlist: the link is locked to the network that unlocked it. */
    private suspend fun probe(s: StremioStream): Boolean? = probeLimit.withPermit {
        withContext(Dispatchers.IO) {
            val url = s.url!!
            val viaWarp = fetch(warp() ?: return@withContext null, url, headersOf(s)) ?: return@withContext null
            if (viaWarp.first !in 200..299 || "#EXTM3U" !in viaWarp.second) return@withContext null
            val direct = fetch(directClient, url, headersOf(s)) ?: return@withContext null
            direct.first in setOf(401, 403)
        }
    }

    /** Points links locked to WARP at the relay; everything else is returned unchanged. */
    suspend fun wrap(streams: List<StremioStream>): List<StremioStream> {
        if (GeoRouter.warp == null) return streams // no WARP: links are unlocked from the server's own IP
        val candidates = streams.filter { isHls(it) && hostOf(it.url!!)?.let { h -> h !in ServerState.ownHosts } == true }
        if (candidates.isEmpty()) return streams
        // `asn=13335` = signed for Cloudflare's network, i.e. unlocked through WARP: no need to ask
        candidates.filter { "asn=13335" in it.url!! }.forEach { verdicts[siteOf(hostOf(it.url!!)!!)] = true to System.currentTimeMillis() }
        val toProbe = candidates.filter { cached(siteOf(hostOf(it.url!!)!!)) == null }.distinctBy { siteOf(hostOf(it.url!!)!!) }
        if (toProbe.isNotEmpty()) {
            withTimeoutOrNull(BUDGET_MS) {
                coroutineScope {
                    toProbe.map { s ->
                        async { probe(s)?.let { verdicts[siteOf(hostOf(s.url!!)!!)] = it to System.currentTimeMillis() } }
                    }.awaitAll()
                }
            }
        }
        return streams.map { s ->
            if (s !in candidates || cached(siteOf(hostOf(s.url!!)!!)) != true) s
            else s.copy(url = relayUrl(s.url!!, headersOf(s)), behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
        }
    }

    // ---- relay ----

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun base(): String = ServerState.streamBaseUrl ?: ServerState.publicBaseUrl.ifBlank { "http://127.0.0.1:${ServerState.serverPort}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun sign(url: String, headers: Map<String, String>): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        val msg = url + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
        return mac.doFinal(msg.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    internal fun relayUrl(url: String, headers: Map<String, String>): String =
        base() + "/proxy/bound?d=" + enc(url) + headers.entries.joinToString("") { "&h_" + enc(it.key) + "=" + enc(it.value) } +
            "&sig=" + sign(url, headers)

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    internal fun rewritePlaylist(text: String, finalUrl: String, headers: Map<String, String>): String {
        val baseUri = java.net.URI(finalUrl)
        fun abs(u: String) = runCatching { baseUri.resolve(u.trim()).toString() }.getOrDefault(u)
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"" + relayUrl(abs(m.groupValues[1]), headers) + "\"" }
                else -> relayUrl(abs(t), headers)
            }
        }
    }

    suspend fun handle(call: ApplicationCall) {
        val q = call.request.queryParameters
        val url = q["d"] ?: return call.respondText("Missing d", status = HttpStatusCode.BadRequest)
        val headers = q.names().filter { it.startsWith("h_") }.associate { it.removePrefix("h_") to (q[it] ?: "") }
        if (q["sig"] != sign(url, headers)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val client = warp() ?: return call.respondText("Relay unavailable", status = HttpStatusCode.ServiceUnavailable)

        val b = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        call.request.header("Range")?.let { b.header("Range", it) }
        val resp = try {
            withContext(Dispatchers.IO) { client.newCall(b.build()).execute() }
        } catch (e: Exception) {
            return call.respondText("Upstream unavailable", status = HttpStatusCode.BadGateway)
        }
        resp.use { r ->
            val body = r.body ?: return call.respondText("", status = HttpStatusCode.fromValue(r.code))
            val type = r.header("Content-Type").orEmpty()
            val path = r.request.url.encodedPath
            val small = body.contentLength() in 0..2_000_000
            val maybePlaylist = type.contains("mpegurl", true) || path.endsWith(".m3u8", true) ||
                (small && (path.endsWith(".txt", true) || type.startsWith("text/", true)))
            if (maybePlaylist) {
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                val text = bytes.toString(Charsets.UTF_8)
                if (text.trimStart().startsWith("#EXTM3U")) {
                    call.respondText(rewritePlaylist(text, r.request.url.toString(), headers),
                        ContentType.parse("application/vnd.apple.mpegurl"), HttpStatusCode.fromValue(r.code))
                } else {
                    val ct = runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream)
                    call.respondBytes(bytes, ct, HttpStatusCode.fromValue(r.code))
                }
                return
            }
            r.header("Content-Range")?.let { call.response.header("Content-Range", it) }
            r.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
            val ct = runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream)
            if (small) {
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                call.respondBytes(bytes, ct, HttpStatusCode.fromValue(r.code))
            } else {
                call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code)) {
                    body.byteStream().use { it.copyTo(this) }
                }
            }
        }
    }

    /** Tests only. */
    internal fun markBound(site: String) { verdicts[site] = true to System.currentTimeMillis() }
}
