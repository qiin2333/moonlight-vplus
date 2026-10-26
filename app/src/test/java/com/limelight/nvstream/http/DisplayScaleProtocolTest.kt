package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the optional Foundation Sunshine display-scale fields. */
class DisplayScaleProtocolTest {
    @Test
    fun parsesDisplayScaleFieldsWithoutChangingLegacyIdentity() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """
            {
              "status_code": 200,
              "displays": [{
                "display_name": "\\\\.\\DISPLAY2",
                "device_id": "monitor-2",
                "friendly_name": "Projector",
                "is_primary": false,
                "current_scale_percent": 125,
                "recommended_scale_percent": 100,
                "supported_scale_percents": [100, 125, 150],
                "scale_set_supported": true
              }]
            }
            """.trimIndent()
        )

        val display = catalog.displays.single()
        assertEquals("Projector", display.name)
        assertEquals("monitor-2", display.guid)
        assertEquals("\\\\.\\DISPLAY2", display.displayName)
        assertEquals("Projector", display.friendlyName)
        assertFalse(display.isPrimary)
        assertEquals(125, display.currentScalePercent)
        assertEquals(100, display.recommendedScalePercent)
        assertEquals(listOf(100, 125, 150), display.supportedScalePercents)
        assertTrue(display.scaleSetSupported)
    }

    @Test
    fun missingScaleFieldsRemainCompatibleWithOlderSunshine() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """
            {
              "status_code": 200,
              "displays": [{"friendly_name": "Main display", "device_id": "monitor-1"}]
            }
            """.trimIndent()
        )

        val display = catalog.displays.single()
        assertNull(display.currentScalePercent)
        assertNull(display.recommendedScalePercent)
        assertTrue(display.supportedScalePercents.isEmpty())
        assertFalse(display.scaleSetSupported)
    }

    @Test
    fun invalidScaleEntriesAreIgnoredInsteadOfBreakingDisplayList() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """
            {
              "status_code": 200,
              "displays": [{
                "display_name": "\\\\.\\DISPLAY1",
                "device_id": "monitor-1",
                "supported_scale_percents": [0, -25, null, "oops", 100, 150]
              }]
            }
            """.trimIndent()
        )

        assertEquals(listOf(100, 150), catalog.displays.single().supportedScalePercents)
    }

    @Test
    fun scaleOptionsAreSortedAndDeduplicatedForDiscreteSlider() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """
            {
              "status_code": 200,
              "displays": [{
                "friendly_name": "Main display",
                "supported_scale_percents": [150, 100, 125, 125, 100]
              }]
            }
            """.trimIndent()
        )

        assertEquals(listOf(100, 125, 150), catalog.displays.single().supportedScalePercents)
    }
}
