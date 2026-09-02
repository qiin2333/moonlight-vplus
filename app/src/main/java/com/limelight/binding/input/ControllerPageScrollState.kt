package com.limelight.binding.input

import android.view.KeyEvent
import android.view.MotionEvent

internal data class ControllerPageScrollUpdate(
    val direction: Int,
    val shouldScroll: Boolean,
    val consumed: Boolean
)

internal class ControllerPageScrollState(
    activationThreshold: Float = 0.65f,
    releaseThreshold: Float = 0.35f,
    private val repeatIntervalMs: Long = 80L
) {
    private val axisNavigationState = MenuAxisNavigationState(
        activationThreshold = activationThreshold,
        releaseThreshold = releaseThreshold
    )
    private var lastScrollAtMs = Long.MIN_VALUE

    fun update(verticalAxes: List<Float>, nowMs: Long): ControllerPageScrollUpdate {
        val axisPairs = verticalAxes.map { 0f to it }
        val transition = axisNavigationState.update(axisPairs)
        val direction = when (transition.pressedKeyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        val shouldScroll = direction != 0 &&
            (transition.changed ||
                lastScrollAtMs == Long.MIN_VALUE ||
                nowMs - lastScrollAtMs >= repeatIntervalMs)
        if (shouldScroll) lastScrollAtMs = nowMs
        if (direction == 0 && transition.changed) lastScrollAtMs = Long.MIN_VALUE

        return ControllerPageScrollUpdate(
            direction = direction,
            shouldScroll = shouldScroll,
            consumed = transition.changed || direction != 0
        )
    }

    fun reset() {
        axisNavigationState.reset()
        lastScrollAtMs = Long.MIN_VALUE
    }
}

internal fun resolveControllerPageRightStickYAxis(
    hasRxRy: Boolean,
    hasZRz: Boolean,
    isLegacyDualShockMapping: Boolean
): Int? = when {
    hasRxRy && !isLegacyDualShockMapping -> MotionEvent.AXIS_RY
    hasZRz -> MotionEvent.AXIS_RZ
    else -> null
}
