package com.limelight.nvstream.http

import java.io.IOException

class PairingTransportException(
    val reason: Reason,
    requestDescription: String,
    cause: IOException
) : IOException(requestDescription, cause) {
    enum class Reason {
        RESPONSE_INTERRUPTED,
        OTHER
    }

    companion object {
        fun from(requestDescription: String, cause: IOException): PairingTransportException {
            val reason = if (cause.message?.startsWith("unexpected end of stream", ignoreCase = true) == true) {
                Reason.RESPONSE_INTERRUPTED
            } else {
                Reason.OTHER
            }
            return PairingTransportException(reason, requestDescription, cause)
        }
    }
}
