package com.limelight.nvstream.http

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class HttpRequestDeadlineTest {
    private val client = OkHttpClient()
    private fun call() = client.newCall(Request.Builder().url("http://127.0.0.1/").build())

    @Test fun dependentRequestKeepsDiscoveryDeadline() {
        var now = 100L
        val budget = HttpRequestDeadline(5, TimeUnit.SECONDS) { now }
        val discovery = budget.applyTo(call())
        now += TimeUnit.SECONDS.toNanos(4)
        val capability = budget.applyTo(call())
        assertEquals(discovery.timeout().deadlineNanoTime(), capability.timeout().deadlineNanoTime())
        assertEquals(TimeUnit.SECONDS.toNanos(1), capability.timeout().deadlineNanoTime() - now)
    }

    @Test fun expiredDiscoveryBudgetPreventsAnotherRequest() {
        var now = 0L
        val budget = HttpRequestDeadline(5, TimeUnit.SECONDS) { now }
        now = TimeUnit.SECONDS.toNanos(5)
        val request = call()
        assertThrows(InterruptedIOException::class.java) { budget.execute(request) }
        assertFalse(request.isExecuted())
    }

    @Test(timeout = 4000) fun absoluteDeadlineCancelsAStalledHttpCall() {
        // The socket accepts TCP into its backlog but never sends an HTTP response.
        ServerSocket(0).use { server ->
            val request = client.newCall(Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/").build())
            val budget = HttpRequestDeadline(150, TimeUnit.MILLISECONDS)
            val start = System.nanoTime()
            assertThrows(IOException::class.java) { budget.execute(request).close() }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
        }
    }
}
