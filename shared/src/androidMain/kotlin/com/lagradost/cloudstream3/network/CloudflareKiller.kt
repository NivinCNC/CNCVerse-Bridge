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

    private fun bypassCloudflare(url: String): CfResult? {
        val appCtx = try {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getMethod("currentApplication").invoke(null) as Context
        } catch (e: Exception) {
            null
        } ?: return null

        val latch = CountDownLatch(1)
        var result: CfResult? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(appCtx)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                val userAgent = webView.settings.userAgentString
                
                webView.webViewClient = object : WebViewClient() {
                    var checkCount = 0
                    val maxChecks = 30
                    
                    override fun onPageFinished(view: WebView?, url: String?) {
                        checkCookies()
                    }
                    
                    private fun checkCookies() {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (cookies != null && cookies.contains("cf_clearance")) {
                            result = CfResult(cookies, userAgent)
                            latch.countDown()
                            webView.destroy()
                        } else {
                            if (checkCount++ < maxChecks) {
                                Handler(Looper.getMainLooper()).postDelayed({ checkCookies() }, 1000)
                            } else {
                                latch.countDown()
                                webView.destroy()
                            }
                        }
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                ServerState.warn("WebView failed: ${e.message}")
                latch.countDown()
            }
        }
        latch.await(40, TimeUnit.SECONDS)
        return result
    }
}
