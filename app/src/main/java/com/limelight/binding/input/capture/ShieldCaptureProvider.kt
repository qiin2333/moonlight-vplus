package com.limelight.binding.input.capture

import android.content.Context
import android.hardware.input.InputManager
import android.view.MotionEvent

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// NVIDIA extended the Android input APIs with support for using an attached mouse in relative
// mode without having to grab the input device (which requires root). The data comes in the form
// of new AXIS_RELATIVE_X and AXIS_RELATIVE_Y constants in the mouse's MotionEvent objects and
// a new function, InputManager.setCursorVisibility(), that allows the cursor to be hidden.
//
// http://docs.nvidia.com/gameworks/index.html#technologies/mobile/game_controller_handling_mouse.htm

class ShieldCaptureProvider(private val context: Context) : InputCaptureProvider() {

    override fun hideCursor() {
        super.hideCursor()
        setCursorVisibility(false)
    }

    override fun showCursor() {
        super.showCursor()
        setCursorVisibility(true)
    }

    private fun setCursorVisibility(visible: Boolean): Boolean {
        return try {
            methodSetCursorVisibility!!.invoke(context.getSystemService(Context.INPUT_SERVICE), visible)
            true
        } catch (e: InvocationTargetException) {
            e.printStackTrace()
            false
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
            false
        }
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        // All mouse events should use relative axes, even if they are zero. This avoids triggering
        // cursor jumps if we get an event with no associated motion, like ACTION_DOWN or ACTION_UP.
        return event.pointerCount == 1 && event.actionIndex == 0 &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    override fun getRelativeAxisX(event: MotionEvent): Float {
        return event.getAxisValue(AXIS_RELATIVE_X)
    }

    override fun getRelativeAxisY(event: MotionEvent): Float {
        return event.getAxisValue(AXIS_RELATIVE_Y)
    }

    companion object {
        private var nvExtensionSupported = false
        private var methodSetCursorVisibility: Method? = null
        private var AXIS_RELATIVE_X = 0
        private var AXIS_RELATIVE_Y = 0

        init {
            try {
                methodSetCursorVisibility = InputManager::class.java.getMethod("setCursorVisibility", Boolean::class.javaPrimitiveType)

                val fieldRelX = MotionEvent::class.java.getField("AXIS_RELATIVE_X")
                val fieldRelY = MotionEvent::class.java.getField("AXIS_RELATIVE_Y")

                AXIS_RELATIVE_X = fieldRelX.get(null) as Int
                AXIS_RELATIVE_Y = fieldRelY.get(null) as Int

                nvExtensionSupported = true
            } catch (_: Exception) {
                nvExtensionSupported = false
            }
        }

        fun isCaptureProviderSupported(): Boolean = nvExtensionSupported
    }
}
