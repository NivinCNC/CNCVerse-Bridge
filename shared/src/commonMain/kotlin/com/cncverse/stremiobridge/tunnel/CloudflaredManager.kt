package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.state.ServerState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStreamReader

object CloudflaredManager {

    private var tunnelProcess: Process? = null
    private var tunnelScope: CoroutineScope? = null

    var deviceIp: String = "127.0.0.1"

    private val QUICK_URL = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

    private val httpClient by lazy {
        HttpClient(CIO)
    }

    fun isInstalled(): Boolean {
        val path = getCloudflaredBinaryPath()
        val file = File(path)
        return file.exists() && file.length() > 100_000 // Cloudflared binary is usually >10MB
    }

    suspend fun downloadCloudflared(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val downloadUrl = getPlatformCloudflaredDownloadUrl()
        val targetPath = getCloudflaredBinaryPath()
        val targetFile = File(targetPath)
        val tempFile = File("$targetPath.tmp")

        ServerState.info("Downloading Cloudflared from $downloadUrl ...")

        try {
            val response = httpClient.get(downloadUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0L) {
                        onProgress(bytesSentTotal.toFloat() / contentLength.toFloat())
                    }
                }
            }

            if (response.status.value in 200..299) {
                val bytes = response.readRawBytes()
                tempFile.writeBytes(bytes)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
                setFileExecutable(targetFile.absolutePath)
                ServerState.info("Cloudflared downloaded successfully (${bytes.size / (1024 * 1024)} MB)")
                return@withContext true
            } else {
                ServerState.error("Cloudflared download failed: HTTP ${response.status.value}")
                return@withContext false
            }
        } catch (e: Exception) {
            ServerState.error("Cloudflared download error: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            return@withContext false
        }
    }

    /**
     * Runs the user's own Cloudflare Tunnel ([TunnelSettings]: token + public
     * hostname). The hostname's route (service `http://localhost:<port>`) is set
     * up in the Cloudflare dashboard; `--url` covers tunnels without one.
     * Returns false (and logs why) when the tunnel isn't set up in Settings.
     */
    fun startTunnel(port: Int): Boolean {
        if (tunnelProcess != null && tunnelProcess?.isAlive == true) {
            ServerState.info("Cloudflare Tunnel is already running")
            return true
        }
        val token = TunnelSettings.token
        val publicUrl = TunnelSettings.publicUrl
        // No token / domain set: a free "quick tunnel" (random https://....trycloudflare.com link, no account)
        val quick = token == null || publicUrl == null
        if (quick) ServerState.info("No Cloudflare token set - starting a free quick tunnel (temporary https link)")

        stopTunnel()

        val binaryPath = getCloudflaredBinaryPath()
        setFileExecutable(binaryPath)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tunnelScope = scope

        scope.launch {
            try {
                ServerState.info(if (quick) "Starting free Cloudflare quick tunnel (local port $port) ..." else "Starting Cloudflare Tunnel for $publicUrl (local port $port) ...")

                // Get edge IPs (bypasses DNS issue on Android 10+)
                val edgeIps = resolveCloudflareEdgeIps()
                fun base(): MutableList<String> {
                    val c = mutableListOf(binaryPath, "tunnel", "--url", "http://${deviceIp}:$port", "--edge-ip-version", "4", "--no-autoupdate")
                    for (ip in edgeIps.take(4)) c.addAll(listOf("--edge", ip))
                    return c
                }

                // Each attempt: a command, the public link it should end up with, and how long to wait for it
                class Attempt(val label: String, val cmd: List<String>, val url: String?)
                val attempts = ArrayList<Attempt>()
                if (!quick && token != null) {
                    attempts += Attempt("named", base().also { it.addAll(listOf("run", "--token", token)) }, publicUrl)
                } else {
                    // 1) Create the quick tunnel ourselves (this app's DNS works on Android; cloudflared's own lookup does not), then run it
                    createQuickTunnel(File(binaryPath).parentFile ?: File("."))?.let { q ->
                        attempts += Attempt("quick (api)", base().also { it.addAll(listOf("run", "--cred-file", q.credsFile, q.id)) }, "https://${q.hostname}")
                    }
                    // 2) cloudflared's own quick tunnel
                    attempts += Attempt("quick (cloudflared)", base().filterIndexed { i, _ -> true }.let { it.toMutableList() }, null)
                }

                for ((idx, at) in attempts.withIndex()) {
                    val pb = ProcessBuilder(at.cmd)
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    tunnelProcess = process
                    val registered = java.util.concurrent.atomic.AtomicBoolean(false)
                    val last = idx == attempts.lastIndex
                    ServerState.info("Cloudflare tunnel attempt: ${at.label}")

                    // An attempt that has not connected after 50 s is given up so the next one can try
                    val watchdog = launch {
                        delay(50_000)
                        if (!registered.get() && !last) {
                            ServerState.warn("Cloudflare tunnel (${at.label}) did not connect in 50 s - trying another way")
                            runCatching { process.destroyForcibly() }
                        }
                    }

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (isActive && process.isAlive) {
                        line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // The token is never echoed by cloudflared, but keep it out of the log regardless
                        ServerState.info("[cloudflared] ${if (token != null) line.replace(token, "***") else line}")
                        val quickUrl = QUICK_URL.find(line)?.value
                        if (quickUrl != null && at.url == null) {
                            registered.set(true)
                            if (ServerState.activeTunnelUrl.value != quickUrl) {
                                ServerState.activeTunnelUrl.value = quickUrl
                                ServerState.info("🚀 Free Cloudflare tunnel ready: $quickUrl  (Stremio add-on: $quickUrl/manifest.json)")
                            }
                        } else if (line.contains("Registered tunnel connection") || (line.contains("Connection ") && line.contains("registered"))) {
                            registered.set(true)
                            val u = at.url
                            if (u != null && ServerState.activeTunnelUrl.value != u) {
                                ServerState.activeTunnelUrl.value = u
                                ServerState.info("🚀 Cloudflare Tunnel connected: $u")
                            }
                        } else if (!quick && (line.contains("Unauthorized", ignoreCase = true) || line.contains("invalid token", ignoreCase = true))) {
                            ServerState.error("Cloudflare rejected the tunnel token — check it in Settings")
                        }
                    }
                    watchdog.cancel()
                    val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                    ServerState.info("Cloudflared process exited with code $exitCode")
                    if (!isActive || registered.get()) break // stopped on purpose, or it ran and later ended
                    ServerState.activeTunnelUrl.value = null
                }
            } catch (e: CancellationException) {
                // Expected when tunnel is stopped
            } catch (e: Exception) {
                ServerState.error("Cloudflared tunnel error: ${e.message}")
            } finally {
                ServerState.activeTunnelUrl.value = null
            }
        }
        return true
    }

    private class QuickTunnel(val id: String, val hostname: String, val credsFile: String)

    /**
     * Asks Cloudflare for a free quick tunnel (the same call `cloudflared tunnel --url` makes) and writes its
     * credentials file. Done here because Android's cloudflared cannot resolve api.trycloudflare.com by itself.
     */
    private fun createQuickTunnel(dir: File): QuickTunnel? = try {
        val client = okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url("https://api.trycloudflare.com/tunnel")
            .header("User-Agent", "cloudflared/2024.1.0")
            .post("".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) { ServerState.warn("Quick tunnel request failed: HTTP ${r.code}"); null }
            else {
                val res = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
                fun f(k: String) = res?.get(k)?.jsonPrimitive?.contentOrNull
                val id = f("id"); val host = f("hostname"); val tag = f("account_tag"); val secret = f("secret")
                if (id == null || host == null || tag == null || secret == null) { ServerState.warn("Quick tunnel answer incomplete"); null }
                else {
                    val creds = File(dir, "quick-tunnel.json")
                    creds.writeText("{\"AccountTag\":\"$tag\",\"TunnelSecret\":\"$secret\",\"TunnelID\":\"$id\"}")
                    QuickTunnel(id, host, creds.absolutePath)
                }
            }
        }
    } catch (e: Exception) {
        ServerState.warn("Quick tunnel request error: ${e.message}")
        null
    }

    fun stopTunnel() {
        tunnelScope?.cancel()
        tunnelScope = null

        try {
            tunnelProcess?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                }
            }
        } catch (e: Exception) {
            ServerState.warn("Error stopping cloudflared process: ${e.message}")
        } finally {
            tunnelProcess = null
            ServerState.activeTunnelUrl.value = null
            ServerState.info("Cloudflare Tunnel stopped")
        }
    }
}
