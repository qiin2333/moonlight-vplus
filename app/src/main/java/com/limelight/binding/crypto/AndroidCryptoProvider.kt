package com.limelight.binding.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64

import com.limelight.LimeLog
import com.limelight.nvstream.http.LimelightCryptoProvider

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AndroidCryptoProvider(c: Context) : LimelightCryptoProvider {

    private val certFile: File
    private val keyFile: File

    private var cert: X509Certificate? = null
    private var key: PrivateKey? = null
    private var pemCertBytes: ByteArray? = null

    init {
        val dataPath = c.filesDir.absolutePath
        certFile = File(dataPath + File.separator + "client.crt")
        keyFile = File(dataPath + File.separator + "client.key")
    }

    private fun loadFileToBytes(f: File): ByteArray? {
        if (!f.exists()) {
            return null
        }

        return try {
            FileInputStream(f).use { fin ->
                val fileData = ByteArray(f.length().toInt())
                if (fin.read(fileData) != f.length().toInt()) {
                    null
                } else {
                    fileData
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun loadCertKeyPair(): Boolean {
        val certBytes = loadFileToBytes(certFile)
        val keyBytes = loadFileToBytes(keyFile)

        if (certBytes == null || keyBytes == null) {
            LimeLog.info("Missing cert or key; need to generate a new one")
            return false
        }

        try {
            val certFactory = CertificateFactory.getInstance("X.509", bcProvider)
            cert = certFactory.generateCertificate(certBytes.inputStream()) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA", bcProvider)
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } catch (e: CertificateException) {
            LimeLog.warning("Corrupted certificate")
            return false
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: InvalidKeySpecException) {
            LimeLog.warning("Corrupted key")
            return false
        }

        return true
    }

    @SuppressLint("TrulyRandom")
    private fun generateCertKeyPair(): Boolean {
        val snBytes = ByteArray(8)
        SecureRandom().nextBytes(snBytes)

        val keyPair = try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider)
            keyPairGenerator.initialize(2048)
            keyPairGenerator.generateKeyPair()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }

        val now = Date()

        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.YEAR, 20)
        val expirationDate = calendar.time

        val serial = BigInteger(snBytes).abs()

        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Client")
        val name = nameBuilder.build()

        val certBuilder = X509v3CertificateBuilder(
            name, serial, now, expirationDate, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )

        try {
            val sigGen = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
            cert = JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(sigGen))
            key = keyPair.private
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        LimeLog.info("Generated a new key pair")

        saveCertKeyPair()

        return true
    }

    private fun saveCertKeyPair() {
        try {
            FileOutputStream(certFile).use { certOut ->
                FileOutputStream(keyFile).use { keyOut ->
                    val strWriter = StringWriter()
                    JcaPEMWriter(strWriter).use { pemWriter ->
                        pemWriter.writeObject(cert)
                    }

                    OutputStreamWriter(certOut).use { certWriter ->
                        val pemStr = strWriter.buffer.toString()
                        for (c in pemStr) {
                            if (c != '\r') {
                                certWriter.append(c)
                            }
                        }
                    }

                    keyOut.write(key!!.encoded)

                    LimeLog.info("Saved generated key pair to disk")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun getClientCertificate(): X509Certificate {
        synchronized(globalCryptoLock) {
            cert?.let { return it }

            if (loadCertKeyPair()) {
                return cert!!
            }

            if (!generateCertKeyPair()) {
                throw RuntimeException("Failed to generate certificate")
            }

            loadCertKeyPair()
            return cert!!
        }
    }

    override fun getClientPrivateKey(): PrivateKey {
        synchronized(globalCryptoLock) {
            key?.let { return it }

            if (loadCertKeyPair()) {
                return key!!
            }

            if (!generateCertKeyPair()) {
                throw RuntimeException("Failed to generate key")
            }

            loadCertKeyPair()
            return key!!
        }
    }

    override fun getPemEncodedClientCertificate(): ByteArray {
        synchronized(globalCryptoLock) {
            getClientCertificate()
            return pemCertBytes!!
        }
    }

    override fun encodeBase64String(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    companion object {
        private val globalCryptoLock = Any()
        private val bcProvider = BouncyCastleProvider()
    }
}
