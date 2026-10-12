package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.format.StreamVariables
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
 * Gives streams their real resolution.
 *
 * Many extensions (AniPM's Settlar player, Anidap, most embed hosts) return one adaptive HLS master
 * playlist and report no quality, so the bridge could only call it "Auto" and sorted it last. The
 * master playlist itself lists every rendition (RESOLUTION=1920x1080, BANDWIDTH=...). This reads it,
 * sets the stream's quality to the best rendition, and can optionally split it into one entry per
 * rendition so the player (or the user) can pick 1080p / 720p / 480p directly.
 */
object QualityProbe {
    class Variant(val url: String, val height: Int, val bandwidth: Long)

    /** What a master playlist lists; [externalAudio] = the sound lives in separate playlists (the master must stay). */
    class Probe(val variants: List<Variant>, val externalAudio: Boolean)

    private class Cached(val at: Long, val probe: Probe)

    private const val TTL_MS = 30 * 60_000L
    private val cache = ConcurrentHashMap<String, Cached>()
    // Playlist reads are tiny: with 8 at a time 40+ HLS links needed several waves and the request waited for the last one
    private val limit = Semaphore(32)

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * The common height tier of a picture of [width] x [height]. Wide films are letterboxed
     * (1920x800 is "1080p"), so the width counts as much as the height.
     */
    fun tierOf(width: Int, height: Int): Int = when {
        width >= 3000 || height >= 1800 -> 2160
        width >= 2300 || height >= 1300 -> 1440
        width >= 1700 || height >= 960 -> 1080
        width >= 1100 || height >= 640 -> 720
        width >= 900 || height >= 520 -> 576
        width >= 700 || height >= 400 -> 480
        width >= 500 || height >= 300 -> 360
        else -> 240
    }

    /** Rough tier from the bitrate, for playlists that give no RESOLUTION at all. */
    fun tierFromBandwidth(bps: Long): Int = when {
        bps >= 14_000_000 -> 2160
        bps >= 4_800_000 -> 1080
        bps >= 2_300_000 -> 720
        bps >= 1_100_000 -> 480
        bps > 0 -> 360
        else -> 0
    }

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    /** Renditions listed by a master playlist (empty for a media playlist or anything else). */
    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        if (!text.trimStart().startsWith("#EXTM3U")) return emptyList()
        val lines = text.lines().map { it.trim() }
        val raw = ArrayList<Triple<String, Int, Long>>() // url, height tier (0 = unknown), bandwidth
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                val bw = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val res = attrs["RESOLUTION"]?.lowercase()?.split('x')
                val tier = if (res != null && res.size == 2) tierOf(res[0].toIntOrNull() ?: 0, res[1].toIntOrNull() ?: 0) else 0
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val abs = runCatching { java.net.URI(baseUrl).resolve(lines[j]).toString() }.getOrDefault(lines[j])
                    raw += Triple(abs, tier, bw)
                }
                i = j
            }
            i++
        }
        if (raw.isEmpty()) return emptyList()
        val noRes = raw.all { it.second == 0 }
        return raw.map { (u, t, bw) -> Variant(u, if (noRes) tierFromBandwidth(bw) else t, bw) }
    }

    private fun isHlsCandidate(s: StremioStream): Boolean {
        val url = s.url ?: return false
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null) return false
        if (!url.startsWith("http", true)) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host in ServerState.ownHosts || host == "127.0.0.1" || host == "localhost") return false
        return s.info?.linkType.equals("M3U8", true) || url.substringBefore('?').endsWith(".m3u8", true)
    }

    /** At most [max] bytes of [input] as text (a master playlist is a few KB). */
    private fun readLimited(input: java.io.InputStream?, max: Int): String {
        if (input == null) return ""
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < max) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private val AUDIO_GROUP = Regex("#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=\"", RegexOption.IGNORE_CASE)

    /** The highest rendition: tallest picture, then highest bitrate. */
    fun topVariant(variants: List<Variant>): Variant? =
        variants.filter { it.url.isNotBlank() }.maxWithOrNull(compareBy<Variant>({ it.height }, { it.bandwidth }))

    private fun fetchVariants(s: StremioStream): Probe {
        val url = s.url ?: return Probe(emptyList(), false)
        cache[url]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.probe }
        val probe = try {
            val b = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
            s.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> runCatching { b.header(k, v) } }
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) Probe(emptyList(), false) else {
                    val text = readLimited(r.body?.byteStream(), 512 * 1024)
                    Probe(parseMaster(text, r.request.url.toString()), AUDIO_GROUP.containsMatchIn(text))
                }
            }
        } catch (e: Exception) {
            Probe(emptyList(), false)
        }
        if (cache.size > 3_000) cache.clear()
        cache[url] = Cached(System.currentTimeMillis(), probe)
        return probe
    }

    /**
     * Fills in the resolution of HLS streams that have none. Anything not answered within [budgetMs]
     * stays as it was (and the playlist read is kept for the next request).
     */
    suspend fun enrich(streams: List<StremioStream>, split: Boolean, budgetMs: Long = 3_500L, bestOnly: Boolean = true): List<StremioStream> {
        val todo = streams.filter { isHlsCandidate(it) && StreamVariables.resolutionOf(it) == null }
        if (todo.isEmpty()) return streams
        val found = ConcurrentHashMap<StremioStream, Probe>()
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                todo.map { s ->
                    async(Dispatchers.IO) { limit.withPermit { found[s] = fetchVariants(s) } }
                }.awaitAll()
            }
        }
        // Playlists that were still loading when the budget ran out are used from the cache next time
        if (found.isEmpty()) return streams
        val out = ArrayList<StremioStream>(streams.size + 4)
        val seen = HashSet<String>()
        for (s in streams) {
            val probe = found[s]?.takeIf { it.variants.isNotEmpty() }
            if (probe == null) { out += s; continue }
            val variants = probe.variants
            val best = variants.maxOf { it.height }
            if (best <= 0) { out += s; continue }
            val info = s.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            if (!split || variants.size < 2) {
                // Play the highest rendition every time (when the sound is inside it), and label it with that resolution
                val top = if (bestOnly && !probe.externalAudio) topVariant(variants) else null
                out += if (top != null && top.url != s.url) {
                    s.copy(url = top.url, info = info.copy(quality = best, linkType = s.info?.linkType ?: "M3U8"))
                } else s.copy(info = info.copy(quality = best))
                continue
            }
            // One entry per rendition (best bandwidth for each height), best first, and the adaptive original last
            val perHeight = variants.filter { it.height > 0 }.groupBy { it.height }.map { (_, v) -> v.maxByOrNull { it.bandwidth }!! }
                .sortedByDescending { it.height }
            for (v in perHeight) {
                if (!seen.add(v.url)) continue
                val label = "${v.height}p"
                val base = info.linkName ?: s.title.orEmpty()
                out += s.copy(
                    url = v.url,
                    name = (s.name ?: "") + " " + label,
                    info = info.copy(quality = v.height, linkName = (base + " " + label).trim()),
                )
            }
            out += s.copy(
                title = "(adaptive) " + s.title.orEmpty(),
                info = info.copy(quality = null, linkName = ((info.linkName ?: s.title.orEmpty()) + " Auto").trim()),
            )
        }
        return out
    }
}
