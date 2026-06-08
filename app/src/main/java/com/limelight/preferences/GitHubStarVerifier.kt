package com.limelight.preferences

import com.limelight.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

object GitHubStarVerifier {
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val API_USER_URL = "https://api.github.com/user"
    private const val API_VERSION = "2022-11-28"
    private const val REPO_OWNER = "qiin2333"
    private const val REPO_NAME = "moonlight-vplus"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    )

    data class StarCheck(
        val starred: Boolean,
        val login: String?
    )

    sealed class TokenPollResult {
        data class Authorized(val accessToken: String) : TokenPollResult()
        object Pending : TokenPollResult()
        data class SlowDown(val intervalSeconds: Int) : TokenPollResult()
        data class Failed(val message: String) : TokenPollResult()
    }

    fun isConfigured(): Boolean =
        BuildConfig.GITHUB_OAUTH_CLIENT_ID.isNotBlank()

    @Throws(IOException::class)
    fun requestDeviceCode(): DeviceCode {
        ensureConfigured()
        val response = postForm(
            DEVICE_CODE_URL,
            mapOf(
                "client_id" to BuildConfig.GITHUB_OAUTH_CLIENT_ID,
                "scope" to ""
            )
        )
        if (response.code != HttpURLConnection.HTTP_OK) {
            throw IOException(errorMessage(response.body, "GitHub device code request failed (${response.code})"))
        }

        val json = JSONObject(response.body)
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            verificationUriComplete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optInt("expires_in", 900),
            intervalSeconds = max(1, json.optInt("interval", 5))
        )
    }

    @Throws(IOException::class)
    fun pollAccessToken(deviceCode: DeviceCode): TokenPollResult {
        ensureConfigured()
        val response = postForm(
            ACCESS_TOKEN_URL,
            mapOf(
                "client_id" to BuildConfig.GITHUB_OAUTH_CLIENT_ID,
                "device_code" to deviceCode.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            )
        )
        val json = if (response.body.isNotBlank()) JSONObject(response.body) else JSONObject()
        val accessToken = json.optString("access_token")
        if (response.code == HttpURLConnection.HTTP_OK && accessToken.isNotBlank()) {
            return TokenPollResult.Authorized(accessToken)
        }

        return when (val error = json.optString("error")) {
            "authorization_pending" -> TokenPollResult.Pending
            "slow_down" -> TokenPollResult.SlowDown(max(deviceCode.intervalSeconds + 5, json.optInt("interval", 0)))
            "expired_token" -> TokenPollResult.Failed("GitHub authorization code expired")
            "access_denied" -> TokenPollResult.Failed("GitHub authorization was cancelled")
            "device_flow_disabled" -> TokenPollResult.Failed("GitHub OAuth Device Flow is disabled for this client ID")
            else -> TokenPollResult.Failed(errorMessage(response.body, "GitHub authorization failed (${response.code}, $error)"))
        }
    }

    @Throws(IOException::class)
    fun checkStar(accessToken: String): StarCheck {
        val login = fetchLogin(accessToken)
        val response = get(
            "https://api.github.com/user/starred/$REPO_OWNER/$REPO_NAME",
            accessToken
        )
        return when (response.code) {
            HttpURLConnection.HTTP_NO_CONTENT -> StarCheck(starred = true, login = login)
            HttpURLConnection.HTTP_NOT_FOUND -> StarCheck(starred = false, login = login)
            HttpURLConnection.HTTP_UNAUTHORIZED -> throw IOException("GitHub authorization expired")
            HttpURLConnection.HTTP_FORBIDDEN -> throw IOException(errorMessage(response.body, "GitHub denied the star check"))
            else -> throw IOException(errorMessage(response.body, "GitHub star check failed (${response.code})"))
        }
    }

    private fun fetchLogin(accessToken: String): String? {
        return try {
            val response = get(API_USER_URL, accessToken)
            if (response.code == HttpURLConnection.HTTP_OK && response.body.isNotBlank()) {
                JSONObject(response.body).optString("login").takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    private fun ensureConfigured() {
        if (!isConfigured()) {
            throw IOException("GitHub OAuth client ID is not configured")
        }
    }

    @Throws(IOException::class)
    private fun postForm(urlString: String, values: Map<String, String>): HttpResponse {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }
        return connection.readResponse()
    }

    @Throws(IOException::class)
    private fun get(urlString: String, accessToken: String): HttpResponse {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        return connection.readResponse()
    }

    private fun HttpURLConnection.readResponse(): HttpResponse {
        val responseCode = responseCode
        val stream = if (responseCode in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        disconnect()
        return HttpResponse(responseCode, body)
    }

    private fun errorMessage(body: String, fallback: String): String {
        if (body.isBlank()) {
            return fallback
        }
        return try {
            val json = JSONObject(body)
            json.optString("error_description").takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class HttpResponse(val code: Int, val body: String)
}
