package com.cncverse.stremiobridge.network

import com.cncverse.stremiobridge.state.ServerState
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import android.webkit.CookieManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * CDP-based Cloudflare challenge solver.
 *
 * Launches the system's Edge or Chrome browser with remote debugging enabled,
 * connects via the Chrome DevTools Protocol (CDP), and polls for `cf_clearance`
 * cookies that are set after a user solves a Turnstile/Cloudflare challenge.
 *
 * Once clearance is acquired, the browser is either closed (if OkHttp retry
 * succeeds) or kept alive as a "fetch proxy" for hosts where Cloudflare binds
 * the clearance to the browser's TLS fingerprint.
 *
 * Ported from CS3-desktop-client-unofficial with CS3-specific dependencies removed.
 */
object SystemBrowserCdpBypass {
    private const val TAG = "SystemBrowserCdpBypass"
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val bypassMutex = Mutex()
    private var isBrowserOpen = false

    data class ProxySession(
        val apexDomain: String,
        val port: Int,
        val process: Process,
        val sessionDirName: String,
        val userDataDir: File,
        @Volatile var webSocket: WebSocket? = null,
        @Volatile var lastActivity: Long = System.currentTimeMillis(),
        @Volatile var userAgent: String? = null,
        val pendingFetches: ConcurrentHashMap<Int, CompletableDeferred<String?>> = ConcurrentHashMap(),
        val messageId: AtomicInteger = AtomicInteger(10000),
        var watchdogJob: Job? = null,
    )

    // Active proxy sessions keyed by apex domain
    private val activeSessions = ConcurrentHashMap<String, ProxySession>()
    @Volatile private var pendingSession: ProxySession? = null
    private const val PROXY_IDLE_TIMEOUT_MS = 2 * 60 * 1000L

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class CdpTarget(
        val id: String,
        val type: String,
        val url: String,
        @com.fasterxml.jackson.annotation.JsonProperty("webSocketDebuggerUrl") val webSocketDebuggerUrl: String? = null,
    )

    data class ClearanceResult(
        val cookies: List<Cookie>,
        val cookieMap: Map<String, String>,
        val userAgent: String,
        val settledUrl: String,
        val settledHtml: String,
        val webSocket: WebSocket? = null,
    )

    data class CdpFetchResult(
        val statusCode: Int,
        val contentType: String?,
        val body: String,
        val bodyBytes: ByteArray? = null,
    )

    /**
     * Launches a single Edge/Chrome window targeting the root domain of the site.
     * Allows the user to manually solve the Turnstile challenge in a genuine browser.
     * Polls cookies via CDP every 1s until cf_clearance is acquired (or window closed).
     */
    suspend fun launchManualClearance(targetUrl: String, hostName: String? = null): Boolean = bypassMutex.withLock {
        val uri = try { URI(targetUrl) } catch (_: Exception) { null }
        val host = hostName?.ifBlank { null } ?: uri?.host ?: ""
        val apex = CloudflareKiller.getApexDomain(host)

        ServerState.info("[CDP-DBG] launchManualClearance called: host=$host apex=$apex url=$targetUrl")
        ServerState.info("[CDP-DBG] isBrowserOpen=$isBrowserOpen activeSessions=${activeSessions.keys} settledCached=${SettledPageCache.get(targetUrl) != null}")

        // 1. If targetUrl HTML is already in SettledPageCache, skip browser launch
        if (SettledPageCache.get(targetUrl) != null) {
            ServerState.info("[$TAG] Settled HTML already in cache — skipping browser launch for $targetUrl")
            return true
        }

        // 2. If an active proxy session already exists for this domain, navigate to targetUrl
        val activeSession = activeSessions[apex] ?: activeSessions[host]
        if (activeSession != null && activeSession.webSocket != null) {
            println("[$TAG] Active browser proxy found for $host. Navigating tab to $targetUrl...")
            val navigated = navigateSessionToUrl(activeSession, targetUrl, host)
            if (navigated) {
                return true
            }
        }

        if (isBrowserOpen) {
            ServerState.warn("[$TAG] A manual clearance window is already open — ignoring duplicate request for $host.")
            return false
        }
        isBrowserOpen = true

        val rootUrl = if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            targetUrl
        } else if (apex.isNotBlank()) {
            "https://$apex/"
        } else {
            targetUrl
        }

