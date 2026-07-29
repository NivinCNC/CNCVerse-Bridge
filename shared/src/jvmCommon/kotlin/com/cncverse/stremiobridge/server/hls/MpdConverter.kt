package com.cncverse.stremiobridge.server.hls

import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import com.cncverse.stremiobridge.state.ServerState

/**
 * Convertitore MPD (DASH) -> HLS (M3U8)
 * Genera Master Playlist e Media Playlist da manifest DASH
 *
 * Porta completa da EasyProxy utils/mpd_converter.py
 *
 * Supporta:
 * - SegmentTemplate con timeline e duration
 * - SegmentList
 * - SegmentBase
 * - Live (dynamic) e VOD (static)
 * - Multi-period (aggregates segments across all Period elements)
 * - ClearKey DRM con decryption server-side
 * - timeShiftBufferDepth for live DVR window
 */
class MpdConverter {

    companion object {
        // HLS Version 6 REQUIRED for fMP4/CMAF content (EXT-X-MAP support)
        // ExoPlayer requires v6+ for proper fMP4 playback
        private const val HLS_VERSION = 6
        private const val DEFAULT_TARGET_DURATION = 6 // Ridotto per latenza migliore
        private const val MAX_LIVE_SEGMENTS = 5000  // Large cap for multi-period MPDs with DVR windows
        private const val LIVE_WINDOW_SECONDS = 180  // Default fallback; overridden by timeShiftBufferDepth
        
        // Stateful tracker for live HLS MEDIA-SEQUENCE.
        // Maps "baseUrl|repId" -> Pair<List of Segment Times, Last Sequence Number>
        private val sequenceState = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Long>, Int>>()
    }

