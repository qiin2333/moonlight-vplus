package com.limelight.nvstream

import java.util.concurrent.Semaphore

/** A single connection attempt owns one slot until its HTTP and native work is drained. */
internal class ConnectionSlot(private val semaphore: Semaphore) {
    private val lock = Any()
    private var attempted = false
    private var revoked = false
    private var owned = false

    fun acquire(): Boolean {
        synchronized(lock) {
            check(!attempted) { "A connection object cannot be started twice" }
            attempted = true
            if (revoked) return false
        }
        semaphore.acquire()
        synchronized(lock) {
            if (revoked) {
                semaphore.release()
                return false
            }
            owned = true
            return true
        }
    }

    fun revoke() = synchronized(lock) { revoked = true }

    /** Call only after the original connection's work is terminal. Idempotent on failures. */
    fun release() = synchronized(lock) {
        revoked = true
        if (owned) {
            owned = false
            semaphore.release()
        }
    }
}
