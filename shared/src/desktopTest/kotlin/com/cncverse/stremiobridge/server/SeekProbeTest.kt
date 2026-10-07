package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekProbeTest {
    private lateinit var server: HttpServer
    private var port = 0

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Honours ranges: 206 with one byte
        server.createContext("/seekable.mkv") { ex ->
            val ranged = ex.requestHeaders.getFirst("Range") != null
            ex.responseHeaders.add("Content-Range", "bytes 0-0/1000")
            ex.sendResponseHeaders(if (ranged) 206 else 200, 1); ex.responseBody.use { it.write(1) }
        }
        // Ignores ranges: always the whole file
        server.createContext("/whole.mkv") { ex ->
            ex.sendResponseHeaders(200, 3); ex.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        // HLS playlist behind a URL without .m3u8 (workers.dev bridges): 200, but must be kept
        server.createContext("/bridge") { ex ->
            val body = "#EXTM3U".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        // Download page answering 206 with one byte of HTML: not a file
        server.createContext("/dl.php") { ex ->
            ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            ex.sendResponseHeaders(206, 1); ex.responseBody.use { it.write('B'.code) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun file(url: String, type: String? = "VIDEO") = StremioStream(name = url, url = url, info = StreamInfo(linkType = type))

    @Test
    fun `only plain direct files are judged`() {
        assertTrue(SeekProbe.isDirectFile(file("https://cdn.example/movie.mkv")))
        assertFalse(SeekProbe.isDirectFile(file("https://cdn.example/master.m3u8", "M3U8")))
        assertFalse(SeekProbe.isDirectFile(file("https://cdn.example/play.m3u8?t=1", null)))
        assertFalse(SeekProbe.isDirectFile(file("http://140.238.244.130/proxy/local/4182/a/master.m3u8")))
        assertFalse(SeekProbe.isDirectFile(file("http://140.238.244.130/decrypt?url=x")))
        assertFalse(SeekProbe.isDirectFile(StremioStream(name = "support", externalUrl = "https://cncverse.pages.dev")))
    }

    @Test
    fun `a host that ignores ranges is hidden, one that honours them is kept`() = runBlocking {
        SeekProbe.resetForTest()
        // Two host names for the same test server (verdicts are per host)
        val keep = file("http://127.0.0.1:$port/seekable.mkv")
        val drop = file("http://localhost:$port/whole.mkv")
        val hls = file("http://localhost:$port/live.m3u8", "M3U8")
        val out = SeekProbe.dropNonSeekable(listOf(keep, drop, hls))
        assertEquals(listOf(keep, hls), out)
    }

    @Test
    fun `a playlist without m3u8 in the url is kept, a download page is hidden`() = runBlocking {
        SeekProbe.resetForTest()
        val playlist = file("http://127.0.0.1:$port/bridge?url=x")
        val page = file("http://localhost:$port/dl.php?link=x")
        assertEquals(listOf(playlist), SeekProbe.dropNonSeekable(listOf(playlist, page)))
    }
}
