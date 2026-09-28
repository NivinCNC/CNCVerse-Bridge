package android.webkit

import android.content.Context
import android.view.ViewGroup
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * android.webkit stubs for desktop JVM.
 *
 * On desktop there is no real browser engine, so we emulate the most
 * important WebView behaviour needed by CS3 plugins:
 *
 *  1. [loadUrl] performs a plain HTTP GET, persists response cookies, then
 *     scans the response HTML/JS for embedded streaming URLs (.m3u8 / .mpd /
 *     .mp4 / etc.) and calls [WebViewClient.shouldInterceptRequest] for every
 *     candidate URL found.
 *  2. After the HTTP GET completes, [WebViewClient.onPageFinished] is called
 *     so the plugin's JS-injection path is triggered.
 *  3. [evaluateJavascript] parses the saved HTML for common stream-variable
 *     patterns (playbackURL, source, file, hls, stream …) and, if the script
 *     is invoking a registered JS-interface method, calls it via reflection so
 *     the plugin bridge receives the value without a real JS engine.
 *
 * This covers plugins that use WebView purely to locate a streaming URL that
 * is already embedded in the page HTML or a simple JS variable.
 */
class WebView(context: Context? = null) : ViewGroup() {

    val settings: WebSettings = WebSettings()

    /**
     * Plugin-assignable WebViewClient.  Both direct property assignment
     * (`webView.webViewClient = …`) and the legacy setter method work because
     * Kotlin generates a `setWebViewClient` JVM method from this var.
     */
    var webViewClient: WebViewClient? = null

    /**
     * Plugin-assignable WebChromeClient.  Same dual-access as above.
     */
    var webChromeClient: WebChromeClient? = null

    // Named JS interfaces registered via addJavascriptInterface()
    private val jsInterfaces = mutableMapOf<String, Any>()

    // Saved HTTP response body from the last loadUrl() call — used by evaluateJavascript()
    @Volatile private var lastHtml: String = ""
    @Volatile private var lastUrl:  String = ""

    /**
     * Registers a Java/Kotlin object whose @JavascriptInterface-annotated
     * methods are callable from within [evaluateJavascript].
     */
    fun addJavascriptInterface(obj: Any, name: String) {
        jsInterfaces[name] = obj
    }

