package ru.vgastream.maxvpn

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import net.jpountz.lz4.LZ4Factory
import java.io.DataInputStream
import java.io.File
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Experimental direct MAX mobile transport using the user's existing PyMax
 * session. No WebView, HTML selectors, direct VPS fallback or published tokens.
 *
 * Runs only as an explicit ADB probe until a full native reply path is verified.
 */
internal class NativeMaxProbe(private val context: Context) {
    data class Frame(val command: Int, val sequence: Int, val opcode: Int, val body: ByteArray)
    private fun packValue(p: org.msgpack.core.MessagePacker, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> p.packNil()
            is String -> p.packString(v)
            is Boolean -> p.packBoolean(v)
            is Int -> p.packInt(v)
            is Long -> p.packLong(v)
            is Double -> p.packDouble(v)
            is Float -> p.packFloat(v)
            is JSONObject -> {
                p.packMapHeader(v.length())
                for (k in v.keys()) {
                    p.packString(k)
                    packValue(p, v.opt(k))
                }
            }
            is Map<*, *> -> {
                p.packMapHeader(v.size)
                v.forEach { (k, value) -> p.packString(k.toString()); packValue(p, value) }
            }
            is JSONArray -> {
                p.packArrayHeader(v.length())
                for (i in 0 until v.length()) packValue(p, v.opt(i))
            }
            is List<*> -> {
                p.packArrayHeader(v.size)
                v.forEach { packValue(p, it) }
            }
            else -> error("Unexpected MAX MessagePack value type")
        }
    }
    private fun encode(map: Map<String, Any?>): ByteArray {
        val p = MessagePack.newDefaultBufferPacker()
        packValue(p, map)
        p.close()
        return p.toByteArray()
    }
    private fun read(input: DataInputStream): Frame {
        val header = ByteArray(10)
        input.readFully(header)
        val seq = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        val op = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
        val factor = header[6].toInt() and 255
        val length = ((header[7].toInt() and 255) shl 16) or
            ((header[8].toInt() and 255) shl 8) or (header[9].toInt() and 255)
        require(length in 0..2_000_000) { "MAX frame size out of bounds" }
        val raw = ByteArray(length)
        input.readFully(raw)
        val body = if (factor == 0 || raw.isEmpty()) raw
            else if (factor == 255) error("MAX zstd response not yet supported")
            else {
                val expanded = length * factor
                require(expanded in 1..8_000_000)
                LZ4Factory.safeInstance().safeDecompressor().decompress(raw, expanded)
            }
        return Frame(header[1].toInt() and 255, seq, op, body)
    }

    private fun write(sock: SSLSocket, seq: Int, op: Int, payload: Map<String, Any?>) {
        val bytes = encode(payload)
        require(bytes.size < 2_000_000)
        val header = byteArrayOf(10, 0,
            (seq ushr 8).toByte(), seq.toByte(),
            (op ushr 8).toByte(), op.toByte(),
            (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte())
        sock.outputStream.write(header)
        sock.outputStream.write(bytes)
        sock.outputStream.flush()
    }

    private fun request(sock: SSLSocket, input: DataInputStream, seq: Int,
                        op: Int, data: Map<String, Any?>,
                        onPush: (Frame) -> Unit = {}): Frame {
        write(sock, seq, op, data)
        repeat(30) {
            val reply = read(input)
            if (reply.sequence == seq) return reply
            onPush(reply)
        }
        error("MAX response sequence not received")
    }

    private fun messageTexts(bytes: ByteArray): List<String> {
        for (offset in 0..minOf(bytes.size - 1, 4)) {
            try {
                val unpacker = MessagePack.newDefaultUnpacker(bytes.copyOfRange(offset, bytes.size))
                val value = unpacker.unpackValue()
                unpacker.close()
                val found = ArrayList<String>()
                fun visit(v: Value, depth: Int) {
                    if (depth > 10) return
                    when {
                        v.isStringValue -> {
                            val text = v.asStringValue().asString()
                            if (text.contains("M0FD-TUNNEL-V1:")) found.add(text)
                        }
                        v.isArrayValue -> v.asArrayValue().list().forEach { visit(it, depth + 1) }
                        v.isMapValue -> v.asMapValue().map().values.forEach { visit(it, depth + 1) }
                    }
                }
                visit(value, 0)
                if (found.isNotEmpty()) return found
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    fun run() {
        val file = File(context.filesDir, "native-max-session.db")
        require(file.isFile) { "PyMax session has not been imported" }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val row = try {
            db.rawQuery("SELECT token,device_id,user_agent FROM sessions LIMIT 1", null).use { cursor ->
                require(cursor.moveToFirst()) { "No saved MAX session" }
                arrayOf(cursor.getString(0), cursor.getString(1),
                    if (cursor.isNull(2)) "" else cursor.getString(2))
            }
        } finally {
            db.close()
        }
        val token = row[0]
        val deviceId = row[1]
        require(token.length > 30 && deviceId.isNotBlank()) { "Invalid saved MAX session" }
        val chatId = context.getSharedPreferences("vpn-private-settings", Context.MODE_PRIVATE)
            .getLong("max_chat_id", 0L)
        require(chatId != 0L) { "No selected dedicated MAX chat" }
        val userAgent = if (row[2].isBlank())
            JSONObject().put("deviceType", "ANDROID").put("locale", "ru")
                .put("appVersion", "26.15.0")
            else JSONObject(row[2])
        Log.i("MaxNative", "MAX_NATIVE_PROBE_START")
        val sock = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket("api.oneme.ru", 443) as SSLSocket
        sock.soTimeout = 15000
        sock.tcpNoDelay = true
        sock.use {
            sock.startHandshake()
            val input = DataInputStream(sock.inputStream)
            val init = request(sock, input, 0, 6, mapOf("userAgent" to userAgent,
                "deviceId" to deviceId))
            if (init.command != 1) error("MAX native INIT rejected cmd=" + init.command)
            Log.i("MaxNative", "MAX_NATIVE_INIT_OK")
            val login = request(sock, input, 1, 19, mapOf(
                "token" to token, "interactive" to false, "chatsCount" to 40,
                "chatsSync" to 0, "contactsSync" to 0,
                "presenceSync" to 0, "draftsSync" to 0))
            if (login.command != 1) error("MAX native LOGIN rejected cmd=" + login.command)
            Log.i("MaxNative", "MAX_NATIVE_LOGIN_OK")
            // After authentication, emit one encrypted tunnel frame. The
            // companion's existing pairing token is never logged.
            val pairing = RelaySettings.load(context)
            require(pairing.length >= 40)
            val wire = MaxWireCodec(pairing)
            val command = JSONObject().put("v",1).put("action","open")
                .put("host","example.com").put("port",80)
            val (requestId, frames) =
                wire.request(command.toString().toByteArray(StandardCharsets.US_ASCII))
            var verified = false
            val pending = ArrayList<Frame>()
            fun process(frame: Frame) {
                for (text in messageTexts(frame.body)) {
                    val reply = wire.accept(text) ?: continue
                    if (reply.id == requestId && reply.kind == "response") {
                        val response = JSONObject(String(reply.payload, StandardCharsets.US_ASCII))
                        if (response.optInt("v") == 1 && response.optString("status") == "ok") {
                            verified = true
                        }
                    }
                }
            }
            for ((index, frame) in frames.withIndex()) {
                val sent = request(sock, input, index+2, 64, mapOf(
                    "chatId" to chatId,
                    "message" to mapOf(
                        "cid" to System.currentTimeMillis()*1000+index,
                        "text" to frame,
                        "detectShare" to false),
                    "notify" to false
                )) { pending.add(it) }
                if (sent.command != 1) error("MAX SEND rejected cmd=" + sent.command)
            }
            Log.i("MaxNative", "MAX_NATIVE_ENCRYPTED_FRAME_SENT")
            for (event in pending) process(event)
            sock.soTimeout = 6000
            val deadline = System.nanoTime() + 35_000_000_000L
            while (!verified && System.nanoTime() < deadline) {
                try {
                    process(read(input))
                } catch (_: SocketTimeoutException) {}
            }
            if (verified) Log.i("MaxNative", "MAX_NATIVE_ROUNDTRIP_OK")
            else error("MAX host reply was not received on native Android socket")
        }
    }
}
