package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.format.SubtitleLangs
import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubDubAndLangTest {
    @Test
    fun `subtitle labels seen on Shang-Chi map to ISO 639-2`() {
        val cases = mapOf(
            "English (Original Audio)" to "eng", "en (Hindi Audio)" to "eng", "English (Hindi Audio)" to "eng",
            "हिन्दी (Original Audio)" to "hin", "বাংলা (Original Audio)" to "ben", "اَلْعَرَبِيَّةُ (Original Audio)" to "ara",
            "Filipino (Original Audio)" to "fil", "Français (Original Audio)" to "fre", "Indonesian (Original Audio)" to "ind",
            "in_id (Original Audio)" to "ind", "Malay (Original Audio)" to "may", "Português (Original Audio)" to "por",
            "Русский (Original Audio)" to "rus", "اُردُو (Original Audio)" to "urd", "中文 (Original Audio)" to "chi",
            "pt-BR" to "por", "eng" to "eng", "English SDH" to "eng",
        )
        cases.forEach { (raw, code) -> assertEquals(code, SubtitleLangs.normalize(raw), raw) }
        assertEquals("Klingon", SubtitleLangs.normalize("Klingon")) // unknown stays as it was
    }

    private fun ep(n: Int, variant: String?, name: String? = "Episode $n" + (variant?.let { " ($it)" } ?: "")) =
        MediaInfoEpisode(name, 1, n, "data-$n-$variant", null, variant = variant)

    @Test
    fun `sub and dub copies of an episode become one episode carrying both`() {
        val merged = mergeDubVariants(listOf(ep(1, "Dubbed"), ep(2, "Dubbed"), ep(1, "Subbed"), ep(2, "Subbed"), ep(3, "Subbed")))
        assertEquals(listOf(1, 2, 3), merged.map { it.episode })
        assertEquals("Episode 1", merged[0].name)
        assertEquals(listOf("Subbed" to "data-1-Subbed", "Dubbed" to "data-1-Dubbed"), VariantData.decode(merged[0].dataUrl))
        // Episode 3 only exists subbed: plain data, no wrapper
        assertEquals("data-3-Subbed", merged[2].dataUrl)
        assertNull(merged[0].variant)
    }

    @Test
    fun `a list with one variant only is left untouched`() {
        val eps = listOf(ep(1, null), ep(2, null))
        assertEquals(eps, mergeDubVariants(eps))
    }

    @Test
    fun `provider order uses the extension display name`() {
        fun s(addon: String, provider: String) = StremioStream(name = addon, url = "u-$addon",
            info = StreamInfo(addonName = addon, quality = 1080, providerName = provider))
        val streams = listOf(s("VegaMovies", "VegaMovies"), s("FourKHDHub", "4K HDHUB"))
        val out = StremioServer.arrangeStreams(streams, StremioServer.ProfileRecord(providerOrder = listOf("4K HDHUB", "VegaMovies")))
        assertEquals(listOf("FourKHDHub", "VegaMovies"), out.map { it.info?.addonName })
    }
}
