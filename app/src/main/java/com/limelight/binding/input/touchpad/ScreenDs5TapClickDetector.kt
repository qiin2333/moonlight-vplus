package com.limelight.binding.input.touchpad

import kotlin.math.abs

/**
 * Decides whether a finger lift completes a DualSense clickpad tap.
 *
 * Mirrors the verified HarmonyOS behavior: a tap is a single-finger contact
 * that stays short (<= [timeThresholdMs]) and still (<= [movementThresholdPx]
 * from the press point). A second finger or drifting movement disqualifies the
 * gesture, and eligibility never returns once lost.
 */
internal class ScreenDs5TapClickDetector(
    private val movementThresholdPx: Float,
    private val timeThresholdMs: Long = DEFAULT_TAP_TIME_MS,
) {
    private var pointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var tapEligible = false

    fun onDown(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        this.pointerId = pointerId
        startX = x
        startY = y
        startTime = eventTime
        tapEligible = true
    }

    /** A second finger landing disqualifies the whole gesture. */
    fun onPointerDown() {
        tapEligible = false
    }

    fun onMove(pointerId: Int, x: Float, y: Float) {
        if (pointerId != this.pointerId) return
        if (exceedsMovement(x, y)) tapEligible = false
    }

    /** Returns true when the lift completes a valid tap; consumes the gesture either way. */
    fun onUp(pointerId: Int, x: Float, y: Float, eventTime: Long): Boolean {
        val isTap = tapEligible &&
            pointerId == this.pointerId &&
            eventTime - startTime <= timeThresholdMs &&
            !exceedsMovement(x, y)
        reset()
        return isTap
    }

    fun cancel() = reset()

    private fun exceedsMovement(x: Float, y: Float) =
        abs(x - startX) > movementThresholdPx || abs(y - startY) > movementThresholdPx

    private fun reset() {
        pointerId = -1
        tapEligible = false
    }

    private companion object {
        const val DEFAULT_TAP_TIME_MS = 250L
    }
}
