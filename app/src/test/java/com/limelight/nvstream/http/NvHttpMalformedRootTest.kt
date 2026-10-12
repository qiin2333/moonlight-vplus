package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Malformed host XML must surface as a typed [HostHttpResponseException] (an
 * [java.io.IOException] the callers already handle) instead of an NPE from a
 * Kotlin null assertion on the platform-typed XmlPullParser attribute.
 */
class NvHttpMalformedRootTest {
    @Test
    fun rootWithoutStatusCodeThrowsHostExceptionNotNpe() {
        val error = assertThrows(HostHttpResponseException::class.java) {
            NvHTTP.getXmlString("<root></root>", "bitrate", true)
        }
        assertTrue(error.message, error.message!!.contains("status_code"))
    }

    @Test
    fun rootWithNonNumericStatusCodeThrowsHostException() {
        assertThrows(HostHttpResponseException::class.java) {
            NvHTTP.getXmlString("<root status_code=\"not-a-number\"></root>", "bitrate", true)
        }
    }

    @Test
    fun errorStatusWithoutStatusMessageStillThrowsTypedError() {
        val error = assertThrows(HostHttpResponseException::class.java) {
            NvHTTP.getXmlString("<root status_code=\"418\"></root>", "bitrate", true)
        }
        assertEquals(418, error.getErrorCode())
    }

    @Test
    fun validRootStillParsesField() {
        val value = NvHTTP.getXmlString(
            "<root status_code=\"200\"><bitrate>8000</bitrate></root>",
            "bitrate",
            true,
        )
        assertEquals("8000", value)
    }
}
