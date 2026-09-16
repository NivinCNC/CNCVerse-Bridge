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
    val allParams = call.request.queryParameters.entries().joinToString(", ") { "${it.key}=${it.value.firstOrNull()?.take(40)}" }
    ServerState.info("MPD_PROXY_REQ [] params: $allParams")

    try {
        val destinationUrl = call.parameters["d"] ?: call.parameters["url"]
            ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'd' or 'url' parameter"))

        // Strip trailing '?' or '&' — Jio/Akamai stream URLs sometimes end with a bare '?'
        // which gets URL-decoded and included in the __hdnea__ HMAC query value, corrupting
        // the signature and causing an immediate HTTP 403 from the CDN.
        val decodedUrl = URLDecoder.decode(destinationUrl, "UTF-8").trimEnd('?', '&')
        val repId = call.request.queryParameters["rep_id"]
        val clearKey = call.request.queryParameters["clearkey"]
            ?: buildClearKey(call.request.queryParameters["key_id"], call.request.queryParameters["key"])

        ServerState.info("MPD_PROXY: url=${decodedUrl.take(120)}, repId=$repId, clearKey=${clearKey?.take(20)}")

        val queryParams = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        val customHeaders = HttpClientManager.extractHeadersFromParams(queryParams)
        ServerState.info("MPD_PROXY_HEADERS extracted=${customHeaders.keys.joinToString()} (from ${queryParams.keys.filter { it.startsWith("h_") }.joinToString()})")

        val mpdContent = SegmentCache.getMpd(decodedUrl) ?: withContext(Dispatchers.IO) {
            HttpClientManager.getString(url = decodedUrl, headers = customHeaders, proxyUrl = null)
                .also { SegmentCache.putMpd(decodedUrl, it) }
        }

        var proxyBase = "http://${call.request.host()}:" + (ServerState.serverPort)
        val activeTunnel = ServerState.activeTunnelUrl.value
        if (ServerState.isStremioMode.value && !activeTunnel.isNullOrBlank()) {
            proxyBase = activeTunnel
        }
        val headerParams = customHeaders.entries.joinToString("") { (key, value) ->
            "&h_${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        // Log the final proxy URL so it can be tested with VLC
        val encodedMpdUrlForLog = URLEncoder.encode(decodedUrl, "UTF-8")
        val vlcTestUrl = "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedMpdUrlForLog${if (!clearKey.isNullOrBlank()) "&clearkey=$clearKey" else ""}$headerParams"
        ServerState.info("[VLC TEST URL] $vlcTestUrl")

        val hlsContent = if (repId != null) {
            converter.convertMediaPlaylist(mpdContent, repId, proxyBase, decodedUrl, headerParams, clearKey)
                .also { ServerState.info("MPD_PROXY: Generated media playlist for rep_id=$repId, lines=${it.lines().size}") }
        } else {
            converter.convertMasterPlaylist(mpdContent, proxyBase, decodedUrl, headerParams, clearKey)
                .also { ServerState.info("MPD_PROXY: Generated master playlist, lines=${it.lines().size}") }
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
    } catch (e: Exception) {
        ServerState.warn("MPD_PROXY_ERR: ${e.message}")
        try { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "MPD proxy error: ${e.message}")) } catch (_: Exception) {}
    }
}

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
    } catch (e: Exception) {
        ServerState.warn("SUBTITLE_PROXY_ERR: ${e.message}")
        try { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Subtitle proxy error: ${e.message}")) } catch (_: Exception) {}
    }
}
