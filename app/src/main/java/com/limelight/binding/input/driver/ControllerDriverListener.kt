package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink

/**
 * Transport-neutral controller events consumed by [com.limelight.binding.input.ControllerHandler].
 *
 * USB-only resources belong in [UsbDriverListener]. Keeping this interface free of Android USB
 * types allows another transport, such as the DualSense wireless bridge, to participate in the
 * existing controller lifecycle without introducing a parallel input path.
 */
interface ControllerDriverListener {
    fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float
    )

    fun deviceRemoved(controller: AbstractController)

    fun deviceAdded(controller: AbstractController)

    fun reportControllerMotion(
        controllerId: Int,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float
    )

    fun reportControllerBattery(
        controllerId: Int,
        batteryState: Byte,
        batteryPercentage: Byte
    ) = Unit

    fun reportControllerTouch(
        controllerId: Int,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float
    ) = Unit

    /**
     * Whether arrival metadata has been accepted and the driver may emit stateful transitions.
     */
    fun isControllerReady(controllerId: Int): Boolean = true

    fun onDualSenseNativeHapticsSinkAvailable(
        controllerId: Int,
        sink: DualSenseNativeHapticsSink
    ) = Unit

    /** The listener must stop and detach the sink before returning. */
    fun onDualSenseNativeHapticsSinkGone(controllerId: Int) = Unit
}
