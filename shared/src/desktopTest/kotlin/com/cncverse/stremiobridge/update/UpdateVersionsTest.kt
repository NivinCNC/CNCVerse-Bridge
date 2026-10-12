package com.cncverse.stremiobridge.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateVersionsTest {
    private fun release(tag: String, vararg files: String, prerelease: Boolean = false, draft: Boolean = false) =
        GithubRelease(
            tagName = tag,
            htmlUrl = "https://example.invalid/$tag",
            prerelease = prerelease,
            draft = draft,
            assets = files.map { GithubAsset("https://example.invalid/$it", it) },
        )

    private fun pick(releases: List<GithubRelease>, current: String = "1.5.9", skipped: String? = null, ext: String = ".apk") =
        UpdateVersions.pickUpdate(releases, current, desktop = false, assetExtension = ext, skipped = skipped)

    @Test
    fun tagsBecomeVersions() {
        assertEquals("1.5.10", UpdateVersions.versionOf("v1.5.10", desktop = false))
        assertEquals("1.5.10", UpdateVersions.versionOf("1.5.10", desktop = true))
        assertNull(UpdateVersions.versionOf("latest", desktop = false))
        assertNull(UpdateVersions.versionOf("nightly-2026", desktop = true))
        // dual tag: Android version first, desktop version after "-d"
        assertEquals("0.0.22", UpdateVersions.versionOf("v0.0.22-d0.0.23", desktop = false))
        assertEquals("0.0.23", UpdateVersions.versionOf("v0.0.22-d0.0.23", desktop = true))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateVersions.isNewer("1.5.10", "1.5.9"))
        assertTrue(UpdateVersions.isNewer("1.6.0", "1.5.99"))
        assertTrue(UpdateVersions.isNewer("2", "1.9.9"))
        assertFalse(UpdateVersions.isNewer("1.5.9", "1.5.9"))
        assertFalse(UpdateVersions.isNewer("1.5.9", "1.5.10"))
        assertEquals(0, UpdateVersions.compare("1.5", "1.5.0"))
    }

    @Test
    fun rollingLatestReleaseNeverHidesARealRelease() {
        // GitHub lists the newest first: the rolling "latest" build is on top of the real releases
        val list = listOf(release("latest", "CNCVerse-Bridge.apk"), release("v1.5.10", "CNCVerse-Bridge-v1.5.10.apk"))
        assertEquals("v1.5.10", pick(list)?.tagName)
    }

    @Test
    fun offersTheHighestNewerVersion() {
        val list = listOf(
            release("v1.5.10", "a.apk"), release("v1.6.0", "b.apk"), release("v1.5.9", "c.apk"),
        )
        assertEquals("v1.6.0", pick(list)?.tagName)
    }

    @Test
    fun nothingWhenUpToDate() {
        assertNull(pick(listOf(release("v1.5.9", "a.apk")), current = "1.5.9"))
        assertNull(pick(listOf(release("v1.5.8", "a.apk")), current = "1.5.9"))
        assertNull(pick(emptyList()))
    }

    @Test
    fun skippedVersionIsNotOfferedButANewerOneIs() {
        val only10 = listOf(release("v1.5.10", "a.apk"))
        assertNull(pick(only10, skipped = "1.5.10"))
        val with11 = listOf(release("v1.5.11", "b.apk"), release("v1.5.10", "a.apk"))
        assertEquals("v1.5.11", pick(with11, skipped = "1.5.10")?.tagName)
        // an old skip never blocks a manual check
        assertEquals("v1.5.10", pick(only10, skipped = "1.5.9")?.tagName)
    }

    @Test
    fun draftsPrereleasesAndReleasesWithoutAFileForThisPlatformAreIgnored() {
        assertNull(pick(listOf(release("v1.6.0", "a.apk", draft = true))))
        assertNull(pick(listOf(release("v1.6.0", "a.apk", prerelease = true))))
        // only a Windows installer: nothing for Android
        assertNull(pick(listOf(release("v1.6.0", "a.msi")), ext = ".apk"))
        assertEquals("v1.6.0", pick(listOf(release("v1.6.0", "a.msi")), ext = ".msi")?.tagName)
    }
}
