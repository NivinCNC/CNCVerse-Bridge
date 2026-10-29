package com.cncverse.stremiobridge.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalLinkCacheTest {
    @Test
    fun localServerLinksAreNeverCached() {
        // ReAnime / GoTaku keep only their last 10–20 streams: a stored link soon 404s
        for (u in listOf(
            "http://127.0.0.1:39295/a5e8774e6057/master.m3u8?lang=sub",
            "http://localhost:36247/da82abd05b/m/2/master.m3u8",
        )) {
            val c = StreamLinkClassifier.classify(u)
            assertFalse(c.isCacheable, u)
            assertEquals("LOCAL_SERVER", c.category)
        }
        assertTrue(StreamLinkClassifier.classify("https://pixeldrain.dev/api/file/abc?download").isCacheable)
    }
}
