package com.lagradost.cloudstream3.network

import com.cncverse.stremiobridge.state.ServerState
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop JVM CloudflareKiller — plain-HTTP edition.
 *
 * No FlareSolverr, no CDP browser. Strategy:
 *  1. Inject any previously-saved cf_clearance / session cookies.
 *  2. Fire the request normally.
 *  3. If a Cloudflare challenge page comes back (403/503 + body markers):
 *       • Parse and save any Set-Cookie headers from the response.
 *       • Strip "Just a moment…" boilerplate from the HTML body.
 *       • Return the response as HTTP 200 so plugins see usable content.
 *
 * This keeps the plugin runtime happy without requiring an external solver
 * or interactive browser session.
 */
class CloudflareKiller : Interceptor {
    companion object {
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        private val CHALLENGE_CODES   = listOf(403, 503)
        /** Challenge pages are small HTML; never buffer more than this. */
        private const val MAX_CHALLENGE_PEEK_BYTES = 2L * 1024 * 1024

        val savedCookies: MutableMap<String, Map<String, String>> = ConcurrentHashMap()
        val savedUserAgents: MutableMap<String, String>           = ConcurrentHashMap()

        fun getApexDomain(host: String): String {
            val clean = host.lowercase().trim()
            val parts = clean.split(".")
            if (parts.size <= 2) return clean
            val twoPartTlds = setOf(
                "co.uk", "org.uk", "com.au", "net.au", "co.jp",
                "com.br", "co.in", "net.in", "org.in"
            )
            val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
            return if (twoPartTlds.contains(lastTwo) && parts.size > 3) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        }

        fun getSavedCookies(host: String): Map<String, String> =
            savedCookies[host] ?: savedCookies[getApexDomain(host)] ?: emptyMap()

        fun getSavedUserAgent(host: String): String? =
            savedUserAgents[host] ?: savedUserAgents[getApexDomain(host)]

        fun isTlsBound(host: String): Boolean = false   // no CDP — never TLS-bound

        fun isImageAsset(url: okhttp3.HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            return path.endsWith(".webp") || path.endsWith(".jpg")  ||
                   path.endsWith(".jpeg") || path.endsWith(".png")  ||
                   path.endsWith(".gif")  || path.endsWith(".svg")  ||
                   path.endsWith(".ico")  || path.endsWith(".avif")
        }

        fun isStaticAsset(url: okhttp3.HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            return isImageAsset(url) ||
                   path.endsWith(".mp4")  || path.endsWith(".m3u8") ||
                   path.endsWith(".ts")   || path.endsWith(".mpd")
        }

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").mapNotNull { pair ->
                val split = pair.split("=", limit = 2)
                val key   = split.getOrNull(0)?.trim().orEmpty()
                val value = split.getOrNull(1)?.trim().orEmpty()
                if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
            }.toMap()

        fun saveClearance(host: String, cookies: Map<String, String>, userAgent: String) {
            val apex = getApexDomain(host)
            savedCookies[host]  = cookies
            savedCookies[apex]  = cookies
            savedUserAgents[host] = userAgent
            savedUserAgents[apex] = userAgent
        }

        fun clearClearanceForDomain(domain: String) {
            val clean = domain.lowercase().trimStart('.')
            val apex  = getApexDomain(clean)
            savedCookies.remove(clean);    savedCookies.remove(apex)
            savedUserAgents.remove(clean); savedUserAgents.remove(apex)
        }

        fun clearAllClearance() {
            savedCookies.clear()
            savedUserAgents.clear()
        }

        // ── CF challenge body detection ───────────────────────────────────────

        private val CF_BODY_MARKERS = listOf(
            "Just a moment...",
            "challenge-platform",
            "cf-chl-",
            "_cf_chl_opt",
            "turnstile",
        )

        fun isChallengeBody(body: String): Boolean =
            CF_BODY_MARKERS.any { body.contains(it, ignoreCase = true) }

