package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink
import com.limelight.binding.input.haptics.HapticRouteSnapshot
import com.limelight.nvstream.jni.MoonBridge

/**
 * Transport-neutral controller events consumed by [com.limelight.binding.input.ControllerHandler].
 *
 * USB-only resources belong in [UsbDriverListener]. Keeping this interface free of Android USB
 * types allows another transport, such as the DualSense wireless bridge, to participate in the
 * existing controller lifecycle without introducing a parallel input path.
 */
interface ControllerDriverListener {
    fun onWaveformRouteChanged(route: HapticRouteSnapshot) = Unit
    fun onWaveformRouteGone(routeId: Int) = Unit
    /** Output-only companion to a system-managed InputDevice; must not allocate a player. */
    fun onSystemWaveformSinkAvailable(
        route: HapticRouteSnapshot,
        sink: com.limelight.binding.input.haptics.WaveformHapticsSink,
        onAssociationLost: () -> Unit
    ) = Unit

    fun onSystemWaveformSinkGone(routeId: Int) = Unit

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

    /** Preserves a driver's same-report six-axis sample; legacy listeners keep receiving both events. */
    fun reportControllerMotionFrame(
        controllerId: Int, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float
    ) {
        reportControllerMotion(controllerId, MoonBridge.LI_MOTION_TYPE_GYRO, gx, gy, gz)
        reportControllerMotion(controllerId, MoonBridge.LI_MOTION_TYPE_ACCEL, ax, ay, az)
    }

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

    /** Detaches routing; the transport owner stops the sink before closing its connection. */
    fun onDualSenseNativeHapticsSinkGone(controllerId: Int) = Unit
}
