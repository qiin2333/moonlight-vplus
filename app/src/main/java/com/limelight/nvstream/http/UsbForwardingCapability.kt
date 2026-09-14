package com.limelight.nvstream.http

import org.json.JSONObject

/** In-memory paired-host credentials; deliberately no generated toString(). */
class UsbForwardingCapability private constructor(
    val available: Boolean,
    val reason: String,
    val port: Int,
    val token: String,
) {
    companion object {
        fun parse(body: String): UsbForwardingCapability {
            require(body.length <= 4096) { "Invalid USB capability response" }
            val json = JSONObject(body)
            require((json.opt("version") as? Number)?.toDouble() == 1.0) { "Unsupported USB capability version" }
            val enabled = json.opt("enabled") as? Boolean ?: error("Invalid USB capability response")
            val advertised = json.opt("available") as? Boolean ?: error("Invalid USB capability response")
            val available = enabled && advertised
            val rawPort = if (available) (json.opt("port") as? Number)?.toDouble() else 0.0
            require(rawPort != null && rawPort == rawPort.toInt().toDouble()) { "Invalid USB capability port" }
            val port = rawPort.toInt()
            val token = if (available) json.opt("token") as? String ?: "" else ""
            require(!available || (port in 1..65535 && token.matches(Regex("[0-9a-fA-F]{64}")))) {
                "Invalid USB capability credentials"
            }
            return UsbForwardingCapability(available, json.optString("reason"), port, token)
        }
    }
}
