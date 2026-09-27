package com.limelight.utils

import com.limelight.LimeLog
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal one-shot completion handle standing in for java.util.concurrent.CompletableFuture.
 * CompletableFuture is API 24+, and current core library desugaring no longer rewrites its
 * references, so loading any code path that touches it crashes API 22/23 TVs with
 * NoClassDefFoundError (issue #631).
 *
 * Listeners registered before completion run on the completing thread; late listeners run
 * inline. A throwing listener is logged and skipped without affecting other listeners or
 * the completer. Not a general replacement: no result value, no chaining, no cancellation.
 */
class CompletionSignal {
    private val lock = Any()
    private val listeners = ArrayList<(Throwable?) -> Unit>(2)
    private var failure: Throwable? = null
    private var done = false

    val isDone: Boolean
        get() = synchronized(lock) { done }

    /** True when completed with a failure (CompletableFuture.isCompletedExceptionally). */
    val isFailed: Boolean
        get() = synchronized(lock) { done && failure != null }

    /** Completes successfully; ignored if already completed. */
    fun complete() = finish(null)

    /** Completes with [error]; ignored if already completed. */
    fun completeExceptionally(error: Throwable) = finish(error)

    /** Registers [action] (receiving the failure, or null on success). */
    fun whenComplete(action: (Throwable?) -> Unit) {
        val pending: Throwable?
        synchronized(lock) {
            if (!done) {
                listeners.add(action)
                return
            }
            pending = failure
        }
        dispatch(action, pending)
    }

    /** Blocks until completion, then rethrows the failure, if any. */
    fun await() {
        await(0L)
    }

    /**
     * Blocks up to [timeoutMs] (0 or less waits indefinitely); throws [TimeoutException]
     * on timeout and rethrows the completion failure.
     */
    fun await(timeoutMs: Long) {
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else 0L
        synchronized(lock) {
            while (!done) {
                if (timeoutMs <= 0) {
                    (lock as Object).wait()
                } else {
                    val remainingNs = deadline - System.nanoTime()
                    if (remainingNs <= 0) throw TimeoutException("Completion timed out after ${timeoutMs}ms")
                    (lock as Object).wait(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
                }
            }
        }
        failure?.let { throw it }
    }

    private fun finish(error: Throwable?) {
        val fired: List<(Throwable?) -> Unit>
        synchronized(lock) {
            if (done) return
            done = true
            failure = error
            fired = ArrayList(listeners)
            listeners.clear()
            (lock as Object).notifyAll()
        }
        fired.forEach { dispatch(it, error) }
    }

    /** One throwing listener must neither abort the remaining listeners nor the completer. */
    private fun dispatch(action: (Throwable?) -> Unit, error: Throwable?) {
        try {
            action(error)
        } catch (t: Throwable) {
            LimeLog.warning("CompletionSignal listener threw: $t")
        }
    }

    companion object {
        /** An already successful signal. */
        fun completed(): CompletionSignal = CompletionSignal().apply { complete() }

        /** Succeeds only when every [signals] succeeds; fails with the first observed failure. */
        fun allOf(signals: Collection<CompletionSignal>): CompletionSignal {
            val all = CompletionSignal()
            if (signals.isEmpty()) {
                all.complete()
                return all
            }
            val remaining = AtomicInteger(signals.size)
            val firstFailure = AtomicReference<Throwable?>()
            signals.forEach { signal ->
                signal.whenComplete { error ->
                    if (error != null) firstFailure.compareAndSet(null, error)
                    if (remaining.decrementAndGet() == 0) {
                        firstFailure.get()?.let(all::completeExceptionally) ?: all.complete()
                    }
                }
            }
            return all
        }
    }
}
