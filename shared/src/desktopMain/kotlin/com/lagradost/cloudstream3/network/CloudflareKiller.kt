package com.lagradost.cloudstream3.network

import com.cncverse.stremiobridge.network.FlareSolverrBypass
import com.cncverse.stremiobridge.network.SettledPageCache
import com.cncverse.stremiobridge.network.SystemBrowserCdpBypass
import com.cncverse.stremiobridge.state.ServerState
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop JVM counterpart of the Android CloudflareKiller.
 *
 * On desktop, instead of an embedded WebView, we launch the user's system
 * Edge/Chrome browser via the Chrome DevTools Protocol (CDP) to allow manual
 * Turnstile/Cloudflare challenge resolution. Clearance cookies are captured
 * automatically via CDP and injected into subsequent OkHttp requests.
 *
 * For TLS-fingerprint-bound hosts, the browser is kept alive as a fetch proxy.
 *
 * Set [cfBypassEnabled] = true to activate the solver (controlled by the
 * Cloudflare Solver toggle in Settings).
 */
class CloudflareKiller : Interceptor {
    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        // Track hosts currently being resolved to avoid duplicate browser launches
        private val resolvingHosts = ConcurrentHashMap.newKeySet<String>()
        // Track hosts where bypass has already failed — don't retry this session
        private val failedHosts = ConcurrentHashMap.newKeySet<String>()
        // Hosts confirmed to require browser-level TLS (cf_clearance bound to TLS fingerprint)
        val tlsBoundHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /**
         * Hosts where FlareSolverr successfully proxied after TLS rejection.
         * All future requests to these hosts are routed through FlareSolverr so we
         * never need to open a browser again — FlareSolverr's Chromium TLS fingerprint
         * matches what Cloudflare expects, and we pass the saved cf_clearance cookie
         * to skip re-solving the Turnstile challenge.
         */
        val flareSolverrBoundHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val savedCookies: MutableMap<String, Map<String, String>> = ConcurrentHashMap()
        val savedUserAgents: MutableMap<String, String> = ConcurrentHashMap()

        /** When false, challenges are logged but the solver window is NOT opened. */
        @Volatile var cfBypassEnabled: Boolean = true

        fun getApexDomain(host: String): String {
            val cleanHost = host.lowercase().trim()
            val parts = cleanHost.split(".")
            if (parts.size <= 2) return cleanHost
            val twoPartTlds = setOf("co.uk", "org.uk", "com.au", "net.au", "co.jp", "com.br", "co.in", "net.in", "org.in")
            val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
            return if (twoPartTlds.contains(lastTwo) && parts.size > 3) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        }

        fun getSavedCookies(host: String): Map<String, String> {
            return savedCookies[host] ?: savedCookies[getApexDomain(host)] ?: emptyMap()
        }

        fun getSavedUserAgent(host: String): String? {
            return savedUserAgents[host] ?: savedUserAgents[getApexDomain(host)]
        }

        fun isTlsBound(host: String): Boolean {
            return tlsBoundHosts.contains(host) || tlsBoundHosts.contains(getApexDomain(host))
        }

        fun isImageAsset(url: okhttp3.HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            return path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif")
        }

