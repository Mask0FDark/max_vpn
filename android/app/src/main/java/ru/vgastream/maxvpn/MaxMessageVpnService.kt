package ru.vgastream.maxvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Experimental genuine MAX message VPN (NOT the direct VPS HTTPS relay).
 * Always verifies an encrypted MAX Web chat roundtrip BEFORE activating TUN.
 * Never breaks mobile connectivity when MAX login or egress is unavailable.
 */
class MaxMessageVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var nativeChat: NativeMaxChatTransport? = null
    private var socks: MaxSocksServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            teardown("Отключено")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        status = "Проверка MAX..."
        // Android requires startForeground() before any early return from this service.
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(NotificationChannel(CHANNEL,"MAX VPN — MAX транспорт",
                        NotificationManager.IMPORTANCE_LOW))
            }
            val notification = (if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(this, CHANNEL) else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(this)
                })
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("MAX VPN — через MAX")
                .setContentText("Передача через сообщения MAX")
                .setOngoing(true).build()
            startForeground(110, notification)
        } catch (e: Exception) {
            fail("notification", e)
            return START_NOT_STICKY
        }
        val token = RelaySettings.load(this)
        if (token.length < 40) {
            teardown("Сначала привяжи телефон к VPS")
            stopSelf()
            return START_NOT_STICKY
        }
        val probeOnly = BuildConfig.DEBUG && intent?.action == PROBE
        if (!probeOnly && VpnService.prepare(this) != null) {
            teardown("Требуется разрешение Android VPN")
            stopSelf()
            return START_NOT_STICKY
        }
        Thread({
            try {
                stage = "max_login"
                status = "Подключение к авторизованному MAX..."
                val selectedChatId = resolveSelectedChatId(token)
                if (selectedChatId == 0L) {
                    error("No selected MAX chat on device or paired VPS")
                }
                val chat = NativeMaxChatTransport.connect(applicationContext, selectedChatId)
                nativeChat = chat
                Log.i("MaxNative", "MAX_NATIVE_TRANSPORT_CONNECTED")
                if (!running.get()) return@Thread

                stage = "max_roundtrip"
                status = "Проверка обмена через MAX и VPS..."
                val server = MaxSocksServer(MaxRpcClient(chat, token))
                socks = server
                server.preflight()
                if (!running.get()) return@Thread
                Log.i("MaxMessageVpn", "MAX_ROUNDTRIP_OK: Android -> MAX -> VPS -> MAX")
                stage = "socks"
                val port = server.start()
                if (probeOnly) {
                    server.selfTestHttp(port)
                    Log.i("MaxNative", "MAX_NATIVE_SOCKS_HTTP_OK")
                    teardown("MAX: SOCKS5 HTTP прошёл через MAX и VPS")
                    stopSelf()
                    return@Thread
                }
                stage = "tun"
                status = "Запускаю MAX VPN..."
                tun = Builder()
                    .setSession("MAX VPN — MAX messages")
                    .setMtu(1280)
                    .addAddress("10.83.0.3", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(packageName)
                    .establish() ?: error("Failed to create Android VPN interface")
                val config = File(filesDir, "max-message-hev.yaml")
                config.writeText(
                    "tunnel:\n  mtu: 1280\n  ipv4: 10.83.0.3\n  icmp: 'off'\n" +
                    "socks5:\n  address: 127.0.0.1\n  port: $port\n  udp: 'udp'\n" +
                    "misc:\n  log-level: warn\n  max-session-count: 8\n"
                )
                stage = "native"
                val started = TProxyService.TProxyStartService(config.absolutePath, tun!!.fd)
                if (!started) error("Native VPN engine refused to start")
                var confirmed = false
                repeat(20) {
                    if (TProxyService.TProxyIsRunning()) confirmed = true
                    if (!confirmed) Thread.sleep(250)
                }
                if (!confirmed) error("Native VPN engine did not remain active")
                if (running.get()) {
                    stage = "connected"
                    status = "MAX VPN подключён: трафик через MAX (эксперимент)"
                }
            } catch (e: Throwable) {
                if (running.get()) fail(stage, e)
            }
        }, "maxvpn-max-startup").start()
        return START_NOT_STICKY
    }

    private fun resolveSelectedChatId(token: String): Long {
        val prefs = getSharedPreferences("vpn-private-settings", MODE_PRIVATE)
        val existing = prefs.getLong("max_chat_id", 0L)
        if (existing != 0L) return existing
        val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder()
                .url("https://max-vpn.mask-0f-darkness.ru/api/max/login/status")
                .header("X-MAXVPN-Token", token)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                val body = JSONObject(response.body?.string() ?: "{}")
                if (body.optString("phase") != "connected") return 0L
                val chatId = body.optLong("chat_id", 0L)
                if (chatId != 0L)
                    prefs.edit().putLong("max_chat_id", chatId).apply()
                return chatId
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun fail(step: String, error: Throwable) {
        Log.e("MaxMessageVpn", "Failed at $step", error)
        val detail = error.javaClass.simpleName + ": " + (error.message ?: "unknown").take(100)
        teardown("Ошибка MAX [$step]: $detail")
        stopSelf()
    }

    private fun teardown(reason: String) {
        if (running.getAndSet(false)) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            try { tun?.close() } catch (_: Throwable) {}
            tun = null
            socks?.stop()
            socks = null
            nativeChat?.close()
            nativeChat = null
        }
        stage = "stopped"
        status = reason
    }

    override fun onRevoke() {
        teardown("Android отозвал VPN")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running.get()) teardown("Android остановил MAX VPN")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val CONNECT = "ru.vgastream.maxvpn.MAX_CONNECT"
        const val PROBE = "ru.vgastream.maxvpn.MAX_PROBE"
        const val STOP = "ru.vgastream.maxvpn.MAX_STOP"
        private const val CHANNEL = "maxvpn-max-chat"
        @Volatile var status = "MAX VPN не подключён"
        @Volatile var stage = "idle"
        fun connected() = stage == "connected"
        fun busy() = stage in setOf("max_login","max_roundtrip","socks","tun","native")
    }
}
