package ru.vgastream.maxvpn

/**
 * Slow MAX chat messages are not a general-purpose high-rate socket.
 *
 * Paces messages (including fragments) from every Firefox connection through
 * one account-level queue. A vendor rejection applies backpressure rather
 * than retrying a possibly already delivered encrypted request.
 */
internal class MaxSendPacer(
    private val intervalMillis: Long = 1_800L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) }
) {
    private var nextSendAt = Long.MIN_VALUE
    private var rejectionDelayMillis = 15_000L

    init { require(intervalMillis in 0L..10_000L) }

    /** False means cooldown exceeds the caller's short send deadline. */
    @Synchronized
    fun waitForSlot(connected: () -> Boolean): Boolean {
        val remaining = if (nextSendAt == Long.MIN_VALUE) 0L
            else (nextSendAt - clockMillis()).coerceAtLeast(0L)
        if (remaining > 12_000L || !connected()) return false
        if (remaining != 0L) sleepMillis(remaining)
        if (!connected()) return false
        nextSendAt = clockMillis() + intervalMillis
        return true
    }

    @Synchronized
    fun rejected(rateLimited: Boolean) {
        val delay = if (rateLimited) rejectionDelayMillis else 5_000L
        nextSendAt = maxOf(nextSendAt, clockMillis() + delay)
        if (rateLimited) {
            rejectionDelayMillis = (rejectionDelayMillis * 2L).coerceAtMost(90_000L)
        }
    }

    @Synchronized
    fun accepted() {
        rejectionDelayMillis = 15_000L
    }
}
