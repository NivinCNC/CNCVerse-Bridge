package com.cncverse.stremiobridge.update

import com.cncverse.stremiobridge.Constants
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
    @SerialName("assets") val assets: List<GithubAsset> = emptyList()
)

object OtaUpdater {
    private const val GITHUB_API_URL = "https://api.github.com/repos/NivinCNC/CNCVerse-Bridge/releases/latest"

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun checkForUpdate(): GithubRelease? = withContext(Dispatchers.Default) {
        try {
            val response = httpClient.get(GITHUB_API_URL)
            if (response.status.value in 200..299) {
                val release: GithubRelease = response.body()
                val latestVersion = release.tagName.removePrefix("v")
                val currentVersion = Constants.APP_VERSION

                if (isNewerVersion(latestVersion, currentVersion)) {
                    return@withContext release
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
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
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
