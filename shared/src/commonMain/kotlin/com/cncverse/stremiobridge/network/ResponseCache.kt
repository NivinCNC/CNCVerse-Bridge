package com.cncverse.stremiobridge.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.buffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shares metadata API answers between extensions: many of them look up the same title on TMDB,
 * Cinemeta, TVmaze… for every stream request (TMDB alone: ~4000 requests in 10 min). Only these
 * public metadata hosts — scraped pages often carry one-time tokens and must stay fresh.
 *
 * GET, no Range, 200 without Set-Cookie, body up to 2 MB; 30 min; 64 MB in all (least recently
 * used dropped first). Identical requests in flight at once share one download.
 */
object ResponseCache : Interceptor {
    internal var hosts = listOf(
        "api.themoviedb.org", "api.tmdb.org", "v3-cinemeta.strem.io", "cinemeta-live.strem.io",
        "api.tvmaze.com", "v2.sg.media-imdb.com", "v3.sg.media-imdb.com", "kitsu.io", "anime-kitsu.strem.fun",
    )
    private const val TTL_MS = 30 * 60_000L
    private const val MAX_BODY = 2 shl 20
    private const val BUDGET = 64L shl 20

    private class Entry(
        val at: Long, val protocol: Protocol, val code: Int, val message: String,
        val headers: Headers, val contentType: MediaType?, val body: ByteArray,
    )

    private val entries = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {}
    private var bytes = 0L
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Entry?>>()

    private fun cacheable(req: Request): Boolean {
        if (req.method != "GET" || req.header("Range") != null) return false
        val h = req.url.host.lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    private fun keyOf(req: Request) = listOf(
        req.url.toString(), req.header("Authorization"), req.header("Cookie"), req.header("Accept"), req.header("Accept-Language"),
    ).joinToString("\u0000")

    private fun lookup(key: String): Entry? = synchronized(entries) {
        val e = entries[key] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            entries.remove(key); bytes -= e.body.size; return null
        }
        e
    }

    private fun store(key: String, e: Entry) = synchronized(entries) {
        entries.put(key, e)?.let { bytes -= it.body.size }
        bytes += e.body.size
        val it = entries.entries.iterator()
        while (bytes > BUDGET && it.hasNext()) {
            bytes -= it.next().value.body.size
            it.remove()
        }
    }

    /**
     * An in-memory body behind a buffered source, like a network body: NiceHttp's .text reads
     * `.source` off it by reflection and logs a failure for a bare Buffer.
     */
    private fun bodyOf(data: ByteArray, type: MediaType?): ResponseBody =
        (Buffer().write(data) as Source).buffer().asResponseBody(type, data.size.toLong())

    private fun Entry.toResponse(req: Request): Response {
        val now = System.currentTimeMillis()
        return Response.Builder().request(req).protocol(protocol).code(code).message(message).headers(headers)
            .body(bodyOf(body, contentType)).sentRequestAtMillis(now).receivedResponseAtMillis(now).build()
    }

    /** Extension clients' interceptor: the network's answer, or a cached copy for the metadata hosts. */
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        return fetch(req) { chain.proceed(req) }
    }

    /** [proceed]'s response, or a cached copy of it for the metadata hosts. */
    fun fetch(req: Request, proceed: () -> Response): Response {
        if (!cacheable(req)) return proceed()
        val key = keyOf(req)
        lookup(key)?.let { return it.toResponse(req) }
        val mine = CompletableFuture<Entry?>()
        val other = inFlight.putIfAbsent(key, mine)
        if (other != null) {
            val shared = runCatching { other.get(20, TimeUnit.SECONDS) }.getOrNull()
            return shared?.toResponse(req) ?: proceed()
        }
        var entry: Entry? = null
        try {
            val resp = proceed()
            val body = resp.body
            if (resp.code != 200 || resp.header("Set-Cookie") != null || body == null || body.contentLength() > MAX_BODY) return resp
            val data = body.bytes()
            val headers = resp.headers.newBuilder().removeAll("Content-Length").build()
            val e = Entry(System.currentTimeMillis(), resp.protocol, resp.code, resp.message, headers, body.contentType(), data)
            if (data.size <= MAX_BODY) {
                store(key, e)
                entry = e
            }
            return resp.newBuilder().body(bodyOf(data, body.contentType())).build()
        } finally {
            mine.complete(entry)
            inFlight.remove(key, mine)
        }
    }

    fun clear() = synchronized(entries) { entries.clear(); bytes = 0 }
}
