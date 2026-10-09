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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental genuine MAX message VPN (NOT the direct VPS HTTPS relay).
 * Always verifies an encrypted MAX Web chat roundtrip BEFORE activating TUN.
 * Never breaks mobile connectivity when MAX login or egress is unavailable.
 */
class MaxMessageVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var browser: MaxBrowserSession? = null
    private var socks: MaxSocksServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            teardown("Отключено")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        status = "Проверка MAX..."
        val token = RelaySettings.load(this)
        if (token.length < 40) {
            teardown("Сначала привяжи телефон к VPS")
            stopSelf()
            return START_NOT_STICKY
        }
        if (VpnService.prepare(this) != null) {
            teardown("Требуется разрешение Android VPN")
            stopSelf()
            return START_NOT_STICKY
        }
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
        Thread({
            try {
                stage = "max_login"
                status = "Подключение к авторизованному MAX..."
                val session = MaxBrowserSession(applicationContext, CHAT_ID)
                browser = session
                val chat = session.connect()
                if (!running.get()) return@Thread

                stage = "max_roundtrip"
                status = "Проверка обмена через MAX и VPS..."
                val server = MaxSocksServer(MaxRpcClient(chat, token))
                socks = server
                server.preflight()
                if (!running.get()) return@Thread

                stage = "socks"
                val port = server.start()
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
            browser?.close()
            browser = null
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
        const val STOP = "ru.vgastream.maxvpn.MAX_STOP"
        private const val CHANNEL = "maxvpn-max-chat"
        // Dedicated test chat; operator must belong to this chat from the
        // SAME MAX account on both Android and VPS.
        private const val CHAT_ID = -76340833015983L
        @Volatile var status = "MAX VPN не подключён"
        @Volatile var stage = "idle"
        fun connected() = stage == "connected"
        fun busy() = stage in setOf("max_login","max_roundtrip","socks","tun","native")
    }
}
