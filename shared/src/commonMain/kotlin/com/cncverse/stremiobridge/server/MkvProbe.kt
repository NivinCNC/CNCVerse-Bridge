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
 * Other formats (MP4 keeps its track list wherever it likes) are left alone. Results are kept
 * per link; reads that miss the request's short budget finish for the next request.
 */
object MkvProbe {
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val FAIL_TTL_MS = 15 * 60_000L
    private const val BUDGET_MS = 2_500L
    private const val HEAD_BYTES = 256 * 1024
    private const val TRACKS_BYTES = 128 * 1024

    /** url -> (audio languages, or null when unreadable / not an MKV; checkedAt) */
    private val results = ConcurrentHashMap<String, Pair<List<String>?, Long>>()
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

    private fun candidate(s: StremioStream) = s.info?.audioLanguages == null && SeekProbe.isDirectFile(s)

    private fun cached(url: String): Pair<List<String>?, Long>? {
        val e = results[url] ?: return null
        val ttl = if (e.first == null) FAIL_TTL_MS else TTL_MS
        if (System.currentTimeMillis() - e.second > ttl) { results.remove(url); return null }
        return e
    }

    /** [streams] with the audio languages filled in for every MKV whose track list could be read. */
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
            val langs = cached(s.url!!)?.first?.takeIf { it.isNotEmpty() } ?: return@map s
            val info = s.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            s.copy(info = info.copy(audioLanguages = langs))
        }
    }

    /** First [max] bytes from [start]; null on error. Second value: the server honoured the range. */
    private fun fetch(s: StremioStream, start: Long, max: Int): Pair<ByteArray, Boolean>? {
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
            src.buffer.readByteArray(n.toLong()) to (r.code == 206)
        }
    }

    private suspend fun read(s: StremioStream): List<String>? = limit.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val (head, ranged) = fetch(s, 0, HEAD_BYTES) ?: return@runCatching null
                when (val r = parse(head)) {
                    is Parsed.Tracks -> r.languages
                    is Parsed.SeekTo -> if (!ranged) null else
                        fetch(s, r.offset, TRACKS_BYTES)?.first?.let { tracksAt(it) }
                    null -> null
                }
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

    internal sealed class Parsed {
        class Tracks(val languages: List<String>) : Parsed()
        /** The track list is not in the bytes read; it starts at this file offset. */
        class SeekTo(val offset: Long) : Parsed()
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
        for (e in children(b, segment.data, segEnd)) {
            when (e.id) {
                TRACKS -> return Parsed.Tracks(audioLanguages(b, e))
                SEEK_HEAD -> for (seek in children(b, e.data, e.end).filter { it.id == SEEK }) {
                    val parts = children(b, seek.data, seek.end).toList()
                    val target = parts.firstOrNull { it.id == SEEK_ID }?.let { id -> (0 until id.size.toInt()).fold(0L) { v, i -> (v shl 8) or (b[id.data + i].toLong() and 0xFF) } }
                    if (target == TRACKS) parts.firstOrNull { it.id == SEEK_POSITION }?.let { tracksOffset = segment.data + uint(b, it) }
                }
                CLUSTER -> break // the video itself: no track list before it
            }
        }
        return tracksOffset?.let { Parsed.SeekTo(it) }
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
