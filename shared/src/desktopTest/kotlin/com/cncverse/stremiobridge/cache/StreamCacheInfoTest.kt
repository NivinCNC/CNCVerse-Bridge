package com.cncverse.stremiobridge.cache

import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StreamCacheInfoTest {
    @Test
    fun `stream info survives a save and restore`() {
        val dir = kotlin.io.path.createTempDirectory("cnc-cache").toFile()
        StreamCacheManager.init(dir.absolutePath)
        val info = StreamInfo(addonName = "FourKHDHub", quality = 2160, linkType = "VIDEO",
            linkName = "Unabomber NF WEB-DL DDP5 ATMOS [18.61 GB]", source = "HubCloud", providerName = "4K HDHUB")
        val key = "stream:generic:movie:tt6933238:test"
        StreamCacheManager.put(key, listOf(StremioStream(name = "FourKHDHub", title = "t", url = "https://example.com/files/unabomber.mkv", info = info)))
        StreamCacheManager.saveToDiskNow()

        // "Restart": load the file again; restored entries replace the in-memory ones
        StreamCacheManager.init(dir.absolutePath)
        val restored = StreamCacheManager.get(key)
        assertNotNull(restored, "entry restored")
        assertEquals(info, restored.single().info)
        dir.deleteRecursively()
    }
}
