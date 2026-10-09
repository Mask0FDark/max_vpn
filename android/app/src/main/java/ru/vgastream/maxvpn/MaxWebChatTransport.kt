package ru.vgastream.maxvpn

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser-side transport for a user's already authorized MAX WebView session.
 *
 * Only interacts with a dedicated explicitly selected MAX chat. This class
 * does not access cookies, passwords, SMS codes, browser storage or MAX APIs.
 * The official MAX page handles its own login and session persistence.
 *
 * DOM selectors are based on the tested desktop MAX Web UI. This adapter is
 * a diagnostic transport until confirmed on physical Android WebView.
 */
class MaxWebChatTransport(
    private val web: WebView,
    private val chatId: Long
) {
    private val main = Handler(Looper.getMainLooper())

    init {
        require(chatId != 0L)
    }

    private fun trustedChat(): Boolean {
        val uri = android.net.Uri.parse(web.url ?: return false)
        return uri.scheme == "https" && uri.host == "web.max.ru" &&
            uri.path?.trimEnd('/') == "/$chatId"
    }

    fun navigate() {
        main.post { web.loadUrl("https://web.max.ru/$chatId") }
    }

    fun isReady(callback: (Boolean) -> Unit) {
        main.post {
            if (!trustedChat()) {
                callback(false)
                return@post
            }
            web.evaluateJavascript("""
                (() => Boolean(document.querySelector(
                  '[data-lexical-editor="true"][role="textbox"], [role="textbox"][contenteditable="true"], [role="textbox"][contenteditable=""]'
                )))()
            """.trimIndent()) { value -> callback(value == "true") }
        }
    }

    fun sendFrame(frame: String, callback: (Boolean) -> Unit) {
        if (!frame.startsWith("M0FD-TUNNEL-V1:") || frame.length > 4000) {
            callback(false)
            return
        }
        main.post {
            if (!trustedChat()) {
                callback(false)
                return@post
            }
            val escapedFrame = JSONObject.quote(frame)
            val script = """
              (() => {
                const editor = document.querySelector(
                  '[data-lexical-editor="true"][role="textbox"], [role="textbox"][contenteditable="true"], [role="textbox"][contenteditable=""]'
                );
                if (!editor) return false;
                editor.focus();
                document.execCommand('selectAll', false, null);
                if (!document.execCommand('insertText', false, $escapedFrame)) return false;
                const button = Array.from(document.querySelectorAll('button'))
                  .find(b => /^(send message|отправить сообщение)$/i.test(
                    (b.getAttribute('aria-label') || '').trim()
                  ));
                if (!button || button.disabled) return false;
                button.click();
                return true;
              })()
            """.trimIndent()
            web.evaluateJavascript(script) { response ->
                callback(response == "true")
            }
        }
    }

    fun readFrames(callback: (List<String>) -> Unit) {
        main.post {
            if (!trustedChat()) {
                callback(emptyList())
                return@post
            }
            val script = """
              (() => JSON.stringify(
                Array.from(document.querySelectorAll('[class*="messageWrapper"]'))
                  .slice(-100)
                  .map(el => el.innerText || '')
                  .filter(text => text.includes('M0FD-TUNNEL-V1:'))
              ))()
            """.trimIndent()
            web.evaluateJavascript(script) { encoded ->
                try {
                    // evaluateJavascript double-encodes a returned JS string.
                    val json = JSONArray("[$encoded]").getString(0)
                    val values = JSONArray(json)
                    callback((0 until values.length()).map { values.getString(it) })
                } catch (_: Exception) {
                    callback(emptyList())
                }
            }
        }
    }
}
