package ru.vgastream.maxvpn

import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encrypted MAX RPC multiplexer.
 *
 * A single reader dispatches replies by request ID. No thread holds the
 * whole MAX channel while waiting for a remote TCP read, so an idle browser
 * connection cannot block unrelated navigation. Limit concurrent RPCs to
 * protect the chat from MAX message-rate limits.
 *
 * All network data travels through MAX messages, never directly to the VPS.
 */
class MaxRpcClient(
    private val chat: MaxChatTransport,
    pairingToken: String
) {
    private val codec = MaxWireCodec(pairingToken)
    private val pending = ConcurrentHashMap<String, ResponseWaiter>()
    private val sending = Semaphore(1, true)
    private val capacity = Semaphore(MAX_IN_FLIGHT, true)
    private val running = AtomicBoolean(true)
    private val receiver = Thread(::receiveLoop, "maxvpn-rpc-demux").apply {
        isDaemon = true
        start()
    }

    private class ResponseWaiter {
        val ready = CountDownLatch(1)
        @Volatile var reply: MaxWireCodec.Reply? = null
    }

    private fun <T> awaitCallback(timeoutMillis: Long, start: ((T) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var answer: T? = null
        start {
            answer = it
            done.countDown()
        }
        if (!done.await(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("MAX transport callback timed out")
        }
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    private fun receiveLoop() {
        try {
            while (running.get()) {
                val messages = awaitCallback<List<String>>(5_000) { chat.readFrames(it) }
                for (message in messages) {
                    val reply = codec.accept(message) ?: continue
                    val waiter = pending[reply.id] ?: continue
                    waiter.reply = reply
                    waiter.ready.countDown()
                }
                if (messages.isEmpty()) Thread.sleep(200)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Shutdown unblocks all requests instead of leaving TCP sockets
            // waiting the full RPC timeout after the MAX connection dies.
            running.set(false)
        } finally {
            pending.values.forEach { it.ready.countDown() }
        }
    }

    fun close() {
        if (!running.getAndSet(false)) return
        pending.values.forEach { it.ready.countDown() }
        receiver.interrupt()
    }

    fun exchange(command: JSONObject, deadlineSeconds: Int = 85): JSONObject {
        require(deadlineSeconds in 5..120)
        check(running.get()) { "MAX RPC transport is stopped" }
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(deadlineSeconds.toLong())
        fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(
            (deadline - System.nanoTime()).coerceAtLeast(0L)
        )
        if (!capacity.tryAcquire(remainingMillis(), TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("MAX RPC queue is saturated")
        }
        try {
            val (id, frames) = codec.request(
                command.toString().toByteArray(Charsets.US_ASCII)
            )
            val waiter = ResponseWaiter()
            pending[id] = waiter
            try {
                // Outgoing multi-frame requests must not interleave on one MAX
                // account. Incoming replies can arrive in any order.
                if (!sending.tryAcquire(remainingMillis(), TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("MAX message sending queue timed out")
                }
                try {
                    for (frame in frames) {
                        check(running.get()) { "MAX connection closed" }
                        val sent = awaitCallback<Boolean>(
                            minOf(20_000L, remainingMillis())
                        ) { done -> chat.sendFrame(frame, done) }
                        if (!sent) {
                            throw IllegalStateException(
                                "MAX frame not acknowledged; inspect MaxNative log for rate limit, vendor rejection or timeout"
                            )
                        }
                    }
                } finally {
                    sending.release()
                }
                if (!waiter.ready.await(remainingMillis(), TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("No encrypted MAX reply received")
                }
                check(running.get()) { "MAX connection closed" }
                val reply = waiter.reply
                    ?: throw IllegalStateException("MAX reply processing stopped")
                if (reply.kind == "error") {
                    throw IllegalStateException("MAX worker rejected network request")
                }
                val response = JSONObject(String(reply.payload, Charsets.US_ASCII))
                if (response.optInt("v") != 1 ||
                    response.optString("status") !in listOf("ok", "timeout", "eof")) {
                    throw IllegalStateException("Invalid MAX relay reply")
                }
                return response
            } finally {
                pending.remove(id)
            }
        } finally {
            capacity.release()
        }
    }

    fun open(host: String, port: Int): String {
        val req = JSONObject().put("v", 1).put("action", "open")
            .put("host", host).put("port", port)
        return exchange(req).getString("session")
    }

    fun write(session: String, data: ByteArray) {
        val req = JSONObject().put("v", 1).put("action", "write")
            .put("session", session)
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        exchange(req)
    }

    fun read(session: String): Pair<String, ByteArray> {
        val req = JSONObject().put("v", 1).put("action", "read")
            .put("session", session)
        val response = exchange(req)
        return response.getString("status") to
            Base64.decode(response.optString("data", ""), Base64.NO_WRAP)
    }

    fun close(session: String) {
        exchange(JSONObject().put("v", 1).put("action", "close")
            .put("session", session), 20)
    }

    fun dns(host: String, data: ByteArray): ByteArray {
        require(data.size in 1..1200)
        val req = JSONObject().put("v", 1).put("action", "udp")
            .put("host", host).put("port", 53)
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        return Base64.decode(exchange(req).getString("data"), Base64.NO_WRAP)
    }

    companion object {
        private const val MAX_IN_FLIGHT = 3
    }
}
