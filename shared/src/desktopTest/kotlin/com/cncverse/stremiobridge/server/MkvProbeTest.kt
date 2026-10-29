package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MkvProbeTest {
    private fun id(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** Element with an 8-byte size field (as mkvmerge writes for big ones). */
    private fun el(id: ByteArray, payload: ByteArray): ByteArray {
        val size = ByteArray(8)
        size[0] = 0x01
        var n = payload.size.toLong()
        for (i in 7 downTo 1) { size[i] = (n and 0xFF).toByte(); n = n shr 8 }
        return id + size + payload
    }

    private fun uint(id: ByteArray, v: Int) = el(id, byteArrayOf(v.toByte()))
    private fun str(id: ByteArray, s: String) = el(id, s.toByteArray())

    private val TRACK_TYPE = id(0x83)
    private val LANGUAGE = id(0x22, 0xB5, 0x9C)
    private val NAME = id(0x53, 0x6E)

    private fun track(type: Int, lang: String?, name: String? = null) = el(id(0xAE),
        uint(TRACK_TYPE, type) + (lang?.let { str(LANGUAGE, it) } ?: ByteArray(0)) + (name?.let { str(NAME, it) } ?: ByteArray(0)))

    private val tracks = el(id(0x16, 0x54, 0xAE, 0x6B),
        track(1, "und") + track(2, "hin") + track(2, "tam", "Tamil 5.1") + track(2, null) + track(2, "ger") + track(17, "eng"))

    private val header = el(id(0x1A, 0x45, 0xDF, 0xA3), str(id(0x42, 0x82), "matroska"))

    @Test
    fun readsAudioTrackLanguages() {
        val info = el(id(0x15, 0x49, 0xA9, 0x66), ByteArray(10))
        val file = header + el(id(0x18, 0x53, 0x80, 0x67), info + tracks + el(id(0x1F, 0x43, 0xB6, 0x75), ByteArray(50)))
        val r = MkvProbe.parse(file)
        assertIs<MkvProbe.Parsed.Tracks>(r)
        // Video and subtitle tracks skipped; no Language element = English; "ger" = German
        assertEquals(listOf("Hindi", "Tamil", "English", "German"), r.languages)
    }

    @Test
    fun followsTheSeekHeadWhenTracksAreFurtherIn() {
        // SeekHead says Tracks is at segment offset 0x200 — past what was read
        val seek = el(id(0x4D, 0xBB), el(id(0x53, 0xAB), id(0x16, 0x54, 0xAE, 0x6B)) + el(id(0x53, 0xAC), byteArrayOf(0x02, 0x00)))
        val seekHead = el(id(0x11, 0x4D, 0x9B, 0x74), seek)
        val segHeader = id(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) // unknown size
        val file = header + segHeader + seekHead + el(id(0x1F, 0x43, 0xB6, 0x75), ByteArray(20))
        val r = MkvProbe.parse(file)
        assertIs<MkvProbe.Parsed.SeekTo>(r)
        assertEquals((header.size + segHeader.size + 0x200).toLong(), r.offset)
        assertEquals(listOf("Hindi", "Tamil", "English", "German"), MkvProbe.tracksAt(tracks))
    }

    @Test
    fun notAnMkv() {
        assertNull(MkvProbe.parse("ftypisom....".toByteArray()))
        assertNull(MkvProbe.parse(ByteArray(0)))
    }
}
