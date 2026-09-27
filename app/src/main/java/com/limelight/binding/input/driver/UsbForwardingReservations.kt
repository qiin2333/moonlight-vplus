package com.limelight.binding.input.driver

import com.limelight.utils.CompletionSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide ownership, including service replacement and late permission callbacks. */
internal class UsbForwardingReservations {
    val lock = ReentrantLock()
    private val owners = mutableMapOf<String, Lease>()

    fun contains(path: String): Boolean = lock.withLock { owners.containsKey(path) }

    /** Unplug removes the physical owner, including a connection whose close failed.
     * Successful handoffs still need exporter cleanup; pending stops must finish first. */
    fun deviceDetached(path: String) {
        val lease = lock.withLock { owners[path] } ?: return
        lease.ready.whenComplete { error ->
            if (error != null) lock.withLock {
                if (owners[path] === lease) owners.remove(path)
            }
        }
    }

    fun reserve(path: String): Lease = lock.withLock {
        check(!owners.containsKey(path)) { "USB device already reserved: $path" }
        Lease(path).also { owners[path] = it }
    }

    inner class Lease internal constructor(private val path: String) {
        val ready = CompletionSignal()

        fun awaitStops(stops: List<CompletionSignal>) {
            CompletionSignal.allOf(stops).whenComplete { error ->
                if (error == null) ready.complete()
                else ready.completeExceptionally(error)
            }
        }

        /** Register only after native exporter cleanup; never block the global cleanup chain. */
        fun restoreWhenReady(onRestored: (String) -> Unit, onFailure: (Throwable) -> Unit) {
            ready.whenComplete { error ->
                if (error == null) {
                    runCatching { restore(onRestored) }.onFailure(onFailure)
                } else {
                    onFailure(error)
                }
            }
        }

        fun canRestore(): Boolean = ready.isDone && !ready.isFailed

        /** A failed or unfinished local release must never allow a new driver owner. */
        fun restore(onRestored: (String) -> Unit) = lock.withLock {
            check(canRestore()) {
                "Local USB driver release has not completed successfully"
            }
            if (owners[path] === this) {
                owners.remove(path)
                onRestored(path)
            }
        }
    }
}
