package com.limelight.nvstream.http

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LegacyControlHttpTest {
    @Test(timeout = 9000)
    fun continuousReadProgressCannotKeepAnOldControlRequestAliveIndefinitely() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val peer = AtomicReference<Socket>()
        val bytesSent = AtomicInteger(0)
        val writer = thread(isDaemon = true, name = "SlowLegacyReply") {
            try {
                server.accept().use { socket ->
                    peer.set(socket)
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    while (!input.readLine().isNullOrEmpty()) { /* consume the real request */ }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    repeat(100) {
                        output.write('x'.code); output.flush(); bytesSent.incrementAndGet()
                        Thread.sleep(250)
                    }
                }
            } catch (_: IOException) { /* expected when the timed-out client is closed */ }
        }
        val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val client = NvHTTP.legacyControlClient(base)
        val started = System.nanoTime()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/bitrate").build()
            assertThrows(IOException::class.java) {
                client.newCall(request).execute().use { it.body.string() }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Continuous progress must exercise the call deadline", bytesSent.get() >= 8)
            assertTrue("Timeout must not be an immediate setup failure: $elapsedMs", elapsedMs >= 4500)
            assertTrue("Old request remained live too long: $elapsedMs", elapsedMs < 7500)
            println("legacy_control_trickle elapsedMs=$elapsedMs responseBytesSent=${bytesSent.get()} completeBody=false")
        } finally {
            peer.get()?.close(); server.close(); writer.join(1000)
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            assertFalse("Fixture writer must be terminal", writer.isAlive)
        }
    }
}
