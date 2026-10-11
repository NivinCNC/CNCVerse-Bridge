package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Settings of the caching media server (Settings -> Media server).
 */
@Serializable
data class MediaServerPrefs(
    /** Master switch. Off = streams go out exactly as the extension produced them. */
    val enabled: Boolean = false,
    /**
     * "headers": only streams that need a Referer / Cookie / custom header.
     * "hls": those plus every HLS stream (smooth playback of slow hosts).
     * "all": every http(s) stream.
     */
    val mode: String = "hls",
    /** Download direct video files (mp4/mkv/...) to this device first, then serve them from disk. */
    val cacheFiles: Boolean = true,
    /** Cache HLS playlists and segments on this device. */
    val cacheHls: Boolean = true,
    /** HLS segments fetched ahead of the player. */
    val prefetchSegments: Int = 30,
    /** How far (MB) past the playing position a file download may run (0 = download the whole file). */
    val readAheadMb: Int = 1024,
    /** Upper limit of the cache folder, in MB (never more than 2048). Oldest entries are deleted first. */
    val maxCacheMb: Int = 2048,
    /** Entries not used for this many hours are deleted. */
    val keepHours: Int = 24,
    /** Settings generation (older files get the newer defaults once). */
    val ver: Int = 2,
)

@Serializable
private class MediaSource(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
private class FileMeta(
    val total: Long,
    val ranges: Boolean,
    val mime: String,
    val have: List<List<Long>> = emptyList(),
)

data class MediaServerStats(val entries: Int, val bytes: Long, val activeDownloads: Int, val served: Long)

/**
 * A small caching media server inside the bridge (the idea of pencarimovie-server).
 *
 * Some links only play when the request carries a Referer, Cookie or User-Agent that the extension
 * knows. Players often cannot send those. With the media server on, such a stream is rewritten to
 * `/media/...` on this bridge. The bridge then
 *  - downloads the file (or the HLS playlist and its segments) itself, with the headers and cookies
 *    the extension asked for, onto this device's storage,
 *  - and serves the requesting device from that copy, with full range / seek support, while the
 *    download is still running.
 *
 * All paths that carry a remote URL are signed (HMAC), so the server cannot be used as an open proxy.
 */
object MediaServer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** The cache folder never grows past this, whatever the settings file says. */
    const val HARD_MAX_MB = 2048
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile var prefs: MediaServerPrefs = MediaServerPrefs()
        private set
    private var prefsFile: File? = null
    @Volatile private var root: File? = null
    @Volatile private var secret: ByteArray = ByteArray(32)
    private val served = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetchLimit = Semaphore(8)

    private val sources = ConcurrentHashMap<String, MediaSource>()
    private val files = ConcurrentHashMap<String, FileEntry>()

    // ── Setup / settings ──────────────────────────────────────────────────────

    fun init(cacheDir: String) {
        val base = File(cacheDir)
        prefsFile = File(base, "media_server.json")
        prefs = (runCatching { prefsFile?.takeIf { it.exists() }?.let { json.decodeFromString<MediaServerPrefs>(it.readText()) } }
            .getOrNull() ?: MediaServerPrefs()).let {
            var q = it.copy(maxCacheMb = it.maxCacheMb.coerceIn(256, HARD_MAX_MB))
            if (q.ver < 2) q = q.copy(ver = 2, mode = if (q.mode == "headers") "hls" else q.mode, prefetchSegments = maxOf(q.prefetchSegments, 30))
            q
        }
        val dir = File(base, "media_cache")
        dir.mkdirs()
        root = dir
        val sf = File(dir, "secret.key")
        secret = runCatching { if (sf.exists() && sf.length() >= 16) sf.readBytes() else null }.getOrNull()
            ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it); runCatching { sf.writeBytes(it) } }
        scope.launch { runCatching { trim() } }
    }

    /** Reloads the saved settings (after a backup import). */
    fun reload(cacheDir: String) = init(cacheDir)

    fun updatePrefs(p: MediaServerPrefs) {
        val clean = p.copy(
            mode = if (p.mode in setOf("headers", "hls", "all")) p.mode else "hls",
            prefetchSegments = p.prefetchSegments.coerceIn(0, 60),
            readAheadMb = p.readAheadMb.coerceIn(0, 100_000),
            maxCacheMb = p.maxCacheMb.coerceIn(256, HARD_MAX_MB),
            keepHours = p.keepHours.coerceIn(1, 24 * 365),
        )
        prefs = clean
        runCatching { prefsFile?.writeText(json.encodeToString(clean)) }
            .onFailure { ServerState.warn("Failed to save media_server.json: ${it.message}") }
    }

    fun shutdown() {
        files.values.forEach { it.stop = true }
    }

    fun stats(): MediaServerStats {
        val dir = root
        val size = dir?.let { dirSize(it) } ?: 0L
        val entries = dir?.listFiles()?.count { it.isDirectory } ?: 0
        return MediaServerStats(entries, size, files.values.count { it.worker != null }, served.get())
    }

    fun clearCache(): Int {
        val dir = root ?: return 0
        files.values.forEach { it.stop = true }
        files.clear()
        var n = 0
        dir.listFiles()?.forEach { if (it.isDirectory) { it.deleteRecursively(); n++ } }
        segLocks.clear(); playlistCache.clear(); playlistSegs.clear(); segPos.clear(); sources.clear()
        return n
    }

    private fun capBytes(): Long = prefs.maxCacheMb.coerceIn(256, HARD_MAX_MB) * 1024L * 1024L

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun lastUsed(d: File): Long = files[d.name]?.lastAccess?.takeIf { it > 0 } ?: d.lastModified()

    private val evictLock = Any()

    /**
     * Keeps the cache folder under its limit by deleting previously cached items, least recently used
     * first, until [extra] more bytes fit. The entry [keepId] (the one being written) is never deleted
     * as a whole; if it is still too big, its oldest HLS segments go. [extra] > 0 means that entry is
     * about to be (re)allocated at that size, so its current size is not counted.
     * Returns true when [extra] bytes fit afterwards.
     */
    private fun makeRoom(extra: Long, keepId: String?): Boolean = synchronized(evictLock) {
        val dir = root ?: return false
        val cap = capBytes()
        var total = dirSize(dir)
        if (extra > 0 && keepId != null) total -= dirSize(File(dir, keepId))
        if (total + extra <= cap) return true
        val victims = dir.listFiles()?.filter { it.isDirectory && it.name != keepId }?.sortedBy { lastUsed(it) } ?: emptyList()
        for (d in victims) {
            if (total + extra <= cap) break
            val sz = dirSize(d)
            files.remove(d.name)?.stop = true
            d.deleteRecursively()
            total -= sz
        }
        if (total + extra > cap && keepId != null && extra <= 0) {
            val segs = File(dir, "$keepId/hls").listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
            for (f in segs) {
                if (total <= cap) break
                total -= f.length(); f.delete()
            }
        }
        total + extra <= cap
    }

    /** Deletes expired entries, then the least recently used ones while the folder is over its limit. */
    private fun trim() {
        val dir = root ?: return
        val now = System.currentTimeMillis()
        val ttl = prefs.keepHours * 3_600_000L
        val busyMs = 3 * 60_000L
        val entries = dir.listFiles()?.filter { it.isDirectory } ?: return
        entries.filter { now - lastUsed(it) > busyMs && now - lastUsed(it) > ttl }
            .forEach { files.remove(it.name)?.stop = true; it.deleteRecursively() }
        makeRoom(0, null)
    }

    // ── Rewriting streams ─────────────────────────────────────────────────────

    private val OWN_PATH = Regex("^https?://[^/]+/(proxy/|decrypt|init_decrypt|media/)", RegexOption.IGNORE_CASE)

    private fun looksLikeHls(url: String, linkType: String?): Boolean =
        linkType.equals("M3U8", true) || url.substringBefore('?').endsWith(".m3u8", true) || url.contains(".m3u8", true)

    /** The stream pointed at this bridge's media server when it qualifies; otherwise unchanged. */
    fun rewrite(s: StremioStream, base: String): StremioStream {
        val p = prefs
        if (!p.enabled || root == null) return s
        val url = s.url ?: return s
        if (s.info?.linkType == "UPSTREAM") return s
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null || s.clearkey != null) return s
        if (!url.startsWith("http", true) || OWN_PATH.containsMatchIn(url)) return s
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return s
        if (host in ServerState.ownHosts || host == "127.0.0.1" || host == "localhost") return s
        if (url.substringBefore('?').endsWith(".mpd", true) || url.contains(".mpd?", true)) return s // own DASH proxy
        val headers = s.behaviorHints?.proxyHeaders?.request.orEmpty()
        val hls = looksLikeHls(url, s.info?.linkType)
        if (p.mode == "headers" && headers.isEmpty()) return s
        if (p.mode == "hls" && headers.isEmpty() && !hls) return s
        if (hls && !p.cacheHls) return s
        if (!hls && !p.cacheFiles) return s
        if (!hls && SeekProbe.isArchiveUrl(url)) return s
        val id = register(url, headers)
        val newUrl = if (hls) "$base/media/$id/pl/${token(id, url)}.m3u8" else "$base/media/$id/file/${fileName(url)}"
        return s.copy(
            url = newUrl,
            behaviorHints = s.behaviorHints?.copy(proxyHeaders = null),
        )
    }

    private fun fileName(url: String): String {
        val last = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("").substringAfterLast('/')
        val clean = last.filter { it.isLetterOrDigit() || it in "._-" }.takeLast(60)
        return if (clean.contains('.')) clean else "video.mp4"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun register(url: String, headers: Map<String, String>): String {
        val sorted = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { it.key.lowercase() + ":" + it.value }
        val id = sha256(url + "\n" + sorted).take(20)
        if (sources.putIfAbsent(id, MediaSource(url, headers)) == null) {
            val dir = File(root ?: return id, id).also { it.mkdirs() }
            runCatching { File(dir, "source.json").writeText(json.encodeToString(MediaSource(url, headers))) }
        }
        return id
    }

    private fun sourceFor(id: String): MediaSource? {
        if (!id.matches(Regex("[0-9a-f]{20}"))) return null
        sources[id]?.let { return it }
        val f = File(root ?: return null, "$id/source.json")
        val loaded = runCatching { if (f.exists()) json.decodeFromString<MediaSource>(f.readText()) else null }.getOrNull() ?: return null
        sources[id] = loaded
        return loaded
    }

    // ── Signed URL tokens ─────────────────────────────────────────────────────

    private fun b64(text: String): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun unb64(text: String): String? = runCatching { String(java.util.Base64.getUrlDecoder().decode(text)) }.getOrNull()

    private fun sign(id: String, url: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal((id + "\n" + url).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** "<base64 url>.<signature>" */
    private fun token(id: String, url: String): String = b64(url) + "." + sign(id, url)

    /** The remote URL inside [tok] ("b64.sig" with an optional extension), or null when it is forged. */
    private fun urlOfToken(id: String, tok: String): String? {
        val parts = tok.split('.')
        if (parts.size < 2) return null
        val url = unb64(parts[0]) ?: return null
        if (!java.security.MessageDigest.isEqual(sign(id, url).toByteArray(), parts[1].toByteArray())) return null
        return url
    }

    // ── HTTP clients ──────────────────────────────────────────────────────────

    private val baseClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Remembers cookies a site sets while its playlist / file is fetched, and sends them back to it. */
    private class SessionCookies : okhttp3.CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            val list = store.getOrPut(url.host) { java.util.concurrent.CopyOnWriteArrayList() }
            cookies.forEach { c -> list.removeAll { it.name == c.name && it.path == c.path }; list.add(c) }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty().filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private val clients = ConcurrentHashMap<String, okhttp3.OkHttpClient>()

    private fun clientFor(id: String, src: MediaSource): okhttp3.OkHttpClient {
        val hasCookie = src.headers.keys.any { it.equals("cookie", true) }
        if (hasCookie) return baseClient // the extension's own Cookie header must not be replaced
        if (clients.size > 200) clients.clear()
        return clients.getOrPut(id) { baseClient.newBuilder().cookieJar(SessionCookies()).build() }
    }

    private fun requestFor(src: MediaSource, url: String, range: String? = null): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url)
        var hasUa = false
        src.headers.forEach { (k, v) ->
            if (k.equals("user-agent", true)) hasUa = true
            if (!k.equals("host", true) && !k.equals("range", true) && !k.startsWith("X-Cnc-", true)) runCatching { b.header(k, v) }
        }
        // A site whose Cloudflare check was solved before: its cookie + the browser User-Agent it was solved with
        val cf = if (src.headers.keys.none { it.equals("cookie", true) })
            runCatching { com.cncverse.stremiobridge.network.CfCookieStore.get(java.net.URI(url).host.orEmpty()) }.getOrNull() else null
        if (cf != null) { b.header("Cookie", cf.cookie); b.header("User-Agent", cf.userAgent); hasUa = true }
        if (!hasUa) b.header("User-Agent", UA)
        if (range != null) b.header("Range", range)
        return b.build()
    }

    // ── Direct files: download to disk, serve from disk ───────────────────────

    /** Sorted, merged byte intervals [start, end) that are on disk. */
    private class RangeSet {
        private val m = java.util.TreeMap<Long, Long>()
        @Synchronized fun add(start: Long, end: Long) {
            if (end <= start) return
            var ns = start
            var ne = end
            val lower = m.floorEntry(ns)
            if (lower != null && lower.value >= ns) { ns = lower.key; ne = maxOf(ne, lower.value) }
            var next = m.ceilingEntry(ns)
            while (next != null && next.key <= ne) {
                ne = maxOf(ne, next.value)
                m.remove(next.key)
                next = m.ceilingEntry(ns)
            }
            m[ns] = ne
        }
        @Synchronized fun contiguousEnd(pos: Long): Long {
            val f = m.floorEntry(pos) ?: return pos
            return if (f.value > pos) f.value else pos
        }
        @Synchronized fun firstGapFrom(pos: Long): Long {
            var p = pos
            while (true) {
                val f = m.floorEntry(p) ?: return p
                if (f.value > p) p = f.value else return p
            }
        }
        @Synchronized fun bytes(): Long = m.entries.sumOf { it.value - it.key }
        @Synchronized fun snapshot(): List<List<Long>> = m.entries.map { listOf(it.key, it.value) }
        @Synchronized fun restore(list: List<List<Long>>) { list.forEach { if (it.size == 2) add(it[0], it[1]) } }
    }

    private class FileEntry(val id: String, val src: MediaSource, val dir: File) {
        val have = RangeSet()
        val file = File(dir, "data.bin")
        val metaFile = File(dir, "meta.json")
        @Volatile var total = -1L
        @Volatile var ranges = false
        @Volatile var mime = "application/octet-stream"
        @Volatile var ready = false
        @Volatile var passthrough = false
        @Volatile var isPlaylist = false
        @Volatile var error: String? = null
        @Volatile var focus = -1L
        @Volatile var stop = false
        @Volatile var lastAccess = System.currentTimeMillis()
        @Volatile var lastRead = 0L
        @Volatile var writePos = 0L
        @Volatile var worker: Thread? = null
        val lock = Any()
        val complete: Boolean get() = total >= 0 && have.firstGapFrom(0) >= total
    }

    private fun fileEntry(id: String): FileEntry? {
        files[id]?.let { return it }
        val src = sourceFor(id) ?: return null
        val dir = File(root ?: return null, id).also { it.mkdirs() }
        val e = FileEntry(id, src, dir)
        // Resume what an earlier run already downloaded
        runCatching {
            if (e.metaFile.exists() && e.file.exists()) {
                val m = json.decodeFromString<FileMeta>(e.metaFile.readText())
                e.total = m.total; e.ranges = m.ranges; e.mime = m.mime
                e.have.restore(m.have)
                e.ready = true
            }
        }
        return files.putIfAbsent(id, e) ?: e
    }

    private fun saveMeta(e: FileEntry) {
        if (e.total < 0) return
        runCatching { e.metaFile.writeText(json.encodeToString(FileMeta(e.total, e.ranges, e.mime, e.have.snapshot()))) }
    }

    private fun ensureWorker(e: FileEntry) {
        if (e.passthrough || e.error != null && e.ready && e.total < 0) return
        synchronized(e.lock) {
            if (e.worker != null || e.complete) return
            e.stop = false
            e.error = null
            val t = Thread({ runWorker(e) }, "media-dl-" + e.id.take(6))
            t.isDaemon = true
            e.worker = t
            t.start()
        }
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private fun parseStart(contentRange: String?): Long? =
        contentRange?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    private fun runWorker(e: FileEntry) {
        var raf: RandomAccessFile? = null
        try {
            val client = clientFor(e.id, e.src)
            raf = RandomAccessFile(e.file, "rw")
            var cursor = 0L
            var sinceSave = 0L
            while (!e.stop) {
                val want = e.focus
                if (want >= 0) e.focus = -1
                var pos = if (want >= 0) want else cursor
                if (e.total >= 0) {
                    pos = e.have.firstGapFrom(pos)
                    if (pos >= e.total) pos = e.have.firstGapFrom(0)
                    if (pos >= e.total) break // everything is on disk
                }
                val range = if (!e.ready || e.ranges) "bytes=$pos-" else null
                if (e.ready && !e.ranges && pos != 0L) { pos = 0L } // origin cannot seek: only a straight download works
                client.newCall(requestFor(e.src, e.src.url, range)).execute().use { r ->
                    if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code} from the source")
                    if (!e.ready) {
                        if (r.code == 206) {
                            e.ranges = true
                            e.total = parseTotal(r.header("Content-Range")) ?: -1L
                        } else {
                            e.ranges = false
                            e.total = r.body?.contentLength() ?: -1L
                        }
                        r.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }?.let { e.mime = it }
                        // The link is really an HLS playlist: hand over to the playlist route
                        if (e.mime.contains("mpegurl", true)) {
                            e.isPlaylist = true
                            e.ready = true
                            e.stop = true
                            return@use
                        }
                        // Bigger than the whole cache, or no room even after deleting old entries: relay live instead
                        val tooBig = e.total > capBytes() || (e.total > 0 && !makeRoom(e.total, e.id))
                        if (e.total <= 0 || tooBig) {
                            // Unknown length (live / chunked) or bigger than the whole cache: relay it live instead
                            e.passthrough = true
                            e.ready = true
                            e.stop = true
                            return@use
                        }
                        raf.setLength(e.total)
                        e.ready = true
                    }
                    val body = r.body ?: throw IOException("empty response")
                    val startAt = if (r.code == 206) (parseStart(r.header("Content-Range")) ?: pos) else 0L
                    val input = body.byteStream()
                    val buf = ByteArray(128 * 1024)
                    var off = startAt
                    while (!e.stop) {
                        val f = e.focus
                        if (f >= 0 && e.ranges && f != off && e.have.contiguousEnd(f) == f) break // a seek: reconnect there
                        // Do not run far past what is being watched
                        val ahead = prefs.readAheadMb * 1024L * 1024L
                        // An origin that cannot seek is downloaded completely, at full speed: stopping would mean starting over
                        if (ahead > 0 && e.ranges && off - e.lastRead > ahead) {
                            if (System.currentTimeMillis() - e.lastAccess > 180_000) { e.stop = true; break }
                            Thread.sleep(400)
                            continue
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.seek(off)
                        raf.write(buf, 0, n)
                        e.have.add(off, off + n)
                        off += n
                        e.writePos = off
                        sinceSave += n
                        if (sinceSave > 32L * 1024 * 1024) {
                            sinceSave = 0; saveMeta(e)
                            // Hard limit: never let the folder pass the cap
                            if (!makeRoom(0, e.id)) throw IOException("the media cache is full (limit ${prefs.maxCacheMb} MB)")
                        }
                        if (e.have.contiguousEnd(off) > off) break // ran into data that is already on disk
                    }
                    cursor = off
                }
                if (e.passthrough) break
                if (!e.stop && e.total >= 0 && !e.ranges && e.have.firstGapFrom(0) < e.total) {
                    throw IOException("the source closed the connection early")
                }
            }
            saveMeta(e)
            if (e.complete) scope.launch { runCatching { trim() } }
        } catch (t: Throwable) {
            e.error = t.message ?: t.javaClass.simpleName
            e.ready = true
            ServerState.warn("[MediaServer] download failed: ${e.error}")
            saveMeta(e)
        } finally {
            runCatching { raf?.close() }
            e.worker = null
        }
    }

    private val CT_BY_EXT = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t",
    )

    private fun contentTypeOf(mime: String, name: String): ContentType {
        val m = if (mime.startsWith("video/") || mime.startsWith("audio/")) mime
        else CT_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "video/mp4"
        return runCatching { ContentType.parse(m) }.getOrDefault(ContentType.Video.MP4)
    }

    /** "bytes=a-b" / "a-" / "-n" -> inclusive (start, end); null = no/unsatisfiable range header. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        val spec = header?.trim()?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-', "").trim()
        val start: Long
        val end: Long
        if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return null
            start = (total - n).coerceAtLeast(0); end = total - 1
        } else {
            start = a.toLongOrNull() ?: return null
            end = b.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        }
        return if (start in 0 until total && end >= start) start to end else null
    }

    suspend fun serveFile(call: ApplicationCall, id: String, name: String) {
        val e = fileEntry(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        e.lastAccess = System.currentTimeMillis()
        served.incrementAndGet()
        ensureWorker(e)
        withTimeoutOrNull(45_000) { while (!e.ready) delay(50) }
        if (!e.ready) return call.respondText("The source did not answer in time", status = HttpStatusCode.GatewayTimeout)
        if (e.isPlaylist) return call.respondRedirect("../pl/${token(id, e.src.url)}.m3u8")
        if (e.passthrough) return passthrough(call, e)
        if (e.total <= 0) {
            return call.respondText("Source error: ${e.error ?: "unknown"}", status = HttpStatusCode.BadGateway)
        }
        val total = e.total
        val rangeHeader = call.request.header("Range")
        val range = parseRange(rangeHeader, total)
        if (rangeHeader != null && range == null) {
            call.response.header("Content-Range", "bytes */$total")
            return call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1)
        val len = end - start + 1
        val httpStatus = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        val ct = contentTypeOf(e.mime, name)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (range != null) call.response.header("Content-Range", "bytes $start-$end/$total")

        if (call.request.httpMethod == HttpMethod.Head) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength: Long = len
                override val contentType: ContentType = ct
                override val status: HttpStatusCode = httpStatus
            })
            return
        }

        if (e.ranges && e.have.contiguousEnd(start) == start) e.focus = start
        call.respondBytesWriter(ct, httpStatus, len) {
            val reader = RandomAccessFile(e.file, "r")
            try {
                var pos = start
                val buf = ByteArray(256 * 1024)
                var lastProgress = System.currentTimeMillis()
                while (pos <= end) {
                    e.lastAccess = System.currentTimeMillis()
                    e.lastRead = pos
                    val avail = e.have.contiguousEnd(pos)
                    if (avail <= pos) {
                        // Not on disk yet: make sure the downloader is heading here, then wait
                        if (e.worker == null) ensureWorker(e)
                        val near = e.writePos in (pos - 1)..(pos + 8L * 1024 * 1024)
                        if (e.ranges && !near && e.focus < 0) e.focus = pos
                        if (e.error != null && e.worker == null) throw IOException(e.error)
                        if (System.currentTimeMillis() - lastProgress > 90_000) throw IOException("download stalled")
                        delay(40)
                        continue
                    }
                    val n = minOf(avail - pos, end - pos + 1, buf.size.toLong()).toInt()
                    reader.seek(pos)
                    reader.readFully(buf, 0, n)
                    writeFully(buf, 0, n)
                    pos += n
                    lastProgress = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                // Player closed the connection (seek / stop) or the source failed: end this response
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                runCatching { reader.close() }
            }
        }
    }

    /** Streams the source straight through (no cache): unknown length or bigger than the cache limit. */
    private suspend fun passthrough(call: ApplicationCall, e: FileEntry) {
        val rangeHeader = call.request.header("Range")
        val resp = try {
            withContext(Dispatchers.IO) { clientFor(e.id, e.src).newCall(requestFor(e.src, e.src.url, rangeHeader)).execute() }
        } catch (t: Exception) {
            return call.respondText("Upstream unavailable", status = HttpStatusCode.BadGateway)
        }
        resp.use { r ->
            r.header("Content-Range")?.let { call.response.header("Content-Range", it) }
            r.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
            val body = r.body ?: return call.respondText("", status = HttpStatusCode.fromValue(r.code))
            val ct = runCatching { ContentType.parse(r.header("Content-Type") ?: "video/mp4") }.getOrDefault(ContentType.Video.MP4)
            call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code)) { body.byteStream().use { it.copyTo(this) } }
        }
    }

    // ── HLS: playlists and segments ───────────────────────────────────────────

    private class CachedPlaylist(val at: Long, val text: String)
    private val playlistCache = ConcurrentHashMap<String, CachedPlaylist>()
    /** playlist key -> its segment urls, in order (for read-ahead) */
    private val playlistSegs = ConcurrentHashMap<String, List<String>>()
    /** "id|segment url" -> (playlist key, index) */
    private val segPos = ConcurrentHashMap<String, Pair<String, Int>>()
    private val segLocks = ConcurrentHashMap<String, Any>()
    private val segWrites = AtomicLong(0)

    private fun segFile(id: String, url: String): File =
        File(File(root!!, "$id/hls").also { it.mkdirs() }, sha256(url).take(32))

    /** Downloads one playlist / segment, trying up to three times (hosts that hiccup must not stall the player). */
    private fun fetchRemote(id: String, src: MediaSource, url: String): Pair<ByteArray, String?> {
        val client = clientFor(id, src)
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                client.newCall(requestFor(src, url)).execute().use { r ->
                    if (!r.isSuccessful) {
                        if (r.code in 400..499 && r.code != 408 && r.code != 429) throw IOException("HTTP ${r.code}")
                        throw IllegalStateException("HTTP ${r.code}")
                    }
                    return (r.body?.bytes() ?: ByteArray(0)) to r.header("Content-Type")?.substringBefore(';')?.trim()
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true) throw e
                last = e
            } catch (e: IllegalStateException) {
                last = IOException(e.message)
            }
            if (attempt < 3) Thread.sleep(250L * attempt)
        }
        throw last ?: IOException("download failed")
    }

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    private fun isPlaylistUrl(u: String) = u.substringBefore('?').endsWith(".m3u8", true)

    private fun resolve(base: String, ref: String): String =
        runCatching { java.net.URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    private fun relTarget(id: String, url: String, playlist: Boolean): String =
        if (playlist) "../pl/${token(id, url)}.m3u8" else "../seg/${token(id, url)}"

    private val ATTRS = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    /** A master playlist cut down to its highest rendition (tallest, then highest bitrate); audio / subtitle groups stay. */
    private fun keepBestVariant(text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return text
        val lines = text.lines()
        class V(val infoIdx: Int, val urlIdx: Int, val height: Int, val bw: Long)
        val vs = ArrayList<V>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = ATTRS.findAll(l.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                val h = a["RESOLUTION"]?.lowercase()?.split('x')?.getOrNull(1)?.toIntOrNull() ?: 0
                val bw = a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: a["BANDWIDTH"]?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) vs += V(i, j, h, bw)
                i = j
            }
            i++
        }
        if (vs.size < 2) return text
        val top = vs.maxWithOrNull(compareBy<V>({ it.height }, { it.bw })) ?: return text
        val drop = HashSet<Int>()
        for (v in vs) if (v !== top) { drop += v.infoIdx; for (k in v.infoIdx..v.urlIdx) drop += k }
        return lines.filterIndexed { idx, _ -> idx !in drop }.joinToString("\n")
    }

    /** Rewrites every URI of a playlist to this server (relative links, so any host/port works). */
    private fun rewritePlaylist(id: String, playlistUrl: String, text: String, playlistKey: String): String {
        val out = StringBuilder()
        val segments = ArrayList<String>()
        var nextIsVariant = false
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line).append('\n')
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsVariant = true
                    val fixed = if (line.contains("URI=\"")) URI_ATTR.replace(line) { m ->
                        val abs = resolve(playlistUrl, m.groupValues[1])
                        "URI=\"" + relTarget(id, abs, isPlaylistUrl(abs)) + "\""
                    } else line
                    out.append(fixed).append('\n')
                }
                else -> {
                    val abs = resolve(playlistUrl, line)
                    val isPl = nextIsVariant || isPlaylistUrl(abs)
                    if (!isPl) segments += abs
                    nextIsVariant = false
                    out.append(relTarget(id, abs, isPl)).append('\n')
                }
            }
        }
        if (segments.isNotEmpty()) {
            if (segPos.size > 60_000) segPos.clear()
            playlistSegs[playlistKey] = segments
            segments.forEachIndexed { i, s -> segPos["$id|$s"] = playlistKey to i }
        }
        return out.toString()
    }

    suspend fun servePlaylist(call: ApplicationCall, id: String, tok: String) {
        val src = sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        served.incrementAndGet()
        touchDir(id)
        val key = "$id|$url"
        val cached = playlistCache[key]
        val text = if (cached != null && System.currentTimeMillis() - cached.at < playlistTtl(cached.text)) cached.text else {
            val raw = try {
                withContext(Dispatchers.IO) { String(fetchRemote(id, src, url).first) }
            } catch (t: Exception) {
                return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
            }
            if (!raw.trimStart().startsWith("#EXTM3U")) {
                return call.respondText("Source did not return a playlist", status = HttpStatusCode.BadGateway)
            }
            val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(raw) else raw
            val rewritten = rewritePlaylist(id, url, useRaw, key)
            if (playlistCache.size > 500) playlistCache.clear()
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            // Start fetching the first segments right away
            if (raw.contains("#EXTINF")) prefetch(id, src, key, 0)
            if (useRaw.contains("#EXT-X-STREAM-INF")) warmVariants(id, src, url, useRaw)
            rewritten
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(text, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** A master playlist was opened: load its rendition playlists now, so the player's next request is instant. */
    private fun warmVariants(id: String, src: MediaSource, masterUrl: String, raw: String) {
        val lines = raw.lines().map { it.trim() }
        val urls = ArrayList<String>()
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) urls += resolve(masterUrl, lines[j])
            }
        }
        for (u in urls.distinct().take(6)) {
            val key = "$id|$u"
            if (playlistCache.containsKey(key)) continue
            scope.launch {
                prefetchLimit.withPermit {
                    runCatching {
                        val text = String(fetchRemote(id, src, u).first)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val rewritten = rewritePlaylist(id, u, text, key)
                            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
                        }
                    }
                }
            }
        }
    }

    /** Finished (VOD) playlists keep for ten minutes, live ones for two seconds. */
    private fun playlistTtl(text: String): Long = if (text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) 600_000L else 2_000L

    private fun touchDir(id: String) {
        runCatching { File(root ?: return, id).setLastModified(System.currentTimeMillis()) }
    }

    private fun prefetch(id: String, src: MediaSource, playlistKey: String, fromIndex: Int) {
        val n = prefs.prefetchSegments
        if (n <= 0) return
        val list = playlistSegs[playlistKey] ?: return
        for (i in fromIndex until minOf(list.size, fromIndex + n)) {
            val url = list[i]
            val f = segFile(id, url)
            if (f.exists()) continue
            scope.launch {
                prefetchLimit.withPermit { runCatching { ensureSegment(id, src, url) } }
            }
        }
    }

    /** Segment bytes on disk (downloaded once, even when several requests ask at the same time). */
    private fun ensureSegment(id: String, src: MediaSource, url: String): File {
        val f = segFile(id, url)
        if (f.exists()) return f
        val lock = segLocks.getOrPut("$id|$url") { Any() }
        synchronized(lock) {
            if (f.exists()) return f
            val (bytes, mime) = fetchRemote(id, src, url)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            mime?.let { runCatching { File(f.parentFile, f.name + ".ct").writeText(it) } }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            if (segWrites.incrementAndGet() % 20L == 0L) makeRoom(0, id)
        }
        segLocks.remove("$id|$url")
        return f
    }

    suspend fun serveSegment(call: ApplicationCall, id: String, tok: String) {
        val src = sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        served.incrementAndGet()
        touchDir(id)
        val file = try {
            withContext(Dispatchers.IO) { ensureSegment(id, src, url) }
        } catch (t: Exception) {
            return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        segPos["$id|$url"]?.let { (pk, i) -> prefetch(id, src, pk, i + 1) }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        // Some hosts hand out playlists under names without ".m3u8": recognise them by content and rewrite them too
        if (bytes.size in 8..2_000_000 && bytes[0] == '#'.code.toByte() && String(bytes, 0, 7) == "#EXTM3U") {
            val key = "$id|$url"
            val rewritten = rewritePlaylist(id, url, String(bytes).let { if (StremioServer.streamPrefs.bestHls) keepBestVariant(it) else it }, key)
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            if (rewritten.contains("#EXTINF")) prefetch(id, src, key, 0)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            return call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        }
        val mime = runCatching { File(file.parentFile, file.name + ".ct").takeIf { it.exists() }?.readText() }.getOrNull()
            ?: guessSegmentType(url)
        val ct = runCatching { ContentType.parse(mime) }.getOrDefault(ContentType.Application.OctetStream)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val range = parseRange(call.request.header("Range"), bytes.size.toLong())
        if (range != null) {
            call.response.header("Content-Range", "bytes ${range.first}-${range.second}/${bytes.size}")
            call.respondBytes(bytes.copyOfRange(range.first.toInt(), range.second.toInt() + 1), ct, HttpStatusCode.PartialContent)
        } else {
            call.respondBytes(bytes, ct)
        }
    }

    private fun guessSegmentType(url: String): String =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "ts" -> "video/mp2t"
            "m4s", "mp4", "m4v" -> "video/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "vtt" -> "text/vtt"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
}
