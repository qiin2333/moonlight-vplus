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
        assertEquals("P01", exception.errorCode)
        assertEquals("pair/getservercert", exception.message)
    }

    @Test
    fun classifiesOtherIoFailuresAsGeneric() {
        val exception = PairingTransportException.from(
            "pair/clientchallenge",
            IOException("connection reset")
        )

        assertEquals(PairingTransportException.Reason.OTHER, exception.reason)
        assertEquals("P99", exception.errorCode)
        assertEquals("pair/clientchallenge", exception.message)
    }

    @Test
    fun classifiesNestedUnexpectedEndOfStreamAsInterrupted() {
        val exception = PairingTransportException.from(
            "pair/getservercert",
            IOException(
                "request failed",
                IOException("Unexpected end of stream on http://host/pair?sensitive=value")
            )
        )

        assertEquals(PairingTransportException.Reason.RESPONSE_INTERRUPTED, exception.reason)
        assertEquals("P01", exception.errorCode)
        assertEquals("pair/getservercert", exception.message)
    }

    @Test
    fun classifiesCommonNetworkFailures() {
        assertEquals(
            PairingTransportException.Reason.TIMEOUT,
            PairingTransportException.from("pair/getservercert", java.net.SocketTimeoutException()).reason
        )
        assertEquals(
            PairingTransportException.Reason.CONNECTION_FAILED,
            PairingTransportException.from("pair/getservercert", java.net.ConnectException()).reason
        )
        assertEquals(
            PairingTransportException.Reason.TLS_HANDSHAKE_FAILED,
            PairingTransportException.from("pair/pairchallenge", javax.net.ssl.SSLHandshakeException("failed")).reason
        )
        assertEquals(
            PairingTransportException.Reason.HOST_LOOKUP_FAILED,
            PairingTransportException.from("pair/getservercert", java.net.UnknownHostException()).reason
        )
    }
}
