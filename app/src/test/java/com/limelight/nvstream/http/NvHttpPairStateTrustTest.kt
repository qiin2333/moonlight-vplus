package com.limelight.nvstream.http

import java.io.IOException
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NvHttpPairStateTrustTest {
    @Test
    fun certificateMismatchDoesNotFallBackToHttp() {
        val pinned = certificate("pinned")
        val presented = certificate("presented")
        MockWebServer().use { httpServer ->
            MockWebServer().use { httpsServer ->
                configureHttps(httpsServer, presented)
                httpServer.start()
                httpsServer.start()
                httpServer.enqueue(MockResponse.Builder().body("untrusted-http").build())

                val error = assertThrows(IOException::class.java) {
                    client(httpServer, httpsServer, pinned).getServerInfo(true)
                }

                assertTrue(error.stackTraceToString(), error.hasCertificateFailure())
                assertEquals(0, httpServer.requestCount)
            }
        }
    }

    @Test
    fun authenticatedHttps401RetainsCompatibilityFallback() {
        val pinned = certificate("pinned")
        MockWebServer().use { httpServer ->
            MockWebServer().use { httpsServer ->
                configureHttps(httpsServer, pinned)
                httpServer.start()
                httpsServer.start()
                httpsServer.enqueue(MockResponse.Builder().code(401).build())
                httpServer.enqueue(serverInfoResponse(httpsServer.port, pairStatus = 1))

                val http = client(httpServer, httpsServer, pinned)
                val details = http.getComputerDetails(true)

                assertEquals(PairingManager.PairState.NOT_PAIRED, details.pairState)
                assertEquals(false, details.serverInfoTrustedByCert)
                assertEquals(true, details.pairStateTrusted)
                assertTrue(http.lastPairStateTrusted)
                assertEquals(1, httpServer.requestCount)
                assertEquals(1, httpsServer.requestCount)
            }
        }
    }

    @Test
    fun httpPortDiscovery401IsNotAuthenticated() {
        val pinned = certificate("pinned")
        MockWebServer().use { httpServer ->
            MockWebServer().use { unusedHttpsServer ->
                httpServer.start()
                unusedHttpsServer.start()
                httpServer.enqueue(MockResponse.Builder().code(401).build())
                httpServer.enqueue(serverInfoResponse(unusedHttpsServer.port, pairStatus = 0))

                val http = client(httpServer, unusedHttpsServer, pinned, httpsPort = 0)
                val error = assertThrows(HostHttpResponseException::class.java) {
                    http.getServerInfo(true)
                }

                assertEquals(401, error.getErrorCode())
                assertFalse(http.lastPairStateTrusted)
                assertEquals(1, httpServer.requestCount)
                assertEquals(0, unusedHttpsServer.requestCount)
            }
        }
    }

    @Test
    fun failedCompatibilityFallbackDoesNotRetainPairStateTrust() {
        val pinned = certificate("pinned")
        MockWebServer().use { httpServer ->
            MockWebServer().use { httpsServer ->
                configureHttps(httpsServer, pinned)
                httpServer.start()
                httpsServer.start()
                httpsServer.enqueue(MockResponse.Builder().code(401).build())
                httpServer.enqueue(MockResponse.Builder().code(500).build())

                val http = client(httpServer, httpsServer, pinned)
                val error = assertThrows(HostHttpResponseException::class.java) {
                    http.getServerInfo(true)
                }

                assertEquals(500, error.getErrorCode())
                assertFalse(http.lastPairStateTrusted)
                assertEquals(1, httpServer.requestCount)
                assertEquals(1, httpsServer.requestCount)
            }
        }
    }

    @Test
    fun directPairStateUsesAuthenticatedHttps401() {
        val pinned = certificate("pinned")
        MockWebServer().use { httpServer ->
            MockWebServer().use { httpsServer ->
                configureHttps(httpsServer, pinned)
                httpServer.start()
                httpsServer.start()
                httpsServer.enqueue(MockResponse.Builder().code(401).build())
                httpServer.enqueue(serverInfoResponse(httpsServer.port, pairStatus = 1))

                val pairState = client(httpServer, httpsServer, pinned).getPairState()

                assertEquals(PairingManager.PairState.NOT_PAIRED, pairState)
                assertEquals(1, httpServer.requestCount)
                assertEquals(1, httpsServer.requestCount)
            }
        }
    }

    @Test
    fun pinnedHttpsResponseTrustsBodyAndPairState() {
        val pinned = certificate("pinned")
        MockWebServer().use { httpServer ->
            MockWebServer().use { httpsServer ->
                configureHttps(httpsServer, pinned)
                httpServer.start()
                httpsServer.start()
                httpsServer.enqueue(serverInfoResponse(httpsServer.port, pairStatus = 1))

                val details = client(httpServer, httpsServer, pinned).getComputerDetails(true)

                assertEquals(PairingManager.PairState.PAIRED, details.pairState)
                assertEquals(true, details.serverInfoTrustedByCert)
                assertEquals(true, details.pairStateTrusted)
                assertEquals(0, httpServer.requestCount)
                assertEquals(1, httpsServer.requestCount)
            }
        }
    }

    @Test
    fun unpairedHttpDiscoveryRemainsUntrusted() {
        MockWebServer().use { httpServer ->
            MockWebServer().use { unusedHttpsServer ->
                httpServer.start()
                unusedHttpsServer.start()
                httpServer.enqueue(serverInfoResponse(unusedHttpsServer.port, pairStatus = 0))

                val details = client(httpServer, unusedHttpsServer, pinned = null)
                    .getComputerDetails(true)

                assertEquals(PairingManager.PairState.NOT_PAIRED, details.pairState)
                assertEquals(false, details.serverInfoTrustedByCert)
                assertEquals(false, details.pairStateTrusted)
                assertEquals(1, httpServer.requestCount)
                assertEquals(0, unusedHttpsServer.requestCount)
            }
        }
    }

    private fun client(
        httpServer: MockWebServer,
        httpsServer: MockWebServer,
        pinned: HeldCertificate?,
        httpsPort: Int = httpsServer.port
    ): NvHTTP {
        val clientIdentity = certificate("client")
        return NvHTTP(
            ComputerDetails.AddressTuple(httpServer.hostName, httpServer.port),
            httpsPort,
            "test-client-id",
            "test-client",
            pinned?.certificate,
            TestCryptoProvider(clientIdentity)
        )
    }

    private fun configureHttps(server: MockWebServer, certificate: HeldCertificate) {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory())
    }

    private fun certificate(commonName: String): HeldCertificate {
        return HeldCertificate.Builder()
            .commonName(commonName)
            .addSubjectAlternativeName("localhost")
            .build()
    }

    private fun serverInfoResponse(httpsPort: Int, pairStatus: Int): MockResponse {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
                <hostname>test-host</hostname>
                <uniqueid>test-host-id</uniqueid>
                <HttpsPort>$httpsPort</HttpsPort>
                <ExternalPort>47989</ExternalPort>
                <LocalIP>127.0.0.1</LocalIP>
                <ExternalIP>127.0.0.1</ExternalIP>
                <mac>00:11:22:33:44:55</mac>
                <PairStatus>$pairStatus</PairStatus>
                <state>MJOLNIR_SERVER_AVAILABLE</state>
                <currentgame>0</currentgame>
                <appversion>7.1.0</appversion>
                <GfeVersion>3.0.0</GfeVersion>
            </root>
        """.trimIndent()
        return MockResponse.Builder().code(200).body(body).build()
    }

    private fun Throwable.hasCertificateFailure(): Boolean {
        val pending = ArrayDeque<Throwable>()
        val visited = HashSet<Throwable>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (current is CertificateException) return true
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return false
    }

    private class TestCryptoProvider(
        private val identity: HeldCertificate
    ) : LimelightCryptoProvider {
        override fun getClientCertificate(): X509Certificate = identity.certificate

        override fun getClientPrivateKey(): PrivateKey = identity.keyPair.private

        override fun getPemEncodedClientCertificate(): ByteArray =
            identity.certificatePem().toByteArray(Charsets.UTF_8)

        override fun encodeBase64String(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
    }
}
