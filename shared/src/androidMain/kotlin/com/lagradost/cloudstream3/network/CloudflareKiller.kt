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
        val request = chain.request()
        var response = chain.proceed(request)

        if (response.code == 503 || response.code == 403) {
            val server = response.header("Server") ?: response.header("server")
            if (server?.contains("cloudflare", ignoreCase = true) == true) {
                ServerState.info("Cloudflare bypass needed for ${request.url}")
                val result = bypassCloudflare(request.url.toString())
                if (result != null) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", result.cookie)
                        .header("User-Agent", result.userAgent)
                        .build()
                    response.close()
                    response = chain.proceed(newRequest)
                }
            }
        }
        return response
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
                    val maxChecks = 15
                    
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
        latch.await(20, TimeUnit.SECONDS)
        return result
    }
}
