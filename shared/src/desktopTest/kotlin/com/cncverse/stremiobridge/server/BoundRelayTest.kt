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
    fun `video files signed for WARP's network are relayed, others are not`() {
        fun s(url: String) = com.cncverse.stremiobridge.model.StremioStream(url = url)
        assertTrue(BoundRelay.warpSignedVideo(s("https://fsldownload.com/p/KUBQ5v.TFwe?mg=1791999293.13335.f-G0JwShPUvrsYaMrpVeqQ")))
        assertTrue(BoundRelay.warpSignedVideo(s("https://cdn.example/v.mkv?x=1&asn=13335&t=2")))
        // Unlocked from the server's own network (Oracle, AS31898): plays anywhere it plays at all
        assertTrue(!BoundRelay.warpSignedVideo(s("https://fsldownload.com/p/KUBQ5v?mg=1791999293.31898.NoO5RJTPy23rcwR0DZdvBg")))
        assertTrue(!BoundRelay.warpSignedVideo(s("https://cdn.example/v.mkv?id=13335")))
        // Playlists go through the playlist check instead
        assertTrue(!BoundRelay.warpSignedVideo(s("https://cdn.example/master.m3u8?asn=13335")))
    }

    @Test
    fun `an IP-bound master relays only its playlists, from the server itself`() {
        val nf = mapOf("Referer" to "https://net52.cc/")
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="https://subscdn.top/subs/81749852/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="https://s00021.freecdn4.top/files/81749852/a/5/5.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aac",RESOLUTION=1920x1080
            https://hd1.freecdn4.top/files/81749852/1080p/1080p.m3u8?in=1.2.3.4::abc::1791988112::ni::t
        """.trimIndent()
        val out = BoundRelay.rewritePlaylist(master, "https://tv.imgcdn.kim/newtv/hls/nf/81749852.m3u8?in=x", nf, "t:abc").lines()
        assertEquals(BoundRelay.relayUrl("https://subscdn.top/subs/81749852/en.m3u8", nf, "d"), Regex("URI=\"([^\"]+)\"").find(out[1])!!.groupValues[1])
        assertEquals(BoundRelay.relayUrl("https://s00021.freecdn4.top/files/81749852/a/5/5.m3u8", nf, "d"), Regex("URI=\"([^\"]+)\"").find(out[2])!!.groupValues[1])
        assertEquals(BoundRelay.relayUrl("https://hd1.freecdn4.top/files/81749852/1080p/1080p.m3u8?in=1.2.3.4::abc::1791988112::ni::t", nf, "d"), out[4])
        // Its segments go straight from the CDN to the player
        val variant = "#EXTM3U\n#EXTINF:3.6,\n3670_000.jpg"
        val v = BoundRelay.rewritePlaylist(variant, "https://hd1.freecdn4.top/files/81749852/1080p/1080p.m3u8?in=t", nf, "d").lines()
        assertEquals("https://hd1.freecdn4.top/files/81749852/1080p/3670_000.jpg", v[2])
        // The route is part of the signature
        assertTrue(BoundRelay.relayUrl("https://a/x.m3u8", nf, "d").substringAfter("sig=") != BoundRelay.relayUrl("https://a/x.m3u8", nf).substringAfter("sig="))
    }

    @Test
    fun `a relay link signed for one url does not open another`() {
        val a = BoundRelay.relayUrl("https://hanerix.com/a.m3u8", ref).substringAfter("sig=")
        val b = BoundRelay.relayUrl("https://evil.example/", ref).substringAfter("sig=")
        val c = BoundRelay.relayUrl("https://hanerix.com/a.m3u8", mapOf("Referer" to "https://evil/")).substringAfter("sig=")
        assertTrue(a != b && a != c)
    }
}
