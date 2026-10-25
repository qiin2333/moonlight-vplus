package com.limelight.binding.input

import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.jni.MoonBridge

/** Platform lifetime/capture checks; queue ordering and stops live in moonlight-common-c. */
internal class ControllerMotionSender(
    private val connection: NvConnection,
    private val canForward: (Short, Byte, Boolean) -> Boolean
) {
    private var closed = false

    @Synchronized
    fun submit(controller: Short, type: Byte, x: Float, y: Float, z: Float) {
        val stop = type == MoonBridge.LI_MOTION_TYPE_GYRO && x == 0f && y == 0f && z == 0f
        if (!closed && controller in 0..15 && canForward(controller, type, stop)) {
            connection.sendControllerMotionEvent(controller.toByte(), type, x, y, z)
        }
    }

    @Synchronized
    fun submitFrame(controller: Short, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        if (closed || controller !in 0..15) return
        if (canForward(controller, MoonBridge.LI_MOTION_TYPE_GYRO, gx == 0f && gy == 0f && gz == 0f) &&
            canForward(controller, MoonBridge.LI_MOTION_TYPE_ACCEL, false)) {
            connection.sendControllerMotionFrame(controller.toByte(), gx, gy, gz, ax, ay, az)
        } else {
            submit(controller, MoonBridge.LI_MOTION_TYPE_GYRO, gx, gy, gz)
            submit(controller, MoonBridge.LI_MOTION_TYPE_ACCEL, ax, ay, az)
        }
    }

    @Synchronized
    fun stopGyro(controller: Short) = pauseGyro(controller)

    @Synchronized
    fun pauseGyro(controller: Short, onlyFromFrame: Boolean = false) {
        if (!closed && controller in 0..15 && canForward(controller, MoonBridge.LI_MOTION_TYPE_GYRO, true)) {
            connection.stopControllerMotionEvent(controller.toByte(), onlyFromFrame)
        }
    }

    @Synchronized
    fun reset(controller: Short, clearDemand: Boolean = false) {
        if (!closed && controller in 0..15) MoonBridge.resetControllerMotionEvents(controller.toByte(), clearDemand)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        for (controller in 0..15) MoonBridge.resetControllerMotionEvents(controller.toByte(), true)
    }
}
