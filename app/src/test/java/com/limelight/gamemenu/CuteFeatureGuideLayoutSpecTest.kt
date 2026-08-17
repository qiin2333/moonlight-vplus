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
    fun portraitAndLargeLandscapeKeepRegularLayout() {
        assertFalse(
            cuteFeatureGuideLayoutSpec(
                orientation = Configuration.ORIENTATION_PORTRAIT,
                safeHeightDp = 360f
            ).compact
        )
        assertFalse(
            cuteFeatureGuideLayoutSpec(
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                safeHeightDp = 600f
            ).compact
        )
    }
}
