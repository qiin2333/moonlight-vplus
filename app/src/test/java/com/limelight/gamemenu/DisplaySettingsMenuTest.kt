package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsMenuTest {
    @Test
    fun adaptiveBitrateKeepsManualPreviewAndApply() {
        assertTrue(manualBitrateChangeAllowed(true))
        assertTrue(manualBitrateChangeAllowed(false))
    }

    @Test
    fun sameDisplaySelectionDoesNotRebuild() {
        assertFalse(shouldApplyDisplaySelection("1920x1080", "1920x1080"))
        assertFalse(shouldApplyDisplaySelection("60", "60"))
        assertFalse(shouldApplyDisplaySelection("1", "1"))
    }

    @Test
    fun changedDisplaySelectionRebuilds() {
        assertTrue(shouldApplyDisplaySelection("1280x720", "1920x1080"))
        assertTrue(shouldApplyDisplaySelection("120", "60"))
        assertTrue(shouldApplyDisplaySelection("2", "1"))
    }

    @Test
    fun rootPageExposesResolutionFrameRateScreenAndCustomEditor() {
        val entries = DisplaySettingsMenu.root(
            resolutionTitle = "分辨率",
            resolutionValue = "1920x1080",
            frameRateTitle = "帧率",
            frameRateValue = "60 FPS",
            screenTitle = "屏幕组合",
            screenValue = "单屏",
            customTitle = "自定义分辨率"
        )

        assertEquals(
            listOf(
                DisplaySettingsPage.RESOLUTION,
                DisplaySettingsPage.FRAME_RATE,
                DisplaySettingsPage.SCREEN,
                null
            ),
            entries.map { it.page }
        )
        assertEquals(listOf("1920x1080", "60 FPS", "单屏", null), entries.map { it.value })
        assertTrue(entries.last().opensCustomResolutions)
        assertFalse(entries.dropLast(1).any { it.opensCustomResolutions })
    }

    @Test
    fun choicePageKeepsTheSelectedItemAndItsSourcePage() {
        val entries = DisplaySettingsMenu.choices(
            DisplaySettingsPage.SCREEN,
            listOf(
                DisplayChoice("0", "单屏", false, "只使用一块屏幕"),
                DisplayChoice("1", "双屏", true, "合并两块屏幕")
            )
        )

        assertEquals(listOf("0", "1"), entries.map { it.choiceValue })
        assertEquals(listOf(false, true), entries.map { it.selected })
        assertEquals(listOf("只使用一块屏幕", "合并两块屏幕"), entries.map { it.description })
        assertTrue(entries.all { it.page == DisplaySettingsPage.SCREEN })
    }
}
