package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameMenuLayoutFocusGraphTest {
    @Test
    fun touchModeGridHandlesOddPrimaryTailAndCrossSectionMovement() {
        assertEquals(
            TouchModeFocusTargets(left = null, right = null, up = 2, down = 5),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 4)
        )
        assertEquals(
            TouchModeFocusTargets(left = 5, right = null, up = 4, down = 8),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 6)
        )
        assertEquals(
            TouchModeFocusTargets(left = 7, right = null, up = 6, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 8)
        )
    }

    @Test
    fun segmentedGridUsesThreeColumnsAndFallsBackToLastTailItem() {
        val topRight = segmentedFocusTargets(itemCount = 5, index = 2, columnCount = 3)
        assertEquals(1, topRight.left)
        assertNull(topRight.right)
        assertNull(topRight.up)
        assertEquals(4, topRight.down)

        assertEquals(
            SegmentedFocusTargets(left = 3, right = null, up = 1, down = null),
            segmentedFocusTargets(itemCount = 5, index = 4, columnCount = 3)
        )
    }
}
