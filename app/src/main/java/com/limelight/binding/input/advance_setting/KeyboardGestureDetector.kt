package com.limelight.binding.input.advance_setting

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

class KeyboardGestureDetector(view: View, private val listener: GestureListener) {

    interface GestureListener {
        fun onKeyPress(view: View, keyCode: Int)
        fun onKeyRelease(view: View, keyCode: Int)
        fun onModifierHoldRelease(view: View, keyCode: Int)
        fun onLongPress(view: View, keyCode: Int)
        fun onDoubleTap(view: View, keyCode: Int)
    }

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var lastDownTime = 0L
    private var downTimeMs = 0L

    fun onTouchEvent(v: View, event: MotionEvent): Boolean {
        val tag = v.tag as? String ?: return false
        if (!tag.startsWith("k")) return false
        val keyCode = tag.substring(1).toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                v.refreshDrawableState()
                downTimeMs = System.currentTimeMillis()

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastDownTime < DOUBLE_TAP_TIMEOUT) {
                    listener.onDoubleTap(v, keyCode)
                    lastDownTime = 0
                } else {
                    listener.onKeyPress(v, keyCode)
                    lastDownTime = currentTime
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> return true

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                v.refreshDrawableState()
                val pressDuration = System.currentTimeMillis() - downTimeMs
                if (pressDuration >= HOLD_THRESHOLD) {
                    listener.onModifierHoldRelease(v, keyCode)
                } else {
                    listener.onKeyRelease(v, keyCode)
                }
                return true
            }
        }
        return false
    }

    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 250
        private const val HOLD_THRESHOLD = 200
    }
}