        val port = (9222..9999).random()
        val sessionDirName = "CNCVerse_CF_${System.currentTimeMillis()}"
        val userDataDir = File(System.getProperty("java.io.tmpdir"), sessionDirName).apply { mkdirs() }

        val edgePaths = mutableListOf<File>()
        val chromePaths = mutableListOf<File>()

        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("win")
        val isMac = osName.contains("mac")

        if (isWindows) {
            val progFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val progFiles86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val localAppData = System.getenv("LOCALAPPDATA") ?: "C:\\Users\\Default\\AppData\\Local"
            edgePaths.add(File("$progFiles86\\Microsoft\\Edge\\Application\\msedge.exe"))
            edgePaths.add(File("$progFiles\\Microsoft\\Edge\\Application\\msedge.exe"))
            chromePaths.add(File("$progFiles\\Google\\Chrome\\Application\\chrome.exe"))
            chromePaths.add(File("$progFiles86\\Google\\Chrome\\Application\\chrome.exe"))
            chromePaths.add(File("$localAppData\\Google\\Chrome\\Application\\chrome.exe"))
        } else if (isMac) {
            edgePaths.add(File("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"))
            chromePaths.add(File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
        } else {
            edgePaths.add(File("/usr/bin/microsoft-edge-stable"))
            edgePaths.add(File("/usr/bin/microsoft-edge"))
            chromePaths.add(File("/usr/bin/google-chrome-stable"))
            chromePaths.add(File("/usr/bin/google-chrome"))
        }

        val browserFile = when {
            edgePaths.any { it.exists() } -> edgePaths.first { it.exists() }
            chromePaths.any { it.exists() } -> chromePaths.first { it.exists() }
            else -> {
                ServerState.error("[$TAG] Neither Edge nor Chrome found on standard paths — cannot open CF solver!")
                ServerState.error("[$TAG] Checked: ${(edgePaths + chromePaths).map { it.absolutePath }}")
                isBrowserOpen = false
                return false
            }
        }

        val browserPath = browserFile.absolutePath
        ServerState.info("[$TAG] Launching CF solver: browser=${browserFile.name} port=$port url=$rootUrl")
        val privateFlag = if (browserPath.contains("msedge", ignoreCase = true)) "--inprivate" else "--incognito"
        val process = ProcessBuilder(
            browserPath,
            "--app=$rootUrl",
            privateFlag,
            "--user-data-dir=${userDataDir.absolutePath}",
            "--remote-debugging-port=$port",
            "--remote-allow-origins=*",
            "--window-size=900,800",
            "--disable-extensions",
            "--disable-component-extensions-with-background-pages",
            "--no-default-browser-check",
            "--no-first-run",
            "--disable-sync",
            "--disable-features=msImplicitSignIn,msEdgeSingleSignOn,Sync,IdentityConsistency,EnableTokenBinding,msProfilePicker,msHub",
            "--password-store=basic",
            "--disable-background-networking",
            "--disable-default-apps",
            "--disable-component-update",
        ).start()

        val session = ProxySession(
            apexDomain = apex,
            port = port,
            process = process,
            sessionDirName = sessionDirName,
            userDataDir = userDataDir,
        )

        try {
            val result = waitForClearance(session, rootUrl, host)
            if (result != null) {
                println("[$TAG] Manual Cloudflare verification succeeded for $host!")
                CloudflareKiller.saveClearance(host, result.cookieMap, result.userAgent)

                // Cache settled HTML to avoid re-opening browser for same URL
                if (result.settledHtml.isNotBlank()) {
                    SettledPageCache.put(targetUrl, result.settledHtml, result.userAgent)
                    if (result.settledUrl.isNotBlank() && result.settledUrl != targetUrl) {
                        SettledPageCache.put(result.settledUrl, result.settledHtml, result.userAgent)
                    }
                }

                // Sync clearance cookies to android.webkit.CookieManager stub
                // (so plugins that use CookieManager directly also see the cookies)
                try {
                    val cookieManager = CookieManager.getInstance()
                    result.cookieMap.forEach { (k, v) ->
                        cookieManager.setCookie(targetUrl, "$k=$v")
                    }
                } catch (_: Exception) {}

                session.lastActivity = System.currentTimeMillis()
                pendingSession = session

                isBrowserOpen = false
                return true
            } else {
                println("[$TAG] Verification window closed before cf_clearance was obtained.")
                destroyBrowserSession(process, sessionDirName, userDataDir)
                isBrowserOpen = false
                return false
            }
        } catch (e: Exception) {
            destroyBrowserSession(process, sessionDirName, userDataDir)
            isBrowserOpen = false
            throw e
        }
    }

