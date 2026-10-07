package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "Hide non-seekable files": a direct-download file can be scrubbed only when its server
 * answers byte-range requests. Each direct-file host is asked for byte 0 once:
 * 206 = seekable, 200 with the whole file = not seekable. Results are kept per host.
 *
 * Never judged (always kept): HLS/DASH playlists, torrents, the bridge's own relay links
 * (/proxy/…, /decrypt, local relay) and anything the check could not decide in time.
 * The check uses its own direct client - never the geo proxy pool or the India tunnel.
 */
object SeekProbe {
    private const val HOST_TTL_MS = 6 * 60 * 60_000L
    private const val BUDGET_MS = 3_500L

    /** host -> (seekable, checkedAt) */
    private val verdicts = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val probeLimit = Semaphore(16)

    /** Tests only: forget every host verdict. */
    internal fun resetForTest() = verdicts.clear()

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val PLAYLIST = Regex("\\.(m3u8|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val RELAY_PATH = Regex("^/(proxy/|decrypt|init_decrypt)")

    /** True for a plain direct file link this filter may judge. */
    fun isDirectFile(s: StremioStream): Boolean {
        val url = s.url ?: return false
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null) return false
        val type = s.info?.linkType?.uppercase()
        if (type != null && type != "VIDEO") return false // M3U8, DASH, TORRENT, MAGNET…
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (PLAYLIST.containsMatchIn(url)) return false
        val host = uri.host?.lowercase() ?: return false
        // The bridge's own relay links (proxy, decrypt, local relay) are never filtered
        if (RELAY_PATH.containsMatchIn(uri.rawPath ?: "")) return false
        if (host in ServerState.ownHosts) return false
        ServerState.streamBaseUrl?.let { b -> if (runCatching { java.net.URI(b).host }.getOrNull() == host) return false }
        return true
    }

    private fun cached(host: String): Boolean? {
        val (ok, at) = verdicts[host] ?: return null
        if (System.currentTimeMillis() - at > HOST_TTL_MS) { verdicts.remove(host); return null }
        return ok
    }

    /** null = could not tell (blocked, timed out, error): such streams are kept. */
    private suspend fun probe(s: StremioStream): Boolean? = probeLimit.withPermit {
        withContext(Dispatchers.IO) {
            val req = okhttp3.Request.Builder().url(s.url!!).header("Range", "bytes=0-0")
            s.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> runCatching { req.header(k, v) } }
            runCatching {
                client.newCall(req.build()).execute().use { r ->
                    val type = r.header("Content-Type").orEmpty().lowercase()
                    val finalHost = r.request.url.host.lowercase()
                    when {
                        // A playlist behind a plain-looking URL (workers.dev HLS bridges): never hidden
                        "mpegurl" in type || "dash+xml" in type -> true
                        r.code !in 200..299 -> null // 403/404/5xx: says nothing about seeking
                        // A web page is not a file at all (download pages such as gamerxyt dl.php,
                        // which can even answer 206 with one byte of HTML)
                        type.startsWith("text/html") -> false
                        // Google Drive downloads ignore ranges
                        finalHost == "video-downloads.googleusercontent.com" -> false
                        r.code == 206 -> true
                        else -> false // 200: ignored the range, sends the whole file
                    }
                }
            }.getOrNull()
        }
    }

    /** [streams] without the direct files whose host cannot seek. */
    suspend fun dropNonSeekable(streams: List<StremioStream>): List<StremioStream> {
        val hostOf = streams.associateWith { s -> if (isDirectFile(s)) runCatching { java.net.URI(s.url!!).host?.lowercase() }.getOrNull() else null }
        // One probe per unknown host, all at once, within a small time budget
        val toProbe = hostOf.entries.filter { (_, h) -> h != null && cached(h) == null }
            .distinctBy { it.value }.map { it.key to it.value!! }
        if (toProbe.isNotEmpty()) {
            withTimeoutOrNull(BUDGET_MS) {
                coroutineScope {
                    toProbe.map { (s, host) ->
                        async { probe(s)?.let { verdicts[host] = it to System.currentTimeMillis() } }
                    }.awaitAll()
                }
            }
        }
        return streams.filter { s ->
            val host = hostOf[s] ?: return@filter true
            cached(host) != false
        }
    }
}
