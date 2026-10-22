package com.limelight.binding.input

/**
 * Limits host forwarding, after local gyro consumers have seen the sample. A pending
 * sample is replaced in place and flushed even if the physical source goes quiet.
 * The scheduler is separate from button delivery and from slow battery polling.
 */
internal class ControllerMotionSender(
    private val nowNanos: () -> Long = System::nanoTime,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (Short, Byte, Float, Float, Float) -> Int
) {
    private inner class Sensor(val controller: Short, val type: Byte) : Runnable {
        var rate = 0
        var hasAttempted = false
        var sentAt = 0L
        var lastWasZero = true
        var scheduled = false
        var x = 0f
        var y = 0f
        var z = 0f
        var paired: Sensor? = null
        val isZero: Boolean get() = x == 0f && y == 0f && z == 0f

        // A disabled gyro may still owe the host its final zero.
        val periodNanos: Long get() = if (rate > 0) (1_000_000_000L + rate - 1) / rate else 10_000_000L

        fun update(x: Float, y: Float, z: Float): Boolean {
            val stopped = type == GYRO && x == 0f && y == 0f && z == 0f && !lastWasZero &&
                !isZero
            this.x = x; this.y = y; this.z = z
            return stopped
        }

        override fun run() = synchronized(this@ControllerMotionSender) {
            if (!scheduled) return
            scheduled = false
            if (!closed) flush(this)
        }
    }

    private val sensors = Array(16) { controller ->
        Array(2) { type -> Sensor(controller.toShort(), (type + 1).toByte()) }
    }
    private var closed = false

    private fun sensor(controller: Short, type: Byte): Sensor? =
        sensors.getOrNull(controller.toInt())?.getOrNull(type.toInt() - 1)

    @Synchronized
    fun setReportRate(controller: Short, type: Byte, rateHz: Int) {
        val state = sensor(controller, type) ?: return
        if (closed) return
        val rate = rateHz.coerceIn(0, 200)
        if (state.rate == rate) return
        unpair(controller)
        clearPending(state)
        state.rate = rate
        state.hasAttempted = false
        if (type == GYRO && !state.lastWasZero && (rate == 0 || state.isZero)) stopGyro(controller)
    }

    @Synchronized
    fun submit(controller: Short, type: Byte, x: Float, y: Float, z: Float) {
        val state = sensor(controller, type) ?: return
        if (closed || state.rate == 0) return
        unpair(controller)
        // A gyro stop is reliable in moonlight-common-c. Never leave rotation stuck
        // because its final zero happened inside the forwarding interval.
        flush(state, immediate = state.update(x, y, z))
    }

    /** A USB report's sensors share one budget when the host requests equal rates. */
    @Synchronized
    fun submitFrame(controller: Short, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val gyro = sensor(controller, GYRO) ?: return
        val accel = sensor(controller, 1) ?: return
        if (closed) return
        if (gyro.rate == 0 || gyro.rate != accel.rate) {
            submit(controller, GYRO, gx, gy, gz)
            submit(controller, 1, ax, ay, az)
            return
        }
        if (gyro.paired == null) {
            clearPending(gyro)
            clearPending(accel)
            gyro.hasAttempted = gyro.hasAttempted || accel.hasAttempted
            gyro.sentAt = maxOf(gyro.sentAt, accel.sentAt)
            gyro.paired = accel
        }
        val stopped = gyro.update(gx, gy, gz)
        accel.update(ax, ay, az)
        // A stop flushes the complete frame immediately, once per transition.
        flush(gyro, immediate = stopped)
    }

    /** Focus loss/local capture must cancel a queued movement before publishing zero. */
    @Synchronized
    fun stopGyro(controller: Short) {
        val state = sensor(controller, GYRO) ?: return
        if (closed) return
        unpair(controller)
        flush(state, immediate = state.update(0f, 0f, 0f))
    }

    /** A local assistant takes ownership without forwarding or delaying its samples. */
    @Synchronized
    fun pauseGyro(controller: Short, onlyPaired: Boolean = false) {
        val state = sensor(controller, GYRO) ?: return
        if (onlyPaired && state.paired == null) return
        unpair(controller)
        if (!state.lastWasZero) stopGyro(controller)
        else clearPending(state)
    }

    /** Keep host demand when a source is replaced within an existing host slot. */
    @Synchronized
    fun reset(controller: Short, clearDemand: Boolean = false) {
        unpair(controller)
        sensors.getOrNull(controller.toInt())?.forEach {
            clearPending(it)
            it.hasAttempted = false
            if (clearDemand) {
                it.lastWasZero = true
                it.rate = 0
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        sensors.forEach { controller -> controller.forEach(::clearPending) }
    }

    private fun flush(state: Sensor, immediate: Boolean = false) {
        val now = nowNanos()
        val remaining = if (state.hasAttempted) state.periodNanos - (now - state.sentAt) else 0L
        if (!immediate && remaining > 0) {
            enqueue(state, remaining)
            return
        }
        clearPending(state)
        state.hasAttempted = true
        state.sentAt = now
        val paired = state.paired
        if (paired != null) {
            clearPending(paired)
            paired.hasAttempted = true
            paired.sentAt = now
        }
        val sent = transmit(state)
        val pairedSent = paired?.let(::transmit) ?: true
        if (!sent || !pairedSent) {
            // JNI can reject a sample before the control stream is ready. Retain
            // the latest one and retry at a bounded rate, rather than caching success.
            enqueue(state, state.periodNanos)
        }
    }

    private fun transmit(state: Sensor): Boolean {
        if (send(state.controller, state.type, state.x, state.y, state.z) != 0) return false
        state.lastWasZero = state.isZero
        return true
    }

    private fun unpair(controller: Short) {
        val gyro = sensor(controller, GYRO) ?: return
        val accel = gyro.paired ?: return
        gyro.paired = null
        // Preserve the other sensor's pending tail when demand or source changes.
        if (gyro.scheduled && accel.rate > 0) {
            enqueue(accel, (accel.periodNanos - (nowNanos() - accel.sentAt)).coerceAtLeast(0))
        }
    }

    private fun enqueue(state: Sensor, delayNanos: Long) {
        if (state.scheduled) return
        state.scheduled = true
        schedule(state, (delayNanos + 999_999L) / 1_000_000L)
    }

    private fun clearPending(state: Sensor) {
        if (state.scheduled) cancel(state)
        state.scheduled = false
    }

    private companion object {
        const val GYRO: Byte = 2
    }
}