    private fun parseCdpCookie(cookieNode: com.fasterxml.jackson.databind.JsonNode): Cookie? {
        return try {
            val name = cookieNode.get("name")?.asText()?.trim().orEmpty()
            val value = cookieNode.get("value")?.asText().orEmpty()
            if (name.isEmpty()) return null

            val rawDomain = cookieNode.get("domain")?.asText().orEmpty()
            val domain = rawDomain.trimStart('.').lowercase()
            if (domain.isEmpty()) return null

            val path = cookieNode.get("path")?.asText()?.ifBlank { "/" } ?: "/"
            val expiresSec = cookieNode.get("expires")?.asDouble() ?: -1.0
            val isSecure = cookieNode.get("secure")?.asBoolean() ?: false
            val isHttpOnly = cookieNode.get("httpOnly")?.asBoolean() ?: false

            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)

            if (expiresSec > 0) {
                builder.expiresAt((expiresSec * 1000).toLong())
            } else {
                builder.expiresAt(System.currentTimeMillis() + 86_400_000L)
            }

            if (isSecure) builder.secure()
            if (isHttpOnly) builder.httpOnly()

            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun waitForClearance(session: ProxySession, targetUrl: String, host: String): ClearanceResult? {
        val port = session.port
        var wsUrl: String? = null
        for (i in 1..40) { // Wait up to 20 seconds for CDP endpoint
            delay(500)
            try {
                val req = Request.Builder().url("http://127.0.0.1:$port/json").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val targets = mapper.readValue<List<CdpTarget>>(resp.body.string())
                        val pageTarget = targets.find { it.type == "page" }
                        if (pageTarget?.webSocketDebuggerUrl != null) {
                            wsUrl = pageTarget.webSocketDebuggerUrl
                        }
                    }
                }
                if (wsUrl != null) break
            } catch (e: Exception) {
                println("[$TAG] CDP probe attempt $i on port $port: ${e.message}")
            }
        }

