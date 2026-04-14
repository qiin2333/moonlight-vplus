package com.limelight.nvstream.http

import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter

import org.xmlpull.v1.XmlPullParserException

import com.limelight.LimeLog

import java.io.*
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Locale

class PairingManager(
    private val http: NvHTTP,
    cryptoProvider: LimelightCryptoProvider
) {
    private val pk: PrivateKey = cryptoProvider.getClientPrivateKey()
    private val cert: X509Certificate = cryptoProvider.getClientCertificate()
    private val pemCertBytes: ByteArray = cryptoProvider.getPemEncodedClientCertificate()

    var pairedCert: X509Certificate? = null
        private set

    enum class PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }

    class PairResult(val state: PairState, pairName: String?) {
        val pairName: String = if (pairName != null && pairName != "unknown") pairName else ""
    }

    /**
     * 配对处理
     */
    @Throws(IOException::class, XmlPullParserException::class, InterruptedException::class)
    fun pair(serverInfo: String, pin: String): PairResult {
        val hashAlgo: PairingHashAlgorithm

        val serverMajorVersion = http.getServerMajorVersion(serverInfo)
        LimeLog.info("Pairing with server generation: $serverMajorVersion")
        hashAlgo = if (serverMajorVersion >= 7) {
            Sha256PairingHash()
        } else {
            Sha1PairingHash()
        }

        val salt = generateRandomBytes(16)
        val aesKey = generateAesKey(hashAlgo, saltPin(salt, pin))

        val getCert = http.executePairingCommand(
            "phrase=getservercert&salt=${bytesToHex(salt)}&clientcert=${bytesToHex(pemCertBytes)}",
            false
        )

        val pairedValue: String? = try {
            NvHTTP.getXmlString(getCert, "paired", true)
        } catch (e: HostHttpResponseException) {
            LimeLog.warning("getservercert returned status ${e.getErrorCode()}, checking paired value anyway")
            extractXmlValue(getCert, "paired")
        }
        if (pairedValue == null || pairedValue != "1") {
            return PairResult(PairState.FAILED, null)
        }

        val pairName: String? = try {
            NvHTTP.getXmlString(getCert, "pairname", false)
        } catch (e: HostHttpResponseException) {
            extractXmlValue(getCert, "pairname")
        }

        val serverCert = extractPlainCert(getCert)
        if (serverCert == null) {
            http.unpair()
            return PairResult(PairState.ALREADY_IN_PROGRESS, pairName)
        }

        pairedCert = serverCert
        http.serverCert = serverCert

        val randomChallenge = generateRandomBytes(16)
        val encryptedChallenge = encryptAes(randomChallenge, aesKey)

        val challengeResp = http.executePairingCommand("clientchallenge=${bytesToHex(encryptedChallenge)}", true)
        if (NvHTTP.getXmlString(challengeResp, "paired", true) != "1") {
            http.unpair()
            return PairResult(PairState.FAILED, pairName)
        }

        val encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true)!!)
        val decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey)

        val serverResponse = decServerChallengeResponse.copyOfRange(0, hashAlgo.hashLength)
        val serverChallenge = decServerChallengeResponse.copyOfRange(hashAlgo.hashLength, hashAlgo.hashLength + 16)

        val clientSecret = generateRandomBytes(16)
        val challengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(serverChallenge, cert.signature), clientSecret))
        val challengeRespEncrypted = encryptAes(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand("serverchallengeresp=${bytesToHex(challengeRespEncrypted)}", true)
        if (NvHTTP.getXmlString(secretResp, "paired", true) != "1") {
            http.unpair()
            return PairResult(PairState.FAILED, pairName)
        }

        val serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true)!!)
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        if (!verifySignature(serverSecret, serverSignature, serverCert)) {
            http.unpair()
            return PairResult(PairState.FAILED, pairName)
        }

        val serverChallengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(randomChallenge, serverCert.signature), serverSecret))
        if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
            http.unpair()
            return PairResult(PairState.PIN_WRONG, pairName)
        }

        val clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk))
        val clientSecretResp = http.executePairingCommand("clientpairingsecret=${bytesToHex(clientPairingSecret)}", true)
        if (NvHTTP.getXmlString(clientSecretResp, "paired", true) != "1") {
            http.unpair()
            return PairResult(PairState.FAILED, pairName)
        }

        val pairChallenge = http.executePairingChallenge()
        if (NvHTTP.getXmlString(pairChallenge, "paired", true) != "1") {
            http.unpair()
            return PairResult(PairState.FAILED, pairName)
        }

        return PairResult(PairState.PAIRED, pairName)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun extractPlainCert(text: String): X509Certificate? {
        val certText: String? = try {
            NvHTTP.getXmlString(text, "plaincert", false)
        } catch (e: HostHttpResponseException) {
            extractXmlValue(text, "plaincert")
        }
        if (certText != null) {
            val certBytes = hexToBytes(certText)
            return try {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            } catch (e: CertificateException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
        return null
    }

    private fun generateRandomBytes(length: Int): ByteArray {
        val rand = ByteArray(length)
        SecureRandom().nextBytes(rand)
        return rand
    }

    private interface PairingHashAlgorithm {
        val hashLength: Int
        fun hashData(data: ByteArray): ByteArray
    }

    private class Sha1PairingHash : PairingHashAlgorithm {
        override val hashLength: Int = 20

        override fun hashData(data: ByteArray): ByteArray {
            return try {
                MessageDigest.getInstance("SHA-1").digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    private class Sha256PairingHash : PairingHashAlgorithm {
        override val hashLength: Int = 32

        override fun hashData(data: ByteArray): ByteArray {
            return try {
                MessageDigest.getInstance("SHA-256").digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    companion object {
        private val hexArray = "0123456789ABCDEF".toCharArray()

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        private fun hexToBytes(s: String): ByteArray {
            val len = s.length
            require(len % 2 == 0) { "Illegal string length: $len" }

            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            }
            return data
        }

        @Throws(UnsupportedEncodingException::class)
        private fun saltPin(salt: ByteArray, pin: String): ByteArray {
            val pinBytes = pin.toByteArray(Charsets.UTF_8)
            val saltedPin = ByteArray(salt.size + pinBytes.size)
            System.arraycopy(salt, 0, saltedPin, 0, salt.size)
            System.arraycopy(pinBytes, 0, saltedPin, salt.size, pinBytes.size)
            return saltedPin
        }

        private fun getSha256SignatureInstanceForKey(key: Key): Signature {
            return when (key.algorithm) {
                "RSA" -> Signature.getInstance("SHA256withRSA")
                "EC" -> Signature.getInstance("SHA256withECDSA")
                else -> throw NoSuchAlgorithmException("Unhandled key algorithm: ${key.algorithm}")
            }
        }

        private fun verifySignature(data: ByteArray, signature: ByteArray, cert: java.security.cert.Certificate): Boolean {
            return try {
                val sig = getSha256SignatureInstanceForKey(cert.publicKey)
                sig.initVerify(cert.publicKey)
                sig.update(data)
                sig.verify(signature)
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
            return try {
                val sig = getSha256SignatureInstanceForKey(key)
                sig.initSign(key)
                sig.update(data)
                sig.sign()
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun performBlockCipher(blockCipher: org.bouncycastle.crypto.BlockCipher, input: ByteArray): ByteArray {
            val blockSize = blockCipher.blockSize
            val blockRoundedSize = (input.size + (blockSize - 1)) and (blockSize - 1).inv()

            val blockRoundedInputData = Arrays.copyOf(input, blockRoundedSize)
            val blockRoundedOutputData = ByteArray(blockRoundedSize)

            var offset = 0
            while (offset < blockRoundedSize) {
                blockCipher.processBlock(blockRoundedInputData, offset, blockRoundedOutputData, offset)
                offset += blockSize
            }

            return blockRoundedOutputData
        }

        private fun decryptAes(encryptedData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine = AESLightEngine()
            aesEngine.init(false, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, encryptedData)
        }

        private fun encryptAes(plaintextData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine = AESLightEngine()
            aesEngine.init(true, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, plaintextData)
        }

        private fun generateAesKey(hashAlgo: PairingHashAlgorithm, keyData: ByteArray): ByteArray {
            return Arrays.copyOf(hashAlgo.hashData(keyData), 16)
        }

        private fun concatBytes(a: ByteArray, b: ByteArray): ByteArray {
            val c = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, c, 0, a.size)
            System.arraycopy(b, 0, c, a.size, b.size)
            return c
        }

        fun generatePinString(): String {
            val r = SecureRandom()
            return String.format(null as Locale?, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10))
        }

        private fun extractXmlValue(xml: String, tagName: String): String? {
            val startTag = "<$tagName>"
            val start = xml.indexOf(startTag)
            if (start < 0) return null
            val valueStart = start + startTag.length
            val end = xml.indexOf("</$tagName>", valueStart)
            if (end < 0) return null
            return xml.substring(valueStart, end)
        }
    }
}
