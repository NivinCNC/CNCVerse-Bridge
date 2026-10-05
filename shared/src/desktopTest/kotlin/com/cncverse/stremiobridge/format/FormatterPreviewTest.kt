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
}
