package ru.vgastream.maxvpn

/** The service must never claim a connection until an end-to-end MAX relay is operational. */
enum class TunnelState {
    NOT_CONFIGURED,
    PERMISSION_REQUIRED,
    READY_FOR_TRANSPORT,
    CONNECTED
}

object TunnelCapabilities {
    // The MAX message transport and authenticated relay do not exist yet.
    const val transportAvailable: Boolean = false

    fun canConnect(permissionGranted: Boolean): Boolean =
        permissionGranted && transportAvailable
}
