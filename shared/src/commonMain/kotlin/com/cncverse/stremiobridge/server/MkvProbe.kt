package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Audio languages of direct MKV files, read from the file itself: an MKV lists its tracks
 * (type, language, name) near the start, so the first 256 KB is enough — or, when the track
 * list sits further in, one more small range the file's own index (SeekHead) points to.
 * Until now only the link/release name was searched for language words, so a "Multi Audio"
 * file showed nothing, or only the languages its name happened to spell out.
 *
 * The same read gives the file's real size (the server's Content-Range) for every direct file,
 * and an MKV's running time from its header: size / time is the speed it needs to play.
 *
 * Other formats (MP4 keeps its track list wherever it likes) only get their size. Results are
 * kept per link; reads that miss the request's short budget finish for the next request.
 */
object MkvProbe {
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val FAIL_TTL_MS = 15 * 60_000L
    private const val BUDGET_MS = 2_500L
    private const val HEAD_BYTES = 256 * 1024
    private const val TRACKS_BYTES = 128 * 1024

    /** What one read of a file's start told. */
    class Facts(val languages: List<String>?, val sizeBytes: Long?, val durationSec: Double?) {
        val empty get() = languages == null && sizeBytes == null && durationSec == null
    }

    /** url -> (facts, or null when unreadable; checkedAt) */
    private val results = ConcurrentHashMap<String, Pair<Facts?, Long>>()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()
    private val limit = Semaphore(16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun candidate(s: StremioStream) =
        (s.info?.audioLanguages == null || s.info?.sizeBytes == null) && SeekProbe.isDirectFile(s)

    private fun cached(url: String): Pair<Facts?, Long>? {
        val e = results[url] ?: return null
        val ttl = if (e.first == null) FAIL_TTL_MS else TTL_MS
        if (System.currentTimeMillis() - e.second > ttl) { results.remove(url); return null }
        return e
    }

    /** [streams] with audio languages, real size and running time filled in where the file's start was read. */
    suspend fun enrich(streams: List<StremioStream>): List<StremioStream> {
        if (results.size > 50_000) { val now = System.currentTimeMillis(); results.entries.removeIf { now - it.value.second > TTL_MS } }
        val todo = streams.filter { candidate(it) && cached(it.url!!) == null }.distinctBy { it.url }
        if (todo.isNotEmpty()) {
            val jobs = todo.map { s ->
                inFlight.computeIfAbsent(s.url!!) { url ->
                    scope.async {
                        try { results[url] = read(s) to System.currentTimeMillis() } finally { inFlight.remove(url) }
                    }
                }
            }
            withTimeoutOrNull(BUDGET_MS) { jobs.awaitAll() }
        }
        return streams.map { s ->
            if (!candidate(s)) return@map s
            val f = cached(s.url!!)?.first ?: return@map s
            val info = s.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            s.copy(info = info.copy(
                audioLanguages = f.languages?.takeIf { it.isNotEmpty() } ?: info.audioLanguages,
                sizeBytes = f.sizeBytes ?: info.sizeBytes,
                durationSec = f.durationSec ?: info.durationSec,
            ))
        }
    }

    private class Read(val bytes: ByteArray, val ranged: Boolean, val total: Long?)

    private val CONTENT_RANGE_TOTAL = Regex("/(\\d+)\\s*$")

    /** First [max] bytes from [start]; null on error. Also: the server honoured the range, the file's size. */
    private fun fetch(s: StremioStream, start: Long, max: Int): Read? {
        val req = okhttp3.Request.Builder().url(s.url!!)
            .header("Range", "bytes=$start-${start + max - 1}")
            .header("User-Agent", "ExoPlayerLib/2.19.1")
        s.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> runCatching { req.header(k, v) } }
        return client.newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful || r.header("Content-Type").orEmpty().startsWith("text/html")) return@use null
            if (start > 0 && r.code != 206) return@use null
            // Read at most [max] bytes even when the range was ignored (whole file coming)
            val src = r.body?.source() ?: return@use null
            src.request(max.toLong())
            val n = minOf(max.toLong(), src.buffer.size).toInt()
            // 206: "bytes 0-262143/14420352696"; 200: the whole file, so its length
            val total = if (r.code == 206) r.header("Content-Range")?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                else r.header("Content-Length")?.toLongOrNull()?.takeIf { r.header("Content-Encoding") == null }
            Read(src.buffer.readByteArray(n.toLong()), r.code == 206, total?.takeIf { it > 0 })
        }
    }

    private suspend fun read(s: StremioStream): Facts? = limit.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val head = fetch(s, 0, HEAD_BYTES) ?: return@runCatching null
                val facts = when (val r = parse(head.bytes)) {
                    is Parsed.Tracks -> Facts(r.languages, head.total, r.durationSec)
                    is Parsed.SeekTo -> Facts(
                        if (!head.ranged) null else fetch(s, r.offset, TRACKS_BYTES)?.bytes?.let { tracksAt(it) },
                        head.total, r.durationSec,
                    )
                    null -> Facts(null, head.total, null) // not an MKV: its size is still worth having
                }
                facts.takeIf { !it.empty }
            }.onFailure { ServerState.debug("[MkvProbe] ${s.url?.take(80)}: ${it.message}") }.getOrNull()
        }
    }

    // ── EBML (Matroska) reading ──────────────────────────────────────────────

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_TYPE = 0x83L
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_BCP47 = 0x22B59DL
    private const val NAME = 0x536EL
    private const val CLUSTER = 0x1F43B675L
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L

    internal sealed class Parsed {
        /** Running time from the segment's Info, when it came before the track list. */
        abstract val durationSec: Double?
        class Tracks(val languages: List<String>, override val durationSec: Double? = null) : Parsed()
        /** The track list is not in the bytes read; it starts at this file offset. */
        class SeekTo(val offset: Long, override val durationSec: Double? = null) : Parsed()
    }

    /** Variable-length integer at [pos]: (value, length). IDs keep their length marker, sizes don't. */
    private fun vint(b: ByteArray, pos: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (pos >= b.size) return null
        val first = b[pos].toInt() and 0xFF
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len > 8 || pos + len > b.size) return null
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
        return v to len
    }

    private class Element(val id: Long, val data: Int, val size: Long, val unknownSize: Boolean) {
        val end: Long get() = data + size
    }

    private fun element(b: ByteArray, pos: Int): Element? {
        val (id, idLen) = vint(b, pos, keepMarker = true) ?: return null
        val (size, sizeLen) = vint(b, pos + idLen, keepMarker = false) ?: return null
        val unknown = size == (1L shl (7 * sizeLen)) - 1
        return Element(id, pos + idLen + sizeLen, size, unknown)
    }

    private fun children(b: ByteArray, from: Int, to: Long): Sequence<Element> = sequence {
        var p = from
        val stop = minOf(to, b.size.toLong())
        while (p < stop) {
            val e = element(b, p) ?: break
            yield(e)
            if (e.unknownSize) break
            if (e.end > Int.MAX_VALUE) break
            p = e.end.toInt()
        }
    }

    private fun uint(b: ByteArray, e: Element): Long {
        var v = 0L
        for (i in 0 until minOf(e.size, 8L).toInt()) { if (e.data + i >= b.size) break; v = (v shl 8) or (b[e.data + i].toLong() and 0xFF) }
        return v
    }

    private fun str(b: ByteArray, e: Element): String? {
        if (e.end > b.size) return null
        return String(b, e.data, e.size.toInt(), Charsets.UTF_8).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    /** null when [b] isn't an MKV (or is cut off before anything useful). */
    internal fun parse(b: ByteArray): Parsed? {
        val header = element(b, 0)?.takeIf { it.id == EBML } ?: return null
        val segment = element(b, header.end.toInt())?.takeIf { it.id == SEGMENT } ?: return null
        val segEnd = if (segment.unknownSize) Long.MAX_VALUE else segment.end
        var tracksOffset: Long? = null
        var duration: Double? = null
        for (e in children(b, segment.data, segEnd)) {
            when (e.id) {
                INFO -> duration = durationOf(b, e)
                TRACKS -> return Parsed.Tracks(audioLanguages(b, e), duration)
                SEEK_HEAD -> for (seek in children(b, e.data, e.end).filter { it.id == SEEK }) {
                    val parts = children(b, seek.data, seek.end).toList()
                    val target = parts.firstOrNull { it.id == SEEK_ID }?.let { id -> (0 until id.size.toInt()).fold(0L) { v, i -> (v shl 8) or (b[id.data + i].toLong() and 0xFF) } }
                    if (target == TRACKS) parts.firstOrNull { it.id == SEEK_POSITION }?.let { tracksOffset = segment.data + uint(b, it) }
                }
                CLUSTER -> break // the video itself: no track list before it
            }
        }
        return tracksOffset?.let { Parsed.SeekTo(it, duration) }
    }

    /** Info's Duration (a float in TimestampScale units, 1 ms by default) in seconds. */
    private fun durationOf(b: ByteArray, info: Element): Double? {
        if (info.end > b.size) return null
        val fields = children(b, info.data, info.end).toList()
        val scaleNs = fields.firstOrNull { it.id == TIMESTAMP_SCALE }?.let { uint(b, it) }?.takeIf { it > 0 } ?: 1_000_000L
        val d = fields.firstOrNull { it.id == DURATION }?.takeIf { it.end <= b.size } ?: return null
        val units = when (d.size) {
            4L -> java.nio.ByteBuffer.wrap(b, d.data, 4).float.toDouble()
            8L -> java.nio.ByteBuffer.wrap(b, d.data, 8).double
            else -> return null
        }
        return (units * scaleNs / 1e9).takeIf { it.isFinite() && it > 0 }
    }

    /** The audio languages of a Tracks element that starts at byte 0 of [b]. */
    internal fun tracksAt(b: ByteArray): List<String>? {
        val e = element(b, 0)?.takeIf { it.id == TRACKS } ?: return null
        return audioLanguages(b, e)
    }

    private fun audioLanguages(b: ByteArray, tracks: Element): List<String> =
        children(b, tracks.data, tracks.end).filter { it.id == TRACK_ENTRY }.mapNotNull { entry ->
            val fields = children(b, entry.data, entry.end).toList()
            if (fields.firstOrNull { it.id == TRACK_TYPE }?.let { uint(b, it) } != 2L) return@mapNotNull null // 2 = audio
            val bcp = fields.firstOrNull { it.id == LANGUAGE_BCP47 }?.let { str(b, it) }
            // Matroska's default when a track has no Language element is English
            val code = bcp ?: fields.firstOrNull { it.id == LANGUAGE }?.let { str(b, it) } ?: "eng"
            HlsProbe.languageOf(fields.firstOrNull { it.id == NAME }?.let { str(b, it) }, code)
        }.distinct().toList()
}
