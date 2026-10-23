package com.limelight.binding.audio

/** A stop request is not proof that the previous SCO connection has disconnected. */
internal class MicrophoneRouteDrain {
    private var deadline: Long? = null
    fun begin(nowMs: Long) {
        if (deadline == null) deadline = nowMs + 5000L
    }
    fun observe(nowMs: Long, disconnected: Boolean): Boolean? {
        val until = deadline ?: return true
        if (disconnected) {
            deadline = null
            return true
        }
        return if (nowMs >= until) false else null
    }
}
