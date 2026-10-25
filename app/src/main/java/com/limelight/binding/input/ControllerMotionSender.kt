package com.limelight.binding.input

/** Forwards source samples unchanged; only a rejected gyro zero may be retried. */
internal class ControllerMotionSender(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (Short, Byte, Float, Float, Float) -> Int
) {
    private inner class Controller(val number: Short) {
        val enabled = BooleanArray(2)
        var gyroMayBeMoving = false
        var gyroFromFrame = false
        var stopRetry: StopRetry? = null
    }

    private inner class StopRetry(private val controller: Controller) : Runnable {
        override fun run() = synchronized(this@ControllerMotionSender) {
            // A callback already dequeued before cancellation must not stop a newer source.
            if (closed || controller.stopRetry !== this) return
            if (!transmit(controller, GYRO, 0f, 0f, 0f)) schedule(this, STOP_RETRY_MS)
        }
    }

    private val controllers = Array(16) { Controller(it.toShort()) }
    private var closed = false

    /** SensorManager controls acquisition frequency; driver samples retain their source cadence. */
    @Synchronized
    fun setReportRate(controller: Short, type: Byte, rateHz: Int) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (closed || type !in ACCEL..GYRO) return
        state.enabled[type.toInt() - 1] = rateHz > 0
        if (type == GYRO && rateHz <= 0) stopGyro(controller)
    }

    @Synchronized
    fun submit(controller: Short, type: Byte, x: Float, y: Float, z: Float) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (closed || type !in ACCEL..GYRO || !state.enabled[type.toInt() - 1]) return
        transmit(state, type, x, y, z)
    }

    /** Both vectors are forwarded in order, with independent host demand and no retained tail. */
    @Synchronized
    fun submitFrame(controller: Short, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (closed) return
        if (state.enabled[1]) transmit(state, GYRO, gx, gy, gz, fromFrame = true)
        if (state.enabled[0]) transmit(state, ACCEL, ax, ay, az)
    }

    /** Explicit focus loss, capture or disable clears rotation immediately, without disabling demand. */
    @Synchronized
    fun stopGyro(controller: Short) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (!closed && state.gyroMayBeMoving && state.stopRetry == null) {
            transmit(state, GYRO, 0f, 0f, 0f)
        }
    }

    /** A rejected USB frame must not stop the phone sensor currently supplying this slot. */
    @Synchronized
    fun pauseGyro(controller: Short, onlyFromFrame: Boolean = false) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (!onlyFromFrame || state.gyroFromFrame) stopGyro(controller)
    }

    /** Clear old rotation on source replacement; forget demand only when the host slot is removed. */
    @Synchronized
    fun reset(controller: Short, clearDemand: Boolean = false) {
        val state = controllers.getOrNull(controller.toInt()) ?: return
        if (closed) return
        if (!clearDemand) {
            stopGyro(controller)
            return
        }
        cancelStop(state)
        state.enabled.fill(false)
        state.gyroMayBeMoving = false
        state.gyroFromFrame = false
    }

    @Synchronized
    fun close() {
        closed = true
        controllers.forEach(::cancelStop)
    }

    private fun transmit(state: Controller, type: Byte, x: Float, y: Float, z: Float, fromFrame: Boolean = false): Boolean {
        val gyro = type == GYRO
        val zero = x == 0f && y == 0f && z == 0f
        if (send(state.number, type, x, y, z) != 0) {
            // Never replay a rejected movement. A failed zero can still owe the host a stop.
            if (gyro && zero && state.gyroMayBeMoving && state.stopRetry == null) {
                state.stopRetry = StopRetry(state).also { schedule(it, STOP_RETRY_MS) }
            }
            return false
        }
        if (gyro) {
            cancelStop(state)
            state.gyroMayBeMoving = !zero
            state.gyroFromFrame = fromFrame
        }
        return true
    }

    private fun cancelStop(state: Controller) {
        state.stopRetry?.let(cancel)
        state.stopRetry = null
    }

    private companion object {
        const val ACCEL: Byte = 1
        const val GYRO: Byte = 2
        const val STOP_RETRY_MS = 10L
    }
}
