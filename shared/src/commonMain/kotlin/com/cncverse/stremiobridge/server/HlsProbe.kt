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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HLS links the extension gave no quality for ("Auto"): their master playlist is read once to
 * learn the variant resolutions and audio track languages. Without this they sorted last, and
 * "max streams per resolution" put every one of them in a single bucket, so they vanished.
 *
 * One small GET per playlist, all at once within a short budget; reads that take longer keep
 * going and fill the cache for the next request. Results are kept per URL.
 */
object HlsProbe {
    private const val TTL_MS = 30 * 60_000L
    private const val FAIL_TTL_MS = 5 * 60_000L
    private const val BUDGET_MS = 2_500L
    private const val MAX_BYTES = 512 * 1024

    /** [bandwidth]: bits per second of the best variant (its AVERAGE-BANDWIDTH, else BANDWIDTH). */
    class Master(val heights: List<Int>, val languages: List<String>, val bandwidth: Long? = null)

    /** url -> (master, or null when unreadable / not a master; checkedAt) */
    private val results = ConcurrentHashMap<String, Pair<Master?, Long>>()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()
    private val limit = Semaphore(16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val RELAY_PATH = Regex("^/(proxy/|decrypt|init_decrypt|d/)")

    /** An HLS link without a known quality that points at someone else's server. */
    private fun candidate(s: StremioStream): Boolean {
        val url = s.url ?: return false
        val info = s.info
        if (info?.quality != null || info?.variantHeights != null) return false
        val type = info?.linkType?.uppercase()
        if (type != "M3U8" && !(type == null && url.contains(".m3u8", ignoreCase = true))) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (RELAY_PATH.containsMatchIn(uri.rawPath ?: "")) return false
        val host = uri.host?.lowercase() ?: return false
        if (host in ServerState.ownHosts) return false
        return true
    }

    private fun cached(url: String): Pair<Master?, Long>? {
        val e = results[url] ?: return null
        val ttl = if (e.first == null) FAIL_TTL_MS else TTL_MS
        if (System.currentTimeMillis() - e.second > ttl) { results.remove(url); return null }
        return e
    }

    /** [streams] with variant heights and audio languages filled in where the master could be read. */
    suspend fun enrich(streams: List<StremioStream>): List<StremioStream> {
        if (results.size > 20_000) { val now = System.currentTimeMillis(); results.entries.removeIf { now - it.value.second > TTL_MS } }
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
            val m = cached(s.url!!)?.first ?: return@map s
            val info = s.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            s.copy(info = info.copy(
                variantHeights = m.heights.takeIf { it.isNotEmpty() },
                audioLanguages = m.languages.takeIf { it.isNotEmpty() } ?: info.audioLanguages,
                bitrateBps = m.bandwidth ?: info.bitrateBps,
            ))
        }
    }

    private suspend fun read(s: StremioStream): Master? = limit.withPermit {
        withContext(Dispatchers.IO) {
            val req = okhttp3.Request.Builder().url(s.url!!)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            // The extension's own headers (Referer, Origin…) win, as the player would send them
            s.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> runCatching { req.header(k, v) } }
            runCatching {
                client.newCall(req.build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.source()?.let { src -> src.request(MAX_BYTES.toLong()); src.buffer.snapshot().utf8() } ?: return@use null
                    parse(body)
                }
            }.onFailure { ServerState.debug("[HlsProbe] ${s.url?.take(80)}: ${it.message}") }.getOrNull()
        }
    }

    private val RESOLUTION = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private fun attr(line: String, name: String): String? =
        Regex("(?<![A-Z-])$name=(\"([^\"]*)\"|[^,]*)", RegexOption.IGNORE_CASE).find(line)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[1] }.trim().takeIf { it.isNotEmpty() }
        }

    /** null when [text] isn't a master playlist; heights best first, languages in playlist order. */
    internal fun parse(text: String): Master? {
        if (!text.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")) return null
        val lines = text.lineSequence().map { it.trim() }
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }.toList()
        if (variants.isEmpty()) return null // a media playlist: one quality, nothing to learn
        val heights = variants.mapNotNull { v ->
            RESOLUTION.find(v)?.let { tier(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        }.distinct().sortedDescending()
        val languages = text.lineSequence().map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA", ignoreCase = true) && attr(it, "TYPE").equals("AUDIO", ignoreCase = true) }
            .mapNotNull { languageOf(attr(it, "NAME"), attr(it, "LANGUAGE")) }
            .distinct().toList()
        // The speed the best quality needs: the top variant's average (else peak) bandwidth
        val best = variants.maxByOrNull { v ->
            RESOLUTION.find(v)?.let { tier(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
        }
        val bandwidth = best?.let { (attr(it, "AVERAGE-BANDWIDTH") ?: attr(it, "BANDWIDTH"))?.toLongOrNull() }?.takeIf { it > 0 }
        return Master(heights, languages, bandwidth)
    }

    /** Resolution tier of a WxH variant: wide films (1920x800) are 1080p by their width. */
    internal fun tier(w: Int, h: Int): Int {
        val byW = when { w >= 3800 -> 2160; w >= 2500 -> 1440; w >= 1900 -> 1080; w >= 1200 -> 720; w >= 800 -> 480; w >= 600 -> 360; w > 0 -> 240; else -> 0 }
        val byH = when { h >= 2000 -> 2160; h >= 1400 -> 1440; h >= 1000 -> 1080; h >= 700 -> 720; h >= 460 -> 480; h >= 340 -> 360; h > 0 -> 240; else -> 0 }
        return maxOf(byW, byH)
    }

    private val KNOWN = listOf(
        "Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Punjabi",
        "Gujarati", "Urdu", "Japanese", "Korean", "Chinese", "Mandarin", "Cantonese", "Spanish", "French",
        "German", "Italian", "Portuguese", "Russian", "Arabic", "Turkish", "Thai", "Indonesian", "Vietnamese",
        "Filipino", "Tagalog", "Polish", "Dutch", "Swedish", "Persian", "Hebrew", "Greek", "Ukrainian",
        "Odia", "Assamese", "Bhojpuri", "Nepali", "Sinhala", "Malay",
    )

    private val BIBLIOGRAPHIC = mapOf(
        "ger" to "de", "fre" to "fr", "chi" to "zh", "dut" to "nl", "per" to "fa", "gre" to "el", "cze" to "cs",
        "rum" to "ro", "slo" to "sk", "ice" to "is", "mac" to "mk", "may" to "ms", "bur" to "my", "arm" to "hy",
        "geo" to "ka", "baq" to "eu", "alb" to "sq", "wel" to "cy", "tib" to "bo",
    )

    /** "Hindi" from a track NAME that says so, else from its LANGUAGE code ("hi", "tam"); null for "und", "Track 1"… */
    internal fun languageOf(name: String?, code: String?): String? {
        name?.let { n -> KNOWN.firstOrNull { k -> Regex("(?<![A-Za-z])${k}(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(n) }?.let { return it } }
        val raw = code?.lowercase()?.substringBefore('-')?.takeIf { it.length in 2..3 && it !in setOf("und", "mul", "mis", "zxx", "qaa") } ?: return null
        // MKV files often use the bibliographic ISO 639-2 codes, which Locale doesn't know
        val c = BIBLIOGRAPHIC[raw] ?: raw
        val display = Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH)
        return display.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
            ?.let { d -> KNOWN.firstOrNull { it.equals(d, ignoreCase = true) } ?: d }
    }
}
