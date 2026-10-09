package ru.vgastream.maxvpn

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted MAX message protocol compatible with bridge.max_rpc / PCWorker MX2.
 * Only request/response protocol metadata is visible to MAX. TCP bytes are AEAD encrypted.
 */
class MaxWireCodec(pairingToken: String) {
    private val secret = sha256(("MAXVPN-MAX-TRANSPORT-V1:" + pairingToken).toByteArray(Charsets.UTF_8))
    private val aeadKey = SecretKeySpec(sha256(secret), "AES")
    private val random = SecureRandom()
    private val fragments = LinkedHashMap<String, MutableMap<Int, ByteArray>>()
    private val received = HashSet<String>()
    private val fragmentMeta = LinkedHashMap<String, Pair<Int, String>>()

    init { require(pairingToken.length >= 40) }

    data class Reply(val id: String, val kind: String, val payload: ByteArray)

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }
    private fun base64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun unbase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    private fun id() = UUID.randomUUID().toString().replace("-", "")

    fun encrypt(id: String, message: ByteArray): ByteArray {
        require(id.matches(Regex("[0-9a-f]{32}")))
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aeadKey, GCMParameterSpec(128, nonce))
        cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
        return "MX2".toByteArray(Charsets.US_ASCII) + nonce + cipher.doFinal(message)
    }

    fun decrypt(id: String, data: ByteArray): ByteArray {
        require(data.size >= 31 && data.copyOfRange(0, 3).contentEquals("MX2".toByteArray())) {
            "unexpected cipher suite"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aeadKey, GCMParameterSpec(128, data.copyOfRange(3, 15)))
        cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
        return cipher.doFinal(data.copyOfRange(15, data.size))
    }

    fun request(payload: ByteArray): Pair<String, List<String>> {
        val rid = id()
        val envelope = JSONObject()
            .put("version", 1)
            .put("kind", "request")
            .put("request_id", rid)
            .put("sender", "mobile")
            .put("recipient", "host")
            .put("payload", base64(encrypt(rid, payload)))
            .toString().toByteArray(Charsets.US_ASCII)
        val digest = hex(sha256(envelope))
        val packetId = id()
        val count = maxOf(1, (envelope.size + 1199) / 1200)
        require(count <= 256)
        val frames = (0 until count).map { i ->
            val data = envelope.copyOfRange(i * 1200, minOf((i + 1) * 1200, envelope.size))
            val body = JSONObject().put("id", packetId).put("i", i).put("n", count)
                .put("sha256", digest).put("data", base64(data))
            "M0FD-TUNNEL-V1:" + body.toString()
        }
        require(frames.all { it.length <= 4000 })
        return rid to frames
    }

    @Synchronized
    fun accept(text: String): Reply? {
        val start = text.indexOf("M0FD-TUNNEL-V1:")
        if (start < 0) return null
        val frame = text.substring(start).lineSequence().first().trim()
        if (frame.length > 4000 || !received.add(frame)) return null
        if (received.size > 800) received.clear()
        try {
            val json = JSONObject(frame.removePrefix("M0FD-TUNNEL-V1:"))
            val pid = json.getString("id")
            val index = json.getInt("i")
            val count = json.getInt("n")
            val digest = json.getString("sha256")
            require(pid.matches(Regex("[0-9a-f]{32}")) && digest.matches(Regex("[0-9a-f]{64}")))
            require(count in 1..256 && index in 0 until count)
            val data = unbase64(json.getString("data"))
            require(data.size <= 1200)
            val meta = count to digest
            if (fragmentMeta[pid] != null && fragmentMeta[pid] != meta) return null
            fragmentMeta[pid] = meta
            val parts = fragments.getOrPut(pid) { mutableMapOf() }
            if (parts[index] != null && !(parts[index]!!.contentEquals(data))) return null
            parts[index] = data
            if (fragments.size > 24) {
                val first = fragments.keys.first()
                fragments.remove(first)
                fragmentMeta.remove(first)
            }
            if (parts.size != count) return null
            val packet = (0 until count).fold(java.io.ByteArrayOutputStream()) { result, i ->
                result.apply { write(parts[i] ?: return null) }
            }.toByteArray()
            fragmentMeta.remove(pid)
            fragments.remove(pid)
            require(hex(sha256(packet)) == digest)
            val envelope = JSONObject(String(packet, Charsets.US_ASCII))
            if (envelope.getInt("version") != 1 ||
                envelope.getString("sender") != "host" ||
                envelope.getString("recipient") != "mobile") return null
            val kind = envelope.getString("kind")
            if (kind != "response" && kind != "error") return null
            val rid = envelope.getString("request_id")
            if (!rid.matches(Regex("[0-9a-f]{32}"))) return null
            return Reply(rid, kind, decrypt(rid, unbase64(envelope.getString("payload"))))
        } catch (_: Exception) {
            return null
        }
    }
}
