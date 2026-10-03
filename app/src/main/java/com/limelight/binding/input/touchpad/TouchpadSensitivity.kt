package com.limelight.binding.input.touchpad

import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Action

/** Keeps fractional movement and one host cursor position across hover, taps and dragging. */
internal class TouchpadSensitivity(val pointerSpeed: Float = 1f, scrollSpeed: Float = 1f) {
    private val movement = Delta(pointerSpeed)
    private val scrolling = Delta(scrollSpeed)
    private var anchored = false
    private var relativeAvailable = false
    private var cursorX = 0f
    private var cursorY = 0f

    fun position(point: Action.Position, width: Int, height: Int): Action.Position {
        relativeAvailable = relativeAvailable || point.relativeX != 0f || point.relativeY != 0f
        if (!anchored || pointerSpeed == 1f || !relativeAvailable) {
            cursorX = point.x
            cursorY = point.y
        } else {
            // Absolute coordinates stop at the system cursor's edges. Scaling their
            // differences would make part of the host unreachable at low speed.
            cursorX += point.relativeX * pointerSpeed
            cursorY += point.relativeY * pointerSpeed
        }
        anchored = true
        cursorX = cursorX.coerceIn(0f, width.toFloat())
        cursorY = cursorY.coerceIn(0f, height.toFloat())
        return Action.Position(cursorX, cursorY)
    }

    fun move(x: Float, y: Float): Action.Move = movement.apply(x, y).let { Action.Move(it.first, it.second) }
    fun scroll(x: Float, y: Float): Action.Scroll = scrolling.apply(x, y).let { Action.Scroll(it.first, it.second) }

    fun resetPointer() {
        anchored = false
        relativeAvailable = false
        movement.reset()
    }

    fun reset() {
        resetPointer()
        scrolling.reset()
    }

    companion object {
        fun toStream(point: Action.Position, originX: Float, originY: Float,
                     scaleX: Float, scaleY: Float): Action.Position = Action.Position(
            (point.x - originX) / scaleX, (point.y - originY) / scaleY,
            point.relativeX / scaleX, point.relativeY / scaleY)
    }

    private class Delta(private val speed: Float) {
        private var x = 0f
        private var y = 0f

        fun apply(dx: Float, dy: Float): Pair<Short, Short> {
            x += dx * speed
            y += dy * speed
            val outX = x.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val outY = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            x -= outX
            y -= outY
            return outX.toShort() to outY.toShort()
        }

        fun reset() { x = 0f; y = 0f }
    }
}
