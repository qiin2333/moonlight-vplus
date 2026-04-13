package com.limelight.binding.input.capture

import android.annotation.TargetApi
import android.app.Activity
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

@TargetApi(Build.VERSION_CODES.O)
class AndroidNativePointerCaptureProvider(
    activity: Activity,
    private val targetView: View
) : AndroidPointerIconCaptureProvider(activity, targetView), InputManager.InputDeviceListener {

    private val inputManager: InputManager = activity.getSystemService(InputManager::class.java)

    companion object {
        fun isCaptureProviderSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }
    }

    private fun hasCaptureCompatibleInputDevice(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue

            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                !targetView.context.packageManager.hasSystemFeature("org.chromium.arc.device_management")
            ) {
                continue
            }

            if (device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            ) {
                return true
            }
        }
        return false
    }

    override fun showCursor() {
        super.showCursor()
        inputManager.unregisterInputDeviceListener(this)
        targetView.releasePointerCapture()
    }

    override fun hideCursor() {
        super.hideCursor()
        inputManager.registerInputDeviceListener(this, null)
        if (hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture()
        }
    }

    override fun onWindowFocusChanged(focusActive: Boolean) {
        if (!focusActive || !isCapturing || isCursorVisible) {
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (hasCaptureCompatibleInputDevice()) {
                targetView.requestPointerCapture()
            }
        }, 500)
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        val eventSource = event.source
        return (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
            (eventSource == InputDevice.SOURCE_TOUCHPAD && targetView.hasPointerCapture())
    }

    override fun getRelativeAxisX(event: MotionEvent): Float {
        val axis = if (event.source == InputDevice.SOURCE_MOUSE_RELATIVE)
            MotionEvent.AXIS_X else MotionEvent.AXIS_RELATIVE_X
        var x = event.getAxisValue(axis)
        for (i in 0 until event.historySize) {
            x += event.getHistoricalAxisValue(axis, i)
        }
        return x
    }

    override fun getRelativeAxisY(event: MotionEvent): Float {
        val axis = if (event.source == InputDevice.SOURCE_MOUSE_RELATIVE)
            MotionEvent.AXIS_Y else MotionEvent.AXIS_RELATIVE_Y
        var y = event.getAxisValue(axis)
        for (i in 0 until event.historySize) {
            y += event.getHistoricalAxisValue(axis, i)
        }
        return y
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (!targetView.hasPointerCapture() && hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture()
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (targetView.hasPointerCapture() && !hasCaptureCompatibleInputDevice()) {
            targetView.releasePointerCapture()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDeviceRemoved(deviceId)
        onInputDeviceAdded(deviceId)
    }
}
