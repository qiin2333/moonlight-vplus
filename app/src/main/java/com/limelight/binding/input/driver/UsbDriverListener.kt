package com.limelight.binding.input.driver

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
}
