package ru.vgastream.maxvpn

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline regression tests: MAX VPN enrolls only one Firefox app, never
 * Chrome, the VPN service itself, or the entire Android device.
 */
@RunWith(AndroidJUnit4::class)
class FirefoxVpnRoutingTest {
    @Test fun stableFirefoxGetsTheOnlyVpnRoute() {
        val selected = mutableListOf<String>()
        val packageName = FirefoxVpnRouting.allowFirstInstalled {
            selected.add(it)
        }
        assertEquals("org.mozilla.firefox", packageName)
        assertEquals(listOf("org.mozilla.firefox"), selected)
    }

    @Test fun missingStableFirefoxFallsBackToFirefoxBeta() {
        val selected = mutableListOf<String>()
        val packageName = FirefoxVpnRouting.allowFirstInstalled {
            if (it == "org.mozilla.firefox") {
                throw PackageManager.NameNotFoundException(it)
            }
            selected.add(it)
        }
        assertEquals("org.mozilla.firefox_beta", packageName)
        assertEquals(listOf("org.mozilla.firefox_beta"), selected)
    }

    @Test fun missingStableAndBetaFallsBackToFirefoxNightly() {
        val selected = mutableListOf<String>()
        val packageName = FirefoxVpnRouting.allowFirstInstalled {
            if (it != "org.mozilla.fenix") {
                throw PackageManager.NameNotFoundException(it)
            }
            selected.add(it)
        }
        assertEquals("org.mozilla.fenix", packageName)
        assertEquals(listOf("org.mozilla.fenix"), selected)
    }

    @Test fun missingFirefoxDoesNotFallBackToDeviceWideVpn() {
        var attempted = 0
        try {
            FirefoxVpnRouting.allowFirstInstalled {
                attempted++
                throw PackageManager.NameNotFoundException(it)
            }
            fail("Missing Firefox must fail closed")
        } catch (error: IllegalStateException) {
            assertTrue(error.message?.contains("Firefox") == true)
        }
        assertEquals(3, attempted)
    }
}