    /**
     * Genera Master Playlist HLS da manifest MPD
     */
    fun convertMasterPlaylist(
        mpdContent: String,
        proxyBase: String,
        mpdUrl: String,
        headerParams: String,
        clearKey: String? = null
    ): String {
        val doc = parseXml(mpdContent)
        val mpd = doc.documentElement

        val baseUrl = getBaseUrl(mpdUrl)

        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:$HLS_VERSION")
        // INDEPENDENT-SEGMENTS: required for fMP4/CMAF content (ExoPlayer compatibility)
        sb.appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        sb.appendLine()

        // Trova tutti gli AdaptationSet (cerca in Period se presente)
        // For live MPDs with multiple periods, use the LAST period (live edge)
        // since all periods share the same Representation structure
        val periods = mpd.getElementsByTagName("Period")
        val adaptationSets = if (periods.length > 0) {
            val mpdType = mpd.getAttribute("type") ?: "static"
            val periodIndex = if (mpdType == "dynamic" && periods.length > 1) periods.length - 1 else 0
            (periods.item(periodIndex) as Element).getElementsByTagName("AdaptationSet")
        } else {
            mpd.getElementsByTagName("AdaptationSet")
        }

        // Prima passa: raccogli audio tracks per EXT-X-MEDIA
        val audioTracks = mutableListOf<AudioTrack>()
        for (i in 0 until adaptationSets.length) {
            val adaptationSet = adaptationSets.item(i) as Element
            val mimeType = adaptationSet.getAttribute("mimeType") ?: ""
            val contentType = adaptationSet.getAttribute("contentType") ?: ""

            // Rileva audio anche senza mimeType esplicito
            val isAudio = mimeType.startsWith("audio/") ||
                          contentType.equals("audio", ignoreCase = true) ||
                          mimeType.contains("audio")

            if (isAudio) {
                val lang = adaptationSet.getAttribute("lang") ?:
                           adaptationSet.getAttribute("language") ?: "und"
                val label = adaptationSet.getAttribute("label") ?: lang
                val representations = adaptationSet.getElementsByTagName("Representation")

                for (j in 0 until representations.length) {
                    val rep = representations.item(j) as Element
                    val repId = rep.getAttribute("id")
                    val bandwidth = rep.getAttribute("bandwidth")?.toIntOrNull() ?: 0
                    val codecs = rep.getAttribute("codecs")
                        ?: adaptationSet.getAttribute("codecs") ?: ""

                    audioTracks.add(AudioTrack(
                        id = repId,
                        language = lang,
                        bandwidth = bandwidth,
                        codecs = codecs,
                        isDefault = audioTracks.isEmpty() // Prima traccia è default
                    ))
                }
            }
        }

        // Genera EXT-X-MEDIA per tracce audio
        val clearKeyParam = if (!clearKey.isNullOrBlank()) "&clearkey=$clearKey" else ""

        audioTracks.forEach { track ->
            val encodedMpdUrl = URLEncoder.encode(mpdUrl, "UTF-8")
            val mediaUrl = "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedMpdUrl&rep_id=${track.id}$headerParams$clearKeyParam"

            sb.appendLine("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="${track.language}",NAME="${track.language.uppercase()}",DEFAULT=${if (track.isDefault) "YES" else "NO"},AUTOSELECT=YES,URI="$mediaUrl"""")
        }

        if (audioTracks.isNotEmpty()) {
            sb.appendLine()
        }

        // Seconda passa: genera EXT-X-STREAM-INF per video
        var videoCount = 0
        for (i in 0 until adaptationSets.length) {
            val adaptationSet = adaptationSets.item(i) as Element
            val mimeType = adaptationSet.getAttribute("mimeType") ?: ""
            val contentType = adaptationSet.getAttribute("contentType") ?: ""

            // Rileva video in modi più flessibili
            val isVideo = mimeType.startsWith("video/") ||
                          contentType.equals("video", ignoreCase = true) ||
                          mimeType.contains("video") ||
                          // Se non c'è contentType/mimeType ma ha width/height, è probabilmente video
                          ((mimeType.isEmpty() && contentType.isEmpty()) &&
                           (adaptationSet.getAttribute("width")?.isNotBlank() == true ||
                            adaptationSet.getElementsByTagName("Representation").let { reps ->
                                (0 until reps.length).any { (reps.item(it) as Element).getAttribute("width").isNotBlank() }
                            }))

            if (isVideo) {
                val representations = adaptationSet.getElementsByTagName("Representation")

                for (j in 0 until representations.length) {
                    val rep = representations.item(j) as Element
                    val repId = rep.getAttribute("id")
                    val bandwidth = rep.getAttribute("bandwidth")?.toIntOrNull() ?: 0
                    val width = rep.getAttribute("width")?.toIntOrNull()
                        ?: adaptationSet.getAttribute("width")?.toIntOrNull()
                    val height = rep.getAttribute("height")?.toIntOrNull()
                        ?: adaptationSet.getAttribute("height")?.toIntOrNull()
                    val codecs = rep.getAttribute("codecs")
                        ?: adaptationSet.getAttribute("codecs") ?: ""
                    val frameRate = rep.getAttribute("frameRate")
                        ?: adaptationSet.getAttribute("frameRate") ?: ""

                    // Costruisci attributi
                    val attrs = mutableListOf<String>()
                    attrs.add("BANDWIDTH=$bandwidth")

                    if (width != null && height != null) {
                        attrs.add("RESOLUTION=${width}x${height}")
                    }

                    if (codecs.isNotEmpty()) {
                        attrs.add("""CODECS="$codecs"""")
                    }

                    if (frameRate.isNotEmpty()) {
                        attrs.add("FRAME-RATE=$frameRate")
                    }

                    if (audioTracks.isNotEmpty()) {
                        attrs.add("""AUDIO="audio"""")
                    }

                    val encodedMpdUrl = URLEncoder.encode(mpdUrl, "UTF-8")
                    val variantUrl = "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedMpdUrl&rep_id=$repId$headerParams$clearKeyParam"

                    sb.appendLine("#EXT-X-STREAM-INF:${attrs.joinToString(",")}")
                    sb.appendLine(variantUrl)
                    videoCount++
                }
            }
        }

        return sb.toString()
    }

    /**
     * Genera Media Playlist HLS per una specifica Representation.
     * Supports multi-period MPDs by aggregating segments across all Period elements.
     * Uses timeShiftBufferDepth from the MPD for live DVR window sizing.
     * Emits #EXT-X-DISCONTINUITY and per-period #EXT-X-MAP at period boundaries.
     */
    fun convertMediaPlaylist(
        mpdContent: String,
        repId: String,
        proxyBase: String,
        mpdUrl: String,
        headerParams: String,
        clearKey: String? = null
    ): String {
        val doc = parseXml(mpdContent)
        val mpd = doc.documentElement

        // Cerca BaseURL nel documento MPD
        val mpdBaseUrl = findMpdBaseUrl(mpd, mpdUrl)
        val baseUrl = mpdBaseUrl ?: getBaseUrl(mpdUrl)

        // Determina se è live o VOD
        val mpdType = mpd.getAttribute("type") ?: "static"
        val isLive = mpdType == "dynamic"

        // Parse availabilityStartTime for absolute PROGRAM-DATE-TIME (live streams)
        val availabilityStartTimeMs: Long? = if (isLive) {
            parseIso8601ToEpochMs(mpd.getAttribute("availabilityStartTime"))
        } else null

        // Parse timeShiftBufferDepth for live window (fallback to LIVE_WINDOW_SECONDS)
        val liveWindowSeconds = if (isLive) {
            parseDuration(mpd.getAttribute("timeShiftBufferDepth")) ?: LIVE_WINDOW_SECONDS.toDouble()
        } else {
            LIVE_WINDOW_SECONDS.toDouble()
        }

        // Multi-period support: iterate ALL periods and aggregate segments
        val periods = mpd.getElementsByTagName("Period")
        val allSegments = mutableListOf<Segment>()
        var lastTargetRep: Element? = null
        var lastParentAdaptationSet: Element? = null
        var lastSegmentTemplate: Element? = null
        var periodCounter = 0

        // ClearKey: decryption server-side (come EasyProxy)
        val useClearKeyDecryption = !clearKey.isNullOrBlank()

        if (periods.length > 0) {
            for (p in 0 until periods.length) {
                val period = periods.item(p) as Element
                // Only process direct children of MPD (not nested elements)
                if (period.parentNode != mpd) continue

                // Find BaseURL for this period (fallback to MPD-level baseUrl)
                val periodBaseUrl = findPeriodBaseUrl(period, baseUrl)

                // Search for matching Representation within this period
                val adaptationSets = period.getElementsByTagName("AdaptationSet")
                var foundInPeriod = false
                for (a in 0 until adaptationSets.length) {
                    val adaptSet = adaptationSets.item(a) as Element
                    if (adaptSet.parentNode != period) continue

                    val representations = adaptSet.getElementsByTagName("Representation")
                    for (r in 0 until representations.length) {
                        val rep = representations.item(r) as Element
                        if (rep.getAttribute("id") == repId) {
                            lastTargetRep = rep
                            lastParentAdaptationSet = adaptSet

                            val segmentTemplate = findSegmentTemplate(rep, adaptSet)
                            lastSegmentTemplate = segmentTemplate
                            val segmentList = rep.getElementsByTagName("SegmentList").item(0) as? Element
                                ?: adaptSet.getElementsByTagName("SegmentList")?.item(0) as? Element

                            // Compute per-period init URL
                            val periodInitUrl = getInitializationUrl(
                                segmentTemplate, rep, adaptSet, repId, periodBaseUrl
                            )

                            val periodSegments = generateSegments(
                                segmentTemplate, segmentList, rep, adaptSet, repId,
                                periodBaseUrl, isLive, periodCounter, periodInitUrl
                            )
                            allSegments.addAll(periodSegments)
                            foundInPeriod = true
                            break
                        }
                    }
                    if (foundInPeriod) {
                        periodCounter++
                        break
                    }
                }
            }
        } else {
            // No Period elements - search globally (legacy fallback)
            val representations = mpd.getElementsByTagName("Representation")
            for (i in 0 until representations.length) {
                val rep = representations.item(i) as Element
                if (rep.getAttribute("id") == repId) {
                    lastTargetRep = rep
                    lastParentAdaptationSet = rep.parentNode as? Element
                    break
                }
            }
            if (lastTargetRep != null) {
                lastSegmentTemplate = findSegmentTemplate(lastTargetRep!!, lastParentAdaptationSet)
                val segmentList = lastTargetRep!!.getElementsByTagName("SegmentList").item(0) as? Element
                    ?: lastParentAdaptationSet?.getElementsByTagName("SegmentList")?.item(0) as? Element
                val fallbackInitUrl = getInitializationUrl(
                    lastSegmentTemplate, lastTargetRep!!, lastParentAdaptationSet, repId, baseUrl
                )
                allSegments.addAll(generateSegments(
                    lastSegmentTemplate, segmentList, lastTargetRep!!, lastParentAdaptationSet,
                    repId, baseUrl, isLive, 0, fallbackInitUrl
                ))
            }
        }

        if (lastTargetRep == null) {
            return generateErrorPlaylist("Representation not found: $repId")
        }

        if (allSegments.isEmpty()) {
            return generateErrorPlaylist("No segments generated for representation: $repId")
        }

        // Apply live windowing using timeShiftBufferDepth
        // This takes segments from the end (live edge) up to the DVR window
        val segments = if (isLive && allSegments.size > 1) {
            var totalDuration = 0.0
            val liveSegments = mutableListOf<Segment>()
            for (seg in allSegments.reversed()) {
                liveSegments.add(0, seg)
                totalDuration += seg.duration
                if (totalDuration >= liveWindowSeconds || liveSegments.size >= MAX_LIVE_SEGMENTS) {
                    break
                }
            }
            liveSegments
        } else {
            allSegments
        }

        val totalDuration = segments.sumOf { it.duration }

        // Calcola TARGETDURATION dal segmento più lungo + 1 (come EasyProxy)
        val maxDuration = segments.maxOfOrNull { it.duration } ?: DEFAULT_TARGET_DURATION.toDouble()
        // Coerce to at least DEFAULT_TARGET_DURATION (6s) so ExoPlayer's playlist reload
        // interval is relaxed and its stuck timeout is at least 21s (handles slow CDNs)
        val targetDuration = (maxDuration.toInt() + 1).coerceAtLeast(DEFAULT_TARGET_DURATION)

        // MEDIA-SEQUENCE: Must be strictly monotonically increasing.
        // We use a stateful tracker to guarantee perfect +1 increments as segments drop off,
        // which avoids any math rounding errors or massive jumps caused by fluctuating durations.
        val mediaSequence = if (isLive && segments.isNotEmpty()) {
            val stateKey = "$baseUrl|$repId"
            val currentTimes = segments.map { it.time }
            val prevState = sequenceState[stateKey]

            val newSeq = if (prevState == null) {
                // First time we see this stream
                val mediaTemplate = lastSegmentTemplate?.getAttribute("media") ?: ""
                val initialSeq = if (mediaTemplate.contains("\$Number\$")) segments.first().number else 0
                initialSeq
            } else {
                val oldTimes = prevState.first
                val oldSeq = prevState.second
                val newFirstTime = currentTimes.first()
                val indexInOld = oldTimes.indexOf(newFirstTime)

                if (indexInOld > 0) {
                    // Window slid forward by exactly indexInOld segments
                    oldSeq + indexInOld
                } else if (indexInOld == 0) {
                    // Window didn't slide
                    oldSeq
                } else {
                    // Missed refreshes, or timeline reset. Estimate jump.
                    val timeDiff = newFirstTime - oldTimes.first()
                    if (timeDiff > 0) {
                        val firstSeg = segments.first()
                        val modeDur = segments.map { Math.round(it.duration * firstSeg.timescale) }
                            .groupBy { it }.maxByOrNull { it.value.size }?.key?.coerceAtLeast(1L) ?: 1L
                        val estimatedJump = Math.round(timeDiff.toDouble() / modeDur.toDouble()).toInt().coerceAtLeast(1)
                        oldSeq + estimatedJump
                    } else {
                        oldSeq + 1
                    }
                }
            }
            sequenceState[stateKey] = Pair(currentTimes, newSeq)
            newSeq
        } else {
            0
        }

        // Default init URL (first segment's init, or last period's as fallback)
        val defaultInitUrl = segments.firstOrNull()?.initUrl
            ?: getInitializationUrl(lastSegmentTemplate, lastTargetRep!!, lastParentAdaptationSet, repId, baseUrl)

        // DEBUG: log multi-period info
        ServerState.info("MpdConverter: repId=$repId, periods=${periodCounter}, totalSegs=${allSegments.size}, windowedSegs=${segments.size}, liveWindow=${liveWindowSeconds}s, initUrl=${defaultInitUrl?.take(80) ?: "NULL"}, mediaSequence=${mediaSequence}")

        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:$HLS_VERSION")
        sb.appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        sb.appendLine("#EXT-X-TARGETDURATION:$targetDuration")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSequence")

        if (isLive) {
            // EXT-X-START per live streams - fa partire il player dal live edge
            // TIME-OFFSET negativo = secondi prima del live edge
            sb.appendLine("#EXT-X-START:TIME-OFFSET=-20.0,PRECISE=NO")
        } else {
            sb.appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        }

        // Emit initial EXT-X-MAP
        val firstInitUrl = defaultInitUrl
        if (firstInitUrl != null) {
            val encodedInit = URLEncoder.encode(firstInitUrl, "UTF-8")
            val proxyInitUrl = if (useClearKeyDecryption) {
                "$proxyBase/init_decrypt?url=$encodedInit$headerParams"
            } else {
                "$proxyBase/proxy/stream?d=$encodedInit$headerParams"
            }
            sb.appendLine("""#EXT-X-MAP:URI="$proxyInitUrl"""")
        }

        // Segmenti
        // Encodiamo l'URL del MPD per permettere refresh degli URL se scaduti (403)
        val encodedMpdUrl = URLEncoder.encode(mpdUrl, "UTF-8")
        var lastPeriodIndex = segments.firstOrNull()?.periodIndex ?: 0
        var lastInitUrlEmitted = firstInitUrl

        for ((index, segment) in segments.withIndex()) {
            // Period boundary: emit DISCONTINUITY and new EXT-X-MAP if init changed
            if (index > 0 && segment.periodIndex != lastPeriodIndex) {
                sb.appendLine("#EXT-X-DISCONTINUITY")

                // Emit new EXT-X-MAP if init URL changed
                if (segment.initUrl != null && segment.initUrl != lastInitUrlEmitted) {
                    val encodedNewInit = URLEncoder.encode(segment.initUrl, "UTF-8")
                    val newProxyInitUrl = if (useClearKeyDecryption) {
                        "$proxyBase/init_decrypt?url=$encodedNewInit$headerParams"
                    } else {
                        "$proxyBase/proxy/stream?d=$encodedNewInit$headerParams"
                    }
                    sb.appendLine("""#EXT-X-MAP:URI="$newProxyInitUrl"""")
                    lastInitUrlEmitted = segment.initUrl
                }
                lastPeriodIndex = segment.periodIndex
            }

            if (isLive) {
                // Add PROGRAM-DATE-TIME to anchor massive fMP4 timestamps for ExoPlayer.
                // Without this, ExoPlayer's getBufferedPercentage() crashes with an Int overflow.
                // Use per-segment timescale (not last period's) for correct computation.
                val segTimescale = segment.timescale.coerceAtLeast(1L)
                val timeSec = segment.time.toDouble() / segTimescale.toDouble()
                try {
                    val epochMs = if (availabilityStartTimeMs != null) {
                        // availabilityStartTime + media timeline offset = wall-clock time
                        availabilityStartTimeMs + (timeSec * 1000).toLong()
                    } else {
                        // Fallback: treat segment.time as epoch-relative (legacy behavior)
                        (timeSec * 1000).toLong()
                    }
                    val instant = java.time.Instant.ofEpochMilli(epochMs)
                    sb.appendLine("#EXT-X-PROGRAM-DATE-TIME:$instant")
                } catch (e: Exception) {
                    // Fallback to a default date if timestamp is completely wild
                    sb.appendLine("#EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00.000Z")
                }
            }

            // EXTINF con 3 decimali come EasyProxy - alcuni player hanno problemi con 6 decimali
            sb.appendLine("#EXTINF:${String.format(java.util.Locale.US, "%.3f", segment.duration)},")
            val encodedSegmentUrl = URLEncoder.encode(segment.url, "UTF-8")

            // Use the segment's own init URL for the decrypt endpoint
            val segInitUrl = segment.initUrl ?: defaultInitUrl
            val encodedSegInit = if (segInitUrl != null) URLEncoder.encode(segInitUrl, "UTF-8") else null

            val proxySegmentUrl = if (useClearKeyDecryption) {
                // Usa decrypt endpoint per decrittare server-side
                val parts = clearKey!!.split(":")
                if (parts.size == 2) {
                    val keyId = parts[0]
                    val key = parts[1]
                    val initParam = if (encodedSegInit != null) "&init_url=$encodedSegInit" else ""
                    // Aggiungiamo mpd_url e rep_id per permettere refresh URL se scaduti
                    val mpdParam = "&mpd_url=$encodedMpdUrl&rep_id=$repId&seg_num=${segment.number}"
                    "$proxyBase/decrypt?url=$encodedSegmentUrl$initParam&key_id=$keyId&key=$key$mpdParam$headerParams"
                } else {
                    "$proxyBase/proxy/stream?d=$encodedSegmentUrl$headerParams"
                }
            } else {
                "$proxyBase/proxy/stream?d=$encodedSegmentUrl$headerParams"
            }

            sb.appendLine(proxySegmentUrl)
        }

        if (!isLive) {
            sb.appendLine("#EXT-X-ENDLIST")
        }

        return sb.toString()
    }

    /**
     * Cerca BaseURL nel documento MPD
     */
    private fun findMpdBaseUrl(mpd: Element, mpdUrl: String): String? {
        // Cerca BaseURL diretto nel MPD
        val baseUrlNodes = mpd.getElementsByTagName("BaseURL")
        for (i in 0 until baseUrlNodes.length) {
            val node = baseUrlNodes.item(i)
            if (node.parentNode == mpd) {
                val baseUrlText = node.textContent?.trim()
                if (!baseUrlText.isNullOrBlank()) {
                    return if (baseUrlText.startsWith("http")) {
                        baseUrlText
                    } else {
                        resolveUrl(getBaseUrl(mpdUrl), baseUrlText)
                    }
                }
            }
        }
        return null
    }

    /**
     * Cerca BaseURL in un Period element
     */
    private fun findPeriodBaseUrl(period: Element, fallbackBaseUrl: String): String {
        val baseUrlNodes = period.getElementsByTagName("BaseURL")
        for (i in 0 until baseUrlNodes.length) {
            val node = baseUrlNodes.item(i)
            if (node.parentNode == period) {
                val baseUrlText = node.textContent?.trim()
                if (!baseUrlText.isNullOrBlank()) {
                    return if (baseUrlText.startsWith("http")) {
                        baseUrlText
                    } else {
                        resolveUrl(fallbackBaseUrl, baseUrlText)
                    }
                }
            }
        }
        return fallbackBaseUrl
    }

    /**
     * Trova SegmentTemplate nella Representation o AdaptationSet
     */
    private fun findSegmentTemplate(rep: Element, adaptationSet: Element?): Element? {
        val repTemplate = rep.getElementsByTagName("SegmentTemplate").item(0) as? Element
        if (repTemplate != null) return repTemplate

        return adaptationSet?.getElementsByTagName("SegmentTemplate")?.item(0) as? Element
    }

    /**
     * Ottiene URL dell'initialization segment
     */
    private fun getInitializationUrl(
        segmentTemplate: Element?,
        rep: Element,
        adaptationSet: Element?,
        repId: String,
        baseUrl: String
    ): String? {
        val initAttr = segmentTemplate?.getAttribute("initialization")
        if (initAttr.isNullOrBlank()) return null

        // Sostituisci template variables
        var initUrl = initAttr
            .replace("\$RepresentationID\$", repId)
            .replace("\$Bandwidth\$", rep.getAttribute("bandwidth") ?: "")

        return resolveUrl(baseUrl, initUrl)
    }

    /**
     * Genera lista di segmenti per un singolo Period (come EasyProxy mpd_converter.py).
     * NOTE: Live windowing is NOT applied here - the caller (convertMediaPlaylist)
     * handles windowing after aggregating segments from all periods.
     */
    private fun generateSegments(
        segmentTemplate: Element?,
        segmentList: Element?,
        rep: Element,
        adaptationSet: Element?,
        repId: String,
        baseUrl: String,
        isLive: Boolean,
        periodIndex: Int = 0,
        periodInitUrl: String? = null
    ): List<Segment> {
        val segments = mutableListOf<Segment>()

        if (segmentTemplate != null) {
            // IMPORTANTE: usa Long per timescale alto (es. 10000000)
            val timescale = segmentTemplate.getAttribute("timescale")?.toLongOrNull() ?: 1L
            val mediaTemplate = segmentTemplate.getAttribute("media") ?: return segments
            val startNumber = segmentTemplate.getAttribute("startNumber")?.toIntOrNull() ?: 1
            val bandwidth = rep.getAttribute("bandwidth") ?: ""

            // Cerca SegmentTimeline
            val timeline = segmentTemplate.getElementsByTagName("SegmentTimeline").item(0) as? Element
                ?: adaptationSet?.getElementsByTagName("SegmentTimeline")?.item(0) as? Element

            if (timeline != null) {
                // Usa SegmentTimeline (come EasyProxy)
                val sElements = timeline.getElementsByTagName("S")
                var time = 0L
                var segmentNumber = startNumber
                val allSegments = mutableListOf<Segment>()

                for (i in 0 until sElements.length) {
                    val s = sElements.item(i) as Element
                    val t = s.getAttribute("t")?.toLongOrNull()
                    val d = s.getAttribute("d")?.toLongOrNull() ?: continue
                    val r = s.getAttribute("r")?.toIntOrNull() ?: 0

                    if (t != null) {
                        time = t
                    }

                    // Genera segmenti (r+1 volte, r può essere -1 per infinito)
                    // r=-1 significa ripetere fino alla fine, per live usiamo MAX_LIVE_SEGMENTS
                    val repeatCount = when {
                        r == -1 -> if (isLive) MAX_LIVE_SEGMENTS else 100
                        else -> r
                    }

                    for (j in 0..repeatCount) {
                        // Usa Double per precisione con timescale alto
                        val duration = d.toDouble() / timescale.toDouble()

                        var segmentUrl = mediaTemplate
                            .replace("\$RepresentationID\$", repId)
                            .replace("\$Bandwidth\$", bandwidth)
                            .replace("\$Number\$", segmentNumber.toString())
                            .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { match ->
                                val width = match.groupValues[1].toIntOrNull() ?: 1
                                segmentNumber.toString().padStart(width, '0')
                            }
                            .replace("\$Time\$", time.toString())

                        allSegments.add(Segment(
                            url = resolveUrl(baseUrl, segmentUrl),
                            duration = duration,
                            number = segmentNumber,
                            time = time,
                            periodIndex = periodIndex,
                            timescale = timescale,
                            initUrl = periodInitUrl
                        ))

                        time += d
                        segmentNumber++

                        // Safety cap to prevent infinite loops during generation
                        if (allSegments.size >= MAX_LIVE_SEGMENTS * 2) break
                    }

                    if (allSegments.size >= MAX_LIVE_SEGMENTS * 2) break
                }

                // Return all segments - live windowing is handled by the caller
                // (convertMediaPlaylist applies timeShiftBufferDepth-based windowing after
                // aggregating segments from all periods)
                return allSegments

            } else {
                // Usa duration-based segments
                val duration = segmentTemplate.getAttribute("duration")?.toLongOrNull() ?: return segments
                val segmentDuration = duration.toDouble() / timescale.toDouble()

                // Per live, calcola quanti segmenti ci sono basato sul tempo corrente
                // Per VOD, usa la durata del contenuto se disponibile
                val numSegments = if (isLive) {
                    MAX_LIVE_SEGMENTS
                } else {
                    // Prova a ottenere duration dalla rappresentazione o AdaptationSet
                    val mediaPresentationDuration = try {
                        val doc = rep.ownerDocument
                        val mpd = doc.documentElement
                        val durationStr = mpd.getAttribute("mediaPresentationDuration")
                        parseDuration(durationStr)
                    } catch (e: Exception) { null }

                    if (mediaPresentationDuration != null) {
                        ceil(mediaPresentationDuration / segmentDuration).toInt()
                    } else {
                        100 // Fallback
                    }
                }

                var cumulativeTime = 0L
                for (i in 0 until numSegments) {
                    val segmentNumber = startNumber + i
                    var segmentUrl = mediaTemplate
                        .replace("\$RepresentationID\$", repId)
                        .replace("\$Bandwidth\$", bandwidth)
                        .replace("\$Number\$", segmentNumber.toString())
                        .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { match ->
                            val width = match.groupValues[1].toIntOrNull() ?: 1
                            segmentNumber.toString().padStart(width, '0')
                        }
                        .replace("\$Time\$", cumulativeTime.toString())

                    segments.add(Segment(
                        url = resolveUrl(baseUrl, segmentUrl),
                        duration = segmentDuration,
                        number = segmentNumber,
                        time = cumulativeTime,
                        periodIndex = periodIndex,
                        timescale = timescale,
                        initUrl = periodInitUrl
                    ))
                    cumulativeTime += duration
                }
            }
        } else if (segmentList != null) {
            // Usa SegmentList
            val timescale = segmentList.getAttribute("timescale")?.toLongOrNull() ?: 1L
            val duration = segmentList.getAttribute("duration")?.toLongOrNull()
            val defaultDuration = if (duration != null) duration.toDouble() / timescale.toDouble() else DEFAULT_TARGET_DURATION.toDouble()

            val segmentUrls = segmentList.getElementsByTagName("SegmentURL")

            for (i in 0 until segmentUrls.length) {
                val segmentUrl = segmentUrls.item(i) as Element
                val media = segmentUrl.getAttribute("media") ?: continue

                segments.add(Segment(
                    url = resolveUrl(baseUrl, media),
                    duration = defaultDuration,
                    number = i + 1,
                    periodIndex = periodIndex,
                    timescale = timescale,
                    initUrl = periodInitUrl
                ))
            }
        }

        // Return all segments - live windowing is handled by the caller
        return segments
    }

    /**
     * Parsa durata ISO 8601 (PT1H30M45S)
     */
    private fun parseDuration(duration: String?): Double? {
        if (duration.isNullOrBlank()) return null

        try {
            var totalSeconds = 0.0
            val regex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""")
            val match = regex.find(duration) ?: return null

            match.groupValues[1].toDoubleOrNull()?.let { totalSeconds += it * 3600 }
            match.groupValues[2].toDoubleOrNull()?.let { totalSeconds += it * 60 }
            match.groupValues[3].toDoubleOrNull()?.let { totalSeconds += it }

            return if (totalSeconds > 0) totalSeconds else null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parsa XML MPD
     */
    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Genera playlist di errore
     */
    private fun generateErrorPlaylist(message: String): String {
        return """
            #EXTM3U
            #EXT-X-VERSION:$HLS_VERSION
            #EXT-X-TARGETDURATION:$DEFAULT_TARGET_DURATION
            #EXT-X-PLAYLIST-TYPE:VOD
            # Error: $message
            #EXT-X-ENDLIST
        """.trimIndent()
    }

    /**
     * Ottiene base URL
     */
    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.path
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash > 0) {
                URI(uri.scheme, uri.authority, path.substring(0, lastSlash + 1), null, null).toString()
            } else {
                URI(uri.scheme, uri.authority, "/", null, null).toString()
            }
        } catch (e: Exception) {
            url.substringBeforeLast('/') + "/"
        }
    }

    /**
     * Risolve URL relativo
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return when {
            relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://") -> relativeUrl
            relativeUrl.startsWith("//") -> "https:$relativeUrl"
            relativeUrl.startsWith("/") -> {
                val uri = URI(baseUrl)
                URI(uri.scheme, uri.authority, relativeUrl, null, null).toString()
            }
            else -> {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                URI(base).resolve(relativeUrl).toString()
            }
        }
    }

    /**
     * Data class per traccia audio
     */
    private data class AudioTrack(
        val id: String,
        val language: String,
        val bandwidth: Int,
        val codecs: String,
        val isDefault: Boolean
    )

    /**
     * Data class per segmento
     */
    private data class Segment(
        val url: String,
        val duration: Double,
        val number: Int,
        val time: Long = 0,
        /** Which Period this segment belongs to (0-based) */
        val periodIndex: Int = 0,
        /** Timescale from the SegmentTemplate for this segment's period */
        val timescale: Long = 1L,
        /** Init segment URL for this segment's period */
        val initUrl: String? = null
    )

    /**
     * Parses ISO 8601 date-time string to epoch milliseconds.
     * E.g. "2024-01-15T12:00:00Z" → epoch ms
     */
    private fun parseIso8601ToEpochMs(dateTime: String?): Long? {
        if (dateTime.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(dateTime).toEpochMilli()
        } catch (e: Exception) {
            try {
                // Some MPDs use formats without 'Z', try OffsetDateTime
                java.time.OffsetDateTime.parse(dateTime).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }
}