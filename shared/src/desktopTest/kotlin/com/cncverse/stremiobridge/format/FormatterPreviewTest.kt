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
    fun `episode context comes from every id form`() {
        assertEquals(StreamRequestContext(1, 2), StreamFormatter.contextFromId("series", "kitsu:7442:2"))
        assertEquals(StreamRequestContext(1, 3), StreamFormatter.contextFromId("series", "tmdb:1396:1:3"))
        assertEquals(StreamRequestContext(2, 5), StreamFormatter.contextFromId("series", "tt0903747:2:5"))
    }
}
