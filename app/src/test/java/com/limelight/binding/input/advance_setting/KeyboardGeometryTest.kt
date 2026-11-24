package com.limelight.binding.input.advance_setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {
    @Test
    fun pocketAt360x640UsesFourLargeRows() {
        val metrics = KeyboardGeometry.compute(360f, 640f, 1f)
        assertEquals(KeyboardTier.POCKET, metrics.tier)
        assertEquals(348, metrics.dockWidthPx)
        assertEquals(31.8f, metrics.keyWidthPx, 0.2f)
        assertEquals(46f, metrics.keyHeightPx, 0.01f)
        assertEquals(234, metrics.dockHeightPx)
    }

    @Test
    fun standardAt640x360FillsTheNumberRow() {
        val metrics = KeyboardGeometry.compute(640f, 360f, 1f)
        assertEquals(KeyboardTier.STANDARD, metrics.tier)
        assertEquals(608, metrics.dockWidthPx)
        assertEquals(35.5f, metrics.keyWidthPx, 0.1f)
        assertEquals(32f, metrics.keyHeightPx, 0.01f)
        assertEquals(230, metrics.dockHeightPx)
    }

    @Test
    fun studioAt900AddsThreeNavColumns() {
        val metrics = KeyboardGeometry.compute(900f, 600f, 1f)
        assertEquals(KeyboardTier.STUDIO, metrics.tier)
        assertEquals(868, metrics.dockWidthPx)
        assertEquals(42.8f, metrics.keyWidthPx, 0.1f)
        assertEquals(50f, metrics.keyHeightPx, 0.01f)
    }

    @Test
    fun ultraWideCapsKeyWidthAndBecomesGutter() {
        val metrics = KeyboardGeometry.compute(1280f, 800f, 1f)
        assertEquals(KeyboardTier.STUDIO, metrics.tier)
        assertEquals(1248, metrics.dockWidthPx)
        assertEquals(56f, metrics.keyWidthPx, 0.01f)
        val fieldWidth = 18 * metrics.keyWidthPx + 16 * metrics.gapPx + metrics.blockGapPx
        assertTrue((metrics.contentWidthPx - fieldWidth) / 2f > 60f)
        assertEquals(50f, metrics.keyHeightPx, 0.01f)
    }

    @Test
    fun shortViewportClampsKeyHeightToFloor() {
        val metrics = KeyboardGeometry.compute(600f, 300f, 1f)
        assertEquals(31f, metrics.keyHeightPx, 0.01f)
        assertEquals(225, metrics.dockHeightPx)
    }

    @Test
    fun keyHeightOverrideIsClampedAndChangesDockHeight() {
        val metrics = KeyboardGeometry.compute(900f, 600f, 1f, keyHeightOverrideDp = 44f)
        assertEquals(44f, metrics.keyHeightPx, 0.01f)
        assertEquals(296, metrics.dockHeightPx)
    }

    @Test
    fun miniForcesPocketWidthAndFourRows() {
        val metrics = KeyboardGeometry.compute(900f, 600f, 1f, mini = true)
        assertEquals(KeyboardTier.POCKET, metrics.tier)
        assertEquals(380, metrics.dockWidthPx)
        assertEquals(12, metrics.marginPx)
        assertEquals(35f, metrics.keyWidthPx, 0.2f)
        assertEquals(46f, metrics.keyHeightPx, 0.01f)
        assertEquals(234, metrics.dockHeightPx)
    }

    @Test
    fun densityBelowOneUsesRealDensity() {
        val metrics = KeyboardGeometry.compute(480f, 640f, 0.75f)
        assertEquals(KeyboardTier.STANDARD, metrics.tier)
        assertEquals(456, metrics.dockWidthPx)
    }

    @Test
    fun keyHeightOverrideIsCappedByViewport() {
        val metrics = KeyboardGeometry.compute(640f, 280f, 1f, keyHeightOverrideDp = 50f)
        assertEquals(38f, metrics.maxKeyHeightDp, 0.01f)
        assertTrue(metrics.dockHeightPx + metrics.marginPx <= 280)
    }

    @Test
    fun breakpointChangesAt600And900() {
        assertEquals(KeyboardTier.POCKET, KeyboardGeometry.compute(599f, 600f, 1f).tier)
        assertEquals(KeyboardTier.STANDARD, KeyboardGeometry.compute(600f, 600f, 1f).tier)
        assertEquals(KeyboardTier.STANDARD, KeyboardGeometry.compute(899f, 600f, 1f).tier)
        assertEquals(KeyboardTier.STUDIO, KeyboardGeometry.compute(900f, 600f, 1f).tier)
    }
}
