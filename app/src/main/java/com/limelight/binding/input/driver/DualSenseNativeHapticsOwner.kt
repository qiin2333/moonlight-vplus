package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink

/**
 * Keeps a native haptics sink attached to the transport that owns its connection.
 *
 * The consumer is notified before shutdown so it cannot route new frames while the transport
 * waits for the sink to stop. The transport must call [close] before releasing its interfaces.
 */
internal class DualSenseNativeHapticsOwner(
    private val onAvailable: (DualSenseNativeHapticsSink) -> Unit,
    private val onGone: () -> Unit
) {
    private val lock = Any()
    private var sink: DualSenseNativeHapticsSink? = null
    private var announced = false
    private var closed = false

    fun install(candidate: DualSenseNativeHapticsSink) {
        val stopImmediately = synchronized(lock) {
            if (closed || sink != null) {
                true
            } else {
                sink = candidate
                false
            }
        }
        if (stopImmediately) candidate.stop()
    }

    fun announce() {
        synchronized(lock) {
            if (closed || announced) return
            val current = sink ?: return
            announced = true
            onAvailable(current)
        }
    }

    fun close(onStopped: () -> Unit = {}) {
        val closeState = synchronized(lock) {
            if (closed) return
            closed = true
            val current = sink
            sink = null
            CloseState(current, announced).also { announced = false }
        }

        try {
            if (closeState.wasAnnounced) onGone()
        } finally {
            val current = closeState.sink
            if (current != null) {
                current.stopAndThen(onStopped)
            } else {
                onStopped()
            }
        }
    }

    private data class CloseState(
        val sink: DualSenseNativeHapticsSink?,
        val wasAnnounced: Boolean
    )
}