    /**
     * Performs a plain HTTP GET of [url], then:
     *  1. Saves response cookies into [CookieManager].
     *  2. Scans the HTML body for streaming URLs and fires
     *     [WebViewClient.shouldInterceptRequest] for each one found.
     *  3. Calls [WebViewClient.onPageFinished] so the plugin can inject JS.
     */
    fun loadUrl(url: String?) {
        if (url.isNullOrBlank()) return
        lastUrl = url
        com.cncverse.stremiobridge.state.ServerState.info("[WebView] loadUrl($url)")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val http = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", url)
                    .build()

                val resp = http.newCall(req).execute()

                // ── Persist cookies ───────────────────────────────────────────
                val cm = CookieManager.getInstance()
                resp.headers.values("Set-Cookie").forEach { hdr ->
                    val nameValue = hdr.split(";").firstOrNull()?.trim() ?: return@forEach
                    if (nameValue.contains("=")) cm.setCookie(url, nameValue)
                }

                // ── Read body ─────────────────────────────────────────────────
                val html = resp.body?.string() ?: ""
                lastHtml = html
                resp.close()

                com.cncverse.stremiobridge.state.ServerState.info(
                    "[WebView] fetched $url (${html.length} chars)"
                )

                // ── Scan for streaming URLs and fire shouldInterceptRequest ───
                val client = webViewClient
                if (client != null) {
                    val streamPattern = Regex(
                        """https?://[^\s"'<>\\]+\.(?:m3u8|mpd|mp4|ts|mkv|webm|flv)[^\s"'<>\\]*""",
                        RegexOption.IGNORE_CASE
                    )
                    val found = streamPattern.findAll(html)
                        .map { it.value.trimEnd(',', ';', ')', '\\', '"', '\'') }
                        .filter { it.startsWith("http") }
                        .toSet()

                    for (streamUrl in found) {
                        com.cncverse.stremiobridge.state.ServerState.info(
                            "[WebView] intercepted candidate: $streamUrl"
                        )
                        val mockReq = MockWebResourceRequest(streamUrl)
                        withContext(Dispatchers.Main) {
                            client.shouldInterceptRequest(this@WebView, mockReq)
                        }
                    }
                }

                // ── Notify plugin that page finished ──────────────────────────
                withContext(Dispatchers.Main) {
                    webViewClient?.onPageFinished(this@WebView, url)
                }

            } catch (e: Exception) {
                com.cncverse.stremiobridge.state.ServerState.warn(
                    "[WebView] loadUrl($url) failed: ${e.message}"
                )
                // Still call onPageFinished so the plugin's timeout path runs
                withContext(Dispatchers.Main) {
                    webViewClient?.onPageFinished(this@WebView, url)
                }
            }
        }
    }

    /**
     * Attempts to satisfy the script by extracting a streaming URL variable
     * from the last-fetched HTML, then:
     *  • Invokes any registered [jsInterfaces] method referenced by the script
     *    (via reflection, respecting the [@JavascriptInterface] convention).
     *  • Calls [resultCallback] with the found value (JSON-encoded string) or
     *    `"null"` when nothing is found.
     */
    fun evaluateJavascript(script: String?, resultCallback: ValueCallback<String>?) {
        if (script == null) { resultCallback?.onReceiveValue("null"); return }

        val html = lastHtml

        // Common names for a stream-URL variable embedded in the page JS
        val varPatterns = listOf(
            Regex(
                """(?:playbackURL|streamUrl|hlsUrl|m3u8Url|source|file|hls|stream|url|videoUrl)\s*(?:=|:)\s*["']([^"']+\.(?:m3u8|mpd|mp4|ts|mkv|webm)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:playbackURL|streamUrl|hlsUrl|m3u8Url|source|file|hls|stream|url)\s*=\s*["']([^"']{10,})["']""",
                RegexOption.IGNORE_CASE
            ),
        )

        var found: String? = null
        for (pat in varPatterns) {
            found = pat.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (found != null) break
        }

        if (!found.isNullOrBlank()) {
            com.cncverse.stremiobridge.state.ServerState.info(
                "[WebView] evaluateJavascript → found stream variable: $found"
            )
            // Invoke the JS bridge method if the script calls one
            for ((ifaceName, obj) in jsInterfaces) {
                if (script.contains("$ifaceName.")) {
                    val methodCallRe = Regex("""$ifaceName\.(\w+)\(""")
                    val methodMatch  = methodCallRe.find(script)
                    if (methodMatch != null) {
                        val methodName = methodMatch.groupValues[1]
                        runCatching {
                            val method = obj.javaClass.declaredMethods.find { m ->
                                m.name == methodName && m.parameterCount == 1
                            }
                            method?.isAccessible = true
                            method?.invoke(obj, found)
                            com.cncverse.stremiobridge.state.ServerState.info(
                                "[WebView] called $ifaceName.$methodName(\"$found\")"
                            )
                        }.onFailure { e ->
                            com.cncverse.stremiobridge.state.ServerState.warn(
                                "[WebView] JS bridge invoke failed: ${e.message}"
                            )
                        }
                    }
                }
            }
            resultCallback?.onReceiveValue("\"$found\"")
        } else {
            resultCallback?.onReceiveValue("null")
        }
    }

    fun destroy()                              { lastHtml = ""; lastUrl = "" }
    fun setJavaScriptEnabled(enabled: Boolean) {}
}

// ─── Internal: mock WebResourceRequest ───────────────────────────────────────

/**
 * [WebResourceRequest] backed by a plain URL string.
 * Note: Kotlin exposes [getUrl] as the `.url` property via Java-interop,
 * so there is NO separate `val url` property here (it would clash on JVM).
 */
private class MockWebResourceRequest(urlStr: String) : WebResourceRequest {
    private val _uri: android.net.Uri? = runCatching { android.net.Uri.parse(urlStr) }.getOrNull()
    override fun getUrl(): android.net.Uri? = _uri
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}

// ─── WebViewClient / WebChromeClient ─────────────────────────────────────────

open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
    open fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? = null
    open fun onPageStarted(view: WebView, url: String, favicon: Any?) {}
    open fun onPageFinished(view: WebView, url: String) {}
    open fun onReceivedError(
        view: WebView, errorCode: Int, description: String?, failingUrl: String?
    ) {}
}

open class WebChromeClient

// ─── WebSettings ─────────────────────────────────────────────────────────────

/**
 * No-op settings container.  All the `var` properties below generate proper
 * JVM getters/setters so plugins can use either property or method-call style:
 *   `settings.javaScriptEnabled = true`   ← Kotlin property
 *   `settings.setJavaScriptEnabled(true)` ← generated JVM setter
 *
 * To avoid JVM signature clashes, there are NO additional explicit setter
 * methods — the generated property setters cover all call sites.
 */
class WebSettings {
    enum class LayoutAlgorithm {
        NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING
    }

