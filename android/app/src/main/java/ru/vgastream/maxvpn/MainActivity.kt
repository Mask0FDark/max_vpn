package ru.vgastream.maxvpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var connectButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            if (::status.isInitialized) refreshStatus()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(9, 18, 34))
        }
        fun label(value: String, size: Float, color: Int = Color.rgb(190, 205, 225)) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 12, 0, 12)
            }

        root.addView(label("MAX VPN", 32f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        status = label("", 20f, Color.rgb(255, 187, 108))
        root.addView(status)
        root.addView(label("VPS HTTPS — прямое защищённое подключение. Передача через сообщения MAX пока не подключена.", 16f))
        connectButton = Button(this).apply {
            text = "Подключить VPS"
            setOnClickListener {
                if (MaxVpnService.status.startsWith("Подключено") || MaxVpnService.status == "Запуск VPN...") {
                    startService(Intent(this@MainActivity, MaxVpnService::class.java)
                        .setAction(MaxVpnService.ACTION_STOP))
                } else {
                    val ask = VpnService.prepare(this@MainActivity)
                    if (ask != null) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(ask, REQUEST_CONNECT)
                    } else startTunnel()
                }
            }
        }
        root.addView(connectButton)

        permissionButton = Button(this).apply {
            text = "Разрешение Android VPN"
            setOnClickListener {
                val ask = VpnService.prepare(this@MainActivity)
                if (ask != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(ask, REQUEST_PERMISSION)
                } else refreshStatus()
            }
        }
        root.addView(permissionButton)

        root.addView(label("Личный MAX-аккаунт можно открыть отдельно в официальном MAX Web. Этот вход пока не связан с VPS-туннелем.", 14f))
        root.addView(Button(this).apply {
            text = "Войти в MAX Web"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MaxWebLoginActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Сайт MAX VPN"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://max-vpn.mask-0f-darkness.ru/")))
            }
        })
        root.addView(label("Режим через MAX не реализован. Прямой VPS-режим не гарантирует доступ во время белых списков.", 13f))
        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun startTunnel() {
        val intent = Intent(this, MaxVpnService::class.java).setAction(MaxVpnService.ACTION_CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    @Deprecated("Legacy result API supports Android 7")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONNECT && resultCode == RESULT_OK) startTunnel()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = "● " + MaxVpnService.status
        permissionButton.isEnabled = VpnService.prepare(this) != null
        connectButton.isEnabled = BuildConfig.RELAY_TOKEN.length >= 40
        connectButton.text = if (MaxVpnService.status.startsWith("Подключено") ||
            MaxVpnService.status == "Запуск VPN...") "Отключить VPS" else "Подключить через VPS HTTPS"
        if (!connectButton.isEnabled) status.text =
            "● Эта сборка не привязана к серверу: требуется персональный ключ"
    }

    companion object {
        private const val REQUEST_PERMISSION = 1001
        private const val REQUEST_CONNECT = 1002
    }
}
