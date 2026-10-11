package com.lagradost.cloudstream3.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cncverse.stremiobridge.state.ServerState
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host
        appContext()?.let { com.cncverse.stremiobridge.network.CfCookieStore.initIfNeeded(it.cacheDir.path) }

        // A site solved before: send its saved clearance straight away (no challenge, no WebView)
        val saved = com.cncverse.stremiobridge.network.CfCookieStore.get(host)
        if (saved != null && request.header("Cookie").isNullOrBlank()) {
            request = request.newBuilder().header("Cookie", saved.cookie).header("User-Agent", saved.userAgent).build()
        }
        var response = chain.proceed(request)

        val challenged = (response.code == 503 || response.code == 403 || response.code == 429) &&
            (response.header("Server")?.let { it.contains("cloudflare", true) || it.contains("ddos-guard", true) } == true ||
                response.header("cf-mitigated")?.contains("challenge", true) == true)
        if (challenged) {
            // The saved clearance did not work (expired / other UA): drop it and solve again, one solver per site at a time
            if (saved != null) com.cncverse.stremiobridge.network.CfCookieStore.forget(host)
            ServerState.info("Cloudflare challenge on ${request.url.host} - solving it")
            val result = synchronized(lockFor(host)) {
                // Another request may have solved it while this one waited
                com.cncverse.stremiobridge.network.CfCookieStore.get(host)?.takeIf { it.cookie != saved?.cookie }?.let { CfResult(it.cookie, it.userAgent) }
                    ?: bypassCloudflare(request.url.toString())
            }
            if (result != null) {
                com.cncverse.stremiobridge.network.CfCookieStore.put(host, result.cookie, result.userAgent)
                val newRequest = request.newBuilder()
                    .header("Cookie", result.cookie)
                    .header("User-Agent", result.userAgent)
                    .build()
                response.close()
                response = chain.proceed(newRequest)
            } else {
                ServerState.warn("Cloudflare challenge on $host could not be solved in time")
            }
        }
        return response
    }

    private fun lockFor(host: String): Any = locks.getOrPut(host) { Any() }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (e: Exception) { null }

    private companion object {
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    data class CfResult(val cookie: String, val userAgent: String)

    private val lastAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Solves the challenge in a real WebView (like Raghav's extensions do). First invisibly for ~15 s (JS
     * challenges pass by themselves); if the site wants a tap (Turnstile), the same WebView is shown in a
     * dialog over the app so the user can finish it. The cookies are returned with this WebView's User-Agent.
     */
    private fun bypassCloudflare(url: String): CfResult? {
        val appCtx = appContext() ?: return null
        val host = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<CfResult?>(null)
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        val maxMs = 110_000L
        val headlessMs = 15_000L

        main.post {
            var dialog: android.app.Dialog? = null
            var webView: WebView? = null
            fun finish() {
                runCatching { dialog?.dismiss() }
                runCatching { webView?.stopLoading(); webView?.destroy() }
                webView = null
                latch.countDown()
            }
            try {
                val wv = WebView(appCtx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                @Suppress("DEPRECATION") wv.settings.databaseEnabled = true
                wv.settings.javaScriptCanOpenWindowsAutomatically = true
                wv.settings.setSupportMultipleWindows(false)
                val userAgent = wv.settings.userAgentString
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = WebViewClient()

                fun cleared(): String? {
                    CookieManager.getInstance().flush()
                    val c = CookieManager.getInstance().getCookie(url)
                    return c?.takeIf { it.contains("cf_clearance") }
                }

                fun showDialog() {
                    val act = com.lagradost.cloudstream3.CommonActivity.activity
                    if (act == null || act.isFinishing || act.isDestroyed) return
                    // Do not nag: one prompt per site every 5 minutes
                    val now = System.currentTimeMillis()
                    if (now - (lastAsked[host] ?: 0L) < 5 * 60_000L) return
                    lastAsked[host] = now
                    runCatching {
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        val dp = act.resources.displayMetrics.density
                        val box = android.widget.LinearLayout(act).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
                            val title = android.widget.TextView(act).apply {
                                text = "Complete the check for $host - tap the box if one appears"
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 14f
                                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                            }
                            addView(title)
                            addView(wv, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
                            val cancel = android.widget.Button(act).apply {
                                text = "Cancel"
                                setOnClickListener { finish() }
                            }
                            addView(cancel)
                        }
                        dialog = android.app.Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                            setContentView(box)
                            setCancelable(true)
                            setOnCancelListener { finish() }
                            show()
                        }
                        ServerState.info("Cloudflare: showing the check for $host so it can be completed")
                    }.onFailure { ServerState.warn("Cloudflare dialog failed: ${it.message}") }
                }

                var shown = false
                fun poll() {
                    if (webView == null) return
                    val c = cleared()
                    when {
                        c != null -> { resultRef.set(CfResult(c, userAgent)); finish() }
                        System.currentTimeMillis() - started > maxMs -> finish()
                        else -> {
                            if (!shown && System.currentTimeMillis() - started > headlessMs) {
                                shown = true
                                showDialog()
                            }
                            main.postDelayed({ poll() }, 1000)
                        }
                    }
                }
                wv.loadUrl(url)
                main.postDelayed({ poll() }, 1500)
            } catch (e: Exception) {
                ServerState.warn("WebView failed: ${e.message}")
                finish()
            }
        }
        latch.await(maxMs + 5_000, TimeUnit.MILLISECONDS)
        return resultRef.get()
    }
}
