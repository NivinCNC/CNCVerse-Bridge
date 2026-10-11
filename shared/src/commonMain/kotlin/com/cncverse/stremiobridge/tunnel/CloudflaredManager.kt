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

    private val QUICK_URL = Regex("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com")
    private val QUICK_HOST = Regex("(?<![a-z0-9-])(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com")

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
     * Without a token a free "quick tunnel" (random https://....trycloudflare.com link) is started.
     * Returns false (and logs why) only when nothing could be started.
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
        val workDir = File(binaryPath).parentFile?.takeIf { it.canWrite() }
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tunnelScope = scope

        scope.launch {
            try {
                ServerState.info(if (quick) "Starting free Cloudflare quick tunnel (local port $port) ..." else "Starting Cloudflare Tunnel for $publicUrl (local port $port) ...")

                // Get edge IPs (bypasses DNS issue on Android 10+)
                val edgeIps = resolveCloudflareEdgeIps()
                // The bridge listens on 0.0.0.0, so the loopback address always reaches it
                val localUrl = "http://127.0.0.1:$port"
                fun base(ipVersion: String): MutableList<String> {
                    val c = mutableListOf(binaryPath, "tunnel", "--edge-ip-version", ipVersion, "--grace-period", "15s", "--no-autoupdate", "--retries", "99")
                    for (ip in edgeIps.take(4)) c.addAll(listOf("--edge", ip))
                    return c
                }

                // Each attempt: a command, the public link it should end up with (null = read it from cloudflared),
                // and the metrics port whose /quicktunnel endpoint reports the link
                class Attempt(val label: String, val cmd: List<String>, val url: String?, val metricsPort: Int = 0)
                val attempts = ArrayList<Attempt>()
                if (!quick && token != null) {
                    attempts += Attempt("named", base("4").also { it.addAll(listOf("--url", localUrl, "run", "--token", token)) }, publicUrl)
                } else {
                    // 0) The quick tunnel of the last run: the link stays the same while Cloudflare still honours it
                    savedQuickTunnel(workDir)?.let { q ->
                        attempts += Attempt("quick (saved link)", base("4").also { it.addAll(listOf("--url", localUrl, "run", "--cred-file", q.credsFile, q.id)) }, "https://${q.hostname}")
                    }
                    // 1) Create the quick tunnel ourselves (this app's DNS works on Android; cloudflared's own lookup does not), then run it
                    createQuickTunnel(workDir)?.let { q ->
                        attempts += Attempt("quick (api)", base("4").also { it.addAll(listOf("--url", localUrl, "run", "--cred-file", q.credsFile, q.id)) }, "https://${q.hostname}")
                    }
                    // 2) cloudflared's own quick tunnel, started the way pencarimovie-server does it: ingress config file,
                    //    metrics endpoint (its /quicktunnel answer is the live link), transport auto, many retries
                    val metrics = freePort()
                    val cfg = writeIngressConfig(workDir, localUrl)
                    attempts += Attempt(
                        "quick (cloudflared)",
                        base("auto").also { it.addAll(listOf("--url", localUrl, "--config", cfg.absolutePath, "--metrics", "127.0.0.1:$metrics")) },
                        null, metrics,
                    )
                }

                for ((idx, at) in attempts.withIndex()) {
                    val pb = ProcessBuilder(at.cmd)
                    pb.redirectErrorStream(true)
                    applyTunnelEnvironment(pb, workDir)
                    val process = pb.start()
                    tunnelProcess = process
                    val registered = java.util.concurrent.atomic.AtomicBoolean(false)
                    val last = idx == attempts.lastIndex
                    ServerState.info("Cloudflare tunnel attempt: ${at.label}")

                    fun announce(url: String) {
                        registered.set(true)
                        if (ServerState.activeTunnelUrl.value != url) {
                            ServerState.activeTunnelUrl.value = url
                            ServerState.info("🚀 Cloudflare tunnel ready: $url  (Stremio add-on: $url/manifest.json)")
                        }
                    }

                    // An attempt that has not connected after 40 s is given up so the next one can try
                    val watchdog = launch {
                        delay(if (at.label.contains("saved")) 25_000 else 40_000)
                        if (!registered.get() && !last) {
                            ServerState.warn("Cloudflare tunnel (${at.label}) did not connect in time - trying another way")
                            runCatching { process.destroyForcibly() }
                        }
                    }
                    // pencarimovie-server reads the link from cloudflared's metrics endpoint instead of scraping the log
                    val poller = if (at.metricsPort > 0 && at.url == null) launch {
                        while (isActive && process.isAlive) {
                            delay(500)
                            readQuickTunnelUrl(at.metricsPort)?.let { announce(it); return@launch }
                        }
                    } else null

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (isActive && process.isAlive) {
                        line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // The token is never echoed by cloudflared, but keep it out of the log regardless
                        ServerState.info("[cloudflared] ${if (token != null) line.replace(token, "***") else line}")
                        val quickUrl = QUICK_URL.find(line)?.value
                        if (quickUrl != null && at.url == null) {
                            announce(quickUrl)
                        } else if (line.contains("Registered tunnel connection") || (line.contains("Connection ") && line.contains("registered"))) {
                            registered.set(true)
                            val u = at.url
                            if (u != null) announce(u)
                        } else if (!quick && (line.contains("Unauthorized", ignoreCase = true) || line.contains("invalid token", ignoreCase = true))) {
                            ServerState.error("Cloudflare rejected the tunnel token — check it in Settings")
                        }
                    }
                    watchdog.cancel()
                    poller?.cancel()
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

    private fun freePort(): Int = runCatching {
        java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }.getOrDefault(20241)

    /** Minimal ingress file for a quick tunnel (same settings pencarimovie-server writes). */
    private fun writeIngressConfig(dir: File, localUrl: String): File {
        val f = File(dir, "quick-ingress.yml")
        f.writeText(
            "ingress:\n" +
                "  - service: $localUrl\n" +
                "    originRequest:\n" +
                "      connectTimeout: 10s\n" +
                "      tcpKeepAlive: 30s\n" +
                "      keepAliveTimeout: 90s\n" +
                "      keepAliveConnections: 100\n" +
                "      noTLSVerify: true\n" +
                "      http2Origin: false\n"
        )
        return f
    }

    /**
     * Environment cloudflared needs on Android, where there is no /etc/ssl and no HOME:
     * transport auto (QUIC, falling back to HTTP/2), a writable HOME, and the system CA store.
     */
    private fun applyTunnelEnvironment(pb: ProcessBuilder, workDir: File) {
        val env = pb.environment()
        env["TUNNEL_TRANSPORT_PROTOCOL"] = "auto"
        val home = env["HOME"]
        if (home.isNullOrBlank() || !File(home).canWrite()) env["HOME"] = workDir.absolutePath
        if (env["SSL_CERT_DIR"].isNullOrBlank()) {
            listOf("/system/etc/security/cacerts", "/etc/ssl/certs").firstOrNull { File(it).isDirectory }?.let { env["SSL_CERT_DIR"] = it }
        }
        if (env["SSL_CERT_FILE"].isNullOrBlank()) {
            listOf("/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/cert.pem").firstOrNull { File(it).isFile }?.let { env["SSL_CERT_FILE"] = it }
        }
    }

    /** The quick-tunnel link from cloudflared's local metrics server (`/quicktunnel` -> {"hostname":"x.trycloudflare.com"}). */
    private fun readQuickTunnelUrl(metricsPort: Int): String? = try {
        val conn = java.net.URL("http://127.0.0.1:$metricsPort/quicktunnel").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        QUICK_HOST.find(body)?.value?.let { "https://$it" }
    } catch (_: Exception) {
        null
    }

    /** The quick tunnel created on an earlier start (credentials file + its hostname), if any. */
    private fun savedQuickTunnel(dir: File): QuickTunnel? = try {
        val f = File(dir, "quick-tunnel.json")
        if (!f.exists()) null else {
            val j = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject
            val id = j["TunnelID"]?.jsonPrimitive?.contentOrNull
            val host = j["Hostname"]?.jsonPrimitive?.contentOrNull
            if (id == null || host == null) null else QuickTunnel(id, host, f.absolutePath)
        }
    } catch (e: Exception) { null }

    private class QuickTunnel(val id: String, val hostname: String, val credsFile: String)

    /**
     * Asks Cloudflare for a free quick tunnel (the same call `cloudflared tunnel --url` makes) and writes its
     * credentials file. Done here because Android's cloudflared cannot resolve api.trycloudflare.com by itself.
     */
    private fun createQuickTunnel(dir: File): QuickTunnel? = try {
        // The app's own resolver (system DNS, then DoH) - the one extensions use when the phone's DNS is flaky
        val dns = runCatching { Class.forName("com.cncverse.stremiobridge.network.AdaptiveHostDns").getField("INSTANCE").get(null) as okhttp3.Dns }
            .getOrDefault(okhttp3.Dns.SYSTEM)
        val client = okhttp3.OkHttpClient.Builder().dns(dns).callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
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
                    creds.writeText("{\"AccountTag\":\"$tag\",\"TunnelSecret\":\"$secret\",\"TunnelID\":\"$id\",\"Hostname\":\"$host\"}")
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
