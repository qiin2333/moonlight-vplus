package com.limelight.binding.input.touch

import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancedTouchPointerStateTest {
    @Test
    fun enhancedZoneScalesIncrementalMovement() {
        val pointer = pointer(initialX = 800f, velocityFactor = 2f)

        pointer.update(810f, 490f)
        pointer.update(815f, 500f)

        assertCoordinates(pointer, 830f, 500f)
    }

    @Test
    fun enhancedZoneSupportsMinimumAndMaximumSensitivity() {
        val slowPointer = pointer(initialX = 800f, velocityFactor = 0.01f)
        val fastPointer = pointer(initialX = 800f, velocityFactor = 5f)

        slowPointer.update(900f, 600f)
        fastPointer.update(900f, 600f)

        assertCoordinates(slowPointer, 801f, 501f)
        assertCoordinates(fastPointer, 1300f, 1000f)
    }

    @Test
    fun normalZoneKeepsRawCoordinates() {
        val pointer = pointer(initialX = 200f, velocityFactor = 5f)

        pointer.update(250f, 550f)

        assertCoordinates(pointer, 250f, 550f)
    }

    @Test
    fun negativeDirectionPreservesLeftSideSelection() {
        val pointer = pointer(
            initialX = 200f,
            velocityFactor = 2f,
            enhancedTouchDirection = -1
        )

        pointer.update(250f, 550f)

        assertCoordinates(pointer, 300f, 600f)
    }

    @Test
    fun unitSensitivityMatchesRawCoordinates() {
        val pointer = pointer(initialX = 800f, velocityFactor = 1f)

        pointer.update(825f, 475f)

        assertCoordinates(pointer, 825f, 475f)
    }

    @Test
    fun finalUpdateIncludesMovementAfterPreviousSample() {
        val pointer = pointer(initialX = 800f, velocityFactor = 2f)
        pointer.update(810f, 500f)

        pointer.update(815f, 505f)

        assertCoordinates(pointer, 830f, 510f)
    }

    @Test
    fun initialJitterIsFlatUntilPointerLeavesZone() {
        val pointer = pointer(
            initialX = 800f,
            velocityFactor = 2f,
            initialZonePixels = 10f
        )

        pointer.update(806f, 507f)
        assertCoordinates(pointer, 800f, 500f)

        pointer.update(812f, 500f)
        assertCoordinates(pointer, 824f, 500f)

        pointer.update(808f, 500f)
        assertCoordinates(pointer, 816f, 500f)
    }

    private fun pointer(
        initialX: Float,
        velocityFactor: Float,
        enhancedTouchDirection: Int = 1,
        initialZonePixels: Float = 0f
    ) = EnhancedTouchPointerState(
        initialX = initialX,
        initialY = 500f,
        config = EnhancedTouchPointerConfig(
            screenWidth = 1_000f,
            initialZonePixels = initialZonePixels,
            enhancedTouchDirection = enhancedTouchDirection,
            enhancedTouchZoneDivider = 0.5f,
            pointerVelocityFactor = velocityFactor
        )
    )

    private fun assertCoordinates(
        pointer: EnhancedTouchPointerState,
        expectedX: Float,
        expectedY: Float
    ) {
        assertEquals(expectedX, pointer.selectedX(), 0.0001f)
        assertEquals(expectedY, pointer.selectedY(), 0.0001f)
    }
}
