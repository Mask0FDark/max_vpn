package ru.vgastream.maxvpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MAX WebView session belongs only to this Android app, and is only used
 * for one configured dedicated chat. Does not extract MAX credentials.
 */
class MaxBrowserSession(private val context: Context, private val chatId: Long) {
    private val main = Handler(Looper.getMainLooper())
    private var browser: WebView? = null
    @Volatile private var chat: MaxWebChatTransport? = null

    fun connect(timeoutSeconds: Long = 40): MaxWebChatTransport {
        val initialized = CountDownLatch(1)
        main.post {
            try {
                val web = WebView(context)
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                web.webViewClient = object : WebViewClient() {}
                browser = web
                val client = MaxWebChatTransport(web, chatId)
                chat = client
                client.navigate()
            } finally {
                initialized.countDown()
            }
        }
        if (!initialized.await(8, TimeUnit.SECONDS)) error("WebView initialization timed out")
        val selected = chat ?: error("WebView could not be created")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val result = CountDownLatch(1)
            var ready = false
            selected.isReady { value -> ready = value; result.countDown() }
            if (result.await(5, TimeUnit.SECONDS) && ready) return selected
            Thread.sleep(400)
        }
        error("MAX chat not ready: sign in on MAX Web before enabling VPN")
    }

    fun close() {
        main.post {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
            chat = null
        }
    }
}
