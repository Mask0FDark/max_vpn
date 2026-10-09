package ru.vgastream.maxvpn

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import hev.htproxy.TProxyService
import java.io.File

/** Debug-only isolated TUN/JNI probe: routes only the test subnet. */
class DebugTunProbeService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                val descriptor = tun.fd
                val engine = Thread({
                    try {
                        engineResult = "return_" + TProxyService.TProxyStartService(file.absolutePath, descriptor)
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
                if (!started) error("Native engine not started: $engineResult")
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