        /**
         * Strips Cloudflare challenge boilerplate from [html].
         * Removes the <script> blocks that drive the challenge JS,
         * the "Just a moment..." heading, and the meta-refresh redirect.
         * What remains is the bare skeleton — enough to stop plugins
         * from crashing on an empty body.
         */
        fun stripChallengeHtml(html: String): String {
            var result = html
            // Remove <script> blocks containing CF challenge tokens
            result = Regex("""<script[^>]*>[\s\S]*?(?:_cf_chl_opt|challenge-platform|cf-chl-)[\s\S]*?</script>""",
                RegexOption.IGNORE_CASE).replace(result, "")
            // Remove "Just a moment..." text (heading / title variants)
            result = Regex("""Just a moment\.{0,3}""", RegexOption.IGNORE_CASE).replace(result, "")
            // Remove meta-refresh that redirects to the challenge URL
            result = Regex("""<meta[^>]+http-equiv=["\']refresh["\'][^>]*>""",
                RegexOption.IGNORE_CASE).replace(result, "")
            return result.trim()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host    = request.url.host

        // ── 1. Inject saved cookies / UA ────────────────────────────────────
        val cookies = getSavedCookies(host)
        val ua      = getSavedUserAgent(host)
        val response = proceed(chain, request, cookies, ua)

        // ── 2. Skip static assets — we never modify them ────────────────────
        if (isStaticAsset(request.url)) return response

        // Only a 403/503 or an explicit cf-mitigated header can be a challenge.
        // Everything else (notably 200 video/file downloads from Cloudflare
        // Workers) passes straight through — peeking those buffered whole
        // multi-hundred-MB files in memory and caused heap OOM crashes.
        val isCfMitigated = response.header("cf-mitigated")
            ?.equals("challenge", ignoreCase = true) == true
        if (!isCfMitigated && response.code !in CHALLENGE_CODES) return response

        val contentType = response.header("content-type")?.lowercase().orEmpty()
        if (!isCfMitigated && contentType.isNotEmpty() &&
            !contentType.contains("html") && !contentType.contains("text")) return response

        // ── 3. Peek (bounded) body to check for challenge markers ────────────
        val rawBody = try { response.peekBody(MAX_CHALLENGE_PEEK_BYTES).string() } catch (_: Exception) { "" }
        val looksLikeChallenge = isCfMitigated || isChallengeBody(rawBody)

        if (!looksLikeChallenge) return response
        response.close()   // replaced below — release the connection

        ServerState.info("[CF] Challenge page detected for $host (HTTP ${response.code}) — stripping & returning 200")

        // ── 4. Extract any cookies from the challenge response ───────────────
        val responseCookies = mutableMapOf<String, String>()
        response.headers.values("Set-Cookie").forEach { setCookieHeader ->
            // "name=value; Path=/; ..." — take only the name=value part
            val nameValue = setCookieHeader.split(";").firstOrNull()?.trim() ?: return@forEach
            val parts = nameValue.split("=", limit = 2)
            val name  = parts.getOrNull(0)?.trim() ?: return@forEach
            val value = parts.getOrNull(1)?.trim() ?: ""
            if (name.isNotBlank()) responseCookies[name] = value
        }

        if (responseCookies.isNotEmpty()) {
            val merged = (cookies + responseCookies).toMap()
            val apex   = getApexDomain(host)
            savedCookies[host] = merged
            savedCookies[apex] = merged
            ServerState.info("[CF] Saved ${responseCookies.size} cookie(s) from challenge response: ${responseCookies.keys}")
        }

        // ── 5. Return cleaned body as 200 ────────────────────────────────────
        val cleaned   = stripChallengeHtml(rawBody)
        val mediaType = "text/html; charset=utf-8".toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("content-type", "text/html; charset=utf-8")
            .body(cleaned.toByteArray(Charsets.UTF_8).toResponseBody(mediaType))
            .build()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun proceed(
        chain: Interceptor.Chain,
        request: Request,
        cookies: Map<String, String>,
        userAgent: String?,
    ): Response {
        val builder = request.newBuilder()
        if (userAgent != null) builder.header("user-agent", userAgent)
        val existingCookies = request.header("cookie")?.let { parseCookieMap(it) } ?: emptyMap()
        val finalCookies    = existingCookies + cookies
        if (finalCookies.isNotEmpty()) {
            builder.header("cookie", finalCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return chain.proceed(builder.build())
    }
}
