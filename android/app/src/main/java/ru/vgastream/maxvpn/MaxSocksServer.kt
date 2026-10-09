package ru.vgastream.maxvpn

import java.io.ByteArrayInputStream
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback SOCKS5 over authenticated MAX messages.
 * No direct VPS WebSocket is used. Single-flight MAX RPC deliberately limits
 * throughput; the relay is not enabled until a live MAX roundtrip succeeds.
 */
class MaxSocksServer(private val rpc: MaxRpcClient) {
    private val active = AtomicBoolean(false)
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val pool = Executors.newCachedThreadPool { task ->
        Thread(task, "maxvpn-max-socks").apply { isDaemon = true }
    }
    private var listener: ServerSocket? = null
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    fun preflight() {
        val session = rpc.open("example.com", 443)
        rpc.close(session)
    }

    fun start(): Int {
        check(active.compareAndSet(false, true))
        listener = ServerSocket(0, 16, loopback)
        pool.execute {
            while (active.get()) {
                try {
                    val client = listener!!.accept()
                    if (clients.size >= 8) {
                        client.close()
                        continue
                    }
                    clients.add(client)
                    pool.execute {
                        try { handle(client) } catch (_: Exception) {}
                        finally {
                            clients.remove(client)
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) { if (!active.get()) break }
            }
        }
        return listener!!.localPort
    }

    fun stop() {
        active.set(false)
        try { listener?.close() } catch (_: Exception) {}
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
        pool.shutdownNow()
    }

    private fun take(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n <= 0) throw EOFException()
            offset += n
        }
        return bytes
    }

    private fun host(input: InputStream, type: Int): String {
        return when (type) {
            1 -> InetAddress.getByAddress(take(input, 4)).hostAddress!!
            4 -> InetAddress.getByAddress(take(input, 16)).hostAddress!!
            3 -> {
                val size = input.read()
                if (size !in 1..253) throw EOFException()
                String(take(input, size), Charsets.US_ASCII)
            }
            else -> throw EOFException()
        }
    }

    private fun response(output: OutputStream, status: Int, port: Int = 0) {
        output.write(byteArrayOf(5, status.toByte(), 0, 1, 127, 0, 0, 1,
            (port shr 8).toByte(), port.toByte()))
        output.flush()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        if (input.read() != 5) return
        val num = input.read()
        if (num !in 1..16) return
        if (0.toByte() !in take(input, num)) {
            output.write(byteArrayOf(5, 0xff.toByte()))
            return
        }
        output.write(byteArrayOf(5, 0))
        val req = take(input, 4)
        if (req[0] != 5.toByte()) return
        val address = host(input, req[3].toInt() and 255)
        val p = take(input, 2)
        val port = ((p[0].toInt() and 255) shl 8) or (p[1].toInt() and 255)
        when (req[1].toInt() and 255) {
            1 -> tcp(socket, input, output, address, port)
            3 -> udp(socket, input, output)
            else -> response(output, 7)
        }
    }

    private fun tcp(socket: Socket, input: InputStream, output: OutputStream,
                    address: String, port: Int) {
        val session = try { rpc.open(address, port) }
            catch (_: Exception) { response(output, 5); return }
        try {
            response(output, 0)
            socket.soTimeout = 350
            val buffer = ByteArray(512)
            while (active.get() && !socket.isClosed) {
                try {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (n > 0) rpc.write(session, buffer.copyOf(n))
                } catch (_: SocketTimeoutException) { }
                val (state, incoming) = rpc.read(session)
                if (incoming.isNotEmpty()) {
                    output.write(incoming)
                    output.flush()
                }
                if (state == "eof") break
            }
        } catch (_: Exception) { }
        finally { try { rpc.close(session) } catch (_: Exception) { } }
    }

    /** DNS-only UDP-over-MAX. Other UDP destinations are rejected server-side. */
    private fun udp(socket: Socket, input: InputStream, output: OutputStream) {
        val relay = DatagramSocket(0, loopback)
        relay.soTimeout = 2000
        var clientAddress: InetSocketAddress? = null
        response(output, 0, relay.localPort)
        val bytes = ByteArray(1500)
        try {
            while (active.get() && !socket.isClosed) {
                val datagram = DatagramPacket(bytes, bytes.size)
                try { relay.receive(datagram) }
                catch (_: SocketTimeoutException) {
                    if (input.available() > 0 && input.read() < 0) break
                    continue
                }
                if (datagram.address != loopback || datagram.length < 10) continue
                clientAddress = InetSocketAddress(datagram.address, datagram.port)
                val packet = ByteArrayInputStream(datagram.data, datagram.offset, datagram.length)
                if (!take(packet, 3).contentEquals(byteArrayOf(0, 0, 0))) continue
                val addrType = packet.read()
                val host = host(packet, addrType)
                val portRaw = take(packet, 2)
                val port = ((portRaw[0].toInt() and 255) shl 8) or (portRaw[1].toInt() and 255)
                if (port != 53) continue
                val dnsAnswer = rpc.dns(host, packet.readBytes())
                val origin = InetAddress.getByName(host)
                val reply = ByteArrayOutputStream()
                reply.write(byteArrayOf(0,0,0,(if (origin.address.size==4) 1 else 4).toByte()))
                reply.write(origin.address)
                reply.write(portRaw)
                reply.write(dnsAnswer)
                val content = reply.toByteArray()
                relay.send(DatagramPacket(content, content.size, clientAddress))
            }
        } catch (_: Exception) { }
        finally { relay.close() }
    }
}
