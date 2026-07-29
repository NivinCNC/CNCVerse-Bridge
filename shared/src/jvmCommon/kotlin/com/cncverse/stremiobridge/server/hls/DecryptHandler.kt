package com.cncverse.stremiobridge.server.hls





import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.*
import com.cncverse.stremiobridge.state.ServerState
import java.net.URLDecoder

/**
 * Handler for segment decryption endpoints.
 *
 * FIX VLC/STREMIO:
 * - VLC requires init segment (EXT-X-MAP) to be served SEPARATELY from media segments
 * - Each media segment should NOT include init (excludeInit = true)
 * - Init is served via /init_decrypt endpoint referenced by EXT-X-MAP in playlist
 *
 * This matches EasyProxy behavior where decrypt returns only moof+mdat (no ftyp/moov)
 */
object DecryptHandler {

    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Handle /decrypt endpoint - decrypts CENC segments.
     *
     * VLC FIX: Uses excludeInit=true so segments don't include init atoms.
     * Init is served separately via /init_decrypt and referenced by EXT-X-MAP.
     */
    suspend fun handleDecryptSegment(call: ApplicationCall) {
        val reqPath = call.request.path()
        ServerState.info("DECRYPT_REQ [$reqPath] starting...")

        SegmentCache.cleanup()

        try {
            val segmentUrl = call.request.queryParameters["url"]
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'url' parameter"))

            val initUrl = call.request.queryParameters["init_url"]
            val keyId = call.request.queryParameters["key_id"]
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'key_id' parameter"))
            val key = call.request.queryParameters["key"]
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'key' parameter"))

            // Parameters for URL refresh if 403 (expired token)
            val mpdUrl = call.request.queryParameters["mpd_url"]?.let { URLDecoder.decode(it, "UTF-8") }
            val repId = call.request.queryParameters["rep_id"]
            val segNum = call.request.queryParameters["seg_num"]?.toIntOrNull()

            var decodedSegmentUrl = URLDecoder.decode(segmentUrl, "UTF-8")
            var decodedInitUrl = initUrl?.let { URLDecoder.decode(it, "UTF-8") }

            // Check cache first
            val segmentCacheKey = "${decodedSegmentUrl.substringBefore("?")}_${keyId}"
            val cached = SegmentCache.getSegment(segmentCacheKey)
            if (cached != null) {
                ServerState.info("DECRYPT: Cache hit for segment")
                respondBytesOrHead(call, cached, ContentType.parse("video/mp4"), false)
                return
            }

            // Extract custom headers
            val queryParams = call.request.queryParameters.entries()
                .associate { it.key to it.value.firstOrNull().orEmpty() }
            val customHeaders = HttpClientManager.extractHeadersFromParams(queryParams)
            val proxyUrl: String? = null

            withContext(Dispatchers.IO) {
                // Fetch init and segment
                val (initContent, segmentContent) = try {
                    fetchSegments(decodedInitUrl, decodedSegmentUrl, customHeaders, proxyUrl)
                } catch (e: Exception) {
                    // If 403 and we have refresh params, try with fresh URLs
                    if (e.message?.contains("403") == true && mpdUrl != null && repId != null && segNum != null) {
                        ServerState.info("DECRYPT: URL expired, refreshing from MPD")
                        SegmentCache.invalidateMpd(mpdUrl)
                        val freshUrls = getFreshSegmentUrls(mpdUrl, repId, segNum, customHeaders, proxyUrl)
                        if (freshUrls != null) {
                            decodedSegmentUrl = freshUrls.first
                            decodedInitUrl = freshUrls.second
                            fetchSegments(decodedInitUrl, decodedSegmentUrl, customHeaders, proxyUrl)
                        } else {
                            throw e
                        }
                    } else {
                        throw e
                    }
                }

                ServerState.info("DECRYPT: Fetched init=${initContent?.size ?: 0} bytes, segment=${segmentContent.size} bytes")

                // VLC/Stremio FIX: excludeInit=true - segments should NOT include init
                // Init is served separately via EXT-X-MAP pointing to /init_decrypt
                // This prevents conflict between cleaned init (EXT-X-MAP) and encrypted init in segments
                val decrypted = try {
                    CencDecryptor.decryptSegment(
                        initSegment = initContent,
                        mediaSegment = segmentContent,
                        keyIdHex = keyId,
                        keyHex = key,
                        excludeInit = true  // VLC FIX: exclude init, use EXT-X-MAP instead
                    )
                } catch (e: Exception) {
                    ServerState.warn("DECRYPT_ERR: Decryption failed: ${e.message}")
                    // Fallback: return raw segment
                    segmentContent
                }

                ServerState.info("DECRYPT: Decrypted segment, output=${decrypted.size} bytes (excludeInit=true, init via EXT-X-MAP)")

                // Cache the decrypted segment
                SegmentCache.putSegment(segmentCacheKey, decrypted)

                // Prefetch next segments
                prefetchNextSegments(
                    currentSegmentUrl = decodedSegmentUrl,
                    initUrl = decodedInitUrl,
                    keyId = keyId,
                    key = key,
                    headers = customHeaders,
                    proxyUrl = proxyUrl
                )

                ServerState.info("DECRYPT_RESPONSE: Sending ${decrypted.size} bytes as video/mp4")

                respondBytesOrHead(call, decrypted, ContentType.parse("video/mp4"), false)
            }

        } catch (e: CancellationException) {
            // Client disconnect - ignore
        } catch (e: Exception) {
            ServerState.warn("DECRYPT_ERR: ${e.message}")
            try {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Decrypt error: ${e.message}")
                )
            } catch (_: Exception) { }
        }
    }

    /**
     * Handle /init_decrypt endpoint - serves cleaned init segment.
     *
     * VLC FIX: This endpoint is referenced by EXT-X-MAP in the playlist.
     * Returns init segment with encryption metadata removed.
     */
    suspend fun handleInitDecrypt(call: ApplicationCall) {
        val reqPath = call.request.path()
        ServerState.info("INIT_DECRYPT_REQ [$reqPath] starting...")

        SegmentCache.cleanup()

        try {
            val initUrl = call.request.queryParameters["url"]
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'url' parameter"))

            val decodedInitUrl = URLDecoder.decode(initUrl, "UTF-8")
            ServerState.info("INIT_DECRYPT: url=${decodedInitUrl.take(100)}")

            // DEBUG: skip cache to test with original init
            val useOriginalInit = false // DEBUG FLAG - set to false for production

            // Check cleaned init cache (skip if testing with original)
            val cacheKey = "cleaned_${decodedInitUrl.substringBefore("?")}"
            if (!useOriginalInit) {
                val cachedCleanedInit = SegmentCache.getCleanedInit(cacheKey)
                if (cachedCleanedInit != null) {
                    ServerState.info("INIT_DECRYPT: Cache hit")
                    respondBytesOrHead(call, cachedCleanedInit, ContentType.parse("video/mp4"), true)
                    return
                }
            }

            val queryParams = call.request.queryParameters.entries()
                .associate { it.key to it.value.firstOrNull().orEmpty() }
            val customHeaders = HttpClientManager.extractHeadersFromParams(queryParams)
            val proxyUrl: String? = null

            withContext(Dispatchers.IO) {
                val initCacheKey = decodedInitUrl.substringBefore("?")

                // Try cache first
                val initContent = SegmentCache.getInitSegment(initCacheKey) ?: run {
                    val fetched = HttpClientManager.getBytes(
                        url = decodedInitUrl,
                        headers = customHeaders,
                        proxyUrl = proxyUrl
                    )
                    SegmentCache.putInitSegment(initCacheKey, fetched)
                    fetched
                }

                ServerState.info("INIT_DECRYPT: Fetched init=${initContent.size} bytes")

                // DEBUG: Try serving original init without cleaning to test VLC
                // Remove encryption metadata
                val cleanedInit = InitSegmentCleaner.removeEncryptionMetadata(initContent)
                ServerState.info("INIT_DECRYPT: Cleaned init=${cleanedInit.size} bytes")

                // Use original or cleaned based on debug flag set above
                val finalInit = if (useOriginalInit) {
                    ServerState.warn("INIT_DECRYPT: DEBUG - Using ORIGINAL init (${initContent.size} bytes)")
                    initContent
                } else {
                    cleanedInit
                }

                SegmentCache.putCleanedInit(cacheKey, finalInit)

                respondBytesOrHead(call, finalInit, ContentType.parse("video/mp4"), true)
            }

        } catch (e: CancellationException) {
            // Client disconnect - ignore
        } catch (e: Exception) {
            ServerState.warn("INIT_DECRYPT_ERR: ${e.message}")
            try {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "InitDecrypt error: ${e.message}")
                )
            } catch (_: Exception) { }
        }
    }

    private suspend fun fetchSegments(
        initUrl: String?,
        segmentUrl: String,
        headers: Map<String, String>,
        proxyUrl: String?
    ): Pair<ByteArray?, ByteArray> {
        return coroutineScope {
            val initDeferred = async {
                initUrl?.let { url ->
                    val cacheKey = url.substringBefore("?")
                    SegmentCache.getInitSegment(cacheKey) ?: run {
                        val fetched = HttpClientManager.getBytes(url = url, headers = headers, proxyUrl = proxyUrl)
                        SegmentCache.putInitSegment(cacheKey, fetched)
                        fetched
                    }
                }
            }
            val segmentDeferred = async {
                HttpClientManager.getBytes(url = segmentUrl, headers = headers, proxyUrl = proxyUrl)
            }
            Pair(initDeferred.await(), segmentDeferred.await())
        }
    }

    private suspend fun getFreshSegmentUrls(
        mpdUrl: String,
        repId: String,
        segNum: Int,
        headers: Map<String, String>,
        proxyUrl: String?
    ): Pair<String, String?>? {
        return try {
            val mpdContent = HttpClientManager.getString(url = mpdUrl, headers = headers, proxyUrl = proxyUrl)

            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(java.io.ByteArrayInputStream(mpdContent.toByteArray(Charsets.UTF_8)))
            val mpd = doc.documentElement

            val baseUrl = mpdUrl.substringBeforeLast('/') + "/"

            val representations = mpd.getElementsByTagName("Representation")
            for (i in 0 until representations.length) {
                val rep = representations.item(i) as org.w3c.dom.Element
                if (rep.getAttribute("id") == repId) {
                    val adaptationSet = rep.parentNode as? org.w3c.dom.Element

                    val segmentTemplate = rep.getElementsByTagName("SegmentTemplate").item(0) as? org.w3c.dom.Element
                        ?: adaptationSet?.getElementsByTagName("SegmentTemplate")?.item(0) as? org.w3c.dom.Element
                        ?: return null

                    val mediaTemplate = segmentTemplate.getAttribute("media") ?: return null
                    val initTemplate = segmentTemplate.getAttribute("initialization")
                    val bandwidth = rep.getAttribute("bandwidth") ?: ""

                    var segmentPath = mediaTemplate
                        .replace("\$RepresentationID\$", repId)
                        .replace("\$Bandwidth\$", bandwidth)
                        .replace("\$Number\$", segNum.toString())
                        .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { match ->
                            val width = match.groupValues[1].toIntOrNull() ?: 1
                            segNum.toString().padStart(width, '0')
                        }

                    val initPath = initTemplate?.let {
                        it.replace("\$RepresentationID\$", repId)
                          .replace("\$Bandwidth\$", bandwidth)
                    }

                    val finalSegmentUrl = if (segmentPath.startsWith("http")) segmentPath else baseUrl + segmentPath
                    val finalInitUrl = initPath?.let { if (it.startsWith("http")) it else baseUrl + it }

                    return Pair(finalSegmentUrl, finalInitUrl)
                }
            }

            null
        } catch (e: Exception) {
            ServerState.warn("getFreshSegmentUrls error: ${e.message}")
            null
        }
    }

    private fun prefetchNextSegments(
        currentSegmentUrl: String,
        initUrl: String?,
        keyId: String,
        key: String,
        headers: Map<String, String>,
        proxyUrl: String?,
        count: Int = 3
    ) {
        val regex = Regex("""[_-](\d+)\.m4[sv]""")
        val match = regex.find(currentSegmentUrl) ?: return
        val currentNum = match.groupValues[1].toIntOrNull() ?: return

        for (i in 1..count) {
            val nextNum = currentNum + i
            val nextUrl = currentSegmentUrl.replace(
                regex,
                match.value.replace(currentNum.toString(), nextNum.toString())
            )

            val segmentCacheKey = "${nextUrl.substringBefore("?")}_$keyId"

            if (SegmentCache.hasSegment(segmentCacheKey) || !SegmentCache.markPrefetching(segmentCacheKey)) {
                continue
            }

            prefetchScope.launch {
                try {
                    val initCacheKey = initUrl?.substringBefore("?")
                    val initContent = initCacheKey?.let { SegmentCache.getInitSegment(it) }
                        ?: initUrl?.let {
                            HttpClientManager.getBytes(url = it, headers = headers, proxyUrl = proxyUrl)
                        }

                    val segmentContent = HttpClientManager.getBytes(
                        url = nextUrl,
                        headers = headers,
                        proxyUrl = proxyUrl
                    )

                    val decrypted = CencDecryptor.decryptSegment(
                        initSegment = initContent,
                        mediaSegment = segmentContent,
                        keyIdHex = keyId,
                        keyHex = key,
                        excludeInit = true  // VLC FIX: exclude init, use EXT-X-MAP
                    )

                    SegmentCache.putSegment(segmentCacheKey, decrypted)
                } catch (e: Exception) {
                    // Silently ignore prefetch errors
                } finally {
                    SegmentCache.unmarkPrefetching(segmentCacheKey)
                }
            }
        }
    }
    private suspend fun respondBytesOrHead(
        call: ApplicationCall,
        bytes: ByteArray,
        contentType: ContentType,
        acceptRanges: Boolean
    ) {
        if (call.request.httpMethod == HttpMethod.Head) {
            call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                override val contentLength: Long = bytes.size.toLong()
                override val contentType: ContentType = contentType
                override val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.AccessControlAllowOrigin, "*")
                    append(HttpHeaders.CacheControl, "no-store")
                    if (acceptRanges) {
                        append(HttpHeaders.AcceptRanges, "bytes")
                    }
                }
            })
            return
        }

        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (acceptRanges) {
            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        }
        call.respondBytes(
            bytes = bytes,
            contentType = contentType,
            status = HttpStatusCode.OK
        )
    }
}
