package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayChoiceListsTest {
    private val store = listOf("1280x720", "1920x1080", "2560x1440", "3840x2160")

    @Test
    fun nativeReplacesSameSizeStoreRowSoFollowingThePanelStaysReachable() {
        val values = DisplayChoiceLists.resolutionValues(store, 1920 to 1080, "1920x1080", false)
        assertEquals(listOf("1280x720", "2560x1440", "3840x2160", "Native"), values)
        assertEquals(
            "Native",
            DisplayChoiceLists.normalizeSelection("1920x1080", 1920 to 1080)
        )
    }

    @Test
    fun normalizeSelectionLeavesOtherValuesUntouched() {
        assertEquals("2560x1440", DisplayChoiceLists.normalizeSelection("2560x1440", 1920 to 1080))
        assertEquals("1280x720", DisplayChoiceLists.normalizeSelection("1280x720", null))
        assertEquals("Native", DisplayChoiceLists.normalizeSelection("Native", 1920 to 1080))
    }

    @Test
    fun lowPresetsAppearWhenToggledOn() {
        val values = DisplayChoiceLists.resolutionValues(store, 1920 to 1080, "1920x1080", true)
        assertEquals(
            listOf("640x360", "854x480", "1280x720", "2560x1440", "3840x2160", "Native"),
            values
        )
    }

    @Test
    fun lowPresetsAppearWhenCurrentSelectionUsesOne() {
        val values = DisplayChoiceLists.resolutionValues(store, 2560 to 1440, "640x360", false)
        assertEquals(
            listOf("640x360", "854x480", "1280x720", "1920x1080", "3840x2160", "Native"),
            values
        )
    }

    @Test
    fun nativeStaysWhenNoEntryMatchesThePanelSize() {
        val values = DisplayChoiceLists.resolutionValues(store, 2340 to 1080, "1920x1080", false)
        assertEquals(
            listOf("1280x720", "1920x1080", "2560x1440", "3840x2160", "Native"),
            values
        )
    }

    @Test
    fun duplicateLowPresetInStoreIsNotAppendedTwice() {
        val values = DisplayChoiceLists.resolutionValues(
            listOf("640x360") + store,
            null,
            "1920x1080",
            false
        )
        assertEquals(
            listOf("640x360", "1280x720", "1920x1080", "2560x1440", "3840x2160", "Native"),
            values
        )
    }
}
