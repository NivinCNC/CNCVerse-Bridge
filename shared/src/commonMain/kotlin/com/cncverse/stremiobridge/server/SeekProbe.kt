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

    /** Archive downloads (a whole season zipped): no player can play them. */
    private val ARCHIVE_PATH = Regex("[.](zip|rar|7z|tar|gz|tgz|r[0-9]{2}|z[0-9]{2})$", RegexOption.IGNORE_CASE)
    private val ARCHIVE_TYPES = listOf("zip", "x-rar", "vnd.rar", "x-7z", "x-tar", "gzip")

    /** True when the link's file name says it is an archive (checked for every request, no network). */
    fun isArchiveUrl(url: String?): Boolean {
        val path = runCatching { java.net.URI(url ?: return false).path }.getOrNull() ?: return false
        return ARCHIVE_PATH.containsMatchIn(path.trimEnd('/'))
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

    private fun cached(key: String): Boolean? {
        val (ok, at) = verdicts[key] ?: return null
        if (System.currentTimeMillis() - at > HOST_TTL_MS) { verdicts.remove(key); return null }
        return ok
    }

    private val VIDEO_FILE = Regex("[.](mkv|mp4|m4v|avi|mov|webm|ts|wmv|flv)$", RegexOption.IGNORE_CASE)

    /**
     * What a verdict is remembered for. A link that names a video file shares its host's verdict
     * (seeking is a server feature). A link with no file name (cinecloud /vz/ebb19ef3) is judged on
     * its own: the same host serves an MKV for one title and a season .zip for another.
     */
    private fun verdictKey(s: StremioStream): String? {
        if (!isDirectFile(s)) return null
        val uri = runCatching { java.net.URI(s.url!!) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = runCatching { java.net.URLDecoder.decode(uri.rawPath ?: "", "UTF-8") }.getOrDefault(uri.path ?: "")
        return if (VIDEO_FILE.containsMatchIn(path.trimEnd('/'))) "host:$host" else "url:" + s.url
    }

    /** null = could not tell (blocked, timed out, error): such streams are kept. */
    private suspend fun probe(s: StremioStream): Boolean? = probeLimit.withPermit {
        withContext(Dispatchers.IO) {
            // Ask like a media player would: some hosts (cinecloud) refuse OkHttp's own User-Agent,
            // and a refused check counts as "unknown", which keeps the link. The extension's own
            // headers (proxyHeaders) still win.
            val req = okhttp3.Request.Builder().url(s.url!!).header("Range", "bytes=0-0")
                .header("User-Agent", "ExoPlayerLib/2.19.1")
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
                        // An archive (cinecloud /vz/… links serve whole-season .zip packs)
                        ARCHIVE_TYPES.any { "application/$it" in type } -> false
                        // Google Drive downloads ignore ranges
                        finalHost == "video-downloads.googleusercontent.com" -> false
                        r.code == 206 -> true
                        else -> false // 200: ignored the range, sends the whole file
                    }
                }
            }.getOrNull()
        }
    }

    /** [streams] without the direct files that cannot seek or are not playable (pages, archives). */
    suspend fun dropNonSeekable(streams: List<StremioStream>): List<StremioStream> {
        if (verdicts.size > 20_000) { val now = System.currentTimeMillis(); verdicts.entries.removeIf { now - it.value.second > HOST_TTL_MS } }
        val hostOf = streams.associateWith { s -> verdictKey(s) }
        // One probe per unknown key (host, or the link itself), all at once, within a small time budget
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
