package com.limelight.gamemenu

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuteFeatureGuideLayoutSpecTest {
    @Test
    fun layoutCompactsOnlyShortWindowsInEachOrientation() {
        for ((orientation, height, compact) in listOf(
            Triple(Configuration.ORIENTATION_LANDSCAPE, 360f, true),
            Triple(Configuration.ORIENTATION_PORTRAIT, 520f, true),
            Triple(Configuration.ORIENTATION_PORTRAIT, 800f, false),
            Triple(Configuration.ORIENTATION_LANDSCAPE, 600f, false)
        )) {
            val spec = cuteFeatureGuideLayoutSpec(orientation, height)
            assertEquals("orientation=$orientation height=$height", compact, spec.compact)
            if (compact) {
                val expectedFraction = if (orientation == Configuration.ORIENTATION_PORTRAIT) 0.62f else 0.72f
                assertEquals("orientation=$orientation height=$height", expectedFraction, spec.maximumHeightFraction, 0f)
            }
        }
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
        val spec = cuteFeatureGuideLayoutSpec(
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            safeHeightDp = 1200f
        )
        val maximumHeight = cuteFeatureGuideMaximumHeightDp(
            safeHeightDp = 1200f,
            maximumHeightFraction = spec.maximumHeightFraction,
            targetSideAvailableDp = 900f,
            preferredMaximumHeightDp = spec.maximumCardHeightDp.toFloat()
        )

        assertTrue(maximumHeight <= 320f)
        assertTrue(maximumHeight < 1200f * 0.5f)
    }

    @Test
    fun guideWaitsForMenuAndEveryShowcaseTarget() {
        assertFalse(
            shouldStartGameMenuGuide(
                guidePending = true,
                isSubmenu = false,
                menuContentLaidOut = true,
                quickActionTargetLaidOut = true,
                crownTargetLaidOut = false
            )
        )
        assertTrue(
            shouldStartGameMenuGuide(
                guidePending = true,
                isSubmenu = false,
                menuContentLaidOut = true,
                quickActionTargetLaidOut = true,
                crownTargetLaidOut = true
            )
        )
    }
}