        fun isStaticAsset(url: okhttp3.HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            return isImageAsset(url) || path.endsWith(".mp4") ||
                path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mpd")
        }

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";")
                .mapNotNull { pair ->
                    val split = pair.split("=", limit = 2)
                    val key = split.getOrNull(0)?.trim().orEmpty()
                    val value = split.getOrNull(1)?.trim().orEmpty()
                    if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
                }
                .toMap()
        }

        fun saveClearance(
            host: String,
            cookies: Map<String, String>,
            userAgent: String,
        ) {
            val apex = getApexDomain(host)
            savedCookies[host] = cookies
            savedCookies[apex] = cookies
            savedUserAgents[host] = userAgent
            savedUserAgents[apex] = userAgent
        }

        fun clearClearanceForDomain(domain: String) {
            val clean = domain.lowercase().trimStart('.')
            val apex = getApexDomain(clean)
            savedCookies.remove(clean)
            savedCookies.remove(apex)
            savedUserAgents.remove(clean)
            savedUserAgents.remove(apex)
            tlsBoundHosts.remove(clean)
            tlsBoundHosts.remove(apex)
            failedHosts.remove(clean)
            failedHosts.remove(apex)
        }

        fun clearAllClearance() {
            savedCookies.clear()
            savedUserAgents.clear()
            tlsBoundHosts.clear()
            flareSolverrBoundHosts.clear()
            failedHosts.clear()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val apex = getApexDomain(host)
        val isStatic = isStaticAsset(request.url)

        ServerState.info("[CF-DBG] intercept → $host isStatic=$isStatic bypassEnabled=$cfBypassEnabled failedHosts=$failedHosts")

        // Serve from SettledPageCache if available
        val cachedPage = SettledPageCache.get(request.url.toString())
        if (cachedPage != null) {
            ServerState.info("[CF-DBG] Serving from SettledPageCache for ${request.url}")
            val bodyBytes = cachedPage.html.toByteArray(Charsets.UTF_8)
            val mediaType = "text/html; charset=utf-8".toMediaTypeOrNull()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("content-type", "text/html; charset=utf-8")
                .body(bodyBytes.toResponseBody(mediaType))
                .build()
        }

        // If an active proxy exists, an earlier bypass already succeeded — clear failure flag
        if (SystemBrowserCdpBypass.hasActiveProxy(apex)) {
            failedHosts.remove(apex)
        }

        // Skip hosts that have permanently failed this session
        if (failedHosts.contains(host) ||
            (!SystemBrowserCdpBypass.hasActiveProxy(apex) && failedHosts.contains(apex))
        ) {
            ServerState.warn("[CF-DBG] SKIP: $host is in failedHosts — clearing and retrying fresh")
            // Clear the stale failure so the next request can attempt again
            failedHosts.remove(host)
            failedHosts.remove(apex)
            return chain.proceed(request)
        }

        // (flareSolverrBound is used only as a cookie-clearing guard — no per-request proxy routing)
        val isImage = isImageAsset(request.url)
        val isStream = isStatic && !isImage

        // Route TLS-bound image requests via browser proxy (CDP path only, not used when FlareSolverr is enabled)
        if (!isStream && !FlareSolverrBypass.isEnabled && isTlsBound(host) && SystemBrowserCdpBypass.hasActiveProxy(host)) {
            val proxyResponse = fetchViaBrowserProxy(request, isBinary = isImage)
            if (proxyResponse != null) return proxyResponse
        }

        var response: Response?
        var usedSavedCookie = false

        val currentCookies = getSavedCookies(host)
        val currentUa = getSavedUserAgent(host)
        ServerState.info("[CF-DBG] savedCookies for $host: ${currentCookies.keys}")
        if (currentCookies.isNotEmpty()) {
            usedSavedCookie = true
            response = proceed(chain, request, currentCookies, currentUa)
        } else {
            response = chain.proceed(request)
        }

        val serverHeader = response.header("Server") ?: ""
        val cfMitigated = response.header("cf-mitigated") ?: ""
        val isCloudflareServer = CLOUDFLARE_SERVERS.any { serverHeader.contains(it, ignoreCase = true) }
        ServerState.info("[CF-DBG] response HTTP ${response.code} Server='$serverHeader' cf-mitigated='$cfMitigated' isStatic=$isStatic isCfServer=$isCloudflareServer")

        val isCloudflareChallenge = !isStatic && response.code in ERROR_CODES && isCloudflareServer && run {
            if (cfMitigated.equals("challenge", ignoreCase = true)) return@run true
            val bodyPreview = try { response.peekBody(4096).string() } catch (_: Exception) { "" }
            val trimmed = bodyPreview.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return@run false
            bodyPreview.contains("Just a moment...") ||
                bodyPreview.contains("challenge-platform") ||
                bodyPreview.contains("cf-chl-") ||
                bodyPreview.contains("_cf_chl_opt") ||
                bodyPreview.contains("turnstile", ignoreCase = true)
        }

        ServerState.info("[CF-DBG] isCloudflareChallenge=$isCloudflareChallenge for $host")

        if (isCloudflareChallenge) {
            ServerState.warn("[CF] Cloudflare challenge detected for $host (HTTP ${response.code})")

            if (usedSavedCookie && !isTlsBound(host)
                && !flareSolverrBoundHosts.contains(host)
                && !flareSolverrBoundHosts.contains(apex)
            ) {
                ServerState.warn("[CF-DBG] Clearing stale cookies for $host")
                savedCookies.remove(host)
                savedCookies.remove(apex)
                savedUserAgents.remove(host)
                savedUserAgents.remove(apex)
            }

            if (!cfBypassEnabled) {
                ServerState.warn("[CF] Solver is DISABLED — enable in Settings → Cloudflare Solver")
                failedHosts.add(host)
                return response
            }

            ServerState.info("[CF-DBG] failedHosts check: host=$host inFailed=${failedHosts.contains(host)} apex=$apex inFailed=${failedHosts.contains(apex)}")

            if (!failedHosts.contains(host) && !failedHosts.contains(apex)) {
                val solved = synchronized(CloudflareKiller::class.java) {
                    val existing = getSavedCookies(host)
                    if (existing.isNotEmpty()) {
                        ServerState.info("[CF-DBG] Already have cookies for $host (${existing.keys}) — skipping browser launch")
                        return@synchronized true
                    }
                    ServerState.info("[CF] Attempting FlareSolverr for $host (url=${request.url})…")
                    val fsResult = if (FlareSolverrBypass.isEnabled) {
                        kotlinx.coroutines.runBlocking {
                            FlareSolverrBypass.solve(request.url.toString())
                        }
                    } else null

                    if (fsResult != null && fsResult.cookies.isNotEmpty()) {
                        saveClearance(host, fsResult.cookies, fsResult.userAgent)
                        // Mark IMMEDIATELY so concurrent requests see this host as
                        // FlareSolverr-managed and don't clear the valid cookies.
                        tlsBoundHosts.add(host); tlsBoundHosts.add(apex)
                        flareSolverrBoundHosts.add(host); flareSolverrBoundHosts.add(apex)
                        // Cache the HTML so the calling request is served instantly
                        // from SettledPageCache without an OkHttp retry.
                        if (fsResult.html.isNotBlank()) {
                            SettledPageCache.put(request.url.toString(), fsResult.html, fsResult.userAgent)
                        }
                        return@synchronized true
                    }

                    // FlareSolverr not configured / failed
                    if (!FlareSolverrBypass.isEnabled) {
                        // CDP fallback (only when FlareSolverr is disabled)
                        ServerState.info("[CF] Opening browser CF solver for $host (url=${request.url})…")
                        kotlinx.coroutines.runBlocking {
                            SystemBrowserCdpBypass.launchManualClearance(
                                targetUrl = request.url.toString(),
                                hostName = host,
                            )
                        }
                    } else {
                        ServerState.warn("[CF] FlareSolverr failed for $host — no CDP fallback (FlareSolverr is enabled).")
                        return@synchronized false
                    }
                }

                ServerState.info("[CF-DBG] launchManualClearance returned solved=$solved")
                val solvedCookies = getSavedCookies(host)
                ServerState.info("[CF-DBG] solvedCookies for $host: ${solvedCookies.keys}")

                if (solved && solvedCookies.isNotEmpty()) {
                    val cfClearance = solvedCookies["cf_clearance"]
                    ServerState.info("[CF] cf_clearance for $host = ${cfClearance?.take(40)}…")
                    ServerState.info("[CF] Retrying request with clearance cookies…")
                    response.close()

                    val cachedAfter = SettledPageCache.get(request.url.toString())
                    if (cachedAfter != null) {
                        if (!FlareSolverrBypass.isEnabled) SystemBrowserCdpBypass.closePendingSession()
                        val bodyBytes = cachedAfter.html.toByteArray(Charsets.UTF_8)
                        val mediaType = "text/html; charset=utf-8".toMediaTypeOrNull()
                        return Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("content-type", "text/html; charset=utf-8")
                            .body(bodyBytes.toResponseBody(mediaType))
                            .build()
                    }

                    val retryResponse = proceed(chain, request, solvedCookies, getSavedUserAgent(host))
                    ServerState.info("[CF-DBG] retry response HTTP ${retryResponse.code}")
                    if (retryResponse.code !in ERROR_CODES) {
                        if (!FlareSolverrBypass.isEnabled) SystemBrowserCdpBypass.closePendingSession()
                        // OkHttp succeeded — flareSolverrBound stays as cookie-clearing guard.
                        return retryResponse
                    }

                    // OkHttp rejected — cookie expired or invalid. Clear it so the NEXT
                    // request triggers a fresh FlareSolverr solve.
                    retryResponse.close()
                    ServerState.warn("[CF] Cookie rejected for $host after retry — clearing for re-solve on next request.")
                    savedCookies.remove(host); savedCookies.remove(apex)
                    savedUserAgents.remove(host); savedUserAgents.remove(apex)
                    flareSolverrBoundHosts.remove(host); flareSolverrBoundHosts.remove(apex)
                    tlsBoundHosts.remove(host); tlsBoundHosts.remove(apex)
                } else {
                    ServerState.warn("[CF] Solver returned solved=$solved but cookies=${solvedCookies.keys} for $host — window closed or timed out.")
                    failedHosts.add(host)
                    if (host.equals(apex, ignoreCase = true)) failedHosts.add(apex)
                }
            } else {
                ServerState.warn("[CF-DBG] Skipping solver: $host is in failedHosts. Call clearAllClearance() to reset.")
            }
        }

        return response
    }

    private fun proceed(chain: Interceptor.Chain, request: Request, cookies: Map<String, String>, userAgent: String?): Response {
        val builder = request.newBuilder()
        if (userAgent != null) {
            builder.header("user-agent", userAgent)
            val chromeVersionMatch = Regex("Chrome/([0-9]+)").find(userAgent)
            val edgeVersionMatch = Regex("Edg/([0-9]+)").find(userAgent)
            val version = edgeVersionMatch?.groupValues?.get(1) ?: chromeVersionMatch?.groupValues?.get(1) ?: "133"
            val brand = if (userAgent.contains("Edg/")) {
                "\"Not(A:Brand\";v=\"99\", \"Microsoft Edge\";v=\"$version\", \"Chromium\";v=\"$version\""
            } else {
                "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$version\", \"Chromium\";v=\"$version\""
            }
            val host = request.url.host
            val apex = getApexDomain(host)
            val isStatic = isStaticAsset(request.url)
            val site = if (host.equals(apex, ignoreCase = true)) "same-origin" else "same-site"
            val mode = if (isStatic) "no-cors" else "cors"
            val dest = if (isStatic) "image" else "empty"
            builder.header("sec-ch-ua", brand)
            builder.header("sec-ch-ua-mobile", "?0")
            builder.header("sec-ch-ua-platform", "\"Windows\"")
            builder.header("sec-fetch-site", site)
            builder.header("sec-fetch-mode", mode)
            builder.header("sec-fetch-dest", dest)
            builder.header("accept-language", "en-US,en;q=0.9")
            builder.header("referer", "https://$apex/")
        }
        val existingCookies = request.header("cookie")?.let { parseCookieMap(it) } ?: emptyMap()
        val finalCookies = existingCookies + cookies
        if (finalCookies.isNotEmpty()) {
            builder.header("cookie", finalCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return chain.proceed(builder.build())
    }

    private fun fetchViaBrowserProxy(request: Request, isBinary: Boolean = false): Response? {
        val result = kotlinx.coroutines.runBlocking {
            SystemBrowserCdpBypass.fetchViaProxy(
                url = request.url.toString(),
                method = request.method,
                headers = buildMap {
                    for (name in request.headers.names()) {
                        put(name, request.header(name) ?: "")
                    }
                },
                body = request.body?.let { body ->
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readUtf8()
                },
                isBinary = isBinary,
            )
        } ?: return null

        val mediaType = (result.contentType ?: "application/octet-stream").toMediaTypeOrNull()
        val bodyBytes = result.bodyBytes ?: result.body.toByteArray(Charsets.UTF_8)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.statusCode)
            .message(if (result.statusCode in 200..299) "OK" else "Proxied")
            .body(bodyBytes.toResponseBody(mediaType))
            .build()
    }

    /**
     * Fetches [request] via FlareSolverr, passing already-saved cf_clearance cookies
     * so FlareSolverr can skip re-solving the Turnstile challenge and return the page
     * immediately using its correct Chromium TLS fingerprint.
     */
    private fun fetchViaFlareSolverr(request: Request): Response? {
        val host = request.url.host
        val savedCookiesForHost = getSavedCookies(host)
        val result = kotlinx.coroutines.runBlocking {
            FlareSolverrBypass.solve(
                targetUrl = request.url.toString(),
                cookies   = savedCookiesForHost,
            )
        } ?: return null

        // Refresh cookies if FlareSolverr returned a new clearance
        if (result.cookies.isNotEmpty()) {
            saveClearance(host, result.cookies, result.userAgent)
            if (result.html.isNotBlank()) {
                SettledPageCache.put(request.url.toString(), result.html, result.userAgent)
            }
        }

        val mediaType = "text/html; charset=utf-8".toMediaTypeOrNull()
        val bodyBytes = result.html.toByteArray(Charsets.UTF_8)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (FlareSolverr)")
            .header("content-type", "text/html; charset=utf-8")
            .body(bodyBytes.toResponseBody(mediaType))
            .build()
    }
}
