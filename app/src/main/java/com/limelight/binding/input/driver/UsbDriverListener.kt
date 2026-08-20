package com.limelight.binding.input.driver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

interface UsbDriverListener {
    fun reportControllerState(
        controllerId: Int, buttonFlags: Int,
        leftStickX: Float, leftStickY: Float,
        rightStickX: Float, rightStickY: Float,
        leftTrigger: Float, rightTrigger: Float
    )

    fun deviceRemoved(controller: AbstractController)
    fun deviceAdded(controller: AbstractController)

    // Report motion data sourced from the USB controller itself
    fun reportControllerMotion(controllerId: Int, motionType: Byte, x: Float, y: Float, z: Float)

    // Report battery state sourced from the USB controller itself
    fun reportControllerBattery(controllerId: Int, batteryState: Byte, batteryPercentage: Byte) {}

    // Report touchpad touch events sourced from the USB controller itself
    fun reportControllerTouch(
        controllerId: Int,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float
    ) {}

    // Whether the controller has reported arrival and received its controller
    // number. Stateful consumers (e.g. touch tracking) must not consume state
    // transitions until this is true.
    fun isUsbControllerReady(controllerId: Int): Boolean = true

    // Report that a DualSense UAC audio streaming interface is available.
    // The handler should create a Ds5HapticsPump and attach it to the
    // haptics coordinator.
    fun onDs5AudioInterfaceAvailable(
        controllerId: Int,
        connection: UsbDeviceConnection,
        streamingInterface: UsbInterface,
        isoEndpoint: UsbEndpoint
    ) {}

    // Report that the previously announced DualSense audio interface is going
    // away. The handler must stop and detach the pump before the connection
    // closes.
    fun onDs5AudioInterfaceGone(controllerId: Int) {}
}
