package com.limelight.binding.audio

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Single completion per asynchronous stage; cancellation also rejects late callbacks. */
internal class MicrophoneRouteStage {
    private enum class State { WAITING, READY, FAILED, CANCELLED }
    private val state = AtomicReference(State.WAITING)
    private val started = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)
    val result: Boolean? get() = when (state.get()) {
        State.READY -> true
        State.FAILED, State.CANCELLED -> false
        else -> null
    }

    /** Called by the capture owner; the route request completes asynchronously. */
    fun start(
        isCurrent: () -> Boolean,
        beforeRequest: () -> Unit,
        request: ((Boolean) -> Unit) -> Unit
    ) {
        if (!started.compareAndSet(false, true)) return
        fun cancelled(): Boolean {
            if (state.get() == State.CANCELLED || !isCurrent()) {
                cancel()
                return true
            }
            return false
        }
        try {
            if (cancelled()) return
            beforeRequest()
            if (cancelled()) return
            request { success -> if (!cancelled()) complete(success) }
        } catch (_: RuntimeException) {
            complete(false)
        }
    }

    /** Waits only on the capture owner, then opens the recorder on that same thread. */
    fun openWhenReady(isCurrent: () -> Boolean, openRecorder: () -> Unit): Boolean {
        while (result == null && isCurrent()) Thread.sleep(5)
        if (result != true || !isCurrent() || !opened.compareAndSet(false, true)) return false
        openRecorder()
        return isCurrent()
    }

    fun complete(success: Boolean) { state.compareAndSet(State.WAITING, if (success) State.READY else State.FAILED) }
    fun cancel() { state.set(State.CANCELLED) }
}
