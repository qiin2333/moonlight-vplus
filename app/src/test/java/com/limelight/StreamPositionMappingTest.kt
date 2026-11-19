package com.limelight

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StreamPositionMappingTest {
    @Test fun mapsTranslatedAndScaledScreenCoordinates() {
        assertArrayEquals(floatArrayOf(0.5f, 0.5f),
            normalizeStreamPosition(300f, 100f, -100f, -100f, 2f, 2f, 400, 200), 0.00001f)
    }

    @Test fun mapsCoordinatesAlreadyRelativeToTheStream() {
        assertArrayEquals(floatArrayOf(0.25f, 0.75f),
            normalizeStreamPosition(100f, 150f, 0f, 0f, 1f, 1f, 400, 200), 0.00001f)
    }

    @Test fun clampsOutsideBoundsAndHandlesInvalidDimensions() {
        assertArrayEquals(floatArrayOf(0f, 1f),
            normalizeStreamPosition(-10f, 300f, 0f, 0f, 1f, 1f, 400, 200), 0.00001f)
        assertArrayEquals(floatArrayOf(0f, 0f),
            normalizeStreamPosition(10f, 20f, 0f, 0f, 0f, 1f, 400, 200), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f),
            normalizeStreamPosition(10f, 20f, 0f, 0f, 1f, 1f, 0, 200), 0f)
    }
}
