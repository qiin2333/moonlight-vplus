package com.limelight.binding.input

import android.view.KeyEvent
import kotlin.math.abs

internal data class MenuNavigationAxisMapping(
    val hatAxes: Pair<Int, Int>? = null,
    val leftStickAxes: Pair<Int, Int>? = null,
    val rightStickAxes: Pair<Int, Int>? = null
)

internal fun readMenuNavigationAxisPairs(
    mapping: MenuNavigationAxisMapping,
    axisValue: (Int) -> Float
): List<Pair<Float, Float>> = buildList {
    mapping.hatAxes?.let { (xAxis, yAxis) -> add(axisValue(xAxis) to axisValue(yAxis)) }
    mapping.leftStickAxes?.let { (xAxis, yAxis) -> add(axisValue(xAxis) to axisValue(yAxis)) }
    mapping.rightStickAxes?.let { (xAxis, yAxis) -> add(axisValue(xAxis) to axisValue(yAxis)) }
}

internal class MenuAxisNavigationState(
    private val activationThreshold: Float = 0.65f,
    private val releaseThreshold: Float = 0.35f
) {
    data class Transition(val pressedKeyCode: Int?, val changed: Boolean)

    private data class DirectionCandidate(
        val pairIndex: Int,
        val keyCode: Int
    )

    private var activePairIndex: Int? = null

    var activeKeyCode: Int? = null
        private set

    fun update(axisPairs: List<Pair<Float, Float>>): Transition {
        val oldKeyCode = activeKeyCode
        val oldPairIndex = activePairIndex
        val activationCandidate = firstDirectionCandidate(axisPairs, activationThreshold)
        val activePairActivationCandidate = oldPairIndex
            ?.takeIf { it in axisPairs.indices }
            ?.let { pairIndex ->
                directionKeyCode(
                    axisPairs[pairIndex].first,
                    axisPairs[pairIndex].second,
                    activationThreshold
                )?.let { keyCode -> DirectionCandidate(pairIndex, keyCode) }
            }
        val activeDirectionHeld = oldKeyCode != null &&
            oldPairIndex != null &&
            oldPairIndex in axisPairs.indices &&
            isDirectionHeld(axisPairs[oldPairIndex], oldKeyCode, releaseThreshold)

        val next = when {
            oldKeyCode == null -> activationCandidate
            activePairActivationCandidate != null &&
                activePairActivationCandidate.keyCode != oldKeyCode -> activePairActivationCandidate
            activeDirectionHeld -> DirectionCandidate(oldPairIndex!!, oldKeyCode)
            else -> activationCandidate
        }

        activePairIndex = next?.pairIndex
        activeKeyCode = next?.keyCode
        return Transition(activeKeyCode, changed = oldKeyCode != activeKeyCode)
    }

    fun isNeutral(axisPairs: List<Pair<Float, Float>>): Boolean {
        return axisPairs.all { (x, y) ->
            abs(x) < releaseThreshold && abs(y) < releaseThreshold
        }
    }

    fun reset() {
        activePairIndex = null
        activeKeyCode = null
    }

    private fun firstDirectionCandidate(
        axisPairs: List<Pair<Float, Float>>,
        threshold: Float
    ): DirectionCandidate? {
        axisPairs.forEachIndexed { index, (x, y) ->
            directionKeyCode(x, y, threshold)?.let { keyCode ->
                return DirectionCandidate(index, keyCode)
            }
        }
        return null
    }

    private fun isDirectionHeld(
        axisPair: Pair<Float, Float>,
        keyCode: Int,
        threshold: Float
    ): Boolean {
        val (x, y) = axisPair
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x <= -threshold
            KeyEvent.KEYCODE_DPAD_RIGHT -> x >= threshold
            KeyEvent.KEYCODE_DPAD_UP -> y <= -threshold
            KeyEvent.KEYCODE_DPAD_DOWN -> y >= threshold
            else -> false
        }
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
