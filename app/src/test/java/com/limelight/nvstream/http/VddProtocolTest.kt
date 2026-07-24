package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Test

class VddProtocolTest {
    @Test
    fun parsesDisplayCatalogVddCapabilityAndState() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """
            {
              "status_code": 200,
              "displays": [
                {
                  "friendly_name": "Main display",
                  "display_name": "\\\\.\\DISPLAY1",
                  "device_id": "device-1"
                }
              ],
              "vdd": {
                "capability_version": 1,
                "state": "ready"
              }
            }
            """.trimIndent()
        )

        assertEquals(1, catalog.displays.size)
        assertEquals("Main display", catalog.displays.single().name)
        assertEquals("device-1", catalog.displays.single().guid)
        assertEquals(1, catalog.vddCapabilityVersion)
        assertEquals(NvHTTP.VddState.READY, catalog.vddState)
    }

    @Test
    fun missingVddMetadataIsNotTreatedAsSupported() {
        val catalog = NvHTTP.parseDisplayCatalog(
            """{"status_code":200,"displays":[]}"""
        )

        assertEquals(0, catalog.vddCapabilityVersion)
        assertEquals(NvHTTP.VddState.UNKNOWN, catalog.vddState)
    }

    @Test
    fun hostErrorCarriesMachineReadableSunshineErrorCode() {
        val error = HostHttpResponseException(503, "VDD unavailable")
            .withSunshineErrorCode("VDD_DRIVER_UNREACHABLE")

        assertEquals(503, error.getErrorCode())
        assertEquals("VDD_DRIVER_UNREACHABLE", error.getSunshineErrorCode())
    }

    @Test
    fun computerDetailsCopyPreservesVddCapabilityVersion() {
        val copy = ComputerDetails(
            ComputerDetails().apply {
                vddCapabilityVersion = 1
            }
        )

        assertEquals(1, copy.vddCapabilityVersion)
    }
}
