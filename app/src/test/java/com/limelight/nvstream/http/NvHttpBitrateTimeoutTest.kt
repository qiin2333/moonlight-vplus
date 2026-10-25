package com.limelight.nvstream.http

import java.io.IOException
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NvHttpBitrateTimeoutTest {
    @Test(timeout = 10_000)
    fun stalledBitrateResponseTimesOutAndNextAdjustmentSucceeds() {
        withHost(timeoutMs = 200) { host ->
            val http = host.client()
            host.warmUp(http)
            host.https.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

            assertBoundedFailure { http.setBitrate(8_000) }

            host.assertBitrateRequest(8_000)
            host.https.enqueue(bitrateResponse())
            assertTrue(http.setBitrate(12_000))
            host.assertBitrateRequest(12_000)
        }
    }

    @Test(timeout = 10_000)
    fun continuouslyArrivingBodyCannotKeepAdjustmentAliveAndNextRequestSucceeds() {
        withHost(timeoutMs = 250) { host ->
            val http = host.client()
            host.warmUp(http)
            host.https.enqueue(
                MockResponse.Builder()
                    .body(BITRATE_BODY)
                    // Each byte arrives well within the read timeout, but the
                    // whole response exceeds the manual-adjustment deadline.
                    .throttleBody(1, 50, TimeUnit.MILLISECONDS)
                    .build()
            )

            assertBoundedFailure { http.setBitrate(8_000) }

            host.assertBitrateRequest(8_000)
            host.https.enqueue(bitrateResponse())
            assertTrue(http.setBitrate(12_000))
            host.assertBitrateRequest(12_000)
        }
    }

    @Test(timeout = 10_000)
    fun unknownHttpsPortDiscoverySharesAdjustmentDeadline() {
        withHost(timeoutMs = 200) { host ->
            val http = host.client(httpsPort = 0)
            host.http.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

            assertBoundedFailure { http.setBitrate(8_000) }

            val discovery = host.http.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("The host must receive the HTTPS-port discovery request", discovery)
            assertEquals("/serverinfo", discovery!!.url.encodedPath)
            assertEquals(0, host.https.requestCount)

            // Recover through the same NvHTTP instance. The ordinary serverinfo
            // request also primes TLS before the next short adjustment deadline.
            host.http.enqueue(host.serverInfoResponse())
            host.warmUp(http)
            host.https.enqueue(bitrateResponse())
            assertTrue(http.setBitrate(12_000))
            host.assertBitrateRequest(12_000)
        }
    }

    private fun assertBoundedFailure(request: () -> Boolean) {
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { request() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("The adjustment took ${elapsedMs}ms", elapsedMs < 1_500)
    }

    private fun withHost(timeoutMs: Int, test: (Host) -> Unit) {
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                val serverIdentity = certificate("host")
                val certificates = HandshakeCertificates.Builder()
                    .heldCertificate(serverIdentity)
                    .build()
                https.useHttps(certificates.sslSocketFactory())
                http.start()
                https.start()
                test(Host(http, https, serverIdentity, timeoutMs))
            }
        }
    }

    private class Host(
        val http: MockWebServer,
        val https: MockWebServer,
        private val identity: HeldCertificate,
        private val timeoutMs: Int
    ) {
        fun client(httpsPort: Int = https.port): NvHTTP = NvHTTP(
            ComputerDetails.AddressTuple(http.hostName, http.port),
            httpsPort,
            "test-client-id",
            "test-client",
            identity.certificate,
            TestCryptoProvider(certificate("client")),
            NvHTTP.TimeoutConfig(
                connectTimeoutMs = 1_000,
                readTimeoutMs = 5_000,
                shortConnectTimeoutMs = 1_000,
                bitrateCallTimeoutMs = timeoutMs
            )
        )

        fun warmUp(client: NvHTTP) {
            https.enqueue(serverInfoResponse())
            client.getServerInfo(true)
            assertEquals("/serverinfo", https.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
        }

        fun serverInfoResponse(): MockResponse = MockResponse.Builder().body(
            "<root status_code=\"200\"><appversion>7.1.0</appversion>" +
                "<HttpsPort>${https.port}</HttpsPort></root>"
        ).build()

        fun assertBitrateRequest(bitrate: Int) {
            val request = https.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("The host must receive the bitrate request", request)
            assertEquals("/bitrate", request!!.url.encodedPath)
            assertEquals(bitrate.toString(), request.url.queryParameter("bitrate"))
        }
    }

    private class TestCryptoProvider(private val identity: HeldCertificate) : LimelightCryptoProvider {
        override fun getClientCertificate(): X509Certificate = identity.certificate

        override fun getClientPrivateKey(): PrivateKey = identity.keyPair.private

        override fun getPemEncodedClientCertificate(): ByteArray =
            identity.certificatePem().toByteArray(Charsets.UTF_8)

        override fun encodeBase64String(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
    }

    private companion object {
        const val BITRATE_BODY = "<root status_code=\"200\"><bitrate>1</bitrate></root>"

        fun bitrateResponse(): MockResponse = MockResponse.Builder().body(BITRATE_BODY).build()

        fun certificate(commonName: String): HeldCertificate = HeldCertificate.Builder()
            .commonName(commonName)
            .addSubjectAlternativeName("localhost")
            .build()
    }
}
