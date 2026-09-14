package com.limelight.nvstream.http

import okhttp3.Call
import okhttp3.Response
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** One monotonic deadline shared by discovery and its dependent request. */
internal class HttpRequestDeadline(
    timeout: Long,
    unit: TimeUnit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadline = nanoTime() + unit.toNanos(timeout)

    internal fun applyTo(call: Call): Call {
        if (deadline - nanoTime() <= 0) throw InterruptedIOException("HTTP operation timed out")
        call.timeout().deadlineNanoTime(deadline)
        return call
    }

    fun execute(call: Call): Response = applyTo(call).execute()
}
