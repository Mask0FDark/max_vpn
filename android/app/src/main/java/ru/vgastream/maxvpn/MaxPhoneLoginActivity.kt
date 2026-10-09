package ru.vgastream.maxvpn

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Phone login: SMS from MAX, optional 2FA, persistent session on private VPS. */
class MaxPhoneLoginActivity : Activity() {
    private val api = OkHttpClient.Builder().build()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var phoneButton: Button
    private lateinit var codeButton: Button
    private lateinit var passwordButton: Button
    private var phase = ""

    private val refresh = object : Runnable {
        override fun run() {
            io.execute {
                try {
                    val response = call("status")
                    runOnUiThread { show(response) }
                } catch (_: Exception) {}
            }
            ui.postDelayed(this, 3500L)
        }
    }

    private fun call(step: String, value: String? = null): JSONObject {
        val token = RelaySettings.load(applicationContext)
        require(token.length >= 40) { "Сначала привяжи свой VPS" }
        val builder = Request.Builder()
            .url("https://max-vpn.mask-0f-darkness.ru/api/max/login/" + step)
            .header("X-MAXVPN-Token", token)
            .header("Cache-Control", "no-store")
        val request = if (value == null) builder.get().build() else {
            val body = JSONObject().put(step, value).toString()
                .toRequestBody("application/json".toMediaType())
            builder.post(body).build()
        }
        api.newCall(request).execute().use { response ->
            val result = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful)
                error("HTTP " + response.code + ": " + result.optString("detail", "ошибка"))
            return result
        }
    }

    private fun send(step: String, value: String) {
        status.text = "Ожидание ответа MAX..."
        io.execute {
            try {
                val result = call(step, value)
                runOnUiThread { show(result) }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка: " + (e.message ?: "нет соединения").take(110)
                }
            }
        }
    }

    private fun show(data: JSONObject) {
        val next = data.optString("phase", "idle")
        if (next != phase) {
            phase = next
            status.text = when (phase) {
                "idle" -> "Введи свой номер MAX"
                "connecting", "requesting_code" -> "Запрашиваю код MAX..."
                "waiting_code" -> "Введи код, который прислал MAX"
                "verifying_code" -> "Проверяю код..."
                "waiting_password" -> "MAX запросил дополнительный пароль"
                "verifying_password" -> "Проверяю пароль..."
                "connected" -> "Вход выполнен. Сессия сохранена на VPS"
                "transport_error" -> "Вход успешен, но MAX-чат пока недоступен"
                "error" -> "Ошибка MAX: " + data.optString("detail", "неизвестно")
                else -> "Состояние MAX: " + next
            }
        }
        phone.visibility = if (next == "idle" || next == "error") View.VISIBLE else View.GONE
        phoneButton.visibility = phone.visibility
        code.visibility = if (next == "waiting_code") View.VISIBLE else View.GONE
        codeButton.visibility = code.visibility
        password.visibility = if (next == "waiting_password") View.VISIBLE else View.GONE
        passwordButton.visibility = password.visibility
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        root.addView(TextView(this).apply { text = "Вход в MAX по номеру"; textSize = 24f })
        status = TextView(this).apply {
            text = "Введи номер, код MAX будет запрошен автоматически"
            textSize = 17f
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)
        phone = EditText(this).apply {
            hint = "+79991234567"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(phone)
        phoneButton = Button(this).apply {
            text = "Получить код MAX"
            setOnClickListener { send("phone", phone.text.toString().trim()) }
        }
        root.addView(phoneButton)
        code = EditText(this).apply {
            hint = "SMS-код MAX"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(code)
        codeButton = Button(this).apply {
            text = "Подтвердить код"
            setOnClickListener { send("code", code.text.toString().trim()); code.text.clear() }
        }
        root.addView(codeButton)
        password = EditText(this).apply {
            hint = "Пароль двухфакторной защиты"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(password)
        passwordButton = Button(this).apply {
            text = "Подтвердить пароль"
            setOnClickListener { send("password", password.text.toString()); password.text.clear() }
        }
        root.addView(passwordButton)
        root.addView(TextView(this).apply {
            text = "MAX сам запрашивает SMS-код. Код и пароль не сохраняются; сессия остаётся на твоём VPS."
        })
        setContentView(ScrollView(this).apply { addView(root) })
        show(JSONObject().put("phase", "idle"))
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }
    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }
    override fun onDestroy() {
        io.shutdownNow()
        api.dispatcher.executorService.shutdown()
        api.connectionPool.evictAll()
        super.onDestroy()
    }
}
