package com.limelight.binding.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneButtonPlacementTest {
    @Test
    fun edgePresetsStayInsideContainer() {
        val positions = listOf(
            MicrophoneButtonPlacement.POSITION_TOP_LEFT,
            MicrophoneButtonPlacement.POSITION_TOP_CENTER,
            MicrophoneButtonPlacement.POSITION_TOP_RIGHT,
            MicrophoneButtonPlacement.POSITION_CENTER_LEFT,
            MicrophoneButtonPlacement.POSITION_CENTER_RIGHT,
            MicrophoneButtonPlacement.POSITION_BOTTOM_LEFT,
            MicrophoneButtonPlacement.POSITION_BOTTOM_CENTER,
            MicrophoneButtonPlacement.POSITION_BOTTOM_RIGHT
        )

        positions.forEach { position ->
            val coordinates = MicrophoneButtonPlacement.resolve(
                position = position,
                normalizedX = 0.5f,
                normalizedY = 0.5f,
                containerWidth = 1000,
                containerHeight = 600,
                buttonWidth = 40,
                buttonHeight = 40,
                edgeInset = 10
            )
            assertTrue(coordinates.x in 10..950)
            assertTrue(coordinates.y in 10..550)
        }
    }

    @Test
    fun customPositionIsClampedAndNormalized() {
        val coordinates = MicrophoneButtonPlacement.clampCustom(
            x = 2000f,
            y = -100f,
            containerWidth = 1000,
            containerHeight = 600,
            buttonWidth = 40,
            buttonHeight = 40,
            edgeInset = 10
        )
        assertEquals(MicrophoneButtonCoordinates(950, 10), coordinates)

        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = coordinates,
            containerWidth = 1000,
            containerHeight = 600,
            buttonWidth = 40,
            buttonHeight = 40,
            edgeInset = 10
        )
        assertEquals(1f, normalized.x, 0.0001f)
        assertEquals(0f, normalized.y, 0.0001f)
    }

    @Test
    fun normalizedPositionSurvivesContainerResize() {
        val original = MicrophoneButtonCoordinates(715, 415)
        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = original,
            containerWidth = 1000,
            containerHeight = 600,
            buttonWidth = 40,
            buttonHeight = 40,
            edgeInset = 10
        )
        val resized = MicrophoneButtonPlacement.resolve(
            position = MicrophoneButtonPlacement.POSITION_CUSTOM,
            normalizedX = normalized.x,
            normalizedY = normalized.y,
            containerWidth = 500,
            containerHeight = 300,
            buttonWidth = 40,
            buttonHeight = 40,
            edgeInset = 10
        )

        assertEquals(MicrophoneButtonCoordinates(340, 190), resized)
    }

    @Test
    fun tinyContainerNeverProducesNegativeCoordinates() {
        val coordinates = MicrophoneButtonPlacement.resolve(
            position = MicrophoneButtonPlacement.POSITION_BOTTOM_RIGHT,
            normalizedX = 1f,
            normalizedY = 1f,
            containerWidth = 20,
            containerHeight = 20,
            buttonWidth = 40,
            buttonHeight = 40,
            edgeInset = 10
        )

        assertEquals(MicrophoneButtonCoordinates(0, 0), coordinates)
    }
}
