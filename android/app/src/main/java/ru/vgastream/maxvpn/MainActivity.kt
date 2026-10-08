package ru.vgastream.maxvpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(48, 80, 48, 48)
   setBackgroundColor(Color.rgb(9, 18, 34))
  }
  fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
   text = value
   textSize = size
   setTextColor(color)
   setPadding(0, 16, 0, 16)
  }
  val heading = label("MAX VPN", 34f, Color.WHITE)
  heading.typeface = Typeface.DEFAULT_BOLD
  root.addView(heading)
  root.addView(label("○  VPN не подключён", 22f, Color.rgb(255, 187, 108)))
  root.addView(label("Прототип: канал через MAX и вход в личный аккаунт ещё недоступны.", 17f, Color.rgb(190, 205, 225)))
  root.addView(label("MAX VPN предназначен для доступа к интернету при белых списках. Другие ограничения он не снимает.", 15f, Color.rgb(190, 205, 225)))
  root.addView(Button(this).apply { text = "Подключение недоступно"; isEnabled = false })
  root.addView(Button(this).apply {
   text = "Сайт проекта"
   setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://max-vpn.vga-stream.ru"))) }
  })
  setContentView(root)
 }
}
