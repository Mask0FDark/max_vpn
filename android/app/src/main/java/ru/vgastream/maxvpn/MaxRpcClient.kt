package ru.vgastream.maxvpn

import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Serial MAX RPC request client. All network work is an opted-in MAX chat.
 * No direct VPS fallback is used in this class.
 */
class MaxRpcClient(
    private val chat: MaxWebChatTransport,
    pairingToken: String
) {
    private val codec = MaxWireCodec(pairingToken)
    private val observed = LinkedHashSet<String>()
    private val lock = Any()

    private fun <T> waitFor(timeout: Long, start: ((T) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var answer: T? = null
        start {
            answer = it
            done.countDown()
        }
        if (!done.await(timeout, TimeUnit.SECONDS)) throw IllegalStateException("MAX Web timed out")
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    @Synchronized
    fun exchange(command: JSONObject, deadlineSeconds: Int = 85): JSONObject {
        require(deadlineSeconds in 5..120)
        val (id, frames) = codec.request(command.toString().toByteArray(Charsets.US_ASCII))
        synchronized(lock) {
            for (frame in frames) {
                val sent = waitFor<Boolean>(20) { done -> chat.sendFrame(frame, done) }
                if (!sent) throw IllegalStateException("MAX Web message editor is unavailable")
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds.toLong())
            while (System.nanoTime() < until) {
                val messages = waitFor<List<String>>(15) { done -> chat.readFrames(done) }
                for (message in messages) {
                    val reply = codec.accept(message) ?: continue
                    if (reply.id != id) continue
                    if (reply.kind == "error")
                        throw IllegalStateException("MAX worker rejected network request")
                    val response = JSONObject(String(reply.payload, Charsets.US_ASCII))
                    if (response.optInt("v") != 1 ||
                        response.optString("status") !in listOf("ok", "timeout", "eof"))
                        throw IllegalStateException("Invalid MAX relay reply")
                    return response
                }
                Thread.sleep(1400)
            }
            throw IllegalStateException("No encrypted MAX reply received")
        }
    }

    fun open(host: String, port: Int): String {
        val req = JSONObject().put("v",1).put("action","open").put("host",host).put("port",port)
        return exchange(req).getString("session")
    }

    fun write(session: String, data: ByteArray) {
        val req = JSONObject().put("v",1).put("action","write").put("session",session)
            .put("data",Base64.encodeToString(data,Base64.NO_WRAP))
        exchange(req)
    }

    fun read(session: String): Pair<String, ByteArray> {
        val req = JSONObject().put("v",1).put("action","read").put("session",session)
        val response = exchange(req)
        return response.getString("status") to
            Base64.decode(response.optString("data", ""),Base64.NO_WRAP)
    }

    fun close(session: String) {
        exchange(JSONObject().put("v",1).put("action","close").put("session",session), 20)
    }

    fun dns(host: String, data: ByteArray): ByteArray {
        require(data.size in 1..1200)
        val req = JSONObject().put("v",1).put("action","udp").put("host",host)
            .put("port",53).put("data",Base64.encodeToString(data, Base64.NO_WRAP))
        return Base64.decode(exchange(req).getString("data"), Base64.NO_WRAP)
    }
}
