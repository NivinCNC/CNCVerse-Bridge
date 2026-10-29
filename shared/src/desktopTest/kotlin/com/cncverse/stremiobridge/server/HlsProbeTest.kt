package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.format.StreamVariables
import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsProbeTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hi",DEFAULT=YES,URI="a/hi.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Track 2",LANGUAGE="tam",URI="a/ta.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="audio_3",LANGUAGE="und",URI="a/x.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="English",LANGUAGE="en",URI="s/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,AUDIO="aud"
        480/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x800,AUDIO="aud"
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
        720/index.m3u8
    """.trimIndent()

    @Test
    fun readsQualitiesAndAudioLanguages() {
        val m = HlsProbe.parse(master)!!
        // 1920x800 (a wide film) is 1080p by its width; best first
        assertEquals(listOf(1080, 720, 480), m.heights)
        // Subtitle tracks and "und" are not audio languages
        assertEquals(listOf("Hindi", "Tamil"), m.languages)
    }

    @Test
    fun mediaPlaylistIsNotAMaster() {
        assertNull(HlsProbe.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"))
        assertNull(HlsProbe.parse("<html>blocked</html>"))
    }

    private fun hls(heights: List<Int>?) = StremioStream(name = "X", url = "https://cdn.example/m.m3u8",
        info = StreamInfo(addonName = "X", linkType = "M3U8", variantHeights = heights))

    @Test
    fun bestVariantIsTheResolution() {
        val s = hls(listOf(1080, 720, 480))
        assertEquals("1080p", StreamVariables.resolutionOf(s))
        assertEquals(listOf("1080p", "720p", "480p"), StreamVariables.qualitiesOf(s))
    }

    @Test
    fun manyLanguagesAreCut() {
        val langs = listOf("Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "English")
        val s = hls(null).let { it.copy(info = it.info!!.copy(audioLanguages = langs)) }
        val vars = StreamVariables.build(s, com.cncverse.stremiobridge.format.StreamRequestContext())
        assertEquals(listOf("Hindi", "Tamil", "Telugu", "Malayalam", "+4 more"), vars["stream.languages"])
        // Preferred languages lead, so the cut never hides them
        val pref = StreamVariables.build(s, com.cncverse.stremiobridge.format.StreamRequestContext(preferredLanguages = listOf("English", "Marathi")))
        assertEquals(listOf("English", "Marathi", "Hindi", "Tamil", "+4 more"), pref["stream.languages"])
    }

    private fun file(name: String, audio: List<String>? = null) = StremioStream(name = "X", title = name, url = "https://f.example/" + name.hashCode(),
        info = StreamInfo(addonName = "X", linkType = "VIDEO", linkName = name, audioLanguages = audio))

    @Test
    fun languageFilter() {
        val hindiByName = file("Movie.2023.Hindi.1080p.mkv")
        val tamilInFile = file("Movie.2023.1080p.mkv", audio = listOf("Tamil", "English"))
        val unknown = file("Movie.2023.720p.mkv")
        val mandarin = file("Movie.2023.Mandarin.720p.mkv")
        val all = listOf(hindiByName, tamilInFile, unknown, mandarin)
        val p = StremioServer.StreamPrefs(allowedLanguages = setOf("Tamil", "Chinese"))
        // The file's own tracks count; Mandarin counts as Chinese; unknown kept by default
        assertEquals(listOf(tamilInFile, unknown, mandarin), StremioServer.filterStreamsByProfile(all, p, "movie"))
        assertEquals(listOf(tamilInFile, mandarin), StremioServer.filterStreamsByProfile(all, p.copy(hideUnknownLanguage = true), "movie"))
        // Live TV is never language-filtered
        assertEquals(all, StremioServer.filterStreamsByProfile(all, p.copy(hideUnknownLanguage = true), "tv"))
    }

    @Test
    fun preferredLanguagesSortFirst() {
        val english = file("Movie.English.1080p.mkv")
        val unknown = file("Movie.1080p.mkv")
        val tamil = file("Movie.1080p.mkv", audio = listOf("Tamil"))
        val tamil720 = file("Movie.Tamil.720p.mkv")
        val hindi4k = file("Movie.Hindi.2160p.mkv")
        val p = StremioServer.StreamPrefs(preferredLanguages = listOf("Tamil", "English"))
        // Every Tamil link (best quality first), then English, then the rest by quality
        assertEquals(listOf(tamil, tamil720, english, hindi4k, unknown),
            StremioServer.arrangeStreams(listOf(english, unknown, tamil720, tamil, hindi4k), p))
        // Grouped by provider: still every Tamil link first, providers grouped inside
        val tamilB = file("Movie.Tamil.720p.mkv").let { it.copy(info = it.info!!.copy(addonName = "B")) }
        assertEquals(listOf(tamilB, tamil, hindi4k),
            StremioServer.arrangeStreams(listOf(hindi4k, tamil, tamilB), p.copy(groupBy = "provider", providerOrder = listOf("B", "X"))))
    }

    @Test
    fun keptLanguagesOnlySortInsideAQuality() {
        val unknown = file("Movie.1080p.mkv")
        val tamil = file("Movie.Tamil.1080p.mkv")
        val hindi4k = file("Movie.Hindi.2160p.mkv")
        val p = StremioServer.StreamPrefs(allowedLanguages = setOf("Tamil", "Hindi"))
        assertEquals(listOf(hindi4k, tamil, unknown), StremioServer.arrangeStreams(listOf(unknown, tamil, hindi4k), p))
    }

    @Test
    fun keptWhenAnyVariantIsAllowed() {
        val only720 = StremioServer.StreamPrefs(allowedResolutions = setOf("720p"))
        assertEquals(1, StremioServer.filterStreamsByProfile(listOf(hls(listOf(1080, 720, 480))), only720).size)
        assertEquals(0, StremioServer.filterStreamsByProfile(listOf(hls(listOf(1080))), only720).size)
    }
}
