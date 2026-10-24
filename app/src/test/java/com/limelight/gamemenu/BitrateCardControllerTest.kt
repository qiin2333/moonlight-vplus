package com.limelight.gamemenu

import com.limelight.preferences.DisplayPreferenceValues
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.compactResolutionLabel
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.HostHttpResponseException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateCardControllerTest {
    private fun display(id: String, primary: Boolean = false, supported: Boolean = true) =
        NvHTTP.DisplayInfo(
            index = 0,
            name = "Display $id",
            guid = id,
            displayName = "native-$id",
            friendlyName = "Display $id",
            isPrimary = primary,
            currentScalePercent = 100,
            supportedScalePercents = if (supported) listOf(100, 125, 150) else emptyList(),
            scaleSetSupported = supported
        )

    private fun scale(id: String) = NvHTTP.DisplayScaleInfo(
        displayName = "native-$id",
        deviceId = id,
        friendlyName = "Display $id",
        isPrimary = false,
        currentScalePercent = 100,
        recommendedScalePercent = 125,
        supportedScalePercents = listOf(100, 125, 150),
        scaleSetSupported = true
    )

    @Test
    fun explicitScaleTargetUsesItsOwnCapabilitiesEvenWhenAnotherDisplayIsPrimary() {
        val result = BitrateCardController.resolveDisplayScale(
            listOf(display("main", primary = true), display("secondary")),
            "SECONDARY"
        ) { _, _ -> error("Available catalog capabilities should not request options") }

        assertEquals("secondary", result.deviceId)
    }

    @Test
    fun missingExplicitScaleTargetQueriesItsOriginalIdentityInsteadOfThePrimaryDisplay() {
        val result = BitrateCardController.resolveDisplayScale(
            listOf(display("main", primary = true)),
            "secondary"
        ) { displayName, deviceId ->
            assertNull(displayName)
            assertEquals("secondary", deviceId)
            scale("secondary")
        }

        assertEquals("secondary", result.deviceId)
    }

    @Test
    fun mismatchedScaleOptionsCannotExposeAnotherDisplayForAnExplicitTarget() {
        val error = runCatching {
            BitrateCardController.resolveDisplayScale(
                listOf(display("main", primary = true)),
                "missing"
            ) { _, _ -> scale("main") }
        }.exceptionOrNull()

        assertTrue(error is NvHTTP.DisplayScaleException)
        assertEquals("display_not_found", (error as NvHTTP.DisplayScaleException).errorCode)
    }

    @Test
    fun legacyCatalogUsesSelectedDisplayIdentityWhenLoadingDedicatedScaleOptions() {
        val result = BitrateCardController.resolveDisplayScale(
            listOf(display("main", primary = true), display("secondary", supported = false)),
            "Display secondary"
        ) { displayName, deviceId ->
            assertEquals("native-secondary", displayName)
            assertEquals("secondary", deviceId)
            scale("secondary").copy(friendlyName = "Renamed monitor")
        }

        assertEquals("secondary", result.deviceId)
    }

    @Test
    fun automaticScaleTargetPreservesPrimaryDisplaySelection() {
        val result = BitrateCardController.resolveDisplayScale(
            listOf(display("secondary"), display("main", primary = true)),
            null
        ) { _, _ -> error("Available primary display should not request options") }

        assertEquals("main", result.deviceId)
    }

    @Test
    fun scaleErrorsAlwaysCarryAnActionableNonSensitiveCode() {
        assertEquals("request_failed", BitrateCardController.displayScaleErrorCode(IOException("private host")))
        assertEquals(
            "display_not_found",
            BitrateCardController.displayScaleErrorCode(
                NvHTTP.DisplayScaleException(404, "display_not_found", "private host")
            )
        )
    }

    @Test
    fun absentOptionalScaleEndpointsRemainHiddenOnOlderHosts() {
        for (status in listOf(404, 501)) {
            assertNull(BitrateCardController.displayScaleErrorCode(HostHttpResponseException(status, "")))
            assertNull(BitrateCardController.displayScaleErrorCode(NvHTTP.DisplayScaleException(status, null, "")))
        }
        assertEquals(
            "display_not_found",
            BitrateCardController.displayScaleErrorCode(HostHttpResponseException(404, "", "display_not_found"))
        )
    }

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
    fun pyrowaveProgressExtendsTo800MbpsWithoutChangingLegacyRange() {
        val maxProgress = BitrateCardController.PYROWAVE_MAX_PROGRESS
        val expected = mapOf(
            58 to 190_000,
            59 to 200_000,
            60 to 250_000,
            63 to 400_000,
            64 to 500_000,
            67 to 800_000
        )

        expected.forEach { (progress, bitrate) ->
            assertEquals(bitrate, BitrateCardController.progressToBitrateKbps(progress, maxProgress))
            assertEquals(progress, BitrateCardController.bitrateToProgress(bitrate, maxProgress))
        }

        assertEquals(
            BitrateCardController.MAX_PROGRESS,
            BitrateCardController.bitrateToProgress(
                800_000,
                BitrateCardController.MAX_PROGRESS
            )
        )
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
            abrMode = "balanced",
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
            abrMode = "balanced",
            screenCombinationMode = 2,
            reverseResolution = true
        )
        assertEquals("1920x1080", reversed["list_resolution"])
        assertEquals("60", values["list_fps"])
        assertEquals(20_000, values["seekbar_bitrate_kbps"])
        assertEquals(true, values["checkbox_adaptive_bitrate"])
        assertEquals("balanced", values["list_abr_mode"])
        assertEquals("2", values["list_screen_combination_mode"])
    }

    @Test
    fun unchangedDisplayDraftDoesNotRequestApply() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")

        assertFalse(current.changedFrom(current))
        assertFalse(current.copy(resolution = current.resolution).changedFrom(current))
    }

    @Test
    fun fixedSizeAndNativeHaveDifferentSelectionSemantics() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")
        assertTrue(current.copy(resolution = PreferenceConfiguration.RES_NATIVE).changedFrom(current))
    }

    @Test
    fun selectedFrameRateBecomesPendingWithoutChangingOtherSettings() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")
        val selected = current.copy(frameRate = "90")

        assertTrue(selected.changedFrom(current))
        assertEquals(current.resolution, selected.resolution)
        assertEquals(current.screenMode, selected.screenMode)
    }

    @Test
    fun everyScreenModeCanBecomePendingWithoutChangingOtherSettings() {
        val current = DisplaySettingsDraft("1920x1080", "60", "2")
        listOf("-1", "0", "1", "4", "3").forEach { mode ->
            val selected = current.copy(screenMode = mode)
            assertTrue(selected.changedFrom(current))
            assertEquals(current.resolution, selected.resolution)
            assertEquals(current.frameRate, selected.frameRate)
        }
    }

    @Test
    fun illegalResolutionValuesAreRejected() {
        listOf("1920", "1920x", "x1080", "0x1080", "-1x720", "wide").forEach { value ->
            assertNull(value, BitrateCardController.parseResolutionSelection(value, 1, 1))
        }
    }

    @Test
    fun standardTierResolutionsCompactToShortNames() {
        listOf(
            "3840x2160" to "4K",
            "2560x1440" to "1440p",
            "1920x1080" to "1080p",
            "1280x720" to "720p",
            "854x480" to "480p",
            "640x360" to "360p",
            "1080x1920" to "1080p"
        ).forEach { (value, expected) ->
            assertEquals(expected, compactResolutionLabel(value))
        }
    }

    @Test
    fun nonStandardResolutionsKeepRawValueIncludingWidePanels() {
        assertEquals("2340x1080", compactResolutionLabel("2340x1080"))
        assertEquals("1024x768", compactResolutionLabel("1024x768"))
        assertEquals("Native", compactResolutionLabel("Native"))
        assertEquals("自定义", compactResolutionLabel("自定义"))
    }
}
