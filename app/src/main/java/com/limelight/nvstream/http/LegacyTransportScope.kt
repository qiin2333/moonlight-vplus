package com.limelight.nvstream.http

import org.json.JSONObject

/** Connection identity negotiated independently of experimental packet control. */
data class LegacyTransportScope(val sessionId: String, val connectionEpoch: String) {
    init {
        require(TransportPolicyCodec.isIdentity(sessionId, "4294967295", nonzero = true))
        require(TransportPolicyCodec.isIdentity(connectionEpoch, nonzero = true))
    }

    fun querySuffix(): String = "&sessionId=$sessionId&connectionEpoch=$connectionEpoch"

    fun attach(payload: JSONObject): JSONObject = JSONObject(payload.toString()).apply {
        put("sessionId", sessionId)
        put("connectionEpoch", connectionEpoch)
    }

    companion object {
        fun fromLaunch(acknowledgment: String?, sessionId: String?, epoch: String?): LegacyTransportScope? {
            if (acknowledgment == null && epoch == null) return null
            require(acknowledgment == "1" && sessionId != null && epoch != null) { "Incomplete transport scope handshake" }
            return LegacyTransportScope(sessionId, epoch)
        }
    }
}
