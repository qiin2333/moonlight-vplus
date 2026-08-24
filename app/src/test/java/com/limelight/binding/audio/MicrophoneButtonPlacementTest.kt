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
                customPosition = MicrophoneButtonNormalizedPosition(0.5f, 0.5f),
                viewport = standardViewport
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
            viewport = standardViewport
        )
        assertEquals(MicrophoneButtonCoordinates(950, 10), coordinates)

        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = coordinates,
            viewport = standardViewport
        )
        assertEquals(1f, normalized.x, 0.0001f)
        assertEquals(0f, normalized.y, 0.0001f)
    }

    @Test
    fun normalizedPositionSurvivesContainerResize() {
        val original = MicrophoneButtonCoordinates(715, 415)
        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = original,
            viewport = standardViewport
        )
        val resized = MicrophoneButtonPlacement.resolve(
            position = MicrophoneButtonPlacement.POSITION_CUSTOM,
            customPosition = normalized,
            viewport = MicrophoneButtonViewport(500, 300, 40, 40, 10)
        )

        assertEquals(MicrophoneButtonCoordinates(340, 190), resized)
    }

    @Test
    fun tinyContainerNeverProducesNegativeCoordinates() {
        val coordinates = MicrophoneButtonPlacement.resolve(
            position = MicrophoneButtonPlacement.POSITION_BOTTOM_RIGHT,
            customPosition = MicrophoneButtonNormalizedPosition(1f, 1f),
            viewport = MicrophoneButtonViewport(20, 20, 40, 40, 10)
        )

        assertEquals(MicrophoneButtonCoordinates(0, 0), coordinates)
    }

    @Test
    fun unknownPresetFallsBackToCenterRight() {
        assertEquals(
            MicrophoneButtonPlacement.DEFAULT_POSITION,
            MicrophoneButtonPlacement.normalizePreset("not-a-position")
        )
    }

    private companion object {
        val standardViewport = MicrophoneButtonViewport(1000, 600, 40, 40, 10)
    }
}
