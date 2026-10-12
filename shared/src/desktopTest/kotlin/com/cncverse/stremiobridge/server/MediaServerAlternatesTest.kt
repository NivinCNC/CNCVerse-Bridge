package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaServerAlternatesTest {
    @Test
    fun pixeldrainMirrorHasPixeldrainsOwnSeekableAddressesBehindIt() {
        val expected = listOf(
            "https://pixeldrain.com/api/file/AbC123_-x",
            "https://pixeldrain.dev/api/file/AbC123_-x",
            "https://pixeldrain.net/api/file/AbC123_-x",
        )
        assertEquals(expected, MediaServer.alternatesOf("https://cdn.pixeldrain.eu.cc/AbC123_-x"))
        // with a query string, a path tail or another case of the host
        assertEquals(expected, MediaServer.alternatesOf("https://cdn.pixeldrain.eu.cc/AbC123_-x?download"))
        assertEquals(expected, MediaServer.alternatesOf("http://CDN.Pixeldrain.EU.cc/AbC123_-x/file.mkv"))
    }

    @Test
    fun otherAddressesHaveNoAlternates() {
        assertEquals(emptyList(), MediaServer.alternatesOf("https://pixeldrain.com/api/file/AbC123"))
        assertEquals(emptyList(), MediaServer.alternatesOf("https://cdn.example.com/AbC123"))
        assertEquals(emptyList(), MediaServer.alternatesOf("https://cdn.pixeldrain.eu.cc/"))
        assertEquals(emptyList(), MediaServer.alternatesOf("https://notcdn.pixeldrain.eu.cc.evil.example/AbC123"))
    }
}
