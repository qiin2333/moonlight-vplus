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
import com.limelight.binding.input.touchpad.TouchpadCompatibilityDevices

@TargetApi(Build.VERSION_CODES.O)
class AndroidNativePointerCaptureProvider(
    activity: Activity,
    private val targetView: View,
    private val fallback: InputCaptureProvider? = null
) : AndroidPointerIconCaptureProvider(activity, targetView), InputManager.InputDeviceListener {

    private val inputManager: InputManager = activity.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val refreshCapture = Runnable { updateCapture() }
    private var compatibilityMode = false
    private var listening = false

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
        handler.removeCallbacks(refreshCapture)
        if (listening) inputManager.unregisterInputDeviceListener(this)
        listening = false
        targetView.releasePointerCapture()
        fallback?.disableCapture()
    }

    override fun hideCursor() {
        super.hideCursor()
        if (!listening) inputManager.registerInputDeviceListener(this, null)
        listening = true
        updateCapture()
    }

    override fun onWindowFocusChanged(focusActive: Boolean) {
        handler.removeCallbacks(refreshCapture)
        if (!focusActive || !isCapturing || isCursorVisible) {
            return
        }
        handler.postDelayed(refreshCapture, 500)
    }

    override fun isCapturingActive(): Boolean {
        return isCapturing && if (fallback != null && !compatibilityMode)
            fallback.isCapturingActive() else targetView.hasPointerCapture()
    }

    override fun isPointerInputActive(): Boolean = isCapturingActive() ||
        (isCapturing && !isCursorVisible && compatibilityMode && targetView.hasWindowFocus())

    private fun updateCapture() {
        compatibilityMode = TouchpadCompatibilityDevices.connected().any {
            TouchpadCompatibilityDevices.contains(targetView.context, it)
        }
        // A saved, disconnected device must not displace Root's Evdev path.
        // Keep the same provider alive across hotplug, releasing it only while
        // Android needs to deliver events for an online compatibility device.
        fallback?.let {
            val shouldEnable = isCapturing && !isCursorVisible && !compatibilityMode
            if (shouldEnable && !it.isCapturingEnabled()) it.enableCapture()
            else if (!shouldEnable && it.isCapturingEnabled()) it.disableCapture()
        }
        val shouldCapture = isCapturing && !isCursorVisible && targetView.hasWindowFocus() &&
            hasCaptureCompatibleInputDevice() && !compatibilityMode && fallback == null
        if (shouldCapture && !targetView.hasPointerCapture()) targetView.requestPointerCapture()
        else if (!shouldCapture && targetView.hasPointerCapture()) targetView.releasePointerCapture()
    }

    override fun destroy() {
        handler.removeCallbacks(refreshCapture)
        if (listening) inputManager.unregisterInputDeviceListener(this)
        listening = false
        targetView.releasePointerCapture()
        fallback?.destroy()
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        if (fallback != null && !compatibilityMode) return fallback.eventHasRelativeMouseAxes(event)
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
        updateCapture()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        updateCapture()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        updateCapture()
    }
}
