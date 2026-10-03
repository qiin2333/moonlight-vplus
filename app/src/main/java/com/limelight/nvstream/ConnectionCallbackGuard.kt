package com.limelight.nvstream

import java.util.concurrent.atomic.AtomicBoolean

/** Revocable ownership of one connection's callbacks; UI work is checked again when executed. */
class ConnectionCallbackGuard(private val ownerIsCurrent: () -> Boolean) {
    private val revoked = AtomicBoolean(false)
    fun isCurrent(): Boolean = !revoked.get() && ownerIsCurrent()
    fun revoke(): Boolean = !revoked.getAndSet(true)

    fun dispatch(action: () -> Unit) {
        if (isCurrent()) action()
    }

    /** The application executes queued UI work and replaces its connection on the same UI thread. */
    fun post(enqueue: (() -> Unit) -> Unit, action: () -> Unit) {
        if (!isCurrent()) return
        enqueue { if (isCurrent()) action() }
    }

    /** Called on the UI thread before replacing the owner; native stop runs even if cleanup fails. */
    fun stop(cleanup: () -> Unit, stopOriginal: () -> Unit) {
        if (!isCurrent() || !revoke()) return
        try { cleanup() }
        finally { stopOriginal() }
    }
}
