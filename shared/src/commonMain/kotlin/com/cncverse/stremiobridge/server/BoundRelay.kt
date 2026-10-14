package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.network.geo.GeoRouter
import com.cncverse.stremiobridge.network.geo.ProxyPool
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
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
 *
 * Direct video files are relayed too when their link says it was signed for WARP's network:
 * MovieLinkBD's fsldownload.com MKVs carry `mg=<expiry>.13335.<sig>` (13335 = Cloudflare's ASN)
 * and answer anyone else with 403 "FILE DELETED!".
 *
 * NetMirror's NewTV links (tv.imgcdn.kim `…m3u8?in=<token>::…`) are bound to the IP that got the
 * token — a residential proxy, since the host refuses datacenter IPs. Only that master playlist
 * checks the IP: it is fetched through the same proxy (route `t:<token>`), its variant and audio
 * playlists (which need the Referer, not the IP) from the server itself (route `d`), and the
 * segments go straight from the CDN to the player.
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

    /** A link signed for Cloudflare's network (AS13335), i.e. unlocked through WARP. */
    private val WARP_SIGNED = Regex("""[?&](asn=13335|mg=\d+\.13335\.)""")

    internal fun warpSignedVideo(s: StremioStream): Boolean {
        val url = s.url ?: return false
        return s.externalUrl == null && s.infoHash == null && !isHls(s) && WARP_SIGNED.containsMatchIn(url)
    }

    /** `in=<token>::…` of an IP-bound NewTV link whose token's proxy is known (see GeoRouter.egressForToken). */
    private val IN_TOKEN = Regex("""[?&]in=([0-9a-f]{16,})::""")

    internal fun ipBoundToken(s: StremioStream): String? {
        val url = s.url ?: return null
        if (!isHls(s)) return null
        val token = IN_TOKEN.find(url)?.groupValues?.get(1) ?: return null
        return token.takeIf { GeoRouter.egressForToken(it) != null }
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
        val tokens = streams.map { ipBoundToken(it) }
        if (tokens.all { it == null }) return wrapWarp(streams)
        val rest = wrapWarp(streams.filterIndexed { i, _ -> tokens[i] == null }).iterator()
        return streams.mapIndexed { i, s ->
            val token = tokens[i] ?: return@mapIndexed rest.next()
            s.copy(url = relayUrl(s.url!!, headersOf(s), "t:$token"), behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
        }
    }

    private suspend fun wrapWarp(streams: List<StremioStream>): List<StremioStream> {
        if (GeoRouter.warp == null) return streams // no WARP: links are unlocked from the server's own IP
        val videos = streams.filter { warpSignedVideo(it) && hostOf(it.url!!)?.let { h -> h !in ServerState.ownHosts } == true }
        val candidates = streams.filter { isHls(it) && hostOf(it.url!!)?.let { h -> h !in ServerState.ownHosts } == true }
        if (candidates.isEmpty() && videos.isEmpty()) return streams
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
            if (s in videos) s.copy(url = relayUrl(s.url!!, headersOf(s)), behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
            else if (s !in candidates || cached(siteOf(hostOf(s.url!!)!!)) != true) s
            else s.copy(url = relayUrl(s.url!!, headersOf(s)), behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
        }
    }

    // ---- relay ----

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun base(): String = ServerState.streamBaseUrl ?: ServerState.publicBaseUrl.ifBlank { "http://127.0.0.1:${ServerState.serverPort}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun sign(url: String, headers: Map<String, String>, route: String? = null): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        val msg = url + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" } +
            (route?.let { "\n#route:$it" } ?: "")
        return mac.doFinal(msg.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    /** [route]: null = through WARP, `d` = from the server itself, `t:<token>` = through the proxy that sent that token. */
    internal fun relayUrl(url: String, headers: Map<String, String>, route: String? = null): String =
        base() + "/proxy/bound?d=" + enc(url) + headers.entries.joinToString("") { "&h_" + enc(it.key) + "=" + enc(it.value) } +
            (route?.let { "&r=" + enc(it) } ?: "") + "&sig=" + sign(url, headers, route)

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    private val M3U8_PATH = Regex("""[.]m3u8([?#]|$)""", RegexOption.IGNORE_CASE)

    /**
     * WARP route (null): every entry goes back through the relay. Other routes: only the IP-bound
     * link's playlists need the relay (from the server itself — they check the Referer, not the IP);
     * segments, keys and subtitles become absolute links the player fetches straight from the CDN.
     */
    internal fun rewritePlaylist(text: String, finalUrl: String, headers: Map<String, String>, route: String? = null): String {
        val baseUri = java.net.URI(finalUrl)
        fun abs(u: String) = runCatching { baseUri.resolve(u.trim()).toString() }.getOrDefault(u)
        fun entry(u: String): String {
            val a = abs(u)
            return when {
                route == null -> relayUrl(a, headers)
                M3U8_PATH.containsMatchIn(a) -> relayUrl(a, headers, "d")
                else -> a
            }
        }
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"" + entry(m.groupValues[1]) + "\"" }
                else -> entry(t)
            }
        }
    }

    private val directRelayClient by lazy {
        clientVia(java.net.Proxy.NO_PROXY).newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }

    suspend fun handle(call: ApplicationCall) {
        val q = call.request.queryParameters
        val url = q["d"] ?: return call.respondText("Missing d", status = HttpStatusCode.BadRequest)
        val headers = q.names().filter { it.startsWith("h_") }.associate { it.removePrefix("h_") to (q[it] ?: "") }
        val route = q["r"]
        if (q["sig"] != sign(url, headers, route)) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val client = when {
            route == null -> warp()
            route == "d" -> directRelayClient
            route.startsWith("t:") -> GeoRouter.egressForToken(route.removePrefix("t:"))?.let { ProxyPool.clientFor(it) }
            else -> null
        } ?: return call.respondText("Relay unavailable", status = HttpStatusCode.ServiceUnavailable)

        val head = call.request.httpMethod == io.ktor.http.HttpMethod.Head
        val b = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        call.request.header("Range")?.let { b.header("Range", it) }
        // A player's HEAD (size / type check) must not start a multi-GB download through WARP
        if (head) b.head()
        val resp = try {
            withContext(Dispatchers.IO) { client.newCall(b.build()).execute() }
        } catch (e: Exception) {
            return call.respondText("Upstream unavailable", status = HttpStatusCode.BadGateway)
        }
        resp.use { r ->
            if (head) {
                r.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                r.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
                val len = r.header("Content-Length")?.toLongOrNull()
                val ct = r.header("Content-Type")?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                return call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                    override val status = HttpStatusCode.fromValue(r.code)
                    override val contentType = ct
                    override val contentLength = len
                })
            }
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
                    call.respondText(rewritePlaylist(text, r.request.url.toString(), headers, route),
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
                // The length lets players seek in a relayed MKV/MP4 (Range requests)
                call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code), body.contentLength().takeIf { it >= 0 }) {
                    body.byteStream().use { it.copyTo(this) }
                }
            }
        }
    }

    /** Tests only. */
    internal fun markBound(site: String) { verdicts[site] = true to System.currentTimeMillis() }
}
