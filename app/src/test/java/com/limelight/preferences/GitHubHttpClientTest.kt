package com.limelight.preferences

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubHttpClientTest {
    @Test
    fun oauthFormEncodesValuesAndReadsResponse() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("{\"device_code\":\"abc\"}").build())

            val result = GitHubHttpClient().form(
                server.url("/login/device/code").toString(),
                mapOf("client_id" to "a+b", "scope" to "public repo")
            )

            assertEquals(200, result.code)
            assertEquals("{\"device_code\":\"abc\"}", result.body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/json", request.headers["Accept"])
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("client_id=a%2Bb"))
            assertTrue(body.contains("scope=public+repo"))
        }
    }

    @Test
    fun apiKeepsRateLimitHeadersForErrorClassification() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(403)
                    .addHeader("Retry-After", "60")
                    .addHeader("X-RateLimit-Remaining", "0")
                    .body("{\"message\":\"secondary rate limit\"}")
                    .build()
            )

            val result = GitHubHttpClient().api(server.url("/user").toString(), "test-token")

            assertEquals(403, result.code)
            assertEquals("60", result.retryAfter)
            assertEquals("0", result.rateLimitRemaining)
            assertEquals("Bearer test-token", server.takeRequest().headers["Authorization"])
        }
    }
}
