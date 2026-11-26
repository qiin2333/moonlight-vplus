package com.limelight

import android.app.Dialog
import android.view.KeyEvent
import com.limelight.binding.input.MenuAxisNavigationState

/**
 * Steering a two-row confirmation sheet with sticks and the D-pad. The sheet has no columns, so
 * left and up both step up, and right and down both step down. A source that is already deflected
 * when the sheet opens must rest once first: it usually still points the way the player picked the
 * item that opened it.
 */
internal class StartWheelConfirmSteering {
    private val axisStates = mutableMapOf<Int, MenuAxisNavigationState>()
    private val heldKeys = mutableMapOf<Int, Int>()
    private val awaitingNeutral = mutableSetOf<Int>()

    fun reset() {
        axisStates.clear()
        heldKeys.clear()
        awaitingNeutral.clear()
    }

    /** Feeds one motion event's axes for [sourceKey], stepping [sheet] when they cross a line. */
    fun steer(sourceKey: Int, axisPairs: List<Pair<Float, Float>>, sheet: Dialog) {
        val knownSource = axisStates.containsKey(sourceKey)
        val state = axisStates.getOrPut(sourceKey) { MenuAxisNavigationState() }
        if (sourceKey in awaitingNeutral) {
            state.reset()
            if (state.isNeutral(axisPairs)) awaitingNeutral.remove(sourceKey)
            return
        }
        if (!knownSource && !state.isNeutral(axisPairs)) {
            awaitingNeutral += sourceKey
            return
        }
        val transition = state.update(axisPairs)
        if (!transition.changed) return
        val targetKey = when (transition.pressedKeyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> null
        }
        val heldKey = heldKeys[sourceKey]
        if (heldKey != null && heldKey != targetKey) {
            sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, heldKey))
            heldKeys.remove(sourceKey)
        }
        if (targetKey != null && heldKey != targetKey) {
            sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, targetKey))
            heldKeys[sourceKey] = targetKey
        }
    }
}
