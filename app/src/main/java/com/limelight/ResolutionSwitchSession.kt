package com.limelight

/** One attempt; terminal decisions cannot be undone by late decoder or timeout callbacks. */
internal class ResolutionSwitchSession(val width: Int, val height: Int) {
    enum class State { WAITING, COMPLETE, RECONNECTING, CANCELLED }
    @Volatile var state = State.WAITING
        private set

    fun presented(width: Int, height: Int): Boolean {
        if (state != State.WAITING || width != this.width || height != this.height) return false
        state = State.COMPLETE
        return true
    }

    fun fallback(): Boolean {
        if (state != State.WAITING) return false
        state = State.RECONNECTING
        return true
    }

    fun cancel() { state = State.CANCELLED }
}
