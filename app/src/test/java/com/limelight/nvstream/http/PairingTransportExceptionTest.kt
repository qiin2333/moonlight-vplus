package com.limelight.nvstream.http

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingTransportExceptionTest {
    @Test
    fun classifiesUnexpectedEndOfStreamWithoutExposingUrl() {
        val exception = PairingTransportException.from(
            "pair/getservercert",
            IOException("unexpected end of stream on http://host/pair?sensitive=value")
        )

        assertEquals(PairingTransportException.Reason.RESPONSE_INTERRUPTED, exception.reason)
        assertEquals("pair/getservercert", exception.message)
    }

    @Test
    fun classifiesOtherIoFailuresAsGeneric() {
        val exception = PairingTransportException.from(
            "pair/clientchallenge",
            IOException("connection reset")
        )

        assertEquals(PairingTransportException.Reason.OTHER, exception.reason)
        assertEquals("pair/clientchallenge", exception.message)
    }
}
