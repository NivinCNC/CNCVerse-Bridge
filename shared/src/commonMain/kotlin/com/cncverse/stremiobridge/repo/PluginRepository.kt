package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.model.CncRepository
import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

private val repoJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Low-level HTTP helpers for fetching repo metadata and downloading plugin files.
 */
object PluginRepository {

    internal val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(repoJson) }
        engine { requestTimeout = 30_000 }
    }

    /**
     * Resolves a CloudStream shortcode (e.g., "Hexated") to a full repository URL.
     * If the input is already a URL or invalid, it returns the input unchanged.
     */
    suspend fun resolveShortCode(url: String): String = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return@withContext trimmed
        if (!trimmed.matches("^[a-zA-Z0-9!_-]+$".toRegex())) return@withContext trimmed

        val isPyMd = trimmed.startsWith("!")
        val baseUrl = if (isPyMd) "https://py.md/${trimmed.removePrefix("!")}" else "https://cutt.ly/$trimmed"
        
        try {
            // We use a temporary client that does not follow redirects to read the Location header.
            val tempClient = HttpClient(CIO) {
                followRedirects = false
            }
            val response = tempClient.get(baseUrl)
            val location = response.headers["Location"]
            tempClient.close()
            
            if (location != null) {
                if (location.startsWith("https://cutt.ly/404") || location.removeSuffix("/") == "https://cutt.ly" ||
                    location.startsWith("https://py.md/404") || location.removeSuffix("/") == "https://py.md"
                ) {
                    return@withContext trimmed
                }
                return@withContext location
            }
            trimmed
        } catch (e: Exception) {
            ServerState.warn("Failed to resolve shortcode '$trimmed': ${e.message}")
            trimmed
        }
    }

    /**
     * Fetches the top-level [CncRepository] manifest from [url].
     * Returns null on failure.
     */
    suspend fun fetchRepoMeta(url: String): CncRepository? = withContext(Dispatchers.IO) {
        try {
            val text = httpClient.get(url).bodyAsText()
            repoJson.decodeFromString<CncRepository>(text)
        } catch (e: Exception) {
            ServerState.warn("Failed to fetch repo meta from $url: ${e.message}")
            null
        }
    }

    /**
     * Fetches all [SitePlugin] entries from a single plugin-list URL.
     */
    suspend fun fetchPluginsFromUrl(listUrl: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        try {
            val text = httpClient.get(listUrl).bodyAsText()
            repoJson.decodeFromString<List<SitePlugin>>(text)
        } catch (e: Exception) {
            ServerState.warn("Failed to fetch plugin list from $listUrl: ${e.message}")
            emptyList()
        }
    }

    /**
     * Downloads a single plugin's .cs3 file to the cache directory.
     * Returns the local [File] on success, null on failure.
     * Skips download if file already exists and hash matches.
     */
    suspend fun downloadPlugin(plugin: SitePlugin, cacheDir: String): File? =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "plugins").also { it.mkdirs() }
            val fileName = "${plugin.internalName.sanitize()}.cs3"
            val destFile = File(dir, fileName)

            // Skip download if cached with matching hash
            if (destFile.exists() && plugin.fileHash != null) {
                val existingHash = sha256(destFile)
                if (existingHash == plugin.fileHash) {
                    return@withContext destFile
                }
            }

            try {
                val safeUrl = plugin.url.replace(" ", "%20")
                ServerState.info("Downloading '${plugin.name}' from $safeUrl")
                val bytes = httpClient.get(safeUrl).readRawBytes()

                if (plugin.fileHash != null) {
                    val downloadedHash = sha256Bytes(bytes)
                    if (downloadedHash != plugin.fileHash) {
                        ServerState.error("Hash mismatch for '${plugin.name}'! Expected: ${plugin.fileHash}, got: $downloadedHash")
                        return@withContext null
                    }
                }

                destFile.writeBytes(bytes)
                ServerState.info("Downloaded '${plugin.name}' (${bytes.size / 1024} KB)")
                destFile
            } catch (e: Exception) {
                ServerState.error("Failed to download '${plugin.name}': ${e.message}")
                null
            }
        }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read = stream.read(buf)
            while (read != -1) {
                digest.update(buf, 0, read)
                read = stream.read(buf)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase()
}
