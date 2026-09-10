package com.limelight.utils

import java.security.MessageDigest
import java.util.UUID

object HostCacheKey {
    private const val LEGACY_PREFIX = "legacy-"
    private const val HEX_DIGITS = "0123456789abcdef"

    fun fromUuid(rawUuid: String?): String? {
        if (rawUuid == null) return null

        if (rawUuid.isBlank()) return null

        val parsedUuid = try {
            UUID.fromString(rawUuid)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsedUuid != null && parsedUuid.toString().equals(rawUuid, ignoreCase = true)) {
            return rawUuid
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawUuid.toByteArray(Charsets.UTF_8))
        return buildString(LEGACY_PREFIX.length + digest.size * 2) {
            append(LEGACY_PREFIX)
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }
}
