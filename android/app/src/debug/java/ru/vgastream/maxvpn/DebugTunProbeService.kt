package ru.vgastream.maxvpn

import android.content.Intent
import android.net.VpnService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.IBinder
import hev.htproxy.TProxyService
import java.io.File

/** Debug-only isolated TUN/JNI probe: routes only the test subnet. */
class DebugTunProbeService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("maxvpn-debug-tun", "VPN debug test", NotificationManager.IMPORTANCE_LOW))
            startForeground(109, Notification.Builder(this, "maxvpn-debug-tun")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Тест TUN").build())
        }
        Thread({
            var tun: android.os.ParcelFileDescriptor? = null
            val prefs = getSharedPreferences("native-vpn-probe", MODE_PRIVATE)
            try {
                if (VpnService.prepare(this) != null) error("VPN permission not granted")
                tun = Builder().setSession("Native test").setMtu(1500)
                    .addAddress("198.19.0.2", 32).addRoute("198.18.0.0", 15)
                    .establish() ?: error("TUN builder returned null")
                val file = File(filesDir, "probe-hev.yaml")
                file.writeText(
                    "tunnel:\n  mtu: 1500\n  ipv4: 198.19.0.2\n  icmp: 'off'\n" +
                    "socks5:\n  address: 127.0.0.1\n  port: 18873\n  udp: 'udp'\n" +
                    "misc:\n  log-level: warn\n  max-session-count: 16\n"
                )
                var engineResult = "not_started"
                val nativeAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
                val descriptor = tun.fd
                val engine = Thread({
                    try {
                        val started = TProxyService.TProxyStartService(file.absolutePath, descriptor)
                        nativeAccepted.set(started)
                        engineResult = "return_" + started
                    } catch (e: Throwable) {
                        engineResult = e.javaClass.simpleName
                    }
                }, "native-smoke-test").apply { isDaemon = true; start() }
                var started = false
                for (attempt in 1..25) {
                    if (TProxyService.TProxyIsRunning()) {
                        started = true
                        break
                    }
                    if (!engine.isAlive) break
                    Thread.sleep(200)
                }
                if (!started || !nativeAccepted.get())
                    error("JNI native start failed: $engineResult running=$started accepted=${nativeAccepted.get()}")
                prefs.edit().putString("result", "started").commit()
            } catch (e: Throwable) {
                prefs.edit().putString("result", e.javaClass.simpleName + ":" +
                    (e.message ?: "unknown").take(120)).commit()
            } finally {
                try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
                try { tun?.close() } catch (_: Throwable) {}
                stopSelf()
            }
        }, "native-probe").start()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
