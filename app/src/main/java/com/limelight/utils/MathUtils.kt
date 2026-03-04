package com.limelight.utils

import java.security.MessageDigest

object MathUtils {
    @JvmStatic
    fun computeMD5(text: String): String {
        try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            return hexString.toString()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
