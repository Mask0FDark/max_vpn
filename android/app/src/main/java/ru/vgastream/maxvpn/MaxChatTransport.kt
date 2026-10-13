package ru.vgastream.maxvpn

/** Only MAX message frames. No direct VPS HTTP data path. */
interface MaxChatTransport {
    fun sendFrame(frame: String, callback: (Boolean) -> Unit)
    fun readFrames(callback: (List<String>) -> Unit)
    fun close()
}
