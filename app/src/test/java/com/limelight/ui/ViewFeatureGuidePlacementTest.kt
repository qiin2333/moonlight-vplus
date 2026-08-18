package com.limelight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewFeatureGuidePlacementTest {
    @Test
    fun wideLandscapePlacesCardBesideTargetWithoutOverlap() {
        val placement = calculateFeatureGuideCardPlacement(
            overlayWidth = 2_800f,
            overlayHeight = 1_272f,
            targetLeft = 240f,
            targetTop = 160f,
            targetRight = 950f,
            targetBottom = 570f,
            cardWidth = 1_110f,
            cardHeight = 790f,
            edge = 56f,
            gap = 224f
        )

        assertEquals(FeatureGuideCardSide.RIGHT, placement.side)
        assertTrue(placement.left >= 950f + 224f)
        assertTrue(placement.left + 1_110f <= 2_800f - 56f)
    }

    @Test
    fun portraitFallsBackBelowWhenHorizontalSpaceIsInsufficient() {
        val placement = calculateFeatureGuideCardPlacement(
            overlayWidth = 1_080f,
            overlayHeight = 2_400f,
            targetLeft = 190f,
            targetTop = 160f,
            targetRight = 890f,
            targetBottom = 520f,
            cardWidth = 830f,
            cardHeight = 700f,
            edge = 42f,
            gap = 120f
        )

        assertEquals(FeatureGuideCardSide.BELOW, placement.side)
        assertTrue(placement.top >= 520f + 120f)
    }

    @Test
    fun cardStaysBelowStatusBarInset() {
        val placement = calculateFeatureGuideCardPlacement(
            overlayWidth = 2_800f,
            overlayHeight = 1_272f,
            targetLeft = 240f,
            targetTop = 160f,
            targetRight = 950f,
            targetBottom = 570f,
            cardWidth = 1_110f,
            cardHeight = 790f,
            edge = 56f,
            gap = 224f,
            safeTop = 92f,
            safeRight = 130f
        )

        assertEquals(FeatureGuideCardSide.RIGHT, placement.side)
        assertTrue(placement.top >= 92f + 56f)
        assertTrue(placement.left + 1_110f <= 2_800f - 130f - 56f)
    }

    @Test
    fun undersizedCardClampFallsBackToCardMidpoint() {
        assertEquals(
            25f,
            clampInsideGuideCard(value = 80f, low = 40f, high = 10f),
            0.001f
        )
    }
}
