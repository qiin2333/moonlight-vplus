package com.limelight.nvstream.http

import org.junit.Assert.*
import org.junit.Test

class UsbForwardingCapabilityTest {
    private val token = "ab".repeat(32)
    private fun ready(port: Int = 47996, secret: String = token) =
        """{"version":1,"enabled":true,"available":true,"port":$port,"token":"$secret","reason":"ready"}"""

    @Test fun acceptsRuntimeCredentialsWithoutExposingThemInToString() {
        val c = UsbForwardingCapability.parse(ready())
        assertTrue(c.available)
        assertEquals(47996, c.port)
        assertEquals(token, c.token)
        assertFalse(c.toString().contains(token))
    }

    @Test fun disabledAndUnavailableDoNotNeedCredentials() {
        for (enabled in listOf(true, false)) {
            val c = UsbForwardingCapability.parse(
                """{"version":1,"enabled":$enabled,"available":false,"reason":"disabled"}""",
            )
            assertFalse(c.available)
            assertEquals("", c.token)
        }
    }

    @Test fun rejectsMalformedCapabilities() {
        for (body in listOf(ready(0), ready(65536), ready(secret = ""),
            ready().replace("\"version\":1", "\"version\":2"),
            ready().replace("47996", "47996.5"), ready().replace("47996", "\"47996\""),
            ready().replace("\"version\":1", "\"version\":\"1\""),
            ready().replace("\"enabled\":true", "\"enabled\":\"true\""), "{}", "x".repeat(4097))) {
            assertTrue(runCatching { UsbForwardingCapability.parse(body) }.isFailure)
        }
    }
}
