package com.limelight.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomResolutionDialogLayoutTest {
    @Test
    fun compactLandscapeUsesTwoPaneLayout() {
        assertTrue(customResolutionDialogLayoutSpec(isLandscape = true, screenHeightDp = 480).useTwoPane)
    }

    @Test
    fun tallLandscapeKeepsRegularLayout() {
        assertFalse(customResolutionDialogLayoutSpec(isLandscape = true, screenHeightDp = 600).useTwoPane)
    }

    @Test
    fun portraitNeverUsesTwoPaneLayout() {
        assertFalse(customResolutionDialogLayoutSpec(isLandscape = false, screenHeightDp = 400).useTwoPane)
    }
}