        val finalWsUrl = wsUrl
        if (finalWsUrl == null) {
            System.err.println("[$TAG] Failed to connect to browser CDP at port $port")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            var resumed = false
            var hasDetectedClearance = false
            var capturedCookies: List<Cookie>? = null
            var capturedCookieMap: Map<String, String>? = null
            var capturedHtml: String? = null
            var capturedUa: String? = null
            var capturedSettledUrl: String = targetUrl

            val wsReq = Request.Builder().url(finalWsUrl).build()
            client.newWebSocket(wsReq, object : WebSocketListener() {
                var messageId = 1
                var pollingJob: Job? = null
                var settleJob: Job? = null

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    session.webSocket = webSocket
                    pollingJob = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            webSocket.send("""{"id": 1, "method": "Network.enable"}""")
                            while (isActive && !resumed && !hasDetectedClearance) {
                                delay(1000)
                                if (resumed || hasDetectedClearance) break
                                webSocket.send("""{"id": ${++messageId}, "method": "Network.getAllCookies"}""")
                            }
                        } catch (_: Exception) {}
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val tree = mapper.readTree(text)
                        val id = tree.get("id")?.asInt() ?: -1

                        // Handle fetch responses for active proxy sessions
                        val pendingDeferred = session.pendingFetches[id]
                        if (pendingDeferred != null) {
                            val result = tree.get("result")
                            if (result != null && result.has("result")) {
                                pendingDeferred.complete(result.get("result").get("value")?.asText())
                            } else {
                                pendingDeferred.complete(null)
                            }
                            session.pendingFetches.remove(id)
                            return
                        }

                        // 1. Polling check: look for cf_clearance
                        if (id in 2..8000 && tree.has("result")) {
                            val cookiesNode = tree.get("result").get("cookies")
                            if (cookiesNode != null && cookiesNode.isArray && !hasDetectedClearance) {
                                for (cookie in cookiesNode) {
                                    val name = cookie.get("name")?.asText().orEmpty()
                                    val value = cookie.get("value")?.asText().orEmpty()
                                    if (name == "cf_clearance" && value.length > 20) {
                                        hasDetectedClearance = true
                                        println("[$TAG] Detected valid cf_clearance! Waiting for page to settle...")
                                        pollingJob?.cancel()
                                        startSettleWatch(webSocket)
                                        break
                                    }
                                }
                            }
                            return
                        }

                        // 2. Page settle check response (ID 8888)
                        if (id == 8888) {
                            val value = tree.get("result")?.get("result")?.get("value")?.asText() ?: ""
                            val parts = value.split(":::", limit = 3)
                            val title = parts.getOrNull(0)?.trim() ?: ""
                            val readyState = parts.getOrNull(1)?.trim() ?: ""
                            val currentUrl = parts.getOrNull(2)?.trim() ?: targetUrl
                            val isChallenge = title.contains("Just a moment", ignoreCase = true) ||
                                title.contains("Attention Required", ignoreCase = true) ||
                                title.contains("Cloudflare", ignoreCase = true)

                            if (!isChallenge && readyState == "complete" && title.isNotEmpty()) {
                                println("[$TAG] Page settled on real site: '$title'. Capturing state...")
                                settleJob?.cancel()
                                capturedSettledUrl = currentUrl
                                webSocket.send("""{"id": 9001, "method": "Network.getAllCookies"}""")
                                webSocket.send("""{"id": 9002, "method": "Runtime.evaluate", "params": {"expression": "document.documentElement.outerHTML"}}""")
                                webSocket.send("""{"id": 9003, "method": "Browser.getVersion"}""")
                                webSocket.send("""{"id": 9004, "method": "Browser.getWindowForTarget"}""")
                            }
                            return
                        }

                        // 3. Atomic Capture: GetAllCookies (ID 9001)
                        if (id == 9001 && tree.has("result")) {
                            val cookiesNode = tree.get("result").get("cookies")
                            if (cookiesNode != null && cookiesNode.isArray) {
                                val okCookies = mutableListOf<Cookie>()
                                val cookieMap = mutableMapOf<String, String>()
                                for (cn in cookiesNode) {
                                    val cookie = parseCdpCookie(cn)
                                    if (cookie != null) {
                                        okCookies.add(cookie)
                                        cookieMap[cookie.name] = cookie.value
                                    }
                                }
                                capturedCookies = okCookies
                                capturedCookieMap = cookieMap
                                println("[$TAG] Captured ${okCookies.size} cookies.")
                                checkCompletion()
                            }
                            return
                        }

                        // 4. Atomic Capture: OuterHTML (ID 9002)
                        if (id == 9002 && tree.has("result")) {
                            capturedHtml = tree.get("result")?.get("result")?.get("value")?.asText() ?: ""
                            checkCompletion()
                            return
                        }

                        // 5. Atomic Capture: User-Agent (ID 9003)
                        if (id == 9003 && tree.has("result")) {
                            capturedUa = tree.get("result")?.get("userAgent")?.asText() ?: ""
                            checkCompletion()
                            return
                        }

                        // 6. Minimize window after capture
                        if (id == 9004 && tree.has("result")) {
                            val windowId = tree.get("result")?.get("windowId")?.asInt()
                            if (windowId != null) {
                                webSocket.send("""{"id": 9005, "method": "Browser.setWindowBounds", "params": {"windowId": $windowId, "bounds": {"windowState": "minimized"}}}""")
                            }
                            return
                        }
                    } catch (e: Exception) {
                        System.err.println("[$TAG] Error parsing CDP message: ${e.message}")
                    }
                }

