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
    fun `episode context comes from every id form`() {
        assertEquals(StreamRequestContext(1, 2), StreamFormatter.contextFromId("series", "kitsu:7442:2"))
        assertEquals(StreamRequestContext(1, 3), StreamFormatter.contextFromId("series", "tmdb:1396:1:3"))
        assertEquals(StreamRequestContext(2, 5), StreamFormatter.contextFromId("series", "tt0903747:2:5"))
    }
}
