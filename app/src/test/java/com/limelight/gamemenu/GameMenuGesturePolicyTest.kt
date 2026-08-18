package com.limelight.gamemenu

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class GameMenuGesturePolicyTest {
    @Test
    fun movementBelowTouchSlopRemainsUndecided() {
        assertEquals(
            SliderGestureDirection.Undecided,
            classifySliderGesture(Offset(3f, 4f), touchSlop = 6f)
        )
    }

    @Test
    fun horizontalMovementLocksSliderGesture() {
        assertEquals(
            SliderGestureDirection.Horizontal,
            classifySliderGesture(Offset(8f, 4f), touchSlop = 5f)
        )
    }

    @Test
    fun verticalAndDiagonalMovementRemainAvailableToParentScroll() {
        assertEquals(
            SliderGestureDirection.Vertical,
            classifySliderGesture(Offset(4f, 8f), touchSlop = 5f)
        )
        assertEquals(
            SliderGestureDirection.Vertical,
            classifySliderGesture(Offset(8f, 8f), touchSlop = 5f)
        )
    }
}
