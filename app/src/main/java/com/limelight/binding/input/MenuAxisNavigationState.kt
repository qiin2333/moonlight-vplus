package com.limelight.binding.input

import android.view.KeyEvent
import kotlin.math.abs

internal class MenuAxisNavigationState(
    private val activationThreshold: Float = 0.65f,
    private val releaseThreshold: Float = 0.35f
) {
    data class Transition(val pressedKeyCode: Int?, val changed: Boolean)

    var activeKeyCode: Int? = null
        private set

    fun update(axisPairs: List<Pair<Float, Float>>): Transition {
        val threshold = if (activeKeyCode == null) activationThreshold else releaseThreshold
        val nextKeyCode = axisPairs.firstNotNullOfOrNull { (x, y) ->
            directionKeyCode(x, y, threshold)
        }
        if (nextKeyCode == activeKeyCode) return Transition(activeKeyCode, changed = false)
        activeKeyCode = nextKeyCode
        return Transition(nextKeyCode, changed = true)
    }

    fun reset() {
        activeKeyCode = null
    }

    private fun directionKeyCode(x: Float, y: Float, threshold: Float): Int? {
        val absoluteX = abs(x)
        val absoluteY = abs(y)
        if (maxOf(absoluteX, absoluteY) < threshold) return null
        return if (absoluteX > absoluteY) {
            if (x < 0f) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            if (y < 0f) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        }
    }
}
