package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
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
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Some extensions (Re:ANIME…) start their own HTTP server inside the bridge and return links
 * like http://127.0.0.1:41949/abc/master.m3u8 - reachable only on this machine. Those links go
 * out as {base}/proxy/local/41949/abc/master.m3u8 and are relayed here. The path keeps its
 * shape, so relative playlist entries resolve through the relay too; absolute loopback entries
 * inside playlists are rewritten. Only ports an extension actually handed out are relayed, so
 * this can never reach the server's other local services.
 */
object LocalRelay {
    private val LOOPBACK = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1\\]):([0-9]{2,5})(/[^\\s\"]*)?$", RegexOption.IGNORE_CASE)
    private val LOOPBACK_IN_TEXT = Regex("https?://(127\\.0\\.0\\.1|localhost|\\[::1\\]):([0-9]{2,5})(/[^\\s\"]*)?", RegexOption.IGNORE_CASE)

    /** port -> last time a stream link used it */
    private val allowed = ConcurrentHashMap<Int, Long>()
    private const val PORT_TTL_MS = 12 * 60 * 60_000L

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun base(): String = ServerState.streamBaseUrl ?: ServerState.publicBaseUrl.ifBlank { "http://127.0.0.1:${ServerState.serverPort}" }

    private fun relayUrl(port: Int, pathAndQuery: String?): String {
        allowed[port] = System.currentTimeMillis()
        val p = pathAndQuery?.takeIf { it.isNotEmpty() } ?: "/"
        return base() + "/proxy/local/" + port + p
    }

    /** Rewrites a loopback link to the relay; any other link is returned unchanged. */
    fun rewrite(url: String?): String? {
        val m = url?.let { LOOPBACK.matchEntire(it.trim()) } ?: return url
        val port = m.groupValues[2].toIntOrNull() ?: return url
        if (port == ServerState.serverPort) return url // the bridge itself
        return relayUrl(port, m.groupValues[3])
    }

    fun rewriteStream(s: StremioStream): StremioStream {
        val u = rewrite(s.url)
        val subs = s.subtitles?.map { sub -> rewrite(sub.url)?.let { sub.copy(url = it) } ?: sub }
        return if (u == s.url && subs == s.subtitles) s else s.copy(url = u, subtitles = subs)
    }

    private fun isAllowed(port: Int): Boolean {
        val at = allowed[port] ?: return false
        if (System.currentTimeMillis() - at > PORT_TTL_MS) { allowed.remove(port); return false }
        return true
    }

    suspend fun handle(call: ApplicationCall, port: Int, path: String, rawQuery: String) {
        if (!isAllowed(port)) return call.respondText("Not found", status = HttpStatusCode.NotFound)
        allowed[port] = System.currentTimeMillis()
        val target = "http://127.0.0.1:$port/" + path + (if (rawQuery.isNotEmpty()) "?$rawQuery" else "")
        val reqB = okhttp3.Request.Builder().url(target)
        call.request.header("Range")?.let { reqB.header("Range", it) }
        call.request.header("User-Agent")?.let { reqB.header("User-Agent", it) }

        val resp = try {
            withContext(Dispatchers.IO) { client.newCall(reqB.build()).execute() }
        } catch (e: Exception) {
            return call.respondText("Upstream unavailable", status = HttpStatusCode.BadGateway)
        }
        resp.use { r ->
            val body = r.body ?: return call.respondText("", status = HttpStatusCode.fromValue(r.code))
            val type = r.header("Content-Type").orEmpty()
            val isPlaylist = path.endsWith(".m3u8", true) || type.contains("mpegurl", true)
            if (isPlaylist) {
                val text = withContext(Dispatchers.IO) { body.string() }
                // Absolute loopback entries -> relay; root-relative entries ("/x/y.ts") stay on this port
                val fixed = text.lines().joinToString("\n") { line ->
                    val l = LOOPBACK_IN_TEXT.replace(line) { m ->
                        val p = m.groupValues[2].toIntOrNull()
                        if (p == null) m.value else relayUrl(p, m.groupValues[3])
                    }
                    when {
                        l.startsWith("/") && !l.startsWith("//") -> "/proxy/local/$port$l"
                        else -> l.replace(Regex("URI=\"(/[^/\"][^\"]*)\"")) { "URI=\"/proxy/local/$port${it.groupValues[1]}\"" }
                    }
                }
                call.respondText(fixed, ContentType.parse("application/vnd.apple.mpegurl"), HttpStatusCode.fromValue(r.code))
                return
            }
            r.header("Content-Range")?.let { call.response.header("Content-Range", it) }
            r.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
            val ct = runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream)
            val len = body.contentLength()
            if (len in 0..2_000_000) {
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                call.respondBytes(bytes, ct, HttpStatusCode.fromValue(r.code))
            } else {
                call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code)) {
                    body.byteStream().use { it.copyTo(this) }
                }
            }
        }
    }
}
