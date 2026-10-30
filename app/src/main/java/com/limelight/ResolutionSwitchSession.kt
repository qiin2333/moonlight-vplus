package com.limelight

/** UI-thread transitions; other threads may inspect the target and state. Terminal states stay final. */
internal class ResolutionSwitchSession(val width: Int, val height: Int) {
    enum class State { WAITING, COMPLETE, RECONNECTING, CANCELLED }
    @Volatile var state = State.WAITING
        private set

    fun isWaitingFor(width: Int, height: Int): Boolean =
        state == State.WAITING && width == this.width && height == this.height

    fun complete(): Boolean {
        if (state != State.WAITING) return false
        state = State.COMPLETE
        return true
    }

    fun fallback(): Boolean {
        if (state != State.WAITING) return false
        state = State.RECONNECTING
        return true
    }

    fun cancel() {
        if (state == State.WAITING) state = State.CANCELLED
    }
}
