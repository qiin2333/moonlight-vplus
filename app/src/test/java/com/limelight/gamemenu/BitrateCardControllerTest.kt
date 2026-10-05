package com.limelight.gamemenu

import com.limelight.preferences.DisplayPreferenceValues
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateCardControllerTest {
    @Test
    fun segmentedProgressMapsAcrossEveryBoundary() {
        val expected = mapOf(
            0 to 500,
            9 to 5_000,
            10 to 6_000,
            24 to 20_000,
            25 to 22_000,
            39 to 50_000,
            40 to 55_000,
            49 to 100_000,
            50 to 110_000,
            59 to 200_000
        )

        expected.forEach { (progress, bitrate) ->
            assertEquals(bitrate, BitrateCardController.progressToBitrateKbps(progress))
            assertEquals(progress, BitrateCardController.bitrateToProgress(bitrate))
        }
    }

    @Test
    fun bitrateConversionClampsToSupportedRange() {
        assertEquals(0, BitrateCardController.bitrateToProgress(100))
        assertEquals(59, BitrateCardController.bitrateToProgress(500_000))
    }

    @Test
    fun nativeResolutionKeepsCurrentSize() {
        val selection = BitrateCardController.parseResolutionSelection(
            PreferenceConfiguration.RES_NATIVE,
            1280,
            720
        )

        assertEquals(true, selection?.native)
        assertEquals(false, selection?.custom)
        assertEquals(1280, selection?.width)
        assertEquals(720, selection?.height)
    }

    @Test
    fun presetResolutionIsNotMarkedCustom() {
        val preset = PreferenceConfiguration.RESOLUTIONS.first()
        val selection = BitrateCardController.parseResolutionSelection(preset, 1, 1)

        assertEquals(false, selection?.native)
        assertEquals(false, selection?.custom)
        assertEquals(preset, "${selection?.width}x${selection?.height}")
    }

    @Test
    fun unknownPositiveResolutionIsCustom() {
        val selection = BitrateCardController.parseResolutionSelection("1919x1079", 1, 1)

        assertEquals(false, selection?.native)
        assertEquals(true, selection?.custom)
        assertEquals(1919, selection?.width)
        assertEquals(1079, selection?.height)
    }

    @Test
    fun displayWriteValuesStayInsideTheSharedSettingsKeys() {
        val values = DisplayPreferenceValues.of(
            nativeResolution = false,
            width = 1920,
            height = 1080,
            fps = 60,
            bitrate = 20_000,
            adaptiveBitrate = true,
            screenCombinationMode = 2
        )

        assertEquals(PreferenceConfiguration.DISPLAY_PREFERENCE_KEYS, values.keys)
        assertEquals("1920x1080", values["list_resolution"])
        val reversed = DisplayPreferenceValues.of(
            nativeResolution = false,
            width = 1080,
            height = 1920,
            fps = 60,
            bitrate = 20_000,
            adaptiveBitrate = true,
            screenCombinationMode = 2,
            reverseResolution = true
        )
        assertEquals("1920x1080", reversed["list_resolution"])
        assertEquals("60", values["list_fps"])
        assertEquals(20_000, values["seekbar_bitrate_kbps"])
        assertEquals(true, values["checkbox_adaptive_bitrate"])
        assertEquals("2", values["list_screen_combination_mode"])
    }

    @Test
    fun lowResolutionPresetsStayHiddenUntilRequestedOrSelected() {
        assertFalse(
            PreferenceConfiguration.includeLowResolutionPreset("640x360", "1920x1080", false)
        )
        assertTrue(
            PreferenceConfiguration.includeLowResolutionPreset("1920x1080", "1920x1080", false)
        )
        assertTrue(
            PreferenceConfiguration.includeLowResolutionPreset("640x360", "1920x1080", true)
        )
        assertTrue(
            PreferenceConfiguration.includeLowResolutionPreset("854x480", "640x360", false)
        )
    }

    @Test
    fun unchangedDisplayDraftDoesNotRequestApply() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")

        assertFalse(current.changedFrom(current))
    }

    @Test
    fun invalidCustomFrameRateDoesNotEnterThePendingDraft() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")
        val typed = current.copy(customFrameRate = "0")

        assertFalse(typed.changedFrom(current))
    }

    @Test
    fun validCustomFrameRateBecomesPendingWithoutChangingOtherSettings() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")
        val typed = current.copy(frameRate = "90", customFrameRate = "90")

        assertTrue(typed.changedFrom(current))
    }

    @Test
    fun illegalResolutionValuesAreRejected() {
        listOf("1920", "1920x", "x1080", "0x1080", "-1x720", "wide").forEach { value ->
            assertNull(value, BitrateCardController.parseResolutionSelection(value, 1, 1))
        }
    }
}
