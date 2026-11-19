package com.limelight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared floating-button placement (mic button, OSC settings button). */
class FloatingButtonPlacementTest {
    @Test
    fun draggingIsClampedInsideScreen() {
        assertEquals(
            FloatingButtonCoordinates(950, 0),
            FloatingButtonPlacement.clampCustom(1200f, -200f, viewport)
        )
    }

    @Test
    fun asymmetricSystemInsetsLimitTheUsableArea() {
        val insetViewport = FloatingButtonViewport(
            containerWidth = 1000,
            containerHeight = 600,
            buttonWidth = 50,
            buttonHeight = 50,
            edgeInset = 0,
            leftInset = 40,
            topInset = 20,
            rightInset = 80,
            bottomInset = 60
        )

        assertEquals(
            FloatingButtonCoordinates(40, 20),
            FloatingButtonPlacement.clampCustom(-100f, -100f, insetViewport)
        )
        assertEquals(
            FloatingButtonCoordinates(870, 490),
            FloatingButtonPlacement.clampCustom(1200f, 900f, insetViewport)
        )
    }

    @Test
    fun normalizedPositionSurvivesOrientationChange() {
        val original = FloatingButtonCoordinates(713, 413)
        val normalized = FloatingButtonPlacement.normalize(original, viewport)
        val portrait = FloatingButtonPlacement.resolve(
            FloatingButtonPlacement.POSITION_CUSTOM,
            normalized,
            FloatingButtonViewport(600, 1000, 50, 50, 0)
        )

        assertEquals(FloatingButtonCoordinates(413, 713), portrait)
    }


    private companion object {
        val viewport = FloatingButtonViewport(1000, 600, 50, 50, 0)
    }
}
