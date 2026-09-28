package android.webkit

import android.content.Context
import android.view.ViewGroup
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * android.webkit stubs. Desktop has no WebView engine, so these are inert —
 * but plugins referencing WebView-based resolvers still verify and load.
 */
class WebView(context: Context? = null) : ViewGroup() {
    var settings: WebSettings = WebSettings()
        private set

    fun setWebViewClient(client: WebViewClient?) {}
    fun setWebChromeClient(client: WebChromeClient?) {}

    /**
     * On desktop, loadUrl performs a plain HTTP GET and stores any cookies
     * returned in Set-Cookie headers into CookieManager so plugins that poll
     * getCookie() can find them. No FlareSolverr or CDP involved.
     */
    fun loadUrl(url: String?) {
        if (url.isNullOrBlank()) return
        com.cncverse.stremiobridge.state.ServerState.info("[WebView-DBG] loadUrl($url) — plain HTTP GET")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val cm = android.webkit.CookieManager.getInstance()

                // Collect cookies from Set-Cookie response headers
                val newCookies = mutableMapOf<String, String>()
                response.headers.values("Set-Cookie").forEach { header ->
                    val nameValue = header.split(";").firstOrNull()?.trim() ?: return@forEach
                    val parts = nameValue.split("=", limit = 2)
                    val name  = parts.getOrNull(0)?.trim() ?: return@forEach
                    val value = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isNotBlank()) {
                        cm.setCookie(url, "$name=$value")
                        newCookies[name] = value
                    }
                }
                response.close()

                if (newCookies.isNotEmpty()) {
                    com.cncverse.stremiobridge.state.ServerState.info(
                        "[WebView-DBG] loadUrl saved cookies: ${newCookies.keys}"
                    )
                } else {
                    com.cncverse.stremiobridge.state.ServerState.info(
                        "[WebView-DBG] loadUrl($url) — no cookies in response"
                    )
                }
            } catch (e: Exception) {
                com.cncverse.stremiobridge.state.ServerState.warn("[WebView-DBG] loadUrl failed: ${e.message}")
            }
        }
    }

    fun evaluateJavascript(script: String?, resultCallback: ValueCallback<String>?) {}
    fun destroy() {}
    fun setJavaScriptEnabled(enabled: Boolean) {}
}

open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
}

open class WebChromeClient

class WebSettings {
    enum class LayoutAlgorithm {
        NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING
    }

    fun setJavaScriptEnabled(flag: Boolean) {}
    fun setDomStorageEnabled(flag: Boolean) {}
    fun setUserAgentString(ua: String?) {}
    fun getUserAgentString(): String? = null
    fun setBlockNetworkImage(flag: Boolean) {}
    fun setMediaPlaybackRequiresUserGesture(require: Boolean) {}
    fun setMixedContentMode(mode: Int) {}
    fun setLoadsImagesAutomatically(flag: Boolean) {}
    fun setAllowContentAccess(allow: Boolean) {}
    
    fun setJavaScriptCanOpenWindowsAutomatically(flag: Boolean) {}
    fun setSupportZoom(flag: Boolean) {}
    fun setBuiltInZoomControls(flag: Boolean) {}
    fun setDisplayZoomControls(flag: Boolean) {}
    fun setUseWideViewPort(flag: Boolean) {}
    fun setLoadWithOverviewMode(flag: Boolean) {}
    fun setCacheMode(mode: Int) {}
    fun setAppCacheEnabled(flag: Boolean) {}
    fun setDatabaseEnabled(flag: Boolean) {}
    fun setAllowFileAccess(flag: Boolean) {}
    fun setAllowFileAccessFromFileURLs(flag: Boolean) {}
    fun setAllowUniversalAccessFromFileURLs(flag: Boolean) {}
    fun setTextZoom(zoom: Int) {}
    fun setLayoutAlgorithm(algorithm: LayoutAlgorithm?) {}
    fun setStandardFontFamily(font: String?) {}
    fun setDefaultFontSize(size: Int) {}
    fun setOffscreenPreRaster(flag: Boolean) {}
    fun setGeolocationEnabled(flag: Boolean) {}
    fun setSafeBrowsingEnabled(flag: Boolean) {}
}

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
        @JvmStatic
        private val INSTANCE = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = INSTANCE
    }

    fun setAcceptCookie(accept: Boolean) {}

    fun setAcceptThirdPartyCookies(webView: WebView?, accept: Boolean) {}

    fun setCookie(url: String?, value: String?) {
        if (url == null || value == null) return
        // Sync cookies set by plugins directly into the CloudflareKiller store so
        // clearance cookies are available to OkHttp interceptors on desktop.
        runCatching {
            val host = java.net.URI(url).host ?: return@runCatching
            val pair = value.split("=", limit = 2)
            val key = pair.getOrNull(0)?.trim() ?: return@runCatching
            val cookieValue = pair.getOrNull(1)?.trim() ?: return@runCatching
            val existing = com.lagradost.cloudstream3.network.CloudflareKiller.savedCookies[host]?.toMutableMap() ?: mutableMapOf()
            existing[key] = cookieValue
            com.lagradost.cloudstream3.network.CloudflareKiller.savedCookies[host] = existing
        }
    }

    fun getCookie(url: String?): String? {
        if (url == null) return null
        return runCatching {
            val host = java.net.URI(url).host ?: return null
            val cookies = com.lagradost.cloudstream3.network.CloudflareKiller.getSavedCookies(host)
            if (cookies.isEmpty()) return null
            // Return all saved cookies in "name=value; name=value" format so the
            // plugin's cf_clearance regex (Regex("cf_clearance=([^;]+)")) can match.
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }.getOrNull()
    }

    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        com.lagradost.cloudstream3.network.CloudflareKiller.clearAllClearance()
        callback?.onReceiveValue(true)
    }

    fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        callback?.onReceiveValue(true)
    }

    fun removeAllCookie() {
        com.lagradost.cloudstream3.network.CloudflareKiller.clearAllClearance()
    }

    fun removeSessionCookie() {}

    fun hasCookies(): Boolean {
        return com.lagradost.cloudstream3.network.CloudflareKiller.savedCookies.isNotEmpty()
    }

    fun flush() {}
}

