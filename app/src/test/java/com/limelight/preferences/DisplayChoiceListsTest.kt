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
        val values = DisplayChoiceLists.resolutionValues(store, "1920x1080")
        assertEquals(store + "Native", values)
        assertTrue("1920x1080" in values)
        assertTrue("Native" in values)
    }

    @Test
    fun currentAndDraftCustomValuesStayReachableWithoutChangingStore() {
        val values = DisplayChoiceLists.resolutionValues(
            store,
            "2340x1080",
            listOf("3440x1440", "1920x1080")
        )
        assertEquals(
            listOf("1280x720", "1920x1080", "2340x1080", "2560x1440", "3440x1440", "3840x2160", "Native"),
            values
        )
        assertEquals(listOf("1280x720", "1920x1080", "2560x1440", "3840x2160"), store)
    }

    @Test
    fun restoredResolutionIsSortedBetweenSurroundingPresets() {
        assertEquals(
            listOf("1280x720", "1600x900", "1920x1080", "2560x1440", "3840x2160", "Native"),
            DisplayChoiceLists.resolutionValues(store, "1600x900")
        )
    }

    @Test
    fun malformedValuesAreExcludedAndNativeAppearsOnlyOnceAtTheEnd() {
        val values = DisplayChoiceLists.resolutionValues(
            store + listOf("Native", "1920x1080", "wide", "0x1080"),
            "Native",
            listOf("-1x720")
        )
        assertEquals(store + "Native", values)
    }

    @Test
    fun currentAndDraftLowResolutionValuesDoNotInjectOtherPresets() {
        assertEquals(listOf("640x360") + store + "Native",
            DisplayChoiceLists.resolutionValues(store, "640x360"))
        assertEquals(
            listOf("854x480") + store + "Native",
            DisplayChoiceLists.resolutionValues(store, "1920x1080", listOf("854x480"))
        )
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
    }

    @Test
    fun highAndRestoredFrameRatesStayInTheListWhileInvalidBaseValuesAreExcluded() {
        assertEquals(
            listOf("30", "60", "75", "90", "120", "144", "165", "240"),
            DisplayChoiceLists.frameRateValues(
                frameRates + listOf("0", "-30", "fast", "75.5", "60"),
                60f,
                listOf("75", "240", "")
            )
        )
    }

    @Test
    fun refreshRateWarningUsesTheDisplayRateRatherThanAFixed60FpsCutoff() {
        val sixtyHz = DisplayChoiceLimits(1920, 60f)
        assertFalse(sixtyHz.exceedsRefreshRate("30"))
        assertFalse(sixtyHz.exceedsRefreshRate("60"))
        assertTrue(sixtyHz.exceedsRefreshRate("90"))
        assertTrue(sixtyHz.exceedsRefreshRate("240"))

        val ninetyHz = DisplayChoiceLimits(1920, 90f)
        assertFalse(ninetyHz.exceedsRefreshRate("60"))
        assertFalse(ninetyHz.exceedsRefreshRate("90"))
        assertTrue(ninetyHz.exceedsRefreshRate("120"))

        val thirtyHz = DisplayChoiceLimits(1920, 30f)
        assertFalse(thirtyHz.exceedsRefreshRate("30"))
        assertTrue(thirtyHz.exceedsRefreshRate("60"))
    }

    @Test
    fun refreshRateWarningAllowsExactlyThreeHzOfTolerance() {
        val sixtyHz = DisplayChoiceLimits(1920, 60f)
        assertFalse(sixtyHz.exceedsRefreshRate("63"))
        assertTrue(sixtyHz.exceedsRefreshRate("64"))

        val ninetyHz = DisplayChoiceLimits(1920, 90f)
        assertFalse(ninetyHz.exceedsRefreshRate("93"))
        assertTrue(ninetyHz.exceedsRefreshRate("94"))
    }

    @Test
    fun fractionalRefreshRateKeepsItsToleranceWithoutWarningFor60Fps() {
        val limits = DisplayChoiceLimits(1920, 59.94f)
        assertFalse(limits.exceedsRefreshRate("60"))
        assertFalse(limits.exceedsRefreshRate("62"))
        assertTrue(limits.exceedsRefreshRate("63"))
    }

    @Test
    fun unknownOrInvalidRefreshRateDoesNotProduceAWarning() {
        listOf(0f, -60f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { refreshRate ->
            assertFalse(DisplayChoiceLimits(1920, refreshRate).exceedsRefreshRate("240"))
        }
    }

    @Test
    fun invalidFrameRateDoesNotProduceAWarning() {
        val limits = DisplayChoiceLimits(1920, 60f)
        listOf("", "fast", "59.94", "0", "-90", "2147483648").forEach { value ->
            assertFalse(limits.exceedsRefreshRate(value))
        }
    }
}
