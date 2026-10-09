package com.cncverse.stremiobridge.server.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeHeadersTest {
    @Test
    fun `LivXow JioTV url loses the pipe and keeps the cookie as a header`() {
        val token = "st=1791522032~exp=1791543632~acl=/bpk-tv/TV9_BTS/WDVLive/*~hmac=77cf59"
        val (url, headers) = splitPipeHeaders(
            "https://jiotvbpkstb.cdn.jio.com/bpk-tv/TV9_BTS/WDVLive/index.mpd?__hdnea__=$token&xxx=|cookie=__hdnea__=$token"
        )
        assertEquals("https://jiotvbpkstb.cdn.jio.com/bpk-tv/TV9_BTS/WDVLive/index.mpd?__hdnea__=$token", url)
        assertEquals(mapOf("cookie" to "__hdnea__=$token"), headers)
        assertTrue(java.net.URI(url).isAbsolute)
    }

    @Test
    fun `several pipe headers, quoted values`() {
        val (url, headers) = splitPipeHeaders("https://a.b/x.mpd|User-Agent=\"Custom\"&Referer=https://r.c/")
        assertEquals("https://a.b/x.mpd", url)
        assertEquals(mapOf("User-Agent" to "Custom", "Referer" to "https://r.c/"), headers)
    }

    @Test
    fun `plain urls only lose a bare trailing question mark`() {
        assertEquals("https://a.b/x.mpd?t=1" to emptyMap<String, String>(), splitPipeHeaders("https://a.b/x.mpd?t=1"))
        assertEquals("https://a.b/x.mpd" to emptyMap<String, String>(), splitPipeHeaders("https://a.b/x.mpd?"))
    }
}
