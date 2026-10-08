package ru.vgastream.maxvpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sign-in happens exclusively on the real MAX website.
 * No JavaScript bridges, custom credential fields or cookie extraction.
 * Android WebView maintains its own private persistent browsing profile.
 */
class MaxWebLoginActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 16, 8)
            setBackgroundColor(Color.rgb(9, 18, 34))
        }
        root.addView(Button(this).apply {
            text = "Назад в MAX VPN"
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Вход в личный MAX через официальный web.max.ru. Используй тот же аккаунт, что и на ПК."
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Сессия остаётся только в WebView телефона. Вход в MAX ещё не запускает VPN."
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return true
                if (uri.scheme == "https" && (host == "max.ru" || host.endsWith(".max.ru"))) {
                    return false
                }
                if (uri.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        // Keep the sign-in page rather than following unsupported URLs.
                    }
                }
                return true
            }
        }
        root.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        if (savedInstanceState == null) {
            web.loadUrl("https://web.max.ru/")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        web.destroy()
        super.onDestroy()
    }
}
