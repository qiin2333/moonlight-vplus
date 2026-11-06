package com.limelight.gamemenu

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.preferences.DisplayPreferenceValues
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Configuration construction loads the streaming JNI, so these checks run on Android. */
@RunWith(AndroidJUnit4::class)
class BitrateDisplayPersistenceTest {
    @Test
    fun failedPersistenceLeavesTheRunningConfigurationUntouched() {
        val current = currentConfiguration()
        val before = snapshot(current)
        var saves = 0

        val result = BitrateCardController.persistDisplayDraft(
            current,
            DisplaySettingsDraft(PreferenceConfiguration.RES_NATIVE, "120", "1"),
            3840 to 2160,
            autoAdjustBitrate = true
        ) { candidate ->
            saves++
            assertNotSame(current, candidate)
            assertEquals(3840, candidate.width)
            assertEquals(2160, candidate.height)
            assertEquals(120, candidate.fps)
            assertTrue(candidate.isNativeResolution)
            assertEquals(before, snapshot(current))
            false
        }

        assertEquals(1, saves)
        assertNull(result)
        assertEquals(before, snapshot(current))
    }

    @Test
    fun successfulNativeDraftSavesAnIndependentFinalCombinationWithRecommendedBitrate() {
        val current = currentConfiguration()
        val before = snapshot(current)
        var saved: PreferenceConfiguration? = null

        val result = BitrateCardController.persistDisplayDraft(
            current,
            DisplaySettingsDraft(PreferenceConfiguration.RES_NATIVE, "120", "1"),
            3840 to 2160,
            autoAdjustBitrate = true
        ) { candidate ->
            saved = candidate
            true
        }!!

        assertSame(saved, result)
        assertNotSame(current, result)
        assertTrue(result.isNativeResolution)
        assertFalse(result.isCustomResolution)
        assertEquals(3840, result.width)
        assertEquals(2160, result.height)
        assertEquals(120, result.fps)
        assertEquals(1, result.screenCombinationMode)
        assertEquals(PreferenceConfiguration.getDefaultBitrate("3840x2160", "120"), result.bitrate)
        assertTrue(result.enableAdaptiveBitrate)
        assertEquals("lowLatency", result.abrMode)
        assertEquals(before, snapshot(current))
    }

    @Test
    fun manualBitrateAndAbrPolicySurviveResolutionAndFpsChanges() {
        val current = currentConfiguration().apply { bitrate = 37_000 }
        val result = BitrateCardController.persistDisplayDraft(
            current,
            DisplaySettingsDraft("3440x1440", "75", "4"),
            1920 to 1080,
            autoAdjustBitrate = false,
            persist = { true }
        )!!

        assertEquals(3440, result.width)
        assertEquals(1440, result.height)
        assertEquals(75, result.fps)
        assertEquals(37_000, result.bitrate)
        assertFalse(result.isNativeResolution)
        assertTrue(result.isCustomResolution)
        assertTrue(result.enableAdaptiveBitrate)
        assertEquals("lowLatency", result.abrMode)
        assertEquals(37_000, current.bitrate)
        assertEquals(1920, current.width)
        assertEquals(60, current.fps)
    }

    @Test
    fun reversedRuntimeDimensionsStillPersistTheOriginalSelectionDirection() {
        val current = currentConfiguration().apply {
            width = 1080
            height = 1920
            reverseResolution = true
        }
        var persisted: Map<String, Any>? = null
        val result = BitrateCardController.persistDisplayDraft(
            current,
            DisplaySettingsDraft("2560x1440", "90", "2"),
            1920 to 1080,
            autoAdjustBitrate = false
        ) { candidate ->
            persisted = DisplayPreferenceValues.of(
                nativeResolution = candidate.isNativeResolution,
                width = candidate.width,
                height = candidate.height,
                reverseResolution = candidate.reverseResolution,
                fps = candidate.fps,
                bitrate = candidate.bitrate,
                adaptiveBitrate = candidate.enableAdaptiveBitrate,
                abrMode = candidate.abrMode,
                screenCombinationMode = candidate.screenCombinationMode
            )
            true
        }!!

        assertTrue(result.reverseResolution)
        assertEquals(1440, result.width)
        assertEquals(2560, result.height)
        assertEquals("2560x1440", persisted!![PreferenceConfiguration.RESOLUTION_PREF_STRING])
        assertEquals("90", persisted!![PreferenceConfiguration.FPS_PREF_STRING])
        assertEquals(1080, current.width)
        assertEquals(1920, current.height)
    }

    private fun currentConfiguration() = PreferenceConfiguration().apply {
        width = 1920
        height = 1080
        fps = 60
        bitrate = 13_000
        isNativeResolution = false
        isCustomResolution = false
        enableAdaptiveBitrate = true
        abrMode = "lowLatency"
        screenCombinationMode = 2
    }

    private fun snapshot(config: PreferenceConfiguration) = listOf(
        config.width,
        config.height,
        config.fps,
        config.bitrate,
        config.isNativeResolution,
        config.isCustomResolution,
        config.enableAdaptiveBitrate,
        config.abrMode,
        config.screenCombinationMode,
        config.reverseResolution
    )
}
