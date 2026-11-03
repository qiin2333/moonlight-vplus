package com.limelight.binding.input.touchpad

import kotlin.math.abs

/** Combines explicit double-tap holds and pressure into one clickpad button state. */
internal class ScreenDs5ClickGestureDetector(
    private val movementThresholdPx: Float,
    private val doubleTapTimeoutMs: Long = 300L,
) {
    enum class Action { NONE, PRESS, RELEASE, TAP }

    private data class Tap(val x: Float, val y: Float, val releasedAt: Long)

    private val tap = ScreenDs5TapClickDetector(movementThresholdPx)
    private var pendingTap: Tap? = null
    private var pointerId = -1
    private var singleTapToClick = false
    private var doubleTapHeld = false
    private var pressurePressed = false
    private var pressureOccurred = false
    private val pressed get() = doubleTapHeld || pressurePressed

    fun onDown(id: Int, x: Float, y: Float, time: Long, singleTapToClick: Boolean): Action {
        val wasPressed = pressed
        val first = pendingTap.takeIf { pointerId == -1 && !singleTapToClick }
        resetCurrent()
        pendingTap = null
        pointerId = id
        this.singleTapToClick = singleTapToClick
        doubleTapHeld = first != null && time - first.releasedAt in 0..doubleTapTimeoutMs &&
            abs(x - first.x) <= movementThresholdPx && abs(y - first.y) <= movementThresholdPx
        tap.onDown(id, x, y, time)
        return transition(wasPressed)
    }

    fun onMove(id: Int, x: Float, y: Float) {
        // Once explicitly pressed, moving the finger must not release the button.
        tap.onMove(id, x, y)
    }

    fun onPointerDown() {
        tap.cancel()
        pendingTap = null
    }

    fun onPressureChanged(down: Boolean): Action {
        if (pointerId == -1) return Action.NONE
        val wasPressed = pressed
        pressurePressed = down
        if (down) {
            pressureOccurred = true
            tap.cancel()
            pendingTap = null
        }
        return transition(wasPressed)
    }

    fun onUp(id: Int, x: Float, y: Float, time: Long): Action {
        if (id != pointerId) return Action.NONE
        val wasPressed = pressed
        val isTap = !doubleTapHeld && !pressureOccurred && tap.onUp(id, x, y, time)
        val click = singleTapToClick && isTap
        pendingTap = if (!singleTapToClick && isTap) Tap(x, y, time) else null
        resetCurrent()
        return if (wasPressed) Action.RELEASE else if (click) Action.TAP else Action.NONE
    }

    /** The tracked finger lifted while other contacts remain: never transfer the click. */
    fun onPointerUp(id: Int): Action = if (id == pointerId) cancel() else Action.NONE

    fun cancel(): Action {
        val wasPressed = pressed
        pendingTap = null
        resetCurrent()
        return if (wasPressed) Action.RELEASE else Action.NONE
    }

    private fun transition(wasPressed: Boolean): Action = when {
        pressed == wasPressed -> Action.NONE
        pressed -> Action.PRESS
        else -> Action.RELEASE
    }

    private fun resetCurrent() {
        pointerId = -1
        doubleTapHeld = false
        pressurePressed = false
        pressureOccurred = false
        tap.cancel()
    }
}
