package ru.vgastream.maxvpn

import android.content.pm.PackageManager
import android.net.VpnService

/**
 * Android per-app VPN allowlist. Only one installed Firefox variant is
 * enrolled, never the entire device or a silently substituted browser.
 */
internal object FirefoxVpnRouting {
    private val candidates = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix"
    )

    // Separate package selection from VpnService.Builder for regression tests.
    fun allowFirstInstalled(allowPackage: (String) -> Unit): String {
        for (pkg in candidates) {
            try {
                allowPackage(pkg)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // Try another official Firefox Android release variant.
            }
        }
        throw IllegalStateException(
            "Firefox не установлен. Установи Firefox, затем включи MAX VPN."
        )
    }

    fun configure(builder: VpnService.Builder): String =
        allowFirstInstalled { pkg -> builder.addAllowedApplication(pkg) }
}
