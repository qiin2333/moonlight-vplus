package com.limelight.binding.audio

/** A stop request is not proof that the previous SCO connection has disconnected. */
internal class MicrophoneRouteDrain {
    private var deadline: Long? = null
    private var expired = false

    fun begin(nowMs: Long) {
        if (deadline == null) {
            deadline = nowMs + 5000L
            expired = false
        }
    }

    /** An expired window belongs to a finished request; the next request waits afresh. */
    fun restartIfExpired(nowMs: Long) {
        if (deadline == null && expired) {
            deadline = nowMs + 5000L
            expired = false
        }
    }

    fun observe(nowMs: Long, disconnected: Boolean): Boolean? {
        if (disconnected) {
            deadline = null
            expired = false
            return true
        }
        val until = deadline ?: return !expired
        if (nowMs >= until) {
            expired = true
            deadline = null
            return false
        }
        return null
    }
}
