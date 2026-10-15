package com.limelight.binding.audio

import java.util.concurrent.atomic.AtomicReference

/** Single completion per asynchronous stage; cancellation also rejects late callbacks. */
internal class MicrophoneRouteStage {
    private enum class State { WAITING, READY, FAILED, CANCELLED }
    private val state = AtomicReference(State.WAITING)
    val result: Boolean? get() = when (state.get()) {
        State.READY -> true
        State.FAILED, State.CANCELLED -> false
        else -> null
    }
    fun complete(success: Boolean) { state.compareAndSet(State.WAITING, if (success) State.READY else State.FAILED) }
    fun cancel() { state.set(State.CANCELLED) }
}