    companion object {
        const val MIXED_CONTENT_ALWAYS_ALLOW       = 0
        const val MIXED_CONTENT_NEVER_ALLOW        = 1
        const val MIXED_CONTENT_COMPATIBILITY_MODE = 2
        const val LOAD_DEFAULT                     = -1
        const val LOAD_CACHE_ELSE_NETWORK          = 1
        const val LOAD_NO_CACHE                    = 2
        const val LOAD_CACHE_ONLY                  = 3
    }

    // All settings are stored as vars so Kotlin generates proper get/set JVM methods.
    var javaScriptEnabled: Boolean                 = false
    var domStorageEnabled: Boolean                 = false
    var loadsImagesAutomatically: Boolean          = true
    var allowContentAccess: Boolean                = true
    var allowFileAccess: Boolean                   = false
    var mediaPlaybackRequiresUserGesture: Boolean  = true
    var mixedContentMode: Int                      = MIXED_CONTENT_NEVER_ALLOW

    // Settings with no property-equivalent (setter-only on Android)
    fun setUserAgentString(ua: String?)                    {}
    fun getUserAgentString(): String?                      = null
    fun setBlockNetworkImage(flag: Boolean)                {}
    fun setJavaScriptCanOpenWindowsAutomatically(flag: Boolean) {}
    fun setSupportZoom(flag: Boolean)                      {}
    fun setBuiltInZoomControls(flag: Boolean)              {}
    fun setDisplayZoomControls(flag: Boolean)              {}
    fun setUseWideViewPort(flag: Boolean)                  {}
    fun setLoadWithOverviewMode(flag: Boolean)             {}
    fun setCacheMode(mode: Int)                            {}
    fun setAppCacheEnabled(flag: Boolean)                  {}
    fun setDatabaseEnabled(flag: Boolean)                  {}
    fun setAllowFileAccessFromFileURLs(flag: Boolean)      {}
    fun setAllowUniversalAccessFromFileURLs(flag: Boolean) {}
    fun setTextZoom(zoom: Int)                             {}
    fun setLayoutAlgorithm(algorithm: LayoutAlgorithm?)    {}
    fun setStandardFontFamily(font: String?)               {}
    fun setDefaultFontSize(size: Int)                      {}
    fun setOffscreenPreRaster(flag: Boolean)               {}
    fun setGeolocationEnabled(flag: Boolean)               {}
    fun setSafeBrowsingEnabled(flag: Boolean)              {}
}

// ─── Misc stubs ───────────────────────────────────────────────────────────────

interface WebResourceRequest {
    fun getUrl(): android.net.Uri?
    fun getRequestHeaders(): Map<String, String>?
}

class WebResourceResponse(
    val mimeType: String?,
    val encoding: String?,
    val data: InputStream?,
) {
    constructor(reason: String?, mimeType: String?) : this(mimeType, null, null)
}

interface ValueCallback<T> {
    fun onReceiveValue(value: T?)
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface

class CookieManager {
    companion object {
        @JvmStatic private val INSTANCE = CookieManager()
        @JvmStatic fun getInstance(): CookieManager = INSTANCE
    }

    fun setAcceptCookie(accept: Boolean) {}
    fun setAcceptThirdPartyCookies(webView: WebView?, accept: Boolean) {}

    fun setCookie(url: String?, value: String?) {
        if (url == null || value == null) return
        runCatching {
            val host = java.net.URI(url).host ?: return@runCatching
            val pair  = value.split("=", limit = 2)
            val key   = pair.getOrNull(0)?.trim() ?: return@runCatching
            val v     = pair.getOrNull(1)?.trim() ?: return@runCatching
            val existing = com.lagradost.cloudstream3.network.CloudflareKiller
                .savedCookies[host]?.toMutableMap() ?: mutableMapOf()
            existing[key] = v
            com.lagradost.cloudstream3.network.CloudflareKiller.savedCookies[host] = existing
        }
    }

    fun getCookie(url: String?): String? {
        if (url == null) return null
        return runCatching {
            val host    = java.net.URI(url).host ?: return null
            val cookies = com.lagradost.cloudstream3.network.CloudflareKiller.getSavedCookies(host)
            if (cookies.isEmpty()) return null
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }.getOrNull()
    }

    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        com.lagradost.cloudstream3.network.CloudflareKiller.clearAllClearance()
        callback?.onReceiveValue(true)
    }

    fun removeSessionCookies(callback: ValueCallback<Boolean>?) { callback?.onReceiveValue(true) }
    fun removeAllCookie()    { com.lagradost.cloudstream3.network.CloudflareKiller.clearAllClearance() }
    fun removeSessionCookie() {}
    fun hasCookies(): Boolean = com.lagradost.cloudstream3.network.CloudflareKiller.savedCookies.isNotEmpty()
    fun flush() {}
}
