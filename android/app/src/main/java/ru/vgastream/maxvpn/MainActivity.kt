package ru.vgastream.maxvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * MAX message VPN preview. Never substitutes direct VPS HTTPS when MAX is selected.
 * Fails before TUN if MAX account session or encrypted VPS reply is unavailable.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var maxButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            if (::status.isInitialized) refreshStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 24)
            setBackgroundColor(Color.rgb(9,18,34))
        }
        fun info(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }

        root.addView(info("MAX VPN — через MAX", 30f))
        status = info("", 19f).apply { setTextColor(Color.rgb(255,182,90)) }
        root.addView(status)

        maxButton = Button(this).apply {
            text = "Подключить через MAX"
            setOnClickListener {
                if (MaxMessageVpnService.connected() || MaxMessageVpnService.busy()) {
                    startService(Intent(this@MainActivity, MaxMessageVpnService::class.java)
                        .setAction(MaxMessageVpnService.STOP))
                } else {
                    val ask = VpnService.prepare(this@MainActivity)
                    if (ask != null) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(ask, REQ_MAX)
                    } else startMax()
                }
            }
        }
        root.addView(maxButton)
        root.addView(info("Вход в MAX: номер → SMS-код → пароль, если его запросит MAX. Сессия сохраняется на VPS.", 14f))
        root.addView(Button(this).apply {
            text = "Войти в MAX по номеру телефона"
            setOnClickListener { startActivity(Intent(this@MainActivity, MaxPhoneLoginActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "Привязать VPS по одноразовому коду"
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    hint = "24-значный код от владельца сервера"
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Привязка своего VPS")
                    .setMessage("Это не SMS-код и не пароль MAX.")
                    .setView(input)
                    .setPositiveButton("Привязать") { _, _ ->
                        val value = input.text.toString()
                        Thread({
                            val success = try {
                                RelaySettings.pair(applicationContext, value)
                                true
                            } catch (_: Exception) { false }
                            runOnUiThread {
                                Toast.makeText(this@MainActivity,
                                    if (success) "Ключ сохранён в Android Keystore" else
                                        "Код неверный, истёк или сервер недоступен",
                                    Toast.LENGTH_LONG).show()
                                refreshStatus()
                            }
                        }, "maxvpn-pair").start()
                    }.setNegativeButton("Отмена", null).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Скопировать диагностику"
            setOnClickListener {
                val report = "MAX VPN API" + Build.VERSION.SDK_INT +
                    " этап=" + MaxMessageVpnService.stage + "; " + MaxMessageVpnService.status
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика MAX VPN", report))
                Toast.makeText(this@MainActivity,"Диагностика скопирована без ключа",Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(info("Экспериментальная версия. При ошибке связи с MAX Android VPN не включается. Скорость и работа при белых списках ещё не подтверждены.", 13f))
        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun startMax() {
        val intent = Intent(this,MaxMessageVpnService::class.java).setAction(MaxMessageVpnService.CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
        refreshStatus()
    }

    override fun onResume() { super.onResume();handler.post(refresher) }
    override fun onPause() { handler.removeCallbacks(refresher);super.onPause() }

    @Deprecated("Used for Android 7 VPN permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MAX && resultCode == RESULT_OK) startMax()
        refreshStatus()
    }

    private fun refreshStatus() {
        val token = try { RelaySettings.load(this) } catch (_: Exception) { "" }
        maxButton.isEnabled = token.length >= 40
        maxButton.text = if (MaxMessageVpnService.connected() || MaxMessageVpnService.busy())
            "Отключить MAX VPN" else "Подключить через MAX"
        status.text = if (token.length < 40) "● Сначала привяжи VPS" else
            "● " + MaxMessageVpnService.status
    }

    companion object { private const val REQ_MAX = 123 }
}
