package com.limelight.binding.input.driver

import android.annotation.SuppressLint
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide ownership, including service replacement and late permission callbacks. */
@SuppressLint("NewApi") // CompletableFuture is supplied by core library desugaring on API 22/23.
internal class UsbForwardingReservations {
    val lock = ReentrantLock()
    private val owners = mutableMapOf<String, Lease>()

    fun contains(path: String): Boolean = lock.withLock { owners.containsKey(path) }

    fun reserve(path: String): Lease = lock.withLock {
        check(!owners.containsKey(path)) { "USB device already reserved: $path" }
        Lease(path).also { owners[path] = it }
    }

    inner class Lease internal constructor(private val path: String) {
        val ready = CompletableFuture<Void>()

        fun awaitStops(stops: List<CompletableFuture<Void>>) {
            CompletableFuture.allOf(*stops.toTypedArray()).whenComplete { _, error ->
                if (error == null) ready.complete(null)
                else ready.completeExceptionally(error)
            }
        }

        fun canRestore(): Boolean = ready.isDone && !ready.isCompletedExceptionally && !ready.isCancelled

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
