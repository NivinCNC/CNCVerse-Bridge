package com.cncverse.stremiobridge.server.hls

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import com.cncverse.stremiobridge.state.ServerState

fun Application.installMpdProxyRoutes() {
    val mpdConverter = MpdConverter()

    routing {
        get("/proxy/mpd/manifest.m3u8") { handleMpdProxy(call, mpdConverter) }
        get("/decrypt") { DecryptHandler.handleDecryptSegment(call) }
        get("/init_decrypt") { DecryptHandler.handleInitDecrypt(call) }

        // ExoPlayer sends HEAD requests to probe content type and length before GET.
        // By calling the actual handler, Ktor will generate the content, calculate
        // the correct Content-Length header, and omit the body for the HEAD response.
        // For decrypt endpoints, this also acts as a prefetch since results are cached.
        head("/proxy/mpd/manifest.m3u8") { handleMpdProxy(call, mpdConverter) }
        head("/decrypt") { DecryptHandler.handleDecryptSegment(call) }
        head("/init_decrypt") { DecryptHandler.handleInitDecrypt(call) }
        get("/proxy/subtitle") { handleSubtitleProxy(call) }
        head("/proxy/subtitle") { handleSubtitleProxy(call) }
    }
}

private suspend fun handleMpdProxy(call: ApplicationCall, converter: MpdConverter) {
    var mpdUrl: String? = null
    try {
        val destinationUrl = call.parameters["d"] ?: call.parameters["url"]
            ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'd' or 'url' parameter"))

        // Strip trailing '?' or '&' — Jio/Akamai stream URLs sometimes end with a bare '?'
        // which gets URL-decoded and included in the __hdnea__ HMAC query value, corrupting
        // the signature and causing an immediate HTTP 403 from the CDN.
        val (decodedUrl, pipeHeaders) = splitPipeHeaders(URLDecoder.decode(destinationUrl, "UTF-8"))
        mpdUrl = decodedUrl
        val repId = call.request.queryParameters["rep_id"]
        val clearKey = call.request.queryParameters["clearkey"]
            ?: buildClearKey(call.request.queryParameters["key_id"], call.request.queryParameters["key"])

        val queryParams = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        // Headers the extension set explicitly (h_*) win over ones riding on the URL
        val explicit = HttpClientManager.extractHeadersFromParams(queryParams)
        val customHeaders = pipeHeaders.filterKeys { k -> explicit.keys.none { it.equals(k, ignoreCase = true) } } + explicit

        // Live manifests refresh every few seconds for every viewer — fetch once, share the result
        val mpdContent = SegmentCache.getMpd(decodedUrl) ?: SegmentCache.singleFlight("mpd:" + decodedUrl) {
            SegmentCache.getMpd(decodedUrl) ?: HttpClientManager.getString(url = decodedUrl, headers = customHeaders, proxyUrl = null)
                .also { SegmentCache.putMpd(decodedUrl, it) }
        }

        var proxyBase = ServerState.streamBaseUrl ?: ("http://${call.request.host()}:" + (ServerState.serverPort))
        val activeTunnel = ServerState.activeTunnelUrl.value
        if (ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
            proxyBase = activeTunnel
        }
        val headerParams = customHeaders.entries.joinToString("") { (key, value) ->
            "&h_${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        val hlsContent = if (repId != null) {
            converter.convertMediaPlaylist(mpdContent, repId, proxyBase, decodedUrl, headerParams, clearKey)
        } else {
            converter.convertMasterPlaylist(mpdContent, proxyBase, decodedUrl, headerParams, clearKey)
        }

        if (call.request.httpMethod == HttpMethod.Head) {
            val contentBytes = hlsContent.toByteArray(Charsets.UTF_8)
            call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                override val contentLength: Long = contentBytes.size.toLong()
                override val contentType: ContentType = ContentType.parse("application/vnd.apple.mpegurl")
                override val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.AccessControlAllowOrigin, "*")
                    append(HttpHeaders.CacheControl, "no-store")
                }
            })
            return
        }

        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(hlsContent, ContentType.parse("application/vnd.apple.mpegurl"))

    } catch (e: kotlinx.coroutines.CancellationException) {
    } catch (e: HttpClientManager.GeoBlockedException) {
        respondGeoBlocked(call, e)
    } catch (e: Exception) {
        ServerState.warn("MPD_PROXY_ERR: ${e.message} | url=$mpdUrl")
        try { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "MPD proxy error: ${e.message}")) } catch (_: Exception) {}
    }
}

/**
 * Splits a Kodi-style `url|Header=value&Header2=value` into the URL and its headers.
 * LivXow's JioTV playlist ships `index.mpd?__hdnea__=…&xxx=%7Ccookie=…`: once decoded the
 * `|` is an illegal URI character and every channel failed. A dangling empty parameter
 * left in front of the pipe (`&xxx=`) and a trailing `?`/`&` are dropped too — Jio/Akamai
 * URLs sometimes end with a bare '?' that would otherwise corrupt the __hdnea__ HMAC → 403.
 */
internal fun splitPipeHeaders(url: String): Pair<String, Map<String, String>> {
    val pipe = url.indexOf('|')
    if (pipe < 0) return url.trimEnd('?', '&') to emptyMap()
    val headers = url.substring(pipe + 1).split('&').mapNotNull { pair ->
        val kv = pair.split('=', limit = 2)
        val key = kv[0].trim()
        if (kv.size == 2 && key.isNotEmpty()) key to kv[1].trim().removeSurrounding("\"") else null
    }.toMap()
    val clean = url.substring(0, pipe).replace(EMPTY_LAST_PARAM, "").trimEnd('?', '&')
    return clean to headers
}

private val EMPTY_LAST_PARAM = Regex("[?&][^=&?]+=$")

private fun buildClearKey(keyId: String?, key: String?): String? {
    if (keyId.isNullOrBlank() || key.isNullOrBlank()) return null
    return "$keyId:$key"
}

private suspend fun handleSubtitleProxy(call: ApplicationCall) {
    try {
        val destinationUrl = call.request.queryParameters["url"]
            ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'url' parameter"))
            
        val decodedUrl = URLDecoder.decode(destinationUrl, "UTF-8")
        val queryParams = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        val customHeaders = HttpClientManager.extractHeadersFromParams(queryParams)

        ServerState.info("SUBTITLE_PROXY: url=${decodedUrl.take(100)}")

        if (call.request.httpMethod == HttpMethod.Head) {
            call.respond(HttpStatusCode.OK)
            return
        }

        val content = withContext(Dispatchers.IO) {
            HttpClientManager.getString(url = decodedUrl, headers = customHeaders, proxyUrl = null)
        }

        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        
        val contentType = when {
            decodedUrl.contains(".vtt", ignoreCase = true) -> ContentType.parse("text/vtt")
            decodedUrl.contains(".srt", ignoreCase = true) -> ContentType.parse("application/x-subrip")
            else -> ContentType.Text.Plain
        }
        call.respondText(content, contentType)

    } catch (e: kotlinx.coroutines.CancellationException) {
    } catch (e: HttpClientManager.GeoBlockedException) {
        respondGeoBlocked(call, e)
    } catch (e: Exception) {
        ServerState.warn("SUBTITLE_PROXY_ERR: ${e.message}")
        try { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Subtitle proxy error: ${e.message}")) } catch (_: Exception) {}
    }
}
