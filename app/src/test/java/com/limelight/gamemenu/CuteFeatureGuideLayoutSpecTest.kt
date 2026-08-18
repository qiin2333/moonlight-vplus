package com.limelight.gamemenu

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuteFeatureGuideLayoutSpecTest {
    @Test
    fun smallLandscapeUsesCompactScrollableLayout() {
        val spec = cuteFeatureGuideLayoutSpec(
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            safeHeightDp = 360f
        )

        assertTrue(spec.compact)
        assertTrue(spec.maximumHeightFraction < 0.8f)
        assertTrue(spec.topPaddingDp < 56)
        assertTrue(spec.bodyLineHeightSp < 25)
    }

    @Test
    fun shortPortraitUsesCompactLayout() {
        val spec = cuteFeatureGuideLayoutSpec(
            orientation = Configuration.ORIENTATION_PORTRAIT,
            safeHeightDp = 520f
        )

        assertTrue(spec.compact)
        assertTrue(spec.maximumHeightFraction < 0.7f)
        assertTrue(spec.topPaddingDp <= 40)
    }

    @Test
    fun regularPortraitUsesPortraitSpecificSpacing() {
        val spec = cuteFeatureGuideLayoutSpec(
            orientation = Configuration.ORIENTATION_PORTRAIT,
            safeHeightDp = 800f
        )

        assertFalse(spec.compact)
        assertTrue(spec.maximumHeightFraction < 0.8f)
        assertTrue(spec.topPaddingDp < 56)
        assertTrue(spec.bodyLineHeightSp < 25)
    }

    @Test
    fun largeLandscapeKeepsRegularLayout() {
        assertFalse(
            cuteFeatureGuideLayoutSpec(
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                safeHeightDp = 600f
            ).compact
        )
    }

    @Test
    fun maximumHeightNeverExceedsSafeWindow() {
        val maximumHeight = cuteFeatureGuideMaximumHeightDp(
            safeHeightDp = 120f,
            maximumHeightFraction = 0.72f
        )

        assertTrue(maximumHeight <= 112f)
        assertTrue(maximumHeight > 0f)
    }

    @Test
    fun targetSideSpaceCapsCardHeight() {
        val maximumHeight = cuteFeatureGuideMaximumHeightDp(
            safeHeightDp = 800f,
            maximumHeightFraction = 0.72f,
            targetSideAvailableDp = 260f
        )

        assertTrue(maximumHeight <= 260f)
        assertTrue(maximumHeight > 0f)
    }

    @Test
    fun largeWindowUsesReadingHeightCap() {
        val maximumHeight = cuteFeatureGuideMaximumHeightDp(
            safeHeightDp = 1200f,
            maximumHeightFraction = 0.88f,
            targetSideAvailableDp = 900f,
            preferredMaximumHeightDp = 260f
        )

        assertTrue(maximumHeight <= 260f)
    }
}
