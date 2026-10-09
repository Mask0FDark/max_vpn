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
 * Direct HTTPS VPS transport. Fail closed: never announce a working tunnel
 * until the native TUN engine has actually started.
 */
class MaxVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var socks: RelaySocksServer? = null
    private var nativeThread: Thread? = null
    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel("Не подключено")
            stopSelf()
            return START_NOT_STICKY
        }
        // Prevent multiple workers while waiting for HTTPS pairing / Android TUN.
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        stopping.set(false)
        stage = "initialization"
        status = "Проверка подключения..."
        val pairingToken = try {
            RelaySettings.load(this)
        } catch (e: Exception) {
            fail("keystore", e)
            return START_NOT_STICKY
        }
        if (pairingToken.length < 40) {
            stopTunnel("Нет ключа VPS — сначала привяжи телефон")
            stopSelf()
            return START_NOT_STICKY
        }
        if (VpnService.prepare(this) != null) {
            stopTunnel("Не предоставлено системное разрешение Android VPN")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(CHANNEL, "MAX VPN — соединение",
                    NotificationManager.IMPORTANCE_LOW)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(this, CHANNEL) else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            startForeground(107, builder
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("MAX VPN — VPS HTTPS")
                .setContentText("Прямое соединение с твоим VPS, не через MAX")
                .setOngoing(true)
                .build())
        } catch (e: Exception) {
            fail("foreground", e)
            return START_NOT_STICKY
        }

        Thread({
            try {
                stage = "websocket"
                status = "Проверка HTTPS и ключа VPS..."
                val localSocks = RelaySocksServer(this, pairingToken)
                socks = localSocks
                localSocks.preflight()
                if (!active.get()) return@Thread

                stage = "socks"
                status = "Запускаю локальный SOCKS5..."
                val port = localSocks.start()
                if (!active.get()) return@Thread

                stage = "tun"
                status = "Создаю Android VPN-интерфейс..."
                val established = Builder()
                    .setSession("MAX VPN — VPS HTTPS")
                    .setMtu(1500)
                    .addAddress("10.83.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(packageName)
                    .establish() ?: error("VPN builder returned null")
                tun = established
                if (!active.get()) return@Thread

                stage = "native"
                status = "Запускаю TUN→SOCKS5..."
                val config = File(filesDir, "hev-configuration.yaml")
                config.writeText(
                    "tunnel:\n  mtu: 1500\n  ipv4: 10.83.0.2\n  icmp: 'off'\n" +
                    "socks5:\n  address: 127.0.0.1\n  port: $port\n  udp: 'udp'\n" +
                    "misc:\n  log-level: warn\n  max-session-count: 128\n"
                )
                nativeThread = Thread({
                    try {
                        val ok = TProxyService.TProxyStartService(config.absolutePath, established.fd)
                        if (active.get() && !stopping.get())
                            fail("native", IllegalStateException("TUN engine returned: $ok"))
                    } catch (e: Throwable) {
                        if (active.get() && !stopping.get()) fail("native", e)
                    }
                }, "maxvpn-tun2socks").apply { isDaemon = true; start() }

                // Native engine startup can take a few seconds on older devices.
                var running = false
                for (attempt in 1..20) {
                    if (!active.get() || nativeThread?.isAlive != true) break
                    if (TProxyService.TProxyIsRunning()) {
                        running = true
                        break
                    }
                    Thread.sleep(250)
                }
                if (running && active.get()) {
                    stage = "connected"
                    status = "Подключено через VPS HTTPS (не MAX)"
                } else if (active.get()) {
                    fail("native", IllegalStateException("TUN engine did not start"))
                }
            } catch (e: Throwable) {
                if (active.get() && !stopping.get()) fail(stage, e)
            }
        }, "maxvpn-start").start()
        // Do not resurrect a VPN with no network or dead process after a crash.
        return START_NOT_STICKY
    }

    private fun fail(step: String, error: Throwable) {
        val kind = error.javaClass.simpleName
        val detail = error.message?.take(110)?.replace(Regex("[\\r\\n]"), " ") ?: "без описания"
        Log.e(TAG, "VPN startup failed at $step ($kind)", error)
        stopTunnel("Ошибка [$step]: $kind — $detail")
        stopSelf()
    }

    private fun stopTunnel(message: String) {
        stopping.set(true)
        if (active.getAndSet(false)) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            try { tun?.close() } catch (_: Throwable) {}
            tun = null
            try { socks?.stop() } catch (_: Throwable) {}
            socks = null
        }
        stage = "stopped"
        status = message
    }

    override fun onRevoke() {
        stopTunnel("Android отозвал разрешение VPN")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (active.get()) stopTunnel("VPN остановлен Android")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CONNECT = "ru.vgastream.maxvpn.CONNECT"
        const val ACTION_STOP = "ru.vgastream.maxvpn.STOP"
        private const val CHANNEL = "maxvpn-relay"
        private const val TAG = "MaxVpnService"
        @Volatile var status = "Не подключено"
        @Volatile var stage = "idle"
        fun isBusy() = stage in setOf("initialization", "websocket", "socks", "tun", "native")
        fun isConnected() = stage == "connected"
    }
}
