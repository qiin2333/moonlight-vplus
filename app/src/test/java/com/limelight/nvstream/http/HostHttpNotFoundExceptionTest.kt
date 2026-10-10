package com.limelight.nvstream.http

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostHttpNotFoundExceptionTest {
    @Test
    fun preservesSafeEndpointClassification() {
        val pairing = HostHttpNotFoundException("pair/getservercert", true)
        val serverInfo = HostHttpNotFoundException("serverinfo", false)
        val legacyCompatible: FileNotFoundException = pairing

        assertTrue(pairing.isPairingEndpoint)
        assertEquals("pair/getservercert", legacyCompatible.message)
        assertFalse(serverInfo.isPairingEndpoint)
        assertEquals("serverinfo", serverInfo.message)
    }
}
