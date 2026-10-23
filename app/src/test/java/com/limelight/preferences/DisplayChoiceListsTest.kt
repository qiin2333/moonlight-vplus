package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayChoiceListsTest {
    private val store = listOf("1280x720", "1920x1080", "2560x1440", "3840x2160")
    private val frameRates = listOf("30", "60", "90", "120", "144", "165")

    @Test
    fun fixedSizeAndFollowingTheDisplayRemainDistinctSelections() {
        val values = DisplayChoiceLists.resolutionValues(store, "1920x1080", false)
        assertEquals(store + "Native", values)
        assertTrue("1920x1080" in values)
        assertTrue("Native" in values)
    }

    @Test
    fun currentAndDraftCustomValuesStayReachableWithoutChangingStore() {
        val values = DisplayChoiceLists.resolutionValues(
            store,
            "2340x1080",
            false,
            listOf("3440x1440", "1920x1080")
        )
        assertEquals(store + listOf("2340x1080", "3440x1440", "Native"), values)
        assertEquals(listOf("1280x720", "1920x1080", "2560x1440", "3840x2160"), store)
    }

    @Test
    fun malformedValuesAreExcludedAndNativeAppearsOnlyOnceAtTheEnd() {
        val values = DisplayChoiceLists.resolutionValues(
            store + listOf("Native", "1920x1080", "wide", "0x1080"),
            "Native",
            false,
            listOf("-1x720")
        )
        assertEquals(store + "Native", values)
    }

    @Test
    fun lowPresetsAppearWhenToggledOn() {
        val values = DisplayChoiceLists.resolutionValues(store, "1920x1080", true)
        assertEquals(listOf("640x360", "854x480") + store + "Native", values)
    }

    @Test
    fun lowPresetsAppearWhenCurrentSelectionOrDraftUsesOne() {
        val expected = listOf("640x360", "854x480") + store + "Native"
        assertEquals(expected, DisplayChoiceLists.resolutionValues(store, "640x360", false))
        assertEquals(
            expected,
            DisplayChoiceLists.resolutionValues(store, "1920x1080", false, listOf("854x480"))
        )
    }

    @Test
    fun duplicateLowPresetInStoreIsNotAppendedTwice() {
        val values = DisplayChoiceLists.resolutionValues(
            listOf("640x360") + store,
            "1920x1080",
            false
        )
        assertEquals(listOf("640x360") + store + "Native", values)
    }

    @Test
    fun deviceRefreshRateAdds75Or240FpsAndKeepsOldAndDraftValues() {
        assertEquals(
            listOf("30", "60", "75", "90", "120", "144", "165", "240"),
            DisplayChoiceLists.frameRateValues(frameRates, 75f, listOf("240", "75"))
        )
        assertEquals(
            frameRates + "240",
            DisplayChoiceLists.frameRateValues(frameRates, 239.76f)
        )
    }

    @Test
    fun fractional60HzDoesNotCreateADuplicateAndInvalidFpsIsIgnored() {
        assertEquals(
            frameRates,
            DisplayChoiceLists.frameRateValues(frameRates, 59.94f, listOf("60", "0", "-1", "fast"))
        )
        assertEquals(frameRates, DisplayChoiceLists.frameRateValues(frameRates, Float.NaN))
    }

    @Test
    fun nativeRecommendationUsesTheActualDisplaySizeAndFinalFrameRate() {
        assertEquals(
            PreferenceConfiguration.getDefaultBitrate("3840x2160", "120"),
            DisplayChoiceLists.recommendedBitrate("Native", "120", 3840 to 2160)
        )
        assertEquals(
            PreferenceConfiguration.getDefaultBitrate("1920x1080", "60"),
            DisplayChoiceLists.recommendedBitrate("1920x1080", "60", 3840 to 2160)
        )
    }

    @Test
    fun presetCapabilityLimitDoesNotBlockValidCustomDimensions() {
        val limits = DisplayChoiceLimits(1920, 60f)
        assertFalse(limits.supportsResolution("3840x2160"))
        assertTrue(limits.supportsResolution("3440x1440"))
        assertTrue(limits.supportsResolution("1080x2340"))
        assertFalse(limits.supportsResolution("0x1080"))
        assertTrue(limits.supportsFrameRate("240", true))
        assertFalse(limits.supportsFrameRate("240", false))
    }
}
