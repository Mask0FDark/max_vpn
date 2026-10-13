package ru.vgastream.maxvpn

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline test of concurrent MAX RPCs using encrypted, correctly routed MX2
 * responses. The mock never calls MAX and never accesses an account token.
 */
@RunWith(AndroidJUnit4::class)
class MaxRpcMultiplexTest {
    private val token = "offline-unit-test-token-not-real-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

    private class FakeChat(private val token: String) : MaxChatTransport {
        private val inbound = ArrayDeque<String>()
        private val requests = ArrayList<String>()
        val bothSent = CountDownLatch(2)

        override fun sendFrame(frame: String, callback: (Boolean) -> Unit) {
            try {
                val packet = JSONObject(frame.removePrefix("M0FD-TUNNEL-V1:"))
                val raw = Base64.decode(packet.getString("data"), Base64.NO_WRAP)
                val request = JSONObject(String(raw, StandardCharsets.US_ASCII))
                synchronized(this) { requests.add(request.getString("request_id")) }
                bothSent.countDown()
                callback(true)
            } catch (_: Exception) {
                callback(false)
            }
        }

        override fun readFrames(callback: (List<String>) -> Unit) {
            val messages = synchronized(this) {
                val copy = inbound.toList()
                inbound.clear()
                copy
            }
            callback(messages)
        }

        override fun close() {}

        fun replyTo(index: Int) {
            val requestId = synchronized(this) { requests[index] }
            fun digest(bytes: ByteArray): ByteArray =
                MessageDigest.getInstance("SHA-256").digest(bytes)
            val secret = digest(
                ("MAXVPN-MAX-TRANSPORT-V1:" + token).toByteArray(StandardCharsets.UTF_8)
            )
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE, SecretKeySpec(digest(secret), "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(requestId.toByteArray(StandardCharsets.US_ASCII))
            val response = JSONObject().put("v", 1).put("status", "ok")
                .put("session", "fake-session-$index").toString()
                .toByteArray(StandardCharsets.US_ASCII)
            val payload = "MX2".toByteArray(StandardCharsets.US_ASCII) +
                nonce + cipher.doFinal(response)
            val envelope = JSONObject()
                .put("version", 1)
                .put("kind", "response")
                .put("request_id", requestId)
                .put("sender", "host")
                .put("recipient", "mobile")
                .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP))
                .toString().toByteArray(StandardCharsets.US_ASCII)
            val sha = digest(envelope).joinToString("") { "%02x".format(it) }
            val fragment = JSONObject()
                .put("id", UUID.randomUUID().toString().replace("-", ""))
                .put("i", 0).put("n", 1).put("sha256", sha)
                .put("data", Base64.encodeToString(envelope, Base64.NO_WRAP))
            synchronized(this) {
                inbound.addLast("M0FD-TUNNEL-V1:" + fragment.toString())
            }
        }
    }

    @Test fun secondRequestFinishesBeforeFirstReply() {
        val chat = FakeChat(token)
        val client = MaxRpcClient(chat, token)
        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = AtomicReference<JSONObject>()
        val second = AtomicReference<JSONObject>()
        val failure = AtomicReference<Throwable?>()
        fun task(action: String, result: AtomicReference<JSONObject>,
                 done: CountDownLatch) = Thread {
            try {
                result.set(client.exchange(
                    JSONObject().put("v", 1).put("action", action), 12))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        val a = task("one", first, firstDone)
        val b = task("two", second, secondDone)
        try {
            a.start()
            // Ensures the index 0 request is issued by the first thread.
            Thread.sleep(250)
            b.start()
            assertTrue("Both requests must be sent concurrently",
                chat.bothSent.await(5, TimeUnit.SECONDS))
            chat.replyTo(1)
            assertTrue("Second reply must not wait for first TCP connection",
                secondDone.await(4, TimeUnit.SECONDS))
            assertFalse("First request was not answered yet",
                firstDone.await(100, TimeUnit.MILLISECONDS))
            chat.replyTo(0)
            assertTrue(firstDone.await(4, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("MAX RPC failed", it) }
            assertEquals("fake-session-0", first.get().getString("session"))
            assertEquals("fake-session-1", second.get().getString("session"))
        } finally {
            client.close()
            a.interrupt()
            b.interrupt()
            a.join(1000)
            b.join(1000)
        }
    }
}
