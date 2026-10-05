package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackArrangementTest {
    private fun stream(addon: String, quality: Int, linkName: String) = StremioStream(
        name = "Release\n$addon - ${quality}p",
        title = linkName,
        url = "https://example.test/$addon/$quality/${linkName.hashCode()}",
        info = StreamInfo(addonName = addon, quality = quality, linkName = linkName),
    )

    private fun order(streams: List<StremioStream>) = streams.map { "${it.info?.addonName}@${it.info?.quality}" }

    private val sample = listOf(
        stream("Alpha", 1080, "1080p [1.0GB]"),
        stream("Beta", 1080, "1080p [4.0GB]"),
        stream("Gamma", 1080, "1080p"),
        stream("Alpha", 720, "720p [0.5GB]"),
        stream("Beta", 2160, "2160p [9.0GB]"),
    ).sortedByDescending { it.info?.quality } // as respondStreams does before filtering

    @Test
    fun `no settings leaves the order alone`() {
        assertEquals(sample, StremioServer.arrangeStreams(sample, StremioServer.ProfileRecord()))
    }

    @Test
    fun `provider order decides who goes first inside a resolution but never beats resolution`() {
        val out = StremioServer.arrangeStreams(sample, StremioServer.ProfileRecord(providerOrder = listOf("Gamma", "Beta", "Alpha")))
        assertEquals(listOf("Beta@2160", "Gamma@1080", "Beta@1080", "Alpha@1080", "Alpha@720"), order(out))
    }

    @Test
    fun `grouping by provider keeps each provider together in the chosen order`() {
        val out = StremioServer.arrangeStreams(sample, StremioServer.ProfileRecord(groupBy = "provider", providerOrder = listOf("Beta", "Alpha")))
        // Beta first (2160 then 1080), then Alpha (1080 then 720), unlisted Gamma last
        assertEquals(listOf("Beta@2160", "Beta@1080", "Alpha@1080", "Alpha@720", "Gamma@1080"), order(out))
    }

    @Test
    fun `sorting by size puts the biggest file first inside a resolution and unknown size last`() {
        val out = StremioServer.arrangeStreams(sample, StremioServer.ProfileRecord(sortBy = "size"))
        assertEquals(listOf("Beta@2160", "Beta@1080", "Alpha@1080", "Gamma@1080", "Alpha@720"), order(out))
    }

    @Test
    fun `size window drops known sizes outside it and keeps unknown ones`() {
        val out = StremioServer.filterStreamsByProfile(sample, StremioServer.ProfileRecord(minSizeGb = 0.8, maxSizeGb = 5.0))
        // 0.5GB too small, 9GB too big; 1GB, 4GB and the size-less one stay
        assertEquals(setOf("Alpha@1080", "Beta@1080", "Gamma@1080"), order(out).toSet())
    }

    @Test
    fun `only a minimum or only a maximum works too`() {
        val minOnly = StremioServer.filterStreamsByProfile(sample, StremioServer.ProfileRecord(minSizeGb = 2.0))
        assertEquals(setOf("Beta@1080", "Gamma@1080", "Beta@2160"), order(minOnly).toSet())
        val maxOnly = StremioServer.filterStreamsByProfile(sample, StremioServer.ProfileRecord(maxSizeGb = 2.0))
        assertEquals(setOf("Alpha@1080", "Gamma@1080", "Alpha@720"), order(maxOnly).toSet())
    }
}
