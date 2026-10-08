package ru.vgastream.maxvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android TUN -> local SOCKS5 -> authenticated WSS egress on VPS.
 *
 * This is a direct VPS HTTPS transport, not yet a MAX-message VPN.
 * Never establish a TUN unless the SOCKS proxy and pairing token are ready.
 */
class MaxVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var socks: RelaySocksServer? = null
    private var nativeThread: Thread? = null
    private val active = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.get()) return START_STICKY
        if (BuildConfig.RELAY_TOKEN.length < 40) {
            status = "Нет ключа подключения VPS; интернет не перенаправлен"
            stopSelf()
            return START_NOT_STICKY
        }
        if (VpnService.prepare(this) != null) {
            status = "Требуется разрешение VPN"
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "MAX VPN — соединение", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val message = notification
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("MAX VPN — HTTPS VPS")
            .setContentText("Трафик через VPS; режим MAX пока не реализован")
            .setOngoing(true)
            .build()
        startForeground(107, message)
        status = "Запуск VPN..."
        Thread({
            try {
                active.set(true)
                val localSocks = RelaySocksServer(this, BuildConfig.RELAY_TOKEN)
                val port = localSocks.start()
                socks = localSocks
                val builder = Builder()
                    .setSession("MAX VPN — VPS HTTPS")
                    .setMtu(1500)
                    .addAddress("10.83.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                // Keep MAX WebView and relay WebSocket outside the TUN.
                builder.addDisallowedApplication(packageName)
                val established = builder.establish() ?: error("TUN unavailable")
                tun = established
                val config = File(filesDir, "hev-configuration.yaml")
                config.writeText(
                    "tunnel:\n  mtu: 1500\n  ipv4: 10.83.0.2\n  icmp: 'off'\n" +
                    "socks5:\n  address: 127.0.0.1\n  port: $port\n  udp: 'udp'\n" +
                    "misc:\n  log-level: warn\n  max-session-count: 128\n"
                )
                status = "Подключено через VPS HTTPS (не через MAX)"
                nativeThread = Thread({
                    try {
                        val success = TProxyService.TProxyStartService(config.absolutePath, established.fd)
                        if (!success && active.get()) status = "Ошибка TUN→SOCKS5"
                    } catch (_: Throwable) {
                        if (active.get()) status = "Сбой нативного VPN-движка"
                    } finally {
                        if (active.get()) {
                            teardown()
                            stopSelf()
                        }
                    }
                }, "maxvpn-tun2socks").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                status = "Ошибка запуска VPN; соединение не установлено"
                teardown()
                stopSelf()
            }
        }, "maxvpn-start").start()
        return START_STICKY
    }

    private fun teardown() {
        if (!active.getAndSet(false)) return
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        socks?.stop()
        socks = null
        status = "Не подключено"
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CONNECT = "ru.vgastream.maxvpn.CONNECT"
        const val ACTION_STOP = "ru.vgastream.maxvpn.STOP"
        private const val CHANNEL = "maxvpn-relay"
        @Volatile var status: String = "Не подключено"
    }
}
