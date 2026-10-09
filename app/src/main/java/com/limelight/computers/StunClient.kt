package com.limelight.computers

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** IPv4 binding discovery only, equivalent to the native SimpleStun operation. */
internal object StunClient {
    private const val COOKIE = 0x2112a442
    private const val HEADER_SIZE = 20

    fun query(
        hostname: String,
        port: Int,
        timeoutMs: Long,
        resolve: (String) -> Array<InetAddress>,
        bind: (DatagramSocket) -> Unit,
        checkActive: () -> Unit
    ): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        checkActive()
        val servers = resolve(hostname).filterIsInstance<Inet4Address>().take(3)
        checkActive()
        if (servers.isEmpty() || System.nanoTime() >= deadline) return null
        val transaction = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val request = ByteBuffer.allocate(HEADER_SIZE).putShort(1).putShort(0).putInt(COOKIE).put(transaction).array()
        DatagramSocket().use { socket ->
            bind(socket)
            var nextSend = 0L
            val buffer = ByteArray(1024)
            while (System.nanoTime() < deadline) {
                checkActive()
                val now = System.nanoTime()
                if (now >= nextSend) {
                    for (server in servers) {
                        socket.send(DatagramPacket(request, request.size, server, port))
                    }
                    nextSend = now + TimeUnit.SECONDS.toNanos(1)
                }
                socket.soTimeout = minOf(200L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
                    .coerceAtLeast(1).toInt()
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (response.port != port || response.address !in servers) continue
                decode(buffer.copyOf(response.length), transaction)?.let { return it }
            }
        }
        return null
    }

    internal fun decode(data: ByteArray, transaction: ByteArray): String? {
        if (data.size < HEADER_SIZE || transaction.size != 12) return null
        val buffer = ByteBuffer.wrap(data)
        if ((buffer.short.toInt() and 0xffff) != 0x0101) return null
        val length = buffer.short.toInt() and 0xffff
        if (length % 4 != 0 || length > data.size - HEADER_SIZE || buffer.int != COOKIE) return null
        val receivedTransaction = ByteArray(12).also { buffer.get(it) }
        if (!receivedTransaction.contentEquals(transaction)) return null
        val end = HEADER_SIZE + length
        while (buffer.position() < end) {
            if (end - buffer.position() < 4) return null
            val type = buffer.short.toInt() and 0xffff
            val size = buffer.short.toInt() and 0xffff
            val padded = (size + 3) and -4
            if (padded > end - buffer.position()) return null
            if (type == 0x0020 && size == 8) {
                buffer.get()
                if (buffer.get().toInt() != 1) return null
                buffer.short
                val address = buffer.int xor COOKIE
                return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(address).array()).hostAddress
            }
            buffer.position(buffer.position() + padded)
        }
        return null
    }
}
