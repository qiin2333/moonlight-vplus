package com.limelight.nvstream.http

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyTransportScopeTest {
    @Test fun oldHostMayOmitBothScopeFields() {
        assertNull(LegacyTransportScope.fromLaunch(null, null, null))
        assertNull(LegacyTransportScope.fromLaunch(null, "1", null))
    }
    @Test fun malformedOrPartialAcknowledgmentCannotDowngrade() {
        for ((ack, id, epoch) in listOf(
            Triple(null, "1", "2"), Triple("1", null, "2"), Triple("1", "1", null),
            Triple("0", "1", "2"), Triple("2", "1", "2"), Triple("1", "01", "2"),
            Triple("1", "1", "0"), Triple("1", "4294967296", "2"),
            Triple("1", "1", "18446744073709551616"))) {
            try { LegacyTransportScope.fromLaunch(ack, id, epoch); fail("accepted malformed handshake") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unsignedIdentityIsPreservedAndPayloadIsNotShared() {
        val scope = LegacyTransportScope.fromLaunch("1", "4294967295", "18446744073709551615")!!
        val original = JSONObject().put("enabled", false)
        val bound = scope.attach(original)
        assertFalse(original.has("sessionId"))
        assertEquals("18446744073709551615", bound.getString("connectionEpoch"))
        assertEquals("&sessionId=4294967295&connectionEpoch=18446744073709551615", scope.querySuffix())
        val replacement = LegacyTransportScope("4294967295", "1")
        assertNotEquals(scope.querySuffix(), replacement.querySuffix())
        assertEquals("18446744073709551615", bound.getString("connectionEpoch"))
    }
}
