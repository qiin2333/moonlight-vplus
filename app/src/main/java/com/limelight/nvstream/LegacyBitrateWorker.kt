package com.limelight.nvstream

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serializes the old unversioned HTTP endpoint; closing cancels queued work and late callbacks. */
internal class LegacyBitrateWorker(private val apply: (Int) -> Boolean) {
    private val lock = Any()
    @Volatile private var thread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LegacyBitrate").apply { isDaemon = true; thread = this }
    }
    private var closed = false
    val isWorkerThread: Boolean get() = Thread.currentThread() === thread

    fun submit(kbps: Int, success: (Int) -> Unit, failure: (String) -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        executor.execute {
            synchronized(lock) { if (closed) return@execute }
            val error = try { if (apply(kbps)) null else "Server returned failure response" }
                catch (e: Exception) { e.message ?: "Bitrate request failed" }
            synchronized(lock) {
                if (!closed) {
                    // Callback exceptions must not discard subsequent explicit requests.
                    runCatching { if (error == null) success(kbps) else failure(error) }
                }
            }
        }
        true
    }

    fun close() = synchronized(lock) {
        closed = true
        executor.shutdown()
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        check(!isWorkerThread) { "A bitrate worker cannot join itself" }
        return executor.awaitTermination(timeout, unit)
    }
}
