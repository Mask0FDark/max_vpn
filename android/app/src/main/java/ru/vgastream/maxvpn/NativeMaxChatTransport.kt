package ru.vgastream.maxvpn

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import net.jpountz.lz4.LZ4Factory
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import java.io.DataInputStream
import java.io.File
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real MAX API message transport. No WebView or HTML selectors.
 * Runs over MAX's api.oneme.ru TLS socket, using a user's local imported
 * PyMax mobile session. MAX encrypted frames are restricted by MaxWireCodec.
 */
internal class NativeMaxChatTransport private constructor(
    private val socket: SSLSocket,
    private val chatId: Long
) : MaxChatTransport {
    private val live = AtomicBoolean(true)
    private val sequence = AtomicInteger(2)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val frames = ArrayDeque<String>()
    private val framesLock = Any()
    private val outputLock = Any()
    private val pacer = MaxSendPacer()
    private val input = DataInputStream(socket.inputStream)

    private class Pending {
        val completed = CountDownLatch(1)
        @Volatile var result: Frame? = null
    }
    private data class Frame(val command: Int, val sequence: Int, val opcode: Int,
                             val body: ByteArray)

    private fun decoded(input: ByteArray): List<String> {
        if (input.isEmpty()) return emptyList()
        for (offset in 0..minOf(input.size - 1, 4)) {
            try {
                val unpacker = MessagePack.newDefaultUnpacker(
                    input.copyOfRange(offset, input.size)
                )
                val value = unpacker.unpackValue()
                unpacker.close()
                val result = ArrayList<String>()
                fun visit(v: Value, depth: Int) {
                    if (depth > 10 || result.size > 256) return
                    when {
                        v.isStringValue -> {
                            val text = v.asStringValue().asString()
                            if (text.contains("M0FD-TUNNEL-V1:")) result.add(text)
                        }
                        v.isArrayValue -> v.asArrayValue().list().forEach { visit(it, depth + 1) }
                        v.isMapValue -> v.asMapValue().map().values.forEach { visit(it, depth + 1) }
                    }
                }
                visit(value, 0)
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun read(): Frame {
        val h = ByteArray(10)
        input.readFully(h)
        require((h[0].toInt() and 255) == 10) { "Bad MAX protocol version" }
        val seq = ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
        val op = ((h[4].toInt() and 255) shl 8) or (h[5].toInt() and 255)
        val factor = h[6].toInt() and 255
        val length = ((h[7].toInt() and 255) shl 16) or
            ((h[8].toInt() and 255) shl 8) or (h[9].toInt() and 255)
        require(length in 0..2_000_000)
        val raw = ByteArray(length)
        input.readFully(raw)
        val body = when {
            factor == 0 || raw.isEmpty() -> raw
            factor == 255 -> error("Unsupported MAX zstd frame")
            else -> {
                val maxSize = length * factor
                require(maxSize in 1..8_000_000)
                LZ4Factory.safeInstance().safeDecompressor().decompress(raw, maxSize)
            }
        }
        return Frame(h[1].toInt() and 255, seq, op, body)
    }

    private fun pack(p: MessagePacker, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> p.packNil()
            is String -> p.packString(v)
            is Boolean -> p.packBoolean(v)
            is Int -> p.packInt(v)
            is Long -> p.packLong(v)
            is Double -> p.packDouble(v)
            is JSONObject -> {
                p.packMapHeader(v.length())
                for (key in v.keys()) {
                    p.packString(key)
                    pack(p, v.opt(key))
                }
            }
            is JSONArray -> {
                p.packArrayHeader(v.length())
                for (i in 0 until v.length()) pack(p, v.opt(i))
            }
            is Map<*, *> -> {
                p.packMapHeader(v.size)
                for ((key, value) in v) {
                    p.packString(key.toString())
                    pack(p, value)
                }
            }
            is List<*> -> {
                p.packArrayHeader(v.size)
                v.forEach { pack(p, it) }
            }
            else -> error("Unsupported MAX parameter")
        }
    }

    private fun send(seq: Int, op: Int, data: Map<String, Any?>) {
        val p = MessagePack.newDefaultBufferPacker()
        pack(p, data)
        p.close()
        val bytes = p.toByteArray()
        require(bytes.size < 2_000_000)
        val h = byteArrayOf(10, 0, (seq ushr 8).toByte(), seq.toByte(),
            (op ushr 8).toByte(), op.toByte(), (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(),
            bytes.size.toByte())
        synchronized(outputLock) {
            check(live.get()) { "MAX socket disconnected" }
            socket.outputStream.write(h)
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    /** Called only before the background receiver is started. */
    private fun bootstrap(seq: Int, op: Int, data: Map<String, Any?>) {
        send(seq, op, data)
        repeat(30) {
            val result = read()
            if (result.sequence == seq) {
                check(result.command == 1) {
                    "MAX opcode $op rejected command ${result.command}"
                }
                return
            }
            bufferIncoming(result)
        }
        error("MAX opcode $op response was not received")
    }

    private fun bufferIncoming(frame: Frame) {
        val items = decoded(frame.body)
        if (items.isEmpty()) return
        synchronized(framesLock) {
            items.forEach { frames.addLast(it) }
            while (frames.size > 512) frames.removeFirst()
        }
    }

    private fun receiveLoop() {
        try {
            while (live.get()) {
                val frame = read()
                val waiter = pending.remove(frame.sequence)
                if (waiter != null) {
                    waiter.result = frame
                    waiter.completed.countDown()
                } else {
                    bufferIncoming(frame)
                }
            }
        } catch (error: Exception) {
            if (live.get()) Log.e("MaxNative", "MAX_API_RECEIVER_STOPPED", error)
        } finally {
            close()
        }
    }

    private fun rejectionCategory(response: Frame): String {
        // Only inspect fixed vendor error identifiers; never log message
        // content, session tokens, peers or account data.
        val packed = String(response.body, Charsets.UTF_8).lowercase()
        return when {
            "too.many.requests" in packed || "rate.limit" in packed ||
                "too many requests" in packed || "слишком много запросов" in packed ->
                "rate_limit"
            "chat.not.found" in packed -> "chat_not_found"
            "token.invalid" in packed || "invalid.token" in packed ->
                "session_invalid"
            else -> "other"
        }
    }

    override fun sendFrame(frame: String, callback: (Boolean) -> Unit) {
        if (!frame.startsWith("M0FD-TUNNEL-V1:") || frame.length > 4000 || !live.get()) {
            Log.w("MaxNative", "MAX_API_SEND_SKIPPED bad_frame_or_connection")
            callback(false)
            return
        }
        val seq = sequence.getAndUpdate { (it + 1) and 65535 }
        val future = Pending()
        try {
            // Firefox may open many sockets simultaneously. Every MAX message
            // for this account goes through the same pacing gate.
            if (!pacer.waitForSlot { live.get() }) {
                Log.w("MaxNative", "MAX_API_SEND_COOLDOWN_OR_DISCONNECTED")
                callback(false)
                return
            }
            pending[seq] = future
            send(seq, 64, mapOf(
                "chatId" to chatId,
                "message" to mapOf(
                    "cid" to System.currentTimeMillis() * 1000 + seq,
                    "text" to frame,
                    "detectShare" to false
                ),
                "notify" to false
            ))
            val done = future.completed.await(18, TimeUnit.SECONDS)
            if (!done || future.result == null) {
                Log.e("MaxNative", "MAX_API_SEND_ACK_TIMEOUT_OR_CLOSED")
                callback(false)
                return
            }
            val reply = future.result!!
            if (reply.command != 1) {
                val reason = rejectionCategory(reply)
                pacer.rejected(reason == "rate_limit")
                Log.e("MaxNative", "MAX_API_SEND_REJECTED command=${reply.command} " +
                    "opcode=${reply.opcode} category=$reason")
                callback(false)
                return
            }
            pacer.accepted()
            callback(true)
        } catch (error: Exception) {
            Log.e("MaxNative", "MAX_API_SEND_EXCEPTION " + error.javaClass.simpleName)
            callback(false)
        } finally {
            pending.remove(seq)
        }
    }

    override fun readFrames(callback: (List<String>) -> Unit) {
        val out = ArrayList<String>()
        synchronized(framesLock) {
            while (frames.isNotEmpty() && out.size < 256) out.add(frames.removeFirst())
        }
        callback(out)
    }

    override fun close() {
        if (!live.getAndSet(false)) return
        try { socket.close() } catch (_: Exception) {}
        for (future in pending.values) future.completed.countDown()
        pending.clear()
    }

    companion object {
        fun connect(context: Context, chatId: Long): NativeMaxChatTransport {
            require(chatId != 0L)
            val file = File(context.filesDir, "native-max-session.db")
            require(file.isFile) { "Import authorized MAX session before connecting" }
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val row = try {
                db.rawQuery(
                    "SELECT token,device_id,user_agent FROM sessions LIMIT 1", null
                ).use { c ->
                    require(c.moveToFirst()) { "Empty MAX session database" }
                    arrayOf(c.getString(0), c.getString(1),
                        if (c.isNull(2)) "" else c.getString(2))
                }
            } finally { db.close() }
            require(row[0].length > 30 && row[1].isNotBlank())
            val agent = if (row[2].isNotBlank()) JSONObject(row[2]) else
                JSONObject().put("deviceType", "ANDROID").put("locale", "ru")
                    .put("appVersion", "26.15.0")
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket("api.oneme.ru", 443) as SSLSocket
            socket.soTimeout = 18000
            socket.tcpNoDelay = true
            val transport = NativeMaxChatTransport(socket, chatId)
            try {
                socket.startHandshake()
                transport.bootstrap(0, 6, mapOf("userAgent" to agent,
                    "deviceId" to row[1]))
                Log.i("MaxNative", "MAX_API_INIT_OK")
                transport.bootstrap(1, 19, mapOf(
                    "token" to row[0], "interactive" to false,
                    "chatsCount" to 40, "chatsSync" to 0,
                    "contactsSync" to 0, "presenceSync" to 0, "draftsSync" to 0
                ))
                Log.i("MaxNative", "MAX_API_LOGIN_OK")
                socket.soTimeout = 0
                Thread({ transport.receiveLoop() }, "maxvpn-native-max-receiver")
                    .apply { isDaemon = true }.start()
                return transport
            } catch (error: Throwable) {
                transport.close()
                throw error
            }
        }
    }
}
