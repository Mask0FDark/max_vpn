package ru.vgastream.maxvpn

import android.content.Intent
import android.net.VpnService
import android.os.IBinder

/**
 * Android VPN lifecycle entry point.
 *
 * No TUN interface is established while the MAX transport is unavailable:
 * establishing one without forwarding packets would blackhole all traffic.
 */
class MaxVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
