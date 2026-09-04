package com.limelight.nvstream.http

import java.io.IOException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PairingTransportException(
    val reason: Reason,
    requestDescription: String,
    cause: IOException
) : IOException(requestDescription, cause) {
    enum class Reason(val errorCode: String, val logName: String) {
        RESPONSE_INTERRUPTED("P01", "response_interrupted"),
        TIMEOUT("P02", "timeout"),
        CONNECTION_FAILED("P03", "connection_failed"),
        TLS_HANDSHAKE_FAILED("P04", "tls_handshake_failed"),
        HOST_LOOKUP_FAILED("P05", "host_lookup_failed"),
        OTHER("P99", "other_io_failure")
    }

    val errorCode: String
        get() = reason.errorCode

    companion object {
        fun from(requestDescription: String, cause: IOException): PairingTransportException {
            val causes = generateSequence<Throwable>(cause) { it.cause }.toList()
            val reason = when {
                causes.any { it is SocketTimeoutException } -> Reason.TIMEOUT
                causes.any { it is SSLHandshakeException } -> Reason.TLS_HANDSHAKE_FAILED
                causes.any { it is UnknownHostException } -> Reason.HOST_LOOKUP_FAILED
                causes.any { it is ConnectException || it is NoRouteToHostException } -> Reason.CONNECTION_FAILED
                causes.any { it is EOFException || it is SocketException } ||
                    causes.any {
                        it.message?.startsWith("unexpected end of stream", ignoreCase = true) == true
                    } ->
                    Reason.RESPONSE_INTERRUPTED
                else -> Reason.OTHER
            }
            return PairingTransportException(reason, requestDescription, cause)
        }
    }
}
