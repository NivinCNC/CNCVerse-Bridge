package com.cncverse.stremiobridge.format

import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SizeBitrateTest {
    private fun file(name: String, size: Long? = null, bps: Long? = null) = StremioStream(
        name = "X", title = name, url = "https://f.example/x.mkv",
        info = StreamInfo(addonName = "4K HDHUB", linkType = "VIDEO", linkName = name, sizeBytes = size, bitrateBps = bps),
    )

    private fun render(s: StremioStream, presetId: String): String =
        StreamFormatter.applyFor(listOf(s), StreamRequestContext(), ProfileFormatter(mode = "preset", presetId = presetId))
            .single().title.orEmpty()

    @Test
    fun bitrateText() {
        assertEquals("~17.6 Mbps", StreamVariables.bitrateText(17_600_000))
        assertEquals("~0.8 Mbps", StreamVariables.bitrateText(800_000))
        assertNull(StreamVariables.bitrateText(null))
        assertNull(StreamVariables.bitrateText(0))
    }

    @Test
    fun realSizeWinsOverTheName() {
        // The name says 2 GB, the server says 14.4 GB: the server is right
        val s = file("Movie.2026.2160p.WEB-DL.mkv [2GB]", size = 14_420_352_696)
        assertEquals(14_420_352_696, StreamVariables.sizeBytesOf(s))
        assertEquals(2L * 1024 * 1024 * 1024, StreamVariables.sizeBytesOf(file("Movie.2026.2160p.WEB-DL.mkv [2GB]")))
    }

    @Test
    fun everyPresetShowsSizeAndBitrate() {
        val s = file("Love.Of.Your.Life.2026.2160p.AMZN.WEB-DL.mkv", size = 14_420_352_696, bps = 17_600_000)
        for (p in StreamFormatter.PRESETS) {
            val text = render(s, p.id)
            assertTrue("13.4 GB" in text, "${p.id}: $text")
            assertTrue("~17.6 Mbps" in text, "${p.id}: $text")
        }
    }

    /** The server template saved on prod (24 Oct), before {stream.bitrate} existed. */
    private val prodTemplate = """
        {stream.seasonEpisode::exists["📡 {metadata.title} • {stream.seasonEpisode::join('')}"||"🍿 {metadata.title::default('CNCstream')}"]}
        {stream.label::exists["🏷️ {stream.label::truncate(90)}"||""]}
        🎞️ {stream.specs::length::>0["{stream.specs::join(' • ')}"||"{stream.resolution::=2160p["4K"||"{stream.resolution::default('Auto')}"]} • {stream.streamType::default('HLS')}"]}
        🛰️ Source: {addon.name}{stream.source::exists[" • {stream.source}"||""]}
        {stream.size::>0["💾 {stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')}"||""]}
        {stream.languages::exists["🎧 Audio: {stream.languages::join(', ')}"||""]}
        {stream.subtitles::exists["📝 Subtitles: {stream.subtitles::join(', ')}"||"🙊 No included subtitles"]}
    """.trimIndent()

    @Test
    fun savedServerTemplateGetsTheBitrate() {
        val old = StreamFormatterConfig(enabled = true, nameTemplate = "{addon.name}", descriptionTemplate = prodTemplate)
        val migrated = StreamFormatter.withBitrate(old)
        assertTrue("stream.bitrate" in migrated.descriptionTemplate)
        assertTrue("🙊 No included subtitles" in migrated.descriptionTemplate) // the admin's wording stays
        // Already migrated, or no size snippet at all: unchanged
        assertEquals(migrated, StreamFormatter.withBitrate(migrated))
        val custom = old.copy(descriptionTemplate = "{addon.name} {stream.size::bytes}")
        assertEquals(custom, StreamFormatter.withBitrate(custom))
        // And it renders: size with the speed after it
        val text = StreamFormatter.applyFor(
            listOf(file("Movie.2026.2160p.mkv", size = 14_420_352_696, bps = 17_600_000)),
            StreamRequestContext(),
            ProfileFormatter(mode = "custom", nameTemplate = "{addon.name}", descriptionTemplate = migrated.descriptionTemplate),
        ).single().title.orEmpty()
        assertTrue("💾 13.4 GB · ~17.6 Mbps" in text, text)
    }

    @Test
    fun usersCustomTemplateGetsTheBitrateToo() {
        // A user's own template saved before {stream.bitrate} existed, used as it is
        val text = StreamFormatter.applyFor(
            listOf(file("Movie.2026.2160p.mkv", size = 14_420_352_696, bps = 17_600_000)),
            StreamRequestContext(),
            ProfileFormatter(mode = "custom", nameTemplate = "{addon.name}", descriptionTemplate = prodTemplate),
        ).single().title.orEmpty()
        assertTrue("💾 13.4 GB · ~17.6 Mbps" in text, text)
        assertEquals(1, Regex("Mbps").findAll(text).count(), text)
    }

    @Test
    fun noBitrateWithoutOne() {
        val text = render(file("Movie.mkv", size = 1_500_000_000), "cncverse")
        assertTrue("1.4 GB" in text, text)
        assertFalse("Mbps" in text, text)
        assertFalse(" · " in text.lines().first { "💾" in it }, text)
    }
}
