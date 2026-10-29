package com.cncverse.stremiobridge.plugin

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Every extension's TMDB requests go through here (plugin client interceptor).
 *
 * TMDB answers in 0.4–0.9 s, even ten at once, yet inside the bridge its calls took
 * 5–8 s: all extensions shared one HTTP/2 connection and the 8 per-host dispatcher
 * slots sat waiting for headers — 4KHDHub's detail pages, which ask TMDB for metadata, took
 * 9–21 s. So TMDB gets:
 *  - its own client and pool, HTTP/1.1 (one request per connection, nothing queues behind a
 *    slow stream);
 *  - a shared answer cache: many extensions ask for the same titles (a stream search fans out to
 *    dozens), so 200 answers are kept for an hour and identical requests in flight share one call.
 *
 * api.themoviedb.org has its connections reset about half the time (India: the
 * hostname is filtered on many networks); api.tmdb.org is the same API and answers reliably.
 * Plugins hard-code the former, so their requests are pointed at the latter.
 */
internal object TmdbGateway : Interceptor {
    private const val TTL_MS = 60 * 60_000L
    private const val MAX_ENTRIES = 4_000
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val MAX_BODY = 1024 * 1024

    private class Entry(val at: Long, val code: Int, val contentType: String?, val body: ByteArray)

    private val cache = java.util.LinkedHashMap<String, Entry>(256, 0.75f, true)
    private var cacheBytes = 0L
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Entry?>>()
    private val hits = java.util.concurrent.atomic.AtomicLong()
    private val shared = java.util.concurrent.atomic.AtomicLong()
    private val misses = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastStatsLog = System.currentTimeMillis()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dns(com.cncverse.stremiobridge.network.AdaptiveHostDns)
            .connectionPool(okhttp3.ConnectionPool(32, 60, TimeUnit.SECONDS))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun isTmdb(host: String) =
        host.equals("api.themoviedb.org", ignoreCase = true) || host.equals("api.tmdb.org", ignoreCase = true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isTmdb(original.url.host)) return chain.proceed(original)
        val req = original.newBuilder().url(original.url.newBuilder().host("api.tmdb.org").build()).build()
        if (req.method != "GET") return client.newCall(req).execute().withRequest(original)

        val key = buildString {
            append(req.url)
            req.header("Authorization")?.let { append('|').append(it) }
            req.header("Accept-Language")?.let { append('|').append(it) }
        }
        logStats()
        cached(key)?.let { hits.incrementAndGet(); return it.toResponse(original) }

        val mine = CompletableFuture<Entry?>()
        val pending = inFlight.putIfAbsent(key, mine)
        if (pending != null) {
            // The same request is already on its way: wait for that answer
            val e = try {
                pending.get(25, TimeUnit.SECONDS)
            } catch (ex: ExecutionException) {
                null
            } catch (ex: java.util.concurrent.TimeoutException) {
                null
            }
            if (e != null) { shared.incrementAndGet(); return e.toResponse(original) }
            misses.incrementAndGet()
            return client.newCall(req).execute().withRequest(original)
        }
        misses.incrementAndGet()
        try {
            val resp = client.newCall(req).execute()
            val len = resp.body.contentLength()
            if (resp.code != 200 || len > MAX_BODY) {
                mine.complete(null)
                return resp.withRequest(original)
            }
            val bytes = resp.use { it.body.bytes() }
            val e = Entry(System.currentTimeMillis(), resp.code, resp.header("Content-Type"), bytes)
            if (bytes.size <= MAX_BODY) store(key, e)
            mine.complete(e)
            return e.toResponse(original)
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun cached(key: String): Entry? = synchronized(cache) {
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            cache.remove(key); cacheBytes -= e.body.size
            return null
        }
        e
    }

    private fun store(key: String, e: Entry) = synchronized(cache) {
        cache.put(key, e)?.let { cacheBytes -= it.body.size }
        cacheBytes += e.body.size
        val it = cache.entries.iterator()
        while ((cache.size > MAX_ENTRIES || cacheBytes > MAX_BYTES) && it.hasNext()) {
            cacheBytes -= it.next().value.body.size
            it.remove()
        }
    }

    private fun Entry.toResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .apply { contentType?.let { header("Content-Type", it) } }
            .body(body.toResponseBody(contentType?.toMediaTypeOrNull()))
            .build()

    /** The plugin sees the request it sent (api.themoviedb.org), as before. */
    private fun Response.withRequest(request: Request): Response = newBuilder().request(request).build()

    /** One line every 10 min: how much of the TMDB traffic the cache answered. */
    private fun logStats() {
        val now = System.currentTimeMillis()
        if (now - lastStatsLog < 10 * 60_000L) return
        lastStatsLog = now
        val (n, kb) = synchronized(cache) { cache.size to cacheBytes / 1024 }
        com.cncverse.stremiobridge.state.ServerState.info(
            "[TMDB] cache: ${hits.get()} hits, ${shared.get()} shared in-flight, ${misses.get()} sent — $n answers, $kb KB"
        )
    }
}
