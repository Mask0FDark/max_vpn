package ru.vgastream.maxvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hev.htproxy.TProxyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Tests on Android 7 (API 24), not just the Java compiler. */
@RunWith(AndroidJUnit4::class)
class NativeTunnelSmokeTest {
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
