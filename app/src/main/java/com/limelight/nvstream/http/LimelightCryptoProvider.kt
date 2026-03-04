package com.limelight.nvstream.http

import java.security.PrivateKey
import java.security.cert.X509Certificate

interface LimelightCryptoProvider {
    fun getClientCertificate(): X509Certificate
    fun getClientPrivateKey(): PrivateKey
    fun getPemEncodedClientCertificate(): ByteArray
    fun encodeBase64String(data: ByteArray): String
}
