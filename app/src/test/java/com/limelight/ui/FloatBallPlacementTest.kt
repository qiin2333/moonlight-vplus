package com.limelight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatBallPlacementTest {
    @Test
    fun presetsResolveToExpectedEdges() {
        val expected = mapOf(
            FloatingButtonPlacement.POSITION_TOP_LEFT to
                (FloatingButtonCoordinates(0, 0) to FloatBallEdge.LEFT),
            FloatingButtonPlacement.POSITION_TOP_CENTER to
                (FloatingButtonCoordinates(475, 0) to FloatBallEdge.TOP),
            FloatingButtonPlacement.POSITION_TOP_RIGHT to
                (FloatingButtonCoordinates(950, 0) to FloatBallEdge.RIGHT),
            FloatingButtonPlacement.POSITION_CENTER_LEFT to
                (FloatingButtonCoordinates(0, 275) to FloatBallEdge.LEFT),
            FloatingButtonPlacement.POSITION_CENTER_RIGHT to
                (FloatingButtonCoordinates(950, 275) to FloatBallEdge.RIGHT),
            FloatingButtonPlacement.POSITION_BOTTOM_LEFT to
                (FloatingButtonCoordinates(0, 550) to FloatBallEdge.LEFT),
            FloatingButtonPlacement.POSITION_BOTTOM_CENTER to
                (FloatingButtonCoordinates(475, 550) to FloatBallEdge.BOTTOM),
            FloatingButtonPlacement.POSITION_BOTTOM_RIGHT to
                (FloatingButtonCoordinates(950, 550) to FloatBallEdge.RIGHT)
        )

        expected.forEach { (preset, expectation) ->
            val stored = FloatBallPlacement.presetPosition(preset)
            val resolved = FloatingButtonPlacement.resolve(
                FloatingButtonPlacement.POSITION_CUSTOM,
                stored.normalized,
                viewport
            )
            val anchored = FloatBallPlacement.applyEdge(resolved, stored.edge, viewport)
            assertEquals(expectation.first, anchored)
            assertEquals(expectation.second, stored.edge)
        }
    }

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

    @Test
    fun legacyPositionMigratesToNormalizedClampedPosition() {
        val migrated = FloatBallPlacement.migrateLegacy(
            FloatBallLegacyPosition(1200, 300),
            viewport,
            enableEdgeSnap = true
        )
        val coordinates = FloatingButtonPlacement.resolve(
            FloatingButtonPlacement.POSITION_CUSTOM,
            migrated.normalized,
            viewport
        )

        assertEquals(FloatBallEdge.RIGHT, migrated.edge)
        assertEquals(FloatingButtonCoordinates(950, 300), coordinates)
        assertTrue(migrated.normalized.x in 0f..1f)
        assertTrue(migrated.normalized.y in 0f..1f)
    }

    @Test
    fun automaticHalfShowOnlyCrossesSelectedEdge() {
        assertEquals(
            FloatingButtonCoordinates(975, 275),
            FloatBallPlacement.halfShownCoordinates(
                FloatingButtonCoordinates(950, 275),
                FloatBallEdge.RIGHT,
                ballSize = 50
            )
        )
    }

    @Test
    fun edgeSnapUsesPhysicalWindowEdgesDespiteSystemInsets() {
        val edgeViewport = floatBallViewport(
            screenWidth = 1000,
            screenHeight = 600,
            ballSize = 50,
            enableEdgeSnap = true,
            safeInsetLeft = 40,
            safeInsetTop = 20,
            safeInsetRight = 80,
            safeInsetBottom = 60
        )

        assertEquals(
            FloatingButtonCoordinates(0, 275),
            FloatBallPlacement.applyEdge(
                FloatingButtonCoordinates(400, 275),
                FloatBallEdge.LEFT,
                edgeViewport
            )
        )
        assertEquals(
            FloatingButtonCoordinates(950, 275),
            FloatBallPlacement.applyEdge(
                FloatingButtonCoordinates(400, 275),
                FloatBallEdge.RIGHT,
                edgeViewport
            )
        )
        assertEquals(
            FloatingButtonCoordinates(-25, 275),
            FloatBallPlacement.halfShownCoordinates(
                FloatingButtonCoordinates(0, 275),
                FloatBallEdge.LEFT,
                ballSize = 50
            )
        )
        assertEquals(
            FloatingButtonCoordinates(975, 275),
            FloatBallPlacement.halfShownCoordinates(
                FloatingButtonCoordinates(950, 275),
                FloatBallEdge.RIGHT,
                ballSize = 50
            )
        )
    }

    @Test
    fun freePlacementStillRespectsSystemInsets() {
        val freeViewport = floatBallViewport(
            screenWidth = 1000,
            screenHeight = 600,
            ballSize = 50,
            enableEdgeSnap = false,
            safeInsetLeft = 40,
            safeInsetTop = 20,
            safeInsetRight = 80,
            safeInsetBottom = 60
        )

        assertEquals(
            FloatingButtonCoordinates(40, 20),
            FloatingButtonPlacement.clampCustom(-100f, -100f, freeViewport)
        )
        assertEquals(
            FloatingButtonCoordinates(870, 490),
            FloatingButtonPlacement.clampCustom(1200f, 900f, freeViewport)
        )
    }

    private companion object {
        val viewport = FloatingButtonViewport(1000, 600, 50, 50, 0)
    }
}
