package com.limelight.nvstream.http

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException

class LegacyTransportScopeTest {
    @Test fun httpParserPreservesLegacyAndScopedHosts() {
        assertNull(NvHTTP.readLegacyTransportScope("<root/>"))
        assertNull(NvHTTP.readLegacyTransportScope("<root><transportSessionId>1</transportSessionId></root>"))
        val scope = NvHTTP.readLegacyTransportScope("<root><transportScope>1</transportScope>" +
            "<transportSessionId>4294967295</transportSessionId>" +
            "<transportConnectionEpoch>18446744073709551615</transportConnectionEpoch></root>")!!
        assertEquals("&sessionId=4294967295&connectionEpoch=18446744073709551615", scope.querySuffix())
    }

    @Test fun httpParserReportsInvalidScopeThroughLaunchXmlFailurePath() {
        for (fields in listOf(
            "<transportScope>1</transportScope>",
            "<transportSessionId>1</transportSessionId><transportConnectionEpoch>2</transportConnectionEpoch>",
            "<transportScope>0</transportScope><transportSessionId>1</transportSessionId><transportConnectionEpoch>2</transportConnectionEpoch>",
            "<transportScope>1</transportScope><transportSessionId>01</transportSessionId><transportConnectionEpoch>2</transportConnectionEpoch>",
            "<transportScope>1</transportScope><transportSessionId>1</transportSessionId><transportConnectionEpoch>0</transportConnectionEpoch>")) {
            try { NvHTTP.readLegacyTransportScope("<root>$fields</root>"); fail("accepted invalid launch scope") }
            catch (e: XmlPullParserException) { assertTrue(e.detail is IllegalArgumentException) }
        }
    }

    @Test fun httpParserRejectsEachDuplicateScopeFieldAsXmlFailure() {
        val valid = "<transportScope>1</transportScope><transportSessionId>1</transportSessionId>" +
            "<transportConnectionEpoch>2</transportConnectionEpoch>"
        for ((name, value) in listOf("transportScope" to "1", "transportSessionId" to "1",
            "transportConnectionEpoch" to "2")) {
            try { NvHTTP.readLegacyTransportScope("<root>$valid<$name>$value</$name></root>"); fail("accepted duplicate identity") }
            catch (_: XmlPullParserException) { }
        }
    }

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
