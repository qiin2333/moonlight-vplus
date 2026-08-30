package com.limelight.utils.easytier

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class EasyTierConnectionProfile(
        val id: String,
        val networkName: String,
        val networkSecret: String,
        val peers: List<String>,
        val hostVirtualIp: String
)

internal data class VPlusConnectionCode(
        val host: String,
        val port: Int,
        val pin: String,
        val name: String?,
        val easyTierProfile: EasyTierConnectionProfile?
)

internal object VPlusConnectionCodeParser {
    private const val DEFAULT_SUNSHINE_PORT = 47989
    private val PROFILE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val ALLOWED_PEER_SCHEMES = setOf("tcp", "udp", "wg", "ws", "wss", "quic")

    fun parse(raw: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): VPlusConnectionCode {
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid connection code", e)
        }

        require(uri.scheme.equals("moonlight", ignoreCase = true) && uri.host.equals("pair", ignoreCase = true)) {
            "Unsupported connection code"
        }

        val query = parseQuery(uri.rawQuery)
        val host = query.first("host")?.trim().orEmpty()
        val pin = query.first("pin")?.trim().orEmpty()
        require(host.isNotEmpty() && host.length <= 255) { "Missing host" }
        require(pin.matches(Regex("[0-9]{4}"))) { "Invalid PIN" }

        val port = query.first("port")?.toIntOrNull() ?: DEFAULT_SUNSHINE_PORT
        require(port in 1..65535) { "Invalid port" }

        val version = query.first("v")?.toIntOrNull() ?: 1
        require(version == 1 || version == 2) { "Unsupported connection code version" }

        val profile = if (version == 2) parseEasyTierProfile(query, host, nowEpochSeconds) else null
        return VPlusConnectionCode(
                host = host,
                port = port,
                pin = pin,
                name = query.first("name")?.take(256),
                easyTierProfile = profile
        )
    }

    private fun parseEasyTierProfile(
            query: Map<String, List<String>>,
            sunshineHost: String,
            nowEpochSeconds: Long
    ): EasyTierConnectionProfile {
        val expiresAt = query.first("expires")?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing expiry")
        require(expiresAt >= nowEpochSeconds && expiresAt <= nowEpochSeconds + 24 * 60 * 60) {
            "Expired connection code"
        }

        val id = query.first("profile")?.trim().orEmpty()
        val networkName = query.first("et_name")?.trim().orEmpty()
        val networkSecret = query.first("et_secret").orEmpty()
        val hostVirtualIp = query.first("et_host")?.trim().orEmpty()
        val peers = query["et_peer"].orEmpty().map(String::trim).filter(String::isNotEmpty)

        require(PROFILE_ID.matches(id)) { "Invalid profile identifier" }
        require(networkName.isNotEmpty() && networkName.length <= 128) { "Invalid network name" }
        require(networkSecret.length in 16..256) { "Invalid network secret" }
        require(isValidIpv4(hostVirtualIp) && sunshineHost == hostVirtualIp) { "Invalid virtual host address" }
        require(peers.isNotEmpty() && peers.size <= 8) { "Invalid peer list" }
        peers.forEach { peer ->
            require(peer.length <= 512) { "Peer URL is too long" }
            val peerUri = runCatching { URI(peer) }.getOrNull()
                    ?: throw IllegalArgumentException("Invalid peer URL")
            require(peerUri.scheme?.lowercase() in ALLOWED_PEER_SCHEMES && peerUri.host != null) {
                "Unsupported peer URL"
            }
        }

        return EasyTierConnectionProfile(id, networkName, networkSecret, peers, hostVirtualIp)
    }

    private fun isValidIpv4(value: String): Boolean {
        if (!IPV4.matches(value)) return false
        return value.split('.').all { it.toIntOrNull() in 0..255 }
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, MutableList<String>>()
        rawQuery.split('&').forEach { entry ->
            val parts = entry.split('=', limit = 2)
            val key = decode(parts[0])
            val value = decode(parts.getOrElse(1) { "" })
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun Map<String, List<String>>.first(key: String): String? = get(key)?.firstOrNull()
}
