package com.limelight.binding.input.driver

import com.limelight.nvstream.jni.MoonBridge

/** Normalized controller state ready for Moonlight's driver listener. */
internal data class DualSenseNormalizedInput(
    val buttonFlags: Int,
    val leftStickX: Float,
    val leftStickY: Float,
    val rightStickX: Float,
    val rightStickY: Float,
    val leftTrigger: Float,
    val rightTrigger: Float,
    val gyro: DualSenseVector3,
    val acceleration: DualSenseVector3
)

/**
 * Stateful, transport-neutral DualSense input behavior.
 *
 * USB and Bluetooth transports only decode their envelope. This session owns axis normalization,
 * battery mapping, and touch contact transitions so both paths produce identical Moonlight events.
 */
internal class DualSenseInputSession(
    private val isControllerReady: () -> Boolean,
    private val reportBattery: (state: Byte, percentage: Byte) -> Unit,
    private val reportTouch: (
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float
    ) -> Unit
) {
    private class TouchSlot {
        var down = false
        var pointerId = -1
        var lastX = -1f
        var lastY = -1f
    }

    private val touchSlots = Array(2) { TouchSlot() }

    fun accept(state: DualSenseInputState): DualSenseNormalizedInput {
        dispatchBattery(state.battery)
        state.touches.forEachIndexed(::dispatchTouch)

        return DualSenseNormalizedInput(
            buttonFlags = state.buttonFlags,
            leftStickX = normalizeThumbStickAxis(state.leftStickX),
            leftStickY = normalizeThumbStickAxis(state.leftStickY),
            rightStickX = normalizeThumbStickAxis(state.rightStickX),
            rightStickY = normalizeThumbStickAxis(state.rightStickY),
            leftTrigger = normalizeTriggerAxis(state.leftTrigger),
            rightTrigger = normalizeTriggerAxis(state.rightTrigger),
            gyro = state.gyro,
            acceleration = state.acceleration
        )
    }

    fun releaseTouches() {
        touchSlots.forEach { slot ->
            if (slot.down) {
                reportTouch(MoonBridge.LI_TOUCH_EVENT_UP, slot.pointerId, slot.lastX, slot.lastY)
                slot.down = false
                slot.pointerId = -1
            }
        }
    }

    fun resetTouchState() {
        touchSlots.forEach {
            it.down = false
            it.pointerId = -1
        }
    }

    private fun dispatchTouch(slotIndex: Int, contact: DualSenseTouchContact) {
        val slot = touchSlots[slotIndex]

        // A DOWN consumed before arrival would be dropped by ControllerHandler. Keep this slot
        // untouched so the same stationary finger is emitted once the controller is ready.
        if (!isControllerReady()) return

        if (!contact.active) {
            if (slot.down) {
                reportTouch(MoonBridge.LI_TOUCH_EVENT_UP, slot.pointerId, slot.lastX, slot.lastY)
            }
            slot.down = false
            slot.pointerId = -1
            return
        }

        if (slot.down && slot.pointerId != contact.trackingId) {
            reportTouch(MoonBridge.LI_TOUCH_EVENT_UP, slot.pointerId, slot.lastX, slot.lastY)
            slot.down = false
        }

        if (!slot.down) {
            reportTouch(MoonBridge.LI_TOUCH_EVENT_DOWN, contact.trackingId, contact.x, contact.y)
        } else if (contact.x != slot.lastX || contact.y != slot.lastY) {
            reportTouch(MoonBridge.LI_TOUCH_EVENT_MOVE, contact.trackingId, contact.x, contact.y)
        }
        slot.down = true
        slot.pointerId = contact.trackingId
        slot.lastX = contact.x
        slot.lastY = contact.y
    }

    private fun dispatchBattery(battery: DualSenseBattery) {
        when (battery.status) {
            DualSenseBatteryStatus.DISCHARGING -> reportBattery(
                MoonBridge.LI_BATTERY_STATE_DISCHARGING,
                requireNotNull(battery.percentage).toByte()
            )
            DualSenseBatteryStatus.CHARGING -> reportBattery(
                MoonBridge.LI_BATTERY_STATE_CHARGING,
                requireNotNull(battery.percentage).toByte()
            )
            DualSenseBatteryStatus.FULL -> reportBattery(
                MoonBridge.LI_BATTERY_STATE_FULL,
                100.toByte()
            )
            DualSenseBatteryStatus.NOT_CHARGING -> reportBattery(
                MoonBridge.LI_BATTERY_STATE_NOT_CHARGING,
                0.toByte()
            )
            DualSenseBatteryStatus.UNKNOWN -> reportBattery(
                MoonBridge.LI_BATTERY_STATE_UNKNOWN,
                MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN
            )
        }
    }

    private fun normalizeThumbStickAxis(value: Int): Float =
        (2.0f * value / 255.0f) - 1.0f

    private fun normalizeTriggerAxis(value: Int): Float = value / 255.0f
}
