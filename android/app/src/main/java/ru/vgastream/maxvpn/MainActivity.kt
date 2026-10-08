package ru.vgastream.maxvpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(Color.rgb(9, 18, 34))
        }
        fun label(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 16, 0, 16)
        }
        val heading = label("MAX VPN", 34f, Color.WHITE)
        heading.typeface = Typeface.DEFAULT_BOLD
        root.addView(heading)
        status = label("", 22f, Color.rgb(255, 187, 108))
        root.addView(status)
        root.addView(label("Один аккаунт MAX на телефоне и ПК. Второй аккаунт не нужен.", 18f, Color.rgb(190, 205, 225)))
        root.addView(label("Войти в личный MAX можно через официальный MAX Web внутри приложения. Интернет-туннель пока не подключён.", 15f, Color.rgb(190, 205, 225)))
        root.addView(Button(this).apply {
            text = "Войти в MAX Web"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MaxWebLoginActivity::class.java))
            }
        })
        root.addView(label("MAX VPN помогает с белыми списками. Другие ограничения он не снимает.", 15f, Color.rgb(190, 205, 225)))
        permissionButton = Button(this).apply {
            text = "Проверить разрешение VPN"
            setOnClickListener {
                val request = VpnService.prepare(this@MainActivity)
                if (request != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(request, REQUEST_VPN)
                } else {
                    refreshStatus()
                }
            }
        }
        root.addView(permissionButton)
        root.addView(Button(this).apply {
            text = "Подключение пока недоступно"
            isEnabled = false
        })
        root.addView(Button(this).apply {
            text = "Сайт проекта"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mask0FDark/max_vpn")))
            }
        })
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    @Deprecated("Uses the legacy result API for compatibility with Android 7")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) refreshStatus()
    }

    private fun refreshStatus() {
        val permissionGranted = VpnService.prepare(this) == null
        status.text = if (permissionGranted) {
            "● Разрешение VPN получено · не подключено"
        } else {
            "● VPN не подключён"
        }
        permissionButton.isEnabled = !permissionGranted
    }

    companion object {
        private const val REQUEST_VPN = 1001
    }
}
