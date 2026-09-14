package com.limelight.binding.input

import android.hardware.Sensor
import android.view.InputDevice
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge

/** Android adapters for pairing, vertical input layout and combined arrival capabilities. */
internal class JoyConControllerSupport(
    private val lookupContext: (Int) -> InputDeviceContext?
) {
    private val pairing = JoyConPairing()

    fun refreshPairing(context: InputDeviceContext) {
        if (context.joyCon?.combineEnabled != true) return
        val devices = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            val side = joyConSide(device.vendorId, device.productId) ?: return@mapNotNull null
            if (ControllerHandler.hasJoystickAxes(device)) id to side else null
        }.toMap()
        pairing.update(devices)
    }

    fun peer(context: InputDeviceContext): InputDeviceContext? {
        if (context.joyCon?.combineEnabled != true) return null
        val peerId = pairing.partner(context.id) ?: return null
        val peer = lookupContext(peerId) ?: return null
        // Never move two already-active players into a different slot implicitly.
        return peer.takeUnless {
            it.assignedControllerNumber && context.assignedControllerNumber &&
                it.controllerNumber != context.controllerNumber
        }
    }

    /** Returns true if this configuration has usable joystick axes. */
    fun configureInput(context: InputDeviceContext, device: InputDevice): Boolean {
        val joyCon = context.joyCon?.takeIf { it.combineEnabled } ?: return false
        context.hasJoystickAxes = ControllerHandler.hasJoystickAxes(device)
        val axes = JoyConMapping.stickAxes(joyCon.side, device.motionRanges.filter {
            ControllerHandler.getMotionRangeForJoystickAxis(device, it.axis) != null
        }.map { it.axis }.toSet())
        val left = axes.takeIf { joyCon.side == JoyConSide.LEFT }
        val right = axes.takeIf { joyCon.side == JoyConSide.RIGHT }
        context.leftStickXAxis = left?.first ?: -1
        context.leftStickYAxis = left?.second ?: -1
        context.rightStickXAxis = right?.first ?: -1
        context.rightStickYAxis = right?.second ?: -1
        // ZL/ZR are digital; Plus/Minus/Home are supplied across the two halves.
        context.leftTriggerAxis = -1
        context.rightTriggerAxis = -1
        context.hasSelect = true
        context.hasMode = true
        return context.hasJoystickAxes
    }

    data class ArrivalContribution(
        val supportedButtonFlags: Int = 0,
        val capabilities: Short = 0,
        val peerHasSensors: Boolean = false
    )

    fun arrivalContribution(context: InputDeviceContext): ArrivalContribution {
        val joyCon = context.joyCon ?: return NO_CONTRIBUTION
        val peer = peer(context)
        // hasKeys() cannot discover the left directions mapped to KEYCODE_UNKNOWN.
        var buttons = if (joyCon.side == JoyConSide.LEFT || peer?.joyCon?.side == JoyConSide.LEFT) {
            ControllerDpadState.MASK
        } else 0
        var capabilities = 0
        peer?.inputDevice?.let { device ->
            for ((key, flag) in ControllerHandler.ANDROID_TO_LI_BUTTON_MAP) {
                if (device.hasKeys(key)[0]) buttons = buttons or flag
            }
            if (peer.hatXAxis != -1) buttons = buttons or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG
            if (peer.hatYAxis != -1) buttons = buttons or ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG
        }
        if (peer?.vibrator != null || peer?.vibratorManager != null) {
            capabilities = capabilities or MoonBridge.LI_CCAP_RUMBLE.toInt()
        }
        val sensors = peer?.sensorManager
        if (sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
            capabilities = capabilities or MoonBridge.LI_CCAP_GYRO.toInt()
        }
        if (sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            capabilities = capabilities or MoonBridge.LI_CCAP_ACCEL.toInt()
        }
        return ArrivalContribution(buttons, capabilities.toShort(), sensors != null)
    }

    private companion object {
        val NO_CONTRIBUTION = ArrivalContribution()
    }
}
