package ru.vgastream.maxvpn

import android.net.VpnService
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Protect WebSocket sockets from being routed into the VPN itself. */
class VpnSocketFactory(private val vpn: VpnService) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { vpn.protect(it) }
    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), 15_000) }
    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port), 15_000) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port), 15_000) }
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port), 15_000) }
}

/**
 * Local SOCKS5 server for hev-tun2socks, forwarding each session over an
 * authenticated TLS WebSocket to the user's VPS. This is NOT the MAX transport.
 */
class RelaySocksServer(private val vpn: VpnService, private val token: String) {
    private val alive = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "maxvpn-socks").apply { isDaemon = true }
    }
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val http = OkHttpClient.Builder()
        .socketFactory(VpnSocketFactory(vpn))
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname == "max-vpn.mask-0f-darkness.ru")
                    listOf(InetAddress.getByAddress(byteArrayOf(135.toByte(), 106, 168.toByte(), 43)))
                else Dns.SYSTEM.lookup(hostname)
            }
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var server: ServerSocket? = null

    /** Verify HTTPS, certificate, and server authorization before activating TUN. */
    fun preflight() {
        val ready = CountDownLatch(1)
        val authorized = AtomicBoolean(false)
        val webSocket = http.newWebSocket(request("/relay/check"), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (text == "paired") authorized.set(true)
                ready.countDown()
                ws.close(1000, "preflight complete")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                ready.countDown()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                ready.countDown()
            }
        })
        val completed = ready.await(20, TimeUnit.SECONDS)
        webSocket.cancel()
        if (!completed || !authorized.get()) throw IllegalStateException("VPS key or network unavailable")
    }

    fun start(): Int {
        check(alive.compareAndSet(false, true))
        server = ServerSocket(0, 32, loopback)
        pool.execute {
            while (alive.get()) {
                try {
                    val s = server!!.accept()
                    if (clients.size > 32) { s.close(); continue }
                    clients.add(s)
                    pool.execute {
                        try { handle(s) } catch (_: Exception) { }
                        finally { clients.remove(s); try { s.close() } catch (_: Exception) { } }
                    }
                } catch (_: Exception) {
                    if (!alive.get()) break
                }
            }
        }
        return server!!.localPort
    }

    fun stop() {
        alive.set(false)
        try { server?.close() } catch (_: Exception) { }
        synchronized(clients) { clients.forEach { try { it.close() } catch (_: Exception) { } }; clients.clear() }
        pool.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun exact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(result, offset, count - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
        return result
    }

    private data class Address(val host: String, val port: Int)

    private fun address(input: InputStream, kind: Int): String {
        return when(kind) {
            1 -> InetAddress.getByAddress(exact(input, 4)).hostAddress ?: ""
            4 -> InetAddress.getByAddress(exact(input, 16)).hostAddress ?: ""
            3 -> {
                val length = input.read()
                if (length !in 1..253) throw EOFException()
                String(exact(input, length), Charsets.US_ASCII)
            }
            else -> throw EOFException()
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val output = s.getOutputStream()
        if (input.read() != 5) return
        val n = input.read()
        if (n !in 1..16) return
        val methods = exact(input, n)
        if (0.toByte() !in methods) { output.write(byteArrayOf(5, 0xff.toByte())); return }
        output.write(byteArrayOf(5, 0))
        val request = exact(input, 4)
        if (request[0].toInt() != 5) return
        val destination = address(input, request[3].toInt() and 255)
        val portBytes = exact(input, 2)
        val port = ((portBytes[0].toInt() and 255) shl 8) or (portBytes[1].toInt() and 255)
        when (request[1].toInt() and 255) {
            1 -> tcp(s, input, output, destination, port)
            3 -> udp(s, input, output)
            else -> reply(output, 7, 0)
        }
    }

    private fun reply(output: OutputStream, status: Int, port: Int) {
        output.write(byteArrayOf(5, status.toByte(), 0, 1, 127, 0, 0, 1,
            (port shr 8).toByte(), port.toByte()))
        output.flush()
    }

    private fun request(path: String): Request = Request.Builder()
        .url("https://max-vpn.mask-0f-darkness.ru$path")
        .addHeader("X-MAXVPN-Token", token)
        .build()

    private fun tcp(s: Socket, input: InputStream, output: OutputStream, dest: String, port: Int) {
        val ready = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val lock = Any()
        val url = "/relay/tcp?host=" + URLEncoder.encode(dest, "UTF-8") + "&port=$port"
        val webSocket = http.newWebSocket(request(url), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                opened.set(true)
                ready.countDown()
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try { synchronized(lock) { output.write(bytes.toByteArray()); output.flush() } }
                catch (_: Exception) { try { s.close() } catch (_: Exception) { } }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                ready.countDown()
                try { s.close() } catch (_: Exception) { }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                try { s.close() } catch (_: Exception) { }
            }
        })
        if (!ready.await(25, TimeUnit.SECONDS) || !opened.get()) {
            reply(output, 5, 0)
            webSocket.cancel()
            return
        }
        reply(output, 0, 0)
        s.soTimeout = 0
        val buffer = ByteArray(16_384)
        try {
            while (alive.get() && !s.isClosed) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (webSocket.queueSize() > 1024 * 1024) break
                if (!webSocket.send(buffer.copyOf(read).toByteString())) break
            }
        } catch (_: Exception) { }
        finally { webSocket.close(1000, "socket closed"); webSocket.cancel() }
    }

    /** SOCKS5 UDP ASSOCIATE for DNS and other UDP; no unauthenticated public listener. */
    private fun udp(s: Socket, input: InputStream, output: OutputStream) {
        val datagram = DatagramSocket(0, loopback)
        datagram.soTimeout = 1000
        var sender: InetSocketAddress? = null
        val ready = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val ws = http.newWebSocket(request("/relay/udp"), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); ready.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size < 4) return
                    val len = data[0].toInt() and 255
                    if (len < 1 || len + 3 > data.size) return
                    val host = String(data, 1, len, Charsets.US_ASCII)
                    val port = ((data[len + 1].toInt() and 255) shl 8) or (data[len + 2].toInt() and 255)
                    val origin = InetAddress.getByName(host)
                    val out = ByteArrayOutputStream()
                    out.write(byteArrayOf(0, 0, 0, if (origin.address.size == 4) 1 else 4))
                    out.write(origin.address)
                    out.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
                    out.write(data, len + 3, data.size - len - 3)
                    val remote = sender ?: return
                    val packet = out.toByteArray()
                    datagram.send(DatagramPacket(packet, packet.size, remote))
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.countDown(); try { datagram.close() } catch (_: Exception) { }
            }
        })
        if (!ready.await(25, TimeUnit.SECONDS) || !connected.get()) {
            reply(output, 5, 0); datagram.close(); ws.cancel(); return
        }
        reply(output, 0, datagram.localPort)
        s.soTimeout = 1000
        val buffer = ByteArray(65535)
        try {
            while (alive.get() && !s.isClosed && !datagram.isClosed) {
                try {
                    val p = DatagramPacket(buffer, buffer.size)
                    datagram.receive(p)
                    if (p.address != loopback) continue
                    sender = InetSocketAddress(p.address, p.port)
                    val data = p.data
                    if (p.length < 10 || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 0.toByte()) continue
                    val addrKind = data[3].toInt() and 255
                    val a = java.io.ByteArrayInputStream(data, 4, p.length - 4)
                    val host = address(a, addrKind).toByteArray(Charsets.US_ASCII)
                    if (host.size !in 1..253) continue
                    val portBytes = exact(a, 2)
                    val payload = a.readBytes()
                    val frame = ByteArrayOutputStream()
                    frame.write(host.size); frame.write(host); frame.write(portBytes); frame.write(payload)
                    if (!ws.send(frame.toByteArray().toByteString())) break
                } catch (_: SocketTimeoutException) {
                    if (input.available() > 0 && input.read() == -1) break
                }
            }
        } catch (_: Exception) { }
        finally { datagram.close(); ws.cancel() }
    }
}
