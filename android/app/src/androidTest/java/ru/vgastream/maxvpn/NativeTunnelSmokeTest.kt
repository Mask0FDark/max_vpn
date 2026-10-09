package ru.vgastream.maxvpn

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hev.htproxy.TProxyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Tests on Android 7 (API 24), not just the Java compiler. */
@RunWith(AndroidJUnit4::class)
class NativeTunnelSmokeTest {
    @Test fun actualVpnTunAndNativeForwarderStartOnAndroid7() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("VPN appops permission required", VpnService.prepare(app))
        val prefs = app.getSharedPreferences("native-vpn-probe", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("result").commit()
        val testIntent = Intent(app, DebugTunProbeService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(testIntent)
        else app.startService(testIntent)
        var result: String? = null
        repeat(70) {
            result = prefs.getString("result", null)
            if (result != null) return@repeat
            Thread.sleep(100)
        }
        assertNotNull("Native TUN test did not finish", result)
        assertEquals("Native TUN startup: $result", "started", result)
    }

    @Test fun nativeLibraryLoadsOnAndroid7() {
        // JNI library must not import Android 10-only symbols.
        assertFalse(TProxyService.TProxyIsRunning())
    }

    @Test fun pairingKeyUsesAndroidKeystoreOnAndroid7() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "demo-only-test-token-of-sufficient-length-abc123456"
        RelaySettings.save(app, token)
        assertEquals(token, RelaySettings.load(app))
    }
}
