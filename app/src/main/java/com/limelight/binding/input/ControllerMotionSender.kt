package com.limelight.binding.input

import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.jni.MoonBridge

/** Platform lifetime/capture checks; queue ordering and stops live in moonlight-common-c. */
internal class ControllerMotionSender(
    private val connection: NvConnection,
    private val canForward: (Short, Byte, Boolean) -> Boolean
) {
    private var closed = false
    // Platform source arbitration only. Failed frame submissions must not claim another source.
    private var frameGyroSlots = 0

    @Synchronized
    fun submit(controller: Short, type: Byte, x: Float, y: Float, z: Float) {
        val stop = type == MoonBridge.LI_MOTION_TYPE_GYRO && x == 0f && y == 0f && z == 0f
        if (!closed && controller in 0..15 && canForward(controller, type, stop)) {
            if (connection.sendControllerMotionEvent(controller.toByte(), type, x, y, z) == 0 &&
                type == MoonBridge.LI_MOTION_TYPE_GYRO) {
                frameGyroSlots = frameGyroSlots and (1 shl controller.toInt()).inv()
            }
        }
    }

    @Synchronized
    fun submitFrame(controller: Short, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        if (closed || controller !in 0..15) return
        if (canForward(controller, MoonBridge.LI_MOTION_TYPE_GYRO, gx == 0f && gy == 0f && gz == 0f) &&
            canForward(controller, MoonBridge.LI_MOTION_TYPE_ACCEL, false)) {
            if (connection.sendControllerMotionFrame(controller.toByte(), gx, gy, gz, ax, ay, az) == 0) {
                frameGyroSlots = frameGyroSlots or (1 shl controller.toInt())
            } else if (gx == 0f && gy == 0f && gz == 0f) {
                pauseGyro(controller, onlyFromFrame = true)
            }
        } else {
            submit(controller, MoonBridge.LI_MOTION_TYPE_GYRO, gx, gy, gz)
            submit(controller, MoonBridge.LI_MOTION_TYPE_ACCEL, ax, ay, az)
        }
    }

    @Synchronized
    fun stopGyro(controller: Short) = pauseGyro(controller)

    @Synchronized
    fun pauseGyro(controller: Short, onlyFromFrame: Boolean = false) {
        if (closed || controller !in 0..15) return
        val slot = 1 shl controller.toInt()
        if ((!onlyFromFrame || (frameGyroSlots and slot) != 0) &&
            canForward(controller, MoonBridge.LI_MOTION_TYPE_GYRO, true)) {
            connection.stopControllerMotionEvent(controller.toByte())
            frameGyroSlots = frameGyroSlots and slot.inv()
        }
    }

    @Synchronized
    fun reset(controller: Short, clearDemand: Boolean = false) {
        if (!closed && controller in 0..15) {
            MoonBridge.resetControllerMotionEvents(controller.toByte(), clearDemand)
            frameGyroSlots = frameGyroSlots and (1 shl controller.toInt()).inv()
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        frameGyroSlots = 0
        for (controller in 0..15) MoonBridge.resetControllerMotionEvents(controller.toByte(), true)
    }
}
