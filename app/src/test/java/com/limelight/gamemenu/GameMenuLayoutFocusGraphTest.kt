package com.limelight.gamemenu

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMenuLayoutFocusGraphTest {
    @Test
    fun touchModeSplitPanesKeepVerticalOrderAndMapAcrossColumns() {
        assertEquals(
            TouchModeFocusTargets(left = null, right = 8, up = 3, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 4)
        )
        assertEquals(
            TouchModeFocusTargets(left = 1, right = null, up = 5, down = 7),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 6)
        )
        assertEquals(
            TouchModeFocusTargets(left = 3, right = null, up = 7, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 8)
        )
    }

    @Test
    fun dialogShellUsesLargestHorizontalSafeInsetOnBothSides() {
        assertEquals(18.dp, symmetricHorizontalPadding(12.dp, 18.dp))
        assertEquals(18.dp, symmetricHorizontalPadding(18.dp, 12.dp))
    }

    @Test
    fun childDialogOptionsAlwaysKeepParentMenuOpen() {
        assertTrue(gameMenuChildDialogOption("Add key", Runnable {}).isKeepDialog)
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
