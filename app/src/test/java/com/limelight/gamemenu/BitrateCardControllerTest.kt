package com.limelight.gamemenu

import org.junit.Assert.assertEquals
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
    fun pyrowaveProgressExtendsTo800MbpsWithoutChangingLegacyRange() {
        val maxProgress = BitrateCardController.PYROWAVE_MAX_PROGRESS
        val expected = mapOf(
            58 to 190_000,
            59 to 200_000,
            60 to 225_000,
            69 to 450_000,
            83 to 800_000
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
}
