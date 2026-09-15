package com.cncverse.stremiobridge.network

import com.cncverse.stremiobridge.state.ServerState
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * FlareSolverr integration for headless Cloudflare challenge solving.
 *
 * FlareSolverr is a self-hosted service (Docker or standalone) that launches
 * its own headless browser to solve Turnstile / JS challenges and returns
 * `cf_clearance` cookies.
 *
 * **Setup on VPS:**
 * ```
 * docker run -d --name flaresolverr -p 8191:8191 ghcr.io/flaresolverr/flaresolverr:latest
 * ```
 * Then set the URL in the admin panel → Settings → FlareSolverr, or via the
 * `CNC_FLARESOLVERR_URL=http://127.0.0.1:8191` environment variable.
 *
 * When [solverrUrl] is non-blank [CloudflareKiller] calls [solve] first and
 * only falls back to the interactive browser CDP solver if FlareSolverr fails.
 *
 * API reference: https://github.com/FlareSolverr/FlareSolverr
 */
object FlareSolverrBypass {

    /**
     * HTTP base URL of the FlareSolverr instance, e.g. "http://127.0.0.1:8191".
     * Empty string = disabled. Can also be configured via [CNC_FLARESOLVERR_URL].
     */
    @Volatile
    var solverrUrl: String = System.getenv("CNC_FLARESOLVERR_URL")?.trim() ?: ""

    val isEnabled: Boolean get() = solverrUrl.isNotBlank()

    private val mapper = jacksonObjectMapper()

    // Dedicated client with long timeout — FlareSolverr can take up to 120 s
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    data class SolveResult(
        val cookies: Map<String, String>,
        val userAgent: String,
        val html: String = "",
    )

    /**
     * Sends a `request.get` command to FlareSolverr for [targetUrl].
     *
     * @param cookies Optionally pre-seed the FlareSolverr browser session with these
     *   cookies (e.g. a previously-solved cf_clearance). When provided FlareSolverr
     *   skips re-solving the Turnstile challenge and returns the page immediately,
     *   using the correct Chromium TLS fingerprint.
     * @return [SolveResult] with clearance cookies + UA on success, `null` on
     * failure or when FlareSolverr is not configured.
     */
    suspend fun solve(targetUrl: String, cookies: Map<String, String> = emptyMap()): SolveResult? {
        val baseUrl = solverrUrl.trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                ServerState.info("[FlareSolverr] Solving CF challenge for $targetUrl via $baseUrl" +
                    if (cookies.isNotEmpty()) " (${cookies.size} pre-seeded cookies)" else "")

                val cookieList = cookies.entries.map { mapOf("name" to it.key, "value" to it.value) }
                val body = mutableMapOf<String, Any>(
                    "cmd"        to "request.get",
                    "url"        to targetUrl,
                    "maxTimeout" to 120000,
                )
                if (cookieList.isNotEmpty()) body["cookies"] = cookieList

                val payload = mapper.writeValueAsString(body)

                val request = Request.Builder()
                    .url("$baseUrl/v1")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    ServerState.warn("[FlareSolverr] HTTP ${response.code} ${response.message}")
                    return@withContext null
                }

                val json = mapper.readTree(response.body?.string() ?: return@withContext null)
                val status = json["status"]?.asText() ?: ""

                if (status != "ok") {
                    val msg = json["message"]?.asText() ?: "(no message)"
                    ServerState.warn("[FlareSolverr] status=$status — $msg")
                    return@withContext null
                }

                val solution = json["solution"] ?: run {
                    ServerState.warn("[FlareSolverr] Missing 'solution' field in response")
                    return@withContext null
                }

                val ua   = solution["userAgent"]?.asText() ?: return@withContext null
                val html = solution["response"]?.asText() ?: ""

                val cookies = mutableMapOf<String, String>()
                solution["cookies"]?.forEach { cookie ->
                    val name  = cookie["name"]?.asText()  ?: return@forEach
                    val value = cookie["value"]?.asText() ?: return@forEach
                    if (name.isNotBlank()) cookies[name] = value
                }

                ServerState.info(
                    "[FlareSolverr] ✓ Solved! cookies=${cookies.keys} ua=${ua.take(60)}…"
                )
                SolveResult(cookies = cookies, userAgent = ua, html = html)
            } catch (e: Exception) {
                ServerState.warn("[FlareSolverr] Error solving $targetUrl: ${e.message}")
                null
            }
        }
    }
}
