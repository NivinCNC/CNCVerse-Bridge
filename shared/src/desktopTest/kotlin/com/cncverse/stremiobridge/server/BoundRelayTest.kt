package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundRelayTest {
    private val ref = mapOf("Referer" to "https://hanerix.com/")

    @Test
    fun `sites are the last two host labels`() {
        assertEquals("acek-cdn.com", BoundRelay.siteOf("h85MclLE5sxF9yF.acek-cdn.com"))
        assertEquals("hanerix.com", BoundRelay.siteOf("hanerix.com"))
    }

    @Test
    fun `every playlist entry goes back through the relay with the same headers`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio0",URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1331391
            index-v1-a1.m3u8?t=abc
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1"
            https://other-cdn.example/seg1.ts
        """.trimIndent()
        val out = BoundRelay.rewritePlaylist(master, "https://x.acek-cdn.com/hls2/01/master.m3u8?t=1", ref).lines()
        assertEquals("#EXTM3U", out[0])
        val audio = Regex("URI=\"([^\"]+)\"").find(out[1])!!.groupValues[1]
        assertEquals(BoundRelay.relayUrl("https://x.acek-cdn.com/hls2/01/audio/index.m3u8", ref), audio)
        assertEquals(BoundRelay.relayUrl("https://x.acek-cdn.com/hls2/01/index-v1-a1.m3u8?t=abc", ref), out[3])
        assertTrue(out[4].contains("/proxy/bound?d=https%3A%2F%2Fkeys.example%2Fk1"))
        assertEquals(BoundRelay.relayUrl("https://other-cdn.example/seg1.ts", ref), out[5])
        assertTrue(out[5].contains("&h_Referer=https%3A%2F%2Fhanerix.com%2F&sig="))
    }

    @Test
    fun `a relay link signed for one url does not open another`() {
        val a = BoundRelay.relayUrl("https://hanerix.com/a.m3u8", ref).substringAfter("sig=")
        val b = BoundRelay.relayUrl("https://evil.example/", ref).substringAfter("sig=")
        val c = BoundRelay.relayUrl("https://hanerix.com/a.m3u8", mapOf("Referer" to "https://evil/")).substringAfter("sig=")
        assertTrue(a != b && a != c)
    }
}
