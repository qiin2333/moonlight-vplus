package com.limelight.binding.input.capture

import android.annotation.TargetApi
import android.app.Activity
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View

@TargetApi(Build.VERSION_CODES.O)
open class AndroidNativePointerCaptureProvider(
    activity: Activity,
    protected val targetView: View
) : AndroidPointerIconCaptureProvider(activity, targetView), InputManager.InputDeviceListener {

    private val inputManager: InputManager = activity.getSystemService(InputManager::class.java)

    companion object {
        fun isCaptureProviderSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }
    }

    protected fun hasCaptureCompatibleInputDevice(): Boolean {
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
            if (isCapturing && !isCursorVisible && targetView.hasWindowFocus() && hasCaptureCompatibleInputDevice()) {
                targetView.requestPointerCapture()
            }
        }, 500)
    }

    override fun isCapturingActive(): Boolean {
        return isCapturing && targetView.hasPointerCapture()
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

/**
 * Captures on the Activity window and on the Presentation window. Some devices keep
 * input focus on Display 0 while a Presentation renders to the external display;
 * other devices let the Presentation window take input focus.
 */
@TargetApi(Build.VERSION_CODES.O)
class AndroidExternalPointerCaptureProvider(
    activity: Activity,
    captureHost: View,
    private val externalStreamView: View
) : AndroidNativePointerCaptureProvider(activity, captureHost) {

    private val captureTargets = listOf(targetView, externalStreamView)

    private fun hasActivePointerCapture(): Boolean =
        captureTargets.any { it.hasPointerCapture() }

    private fun requestCaptureOnFocusedTargets() {
        val focusedTargets = captureTargets.filter { it.hasWindowFocus() }
        val targets = focusedTargets.ifEmpty { listOf(targetView) }

        targets.forEach { target ->
            if (!target.hasPointerCapture() && hasCaptureCompatibleInputDevice()) {
                target.requestFocus()
                target.requestPointerCapture()
            }
        }
    }

    override fun showCursor() {
        super.showCursor()
        externalStreamView.releasePointerCapture()
        externalStreamView.pointerIcon = null
    }

    override fun hideCursor() {
        super.hideCursor()
        externalStreamView.pointerIcon = PointerIcon.getSystemIcon(
            externalStreamView.context,
            PointerIcon.TYPE_NULL
        )
        requestCaptureOnFocusedTargets()
    }

    override fun onWindowFocusChanged(focusActive: Boolean) {
        if (!focusActive || !isCapturing || isCursorVisible) return

        Handler(Looper.getMainLooper()).postDelayed({
            if (isCapturing && !isCursorVisible && hasCaptureCompatibleInputDevice()) {
                requestCaptureOnFocusedTargets()
            }
        }, CAPTURE_RETRY_DELAY_MS)
    }

    override fun isCapturingActive(): Boolean =
        isCapturing && hasActivePointerCapture()

    override fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        if (super.eventHasRelativeMouseAxes(event)) return true
        val eventSource = event.source
        return eventSource == InputDevice.SOURCE_TOUCHPAD && hasActivePointerCapture()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (!hasActivePointerCapture() && hasCaptureCompatibleInputDevice()) {
            requestCaptureOnFocusedTargets()
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (hasActivePointerCapture() && !hasCaptureCompatibleInputDevice()) {
            captureTargets.forEach(View::releasePointerCapture)
        }
    }

    private companion object {
        private const val CAPTURE_RETRY_DELAY_MS = 500L
    }
}
