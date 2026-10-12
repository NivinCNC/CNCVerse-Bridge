package com.cncverse.stremiobridge.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatterPreviewTest {
    @Test
    fun `every preset previews without throwing`() {
        for (p in StreamFormatter.PRESETS) {
            val r = StreamFormatter.preview(p.nameTemplate, p.descriptionTemplate)
            assertTrue(r.ok, "preset ${p.id}: ${r.error}")
            assertEquals(3, r.samples.size, "preset ${p.id}")
        }
    }

    @Test
    fun `custom and empty templates preview`() {
        assertTrue(StreamFormatter.preview("{addon.name} {stream.resolution}", "").ok)
        assertTrue(StreamFormatter.preview("", "").ok)
    }

    @Test
    fun `a bad template is reported, not thrown`() {
        assertTrue(!StreamFormatter.preview("{unclosed", "").ok)
    }

    @Test
    fun `the matched extension entry is shown so the user can judge the result`() {
        val s = com.cncverse.stremiobridge.model.StremioStream(
            name = "Clevatess Season 2\nAnimePahe - 1080p",
            title = "Clevatess - 07",
            url = "https://example.com/a.mp4",
            info = com.cncverse.stremiobridge.model.StreamInfo(addonName = "AnimePahe", quality = 1080, matchedName = "Clevatess Season 2"),
        )
        val out = StreamFormatter.format(
            s, StreamRequestContext(2, 7),
            Template.parse(StreamFormatter.PRESET_NAME), Template.parse(StreamFormatter.PRESET_DESCRIPTION),
        )
        assertTrue(out.title.orEmpty().contains("Matched: Clevatess Season 2"), out.title)
        // links cached by an older version have no matchedName: the entry is still the first line of the name
        val old = s.copy(info = s.info!!.copy(matchedName = null))
        val outOld = StreamFormatter.format(
            old, StreamRequestContext(2, 7),
            Template.parse(StreamFormatter.PRESET_NAME), Template.parse(StreamFormatter.PRESET_DESCRIPTION),
        )
        assertTrue(outOld.title.orEmpty().contains("Matched: Clevatess Season 2"), outOld.title)
    }

    @Test
    fun `the file name of the link is shown`() {
        fun stream(url: String, linkName: String? = null) = com.cncverse.stremiobridge.model.StremioStream(
            name = "Clevatess Season 2\nAnimePahe - 1080p", title = "Clevatess - 08", url = url,
            info = com.cncverse.stremiobridge.model.StreamInfo(addonName = "AnimePahe", quality = 1080, linkName = linkName),
        )
        fun render(s: com.cncverse.stremiobridge.model.StremioStream) = StreamFormatter.format(
            s, StreamRequestContext(2, 8),
            Template.parse(StreamFormatter.PRESET_NAME), Template.parse(StreamFormatter.PRESET_DESCRIPTION),
        ).title.orEmpty()

        // from the path (percent-encoded and with a query string)
        assertTrue(render(stream("https://cdn.example.com/dl/Clevatess%20S02E08.1080p.WEB-DL.mkv?token=a+b")).contains("File: Clevatess S02E08.1080p.WEB-DL.mkv"))
        // from a query parameter
        assertTrue(render(stream("https://host.example.com/get?file=Clevatess.S02E08.720p.mp4&x=1")).contains("File: Clevatess.S02E08.720p.mp4"))
        // from the link's own name
        assertTrue(render(stream("https://host.example.com/v/abc123", "Clevatess.S02E08.1080p.mkv")).contains("File: Clevatess.S02E08.1080p.mkv"))
        // adaptive streams and ids without a file name get no file line
        assertTrue(!render(stream("https://host.example.com/hls/master.m3u8")).contains("File:"))
        // the file name is not repeated as the link label
        val twice = render(stream("https://cdn.example.com/a/Clevatess.S02E08.mkv", "Clevatess.S02E08.mkv"))
        assertEquals(1, Regex("Clevatess\\.S02E08\\.mkv").findAll(twice).count(), twice)
    }

    @Test
    fun `the extension's episode number and name are shown with the file name`() {
        fun render(matchedEpisode: String?, url: String = "https://cdn.example.com/Clevatess.S02E08.1080p.mkv") = StreamFormatter.format(
            com.cncverse.stremiobridge.model.StremioStream(
                name = "Clevatess Season 2\nAnimePahe - 1080p", title = "Clevatess - 08", url = url,
                info = com.cncverse.stremiobridge.model.StreamInfo(
                    addonName = "AnimePahe", quality = 1080, matchedName = "Clevatess Season 2", matchedEpisode = matchedEpisode,
                ),
            ),
            StreamRequestContext(2, 8),
            Template.parse(StreamFormatter.PRESET_NAME), Template.parse(StreamFormatter.PRESET_DESCRIPTION),
        ).title.orEmpty()

        // episode as the extension lists it, then the file, on one line
        val both = render("S2E8 · The Demon King")
        assertTrue(both.contains("Ep S2E8 · The Demon King • File: Clevatess.S02E08.1080p.mkv"), both)
        // a link with no file name still shows the episode; one without an episode keeps the file line as before
        assertTrue(render("S2E8 · The Demon King", "https://host.example.com/hls/master.m3u8").contains("Ep S2E8 · The Demon King"))
        val noEpisode = render(null)
        assertTrue(noEpisode.contains("File: Clevatess.S02E08.1080p.mkv") && !noEpisode.contains("Ep S"), noEpisode)
        // every built-in layout carries it
        for (p in StreamFormatter.PRESETS) {
            val out = StreamFormatter.format(
                com.cncverse.stremiobridge.model.StremioStream(
                    name = "Clevatess Season 2\nAnimePahe - 1080p", title = "Clevatess - 08", url = "https://cdn.example.com/a.mkv",
                    info = com.cncverse.stremiobridge.model.StreamInfo(addonName = "AnimePahe", quality = 1080, matchedEpisode = "S2E8 · The Demon King"),
                ),
                StreamRequestContext(2, 8), Template.parse(p.nameTemplate), Template.parse(p.descriptionTemplate),
            ).title.orEmpty()
            assertTrue(out.contains("S2E8 · The Demon King"), "preset ${p.id}: $out")
        }
    }

    @Test
    fun `episode context comes from every id form`() {
        assertEquals(StreamRequestContext(1, 2), StreamFormatter.contextFromId("series", "kitsu:7442:2"))
        assertEquals(StreamRequestContext(1, 3), StreamFormatter.contextFromId("series", "tmdb:1396:1:3"))
        assertEquals(StreamRequestContext(2, 5), StreamFormatter.contextFromId("series", "tt0903747:2:5"))
    }
}