                private fun startSettleWatch(ws: WebSocket) {
                    settleJob = CoroutineScope(Dispatchers.IO).launch {
                        var attempts = 0
                        while (isActive && attempts++ < 20) {
                            delay(500)
                            if (!isActive) break
                            ws.send("""{"id": 8888, "method": "Runtime.evaluate", "params": {"expression": "document.title + ':::' + document.readyState + ':::' + window.location.href"}}""")
                        }
                        if (isActive && !resumed) {
                            println("[$TAG] Page settle poll timed out; capturing anyway.")
                            ws.send("""{"id": 9001, "method": "Network.getAllCookies"}""")
                            ws.send("""{"id": 9002, "method": "Runtime.evaluate", "params": {"expression": "document.documentElement.outerHTML"}}""")
                            ws.send("""{"id": 9003, "method": "Browser.getVersion"}""")
                        }
                    }
                }

                private fun checkCompletion() {
                    val cookies = capturedCookies ?: return
                    val html = capturedHtml ?: return
                    val ua = capturedUa ?: return
                    if (!resumed) {
                        resumed = true
                        pollingJob?.cancel()
                        settleJob?.cancel()
                        cont.resume(
                            ClearanceResult(
                                cookies = cookies,
                                cookieMap = capturedCookieMap ?: emptyMap(),
                                userAgent = ua,
                                settledUrl = capturedSettledUrl,
                                settledHtml = html,
                                webSocket = session.webSocket,
                            )
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    pollingJob?.cancel()
                    settleJob?.cancel()
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    pollingJob?.cancel()
                    settleJob?.cancel()
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
            })

            cont.invokeOnCancellation {
                session.webSocket?.close(1000, "Cancelled")
            }
        }
    }

    // ── Proxy Session Lifecycle ──────────────────────────────────────────

    private fun destroyBrowserSession(process: Process, sessionDirName: String, userDataDir: File) {
        runCatching { process.destroy() }
        runCatching {
            val script = "Get-CimInstance Win32_Process -Filter \"Name = 'msedge.exe' OR Name = 'chrome.exe'\" | Where-Object { \$_.CommandLine -match '$sessionDirName' } | Invoke-CimMethod -MethodName Terminate"
            ProcessBuilder("powershell", "-NoProfile", "-Command", script).start().waitFor()
        }
        runCatching {
            Thread.sleep(1000)
            userDataDir.deleteRecursively()
        }
    }

    /** Returns true if a persistent fetch proxy is active for the given host. */
    fun hasActiveProxy(host: String): Boolean {
        val apex = CloudflareKiller.getApexDomain(host)
        val session = activeSessions[apex] ?: activeSessions[host]
        return session?.webSocket != null
    }

    /** Close pending browser session (OkHttp retry succeeded, proxy not needed). */
    fun closePendingSession() {
        val session = pendingSession ?: return
        pendingSession = null
        println("[$TAG] Clearance verified — terminating solver session for ${session.apexDomain}.")
        closeSession(session)
    }

    /** Close all proxy sessions or the session for a specific host. */
    fun closeProxySession(host: String? = null) {
        if (host != null) {
            val apex = CloudflareKiller.getApexDomain(host)
            val session = activeSessions.remove(apex) ?: activeSessions.remove(host)
            if (session != null) closeSession(session)
        } else {
            activeSessions.values.forEach { closeSession(it) }
            activeSessions.clear()
        }
    }

    private fun closeSession(session: ProxySession) {
        session.watchdogJob?.cancel()
        session.webSocket?.close(1000, "Session ended")
        session.webSocket = null
        session.pendingFetches.values.forEach { it.complete(null) }
        session.pendingFetches.clear()
        activeSessions.remove(session.apexDomain)
        CoroutineScope(Dispatchers.IO).launch {
            destroyBrowserSession(session.process, session.sessionDirName, session.userDataDir)
        }
    }

    /**
     * Activate a persistent browser proxy for TLS-fingerprint-bound hosts.
     * Called after OkHttp retry is rejected even with valid cf_clearance cookies.
     */
    suspend fun activateFetchProxy(host: String = ""): Boolean {
        val targetApex = if (host.isNotBlank()) CloudflareKiller.getApexDomain(host) else ""
        val session = pendingSession?.takeIf { targetApex.isBlank() || it.apexDomain == targetApex }
            ?: (if (targetApex.isNotBlank()) activeSessions[targetApex] else null)
            ?: pendingSession

        if (session == null) {
            System.err.println("[$TAG] No pending browser session to activate as proxy for $host.")
            return false
        }

        if (session.webSocket != null) {
            return true
        }

        val port = session.port
        val apex = session.apexDomain

        var wsUrl: String? = null
        for (i in 1..10) {
            delay(300)
            try {
                val req = Request.Builder().url("http://127.0.0.1:$port/json").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val targets = mapper.readValue<List<CdpTarget>>(resp.body.string())
                        val pageTarget = targets.find { it.type == "page" && it.webSocketDebuggerUrl != null }
                        if (pageTarget != null) wsUrl = pageTarget.webSocketDebuggerUrl
                    }
                }
                if (wsUrl != null) break
            } catch (e: Exception) {
                println("[$TAG] Proxy CDP probe attempt $i: ${e.message}")
            }
        }

        val finalWsUrl = wsUrl ?: run {
            System.err.println("[$TAG] Failed to reconnect to browser CDP for proxy on port $port.")
            closePendingSession()
            return false
        }

        println("[$TAG] Activating fetch proxy for $apex via CDP")
        val wsReq = Request.Builder().url(finalWsUrl).build()
        session.webSocket = client.newWebSocket(wsReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"id": 1, "method": "Network.enable"}""")
                webSocket.send("""{"id": 2, "method": "Browser.getWindowForTarget"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val tree = mapper.readTree(text)
                    val msgId = tree.get("id")?.asInt() ?: return
                    if (msgId == 2 && tree.has("result")) {
                        val windowId = tree.get("result")?.get("windowId")?.asInt()
                        if (windowId != null) {
                            webSocket.send("""{"id": 3, "method": "Browser.setWindowBounds", "params": {"windowId": $windowId, "bounds": {"windowState": "minimized"}}}""")
                        }
                        return
                    }
                    val deferred = session.pendingFetches[msgId]
                    if (deferred != null) {
                        val result = tree.get("result")
                        if (result != null && result.has("result")) {
                            deferred.complete(result.get("result").get("value")?.asText())
                        } else {
                            deferred.complete(null)
                        }
                        session.pendingFetches.remove(msgId)
                    }
                } catch (e: Exception) {
                    System.err.println("[$TAG] Error parsing proxy CDP message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                System.err.println("[$TAG] Proxy WebSocket failed for $apex: ${t.message}")
                session.webSocket = null
                session.pendingFetches.values.forEach { it.complete(null) }
                session.pendingFetches.clear()
                activeSessions.remove(apex)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.webSocket = null
                session.pendingFetches.values.forEach { it.complete(null) }
                session.pendingFetches.clear()
                activeSessions.remove(apex)
            }
        })

        session.lastActivity = System.currentTimeMillis()
        activeSessions[apex] = session
        if (pendingSession === session) pendingSession = null

        // Idle watchdog — auto-close browser after inactivity
        session.watchdogJob?.cancel()
        session.watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(60_000)
                if (System.currentTimeMillis() - session.lastActivity > PROXY_IDLE_TIMEOUT_MS) {
                    println("[$TAG] Proxy session for $apex idle — auto-closing.")
                    closeSession(session)
                    break
                }
            }
        }

        return true
    }

    /**
     * Execute an HTTP request through the browser's Chromium network stack via CDP.
     * Used for TLS-fingerprint-bound hosts where OkHttp's TLS differs from the browser's.
     */
    suspend fun fetchViaProxy(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        isBinary: Boolean = false,
    ): CdpFetchResult? {
        val uri = try { URI(url) } catch (_: Exception) { null } ?: return null
        val host = uri.host ?: return null
        val apex = CloudflareKiller.getApexDomain(host)
        val session = activeSessions[apex] ?: activeSessions[host] ?: return null
        val ws = session.webSocket ?: return null
        session.lastActivity = System.currentTimeMillis()

        val msgId = session.messageId.incrementAndGet()
        val deferred = CompletableDeferred<String?>()
        session.pendingFetches[msgId] = deferred

        // Never forward browser-controlled or fingerprint-sensitive headers
        val forbiddenHeaders = setOf(
            "user-agent", "cookie", "host", "content-length", "transfer-encoding",
            "connection", "accept-encoding", "referer", "origin",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-user", "sec-fetch-dest",
        )
        val filteredHeaders = headers.filterKeys { it.lowercase() !in forbiddenHeaders }
        val requestPayload = mapper.writeValueAsString(mapOf(
            "url" to url,
            "method" to method,
            "headers" to filteredHeaders,
            "body" to body,
        ))
        val b64 = java.util.Base64.getEncoder().encodeToString(requestPayload.toByteArray())
        val referrerUrl = "https://$apex/"

        val expression = if (isBinary) {
            """(async()=>{try{const req=JSON.parse(atob("$b64"));const opts={method:req.method,headers:req.headers||{},credentials:"include",referrer:"$referrerUrl"};if(req.body)opts.body=req.body;const r=await fetch(req.url,opts);const buf=await r.arrayBuffer();const bytes=new Uint8Array(buf);let bin='';const chunk=8192;for(let i=0;i<bytes.length;i+=chunk){bin+=String.fromCharCode.apply(null,bytes.subarray(i,i+chunk));}return JSON.stringify({s:r.status,ct:r.headers.get("content-type")||"",b64:btoa(bin)})}catch(e){return JSON.stringify({s:0,ct:"",b64:""})}})("""
        } else {
            """(async()=>{try{const req=JSON.parse(atob("$b64"));const opts={method:req.method,headers:req.headers||{},credentials:"include",referrer:"$referrerUrl"};if(req.body)opts.body=req.body;const r=await fetch(req.url,opts);const t=await r.text();return JSON.stringify({s:r.status,ct:r.headers.get("content-type")||"",b:t})}catch(e){return JSON.stringify({s:0,ct:"",b:""+e})}})("""
        } + ")"

        val command = mapper.writeValueAsString(mapOf(
            "id" to msgId,
            "method" to "Runtime.evaluate",
            "params" to mapOf(
                "expression" to expression,
                "awaitPromise" to true,
                "returnByValue" to true,
            ),
        ))

        ws.send(command)

        return try {
            val resultJson = withTimeout(30_000) { deferred.await() } ?: return null
            val resultTree = mapper.readTree(resultJson)
            val statusCode = resultTree.get("s")?.asInt() ?: 0
            val contentType = resultTree.get("ct")?.asText()?.ifBlank { null }
            if (isBinary) {
                val b64Data = resultTree.get("b64")?.asText().orEmpty()
                val bytes = try { java.util.Base64.getDecoder().decode(b64Data) } catch (_: Exception) { ByteArray(0) }
                CdpFetchResult(statusCode = statusCode, contentType = contentType, body = "", bodyBytes = bytes)
            } else {
                val bodyText = resultTree.get("b")?.asText() ?: ""
                CdpFetchResult(statusCode = statusCode, contentType = contentType, body = bodyText, bodyBytes = null)
            }
        } catch (e: Exception) {
            System.err.println("[$TAG] fetchViaProxy failed: ${e.message}")
            session.pendingFetches.remove(msgId)
            null
        }
    }

    private suspend fun navigateSessionToUrl(session: ProxySession, targetUrl: String, host: String): Boolean {
        val ws = session.webSocket ?: return false
        return try {
            val msgId = session.messageId.incrementAndGet()
            val deferred = CompletableDeferred<String?>()
            session.pendingFetches[msgId] = deferred
            val js = "window.location.href = ${mapper.writeValueAsString(targetUrl)}; 'navigated'"
            val cmd = mapper.writeValueAsString(mapOf(
                "id" to msgId,
                "method" to "Runtime.evaluate",
                "params" to mapOf("expression" to js, "awaitPromise" to false, "returnByValue" to true),
            ))
            ws.send(cmd)
            withTimeout(5000) { deferred.await() }
            println("[$TAG] Navigated existing browser to $targetUrl for $host.")
            true
        } catch (_: Exception) {
            false
        }
    }
}
