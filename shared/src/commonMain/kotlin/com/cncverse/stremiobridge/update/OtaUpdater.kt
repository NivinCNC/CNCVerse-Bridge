package com.cncverse.stremiobridge.update

import com.cncverse.stremiobridge.Constants
import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.onDownload
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName

@Serializable
data class GithubAsset(
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("name") val name: String
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList()
)

/**
 * Version handling of the update check. Pure functions (no network, no settings), so they can be tested.
 */
object UpdateVersions {
    private val VERSION = Regex("""\d+(\.\d+)*""")

    /**
     * The version a release tag stands for on this platform, or null when the tag is not a version
     * (the rolling build is tagged "latest"). Supports the dual-tag format v0.0.22-d0.0.22
     * (Android version, then the desktop version after "-d").
     */
    fun versionOf(tag: String, desktop: Boolean): String? {
        val raw = tag.trim().removePrefix("v").removePrefix("V")
        val version = if (desktop && raw.contains("-d")) raw.substringAfter("-d") else raw.substringBefore("-d")
        return version.takeIf { VERSION.matches(it) }
    }

    /** Numeric comparison of dotted versions: 1.5.10 is newer than 1.5.9. */
    fun compare(a: String, b: String): Int {
        val ap = a.split(".").mapNotNull { it.toIntOrNull() }
        val bp = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrElse(i) { 0 }
            val y = bp.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /**
     * The release to offer: the highest published version that is newer than [current], has a file for this
     * platform ([assetExtension]) and was not skipped by the user. Drafts, pre-releases and releases whose tag
     * is not a version (the rolling "latest" build) are ignored, so they can never hide a real release.
     */
    fun pickUpdate(
        releases: List<GithubRelease>,
        current: String,
        desktop: Boolean,
        assetExtension: String,
        skipped: String? = null,
    ): GithubRelease? {
        val best = releases.asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { r -> versionOf(r.tagName, desktop)?.let { v -> r to v } }
            .filter { (r, _) -> r.assets.any { it.name.endsWith(assetExtension, ignoreCase = true) } }
            .maxWithOrNull { x, y -> compare(x.second, y.second) }
            ?: return null
        val (release, version) = best
        if (!isNewer(version, current)) return null
        if (skipped != null && compare(version, skipped) == 0) return null
        return release
    }
}

object OtaUpdater {
    /** Releases of this fork, newest first. The list (not /releases/latest) is used because the rolling "latest" build is also a release. */
    private const val RELEASES_URL = "https://api.github.com/repos/${Constants.UPDATE_REPO}/releases?per_page=30"

    /** Setting that remembers the version the user chose to skip. */
    private const val KEY_SKIPPED_VERSION = "KEY_UPDATE_SKIPPED_VERSION"

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            // coerceInputValues: GitHub sends "body": null for a release without notes
            json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
        }
    }

    /** The version of this running app. */
    val currentVersion: String
        get() = if (isDesktopPlatform) Constants.DESKTOP_VERSION else Constants.ANDROID_VERSION

    /** The version a release stands for on this platform (the tag without "v"). */
    fun versionOf(release: GithubRelease): String =
        UpdateVersions.versionOf(release.tagName, isDesktopPlatform) ?: release.tagName.removePrefix("v")

    /** The version the user chose to skip, or null. */
    fun skippedVersion(): String? = getExtensionSetting(KEY_SKIPPED_VERSION)?.trim()?.takeIf { it.isNotEmpty() }

    /** "Skip this version": no dialog for [release] again. A newer release is offered as usual. */
    fun skipVersion(release: GithubRelease) {
        setExtensionSetting(KEY_SKIPPED_VERSION, versionOf(release))
    }

    /** The release notes for the dialog: the part of the release text before the download list. */
    fun notesOf(release: GithubRelease): String =
        release.body.substringBefore("## Downloads")
            .replace("**", "").replace("`", "")
            .lines().joinToString("\n") { it.trimEnd().removePrefix("## ").removePrefix("# ") }
            .trim()

    /**
     * The newer release of this fork to offer, or null (up to date, skipped, no file for this platform,
     * or the check failed). [ignoreSkipped] is for a manual "check for updates".
     */
    suspend fun checkForUpdate(ignoreSkipped: Boolean = false): GithubRelease? = withContext(Dispatchers.Default) {
        try {
            val response = httpClient.get(RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "CNCVerse-Bridge/${Constants.APP_VERSION}")
            }
            if (response.status.value !in 200..299) {
                ServerState.warn("Update check: GitHub answered ${response.status.value}")
                return@withContext null
            }
            val releases: List<GithubRelease> = response.body()
            UpdateVersions.pickUpdate(
                releases = releases,
                current = currentVersion,
                desktop = isDesktopPlatform,
                assetExtension = otaAssetExtension,
                skipped = if (ignoreSkipped) null else skippedVersion(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerState.warn("Update check failed: ${e.message?.take(100)}")
            null
        }
    }

    suspend fun downloadUpdate(assetUrl: String, fileName: String, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.Default) {
        try {
            val response = httpClient.get(assetUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0L) {
                        onProgress(bytesSentTotal.toFloat() / contentLength.toFloat())
                    }
                }
            }
            if (response.status.value in 200..299) {
                val bytes = response.readRawBytes()
                return@withContext saveOtaFile(bytes, fileName)
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerState.warn("Update download failed: ${e.message?.take(100)}")
            null
        }
    }
}
