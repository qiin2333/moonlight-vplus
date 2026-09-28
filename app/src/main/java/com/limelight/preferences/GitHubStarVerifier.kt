package com.limelight.preferences

import com.limelight.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.math.max

object GitHubStarVerifier {
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val API_USER_URL = "https://api.github.com/user"
    private const val REPO_OWNER = "qiin2333"
    private const val REPO_NAME = "moonlight-vplus"
    private val http = GitHubHttpClient()

    enum class OAuthScope(val preferenceValue: String, val requestValue: String) {
        STAR_VERIFICATION("star", ""),
        CROWN_STORE_PUBLISH("crown_store_publish", "public_repo");

        fun grants(requiredScope: OAuthScope): Boolean {
            return this == requiredScope || this == CROWN_STORE_PUBLISH
        }

        companion object {
            fun fromPreference(value: String?): OAuthScope? {
                return values().firstOrNull { it.preferenceValue == value }
            }
        }
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
        val scope: OAuthScope = OAuthScope.STAR_VERIFICATION
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
    fun requestDeviceCode(scope: OAuthScope = OAuthScope.STAR_VERIFICATION): DeviceCode {
        ensureConfigured()
        val formValues = mutableMapOf(
            "client_id" to BuildConfig.GITHUB_OAUTH_CLIENT_ID
        )
        if (scope.requestValue.isNotBlank()) {
            formValues["scope"] = scope.requestValue
        }
        val response = http.form(
            DEVICE_CODE_URL,
            formValues
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
            intervalSeconds = max(1, json.optInt("interval", 5)),
            scope = scope
        )
    }

    @Throws(IOException::class)
    fun pollAccessToken(deviceCode: DeviceCode): TokenPollResult {
        ensureConfigured()
        val response = http.form(
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
        val response = http.api(
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
            val response = http.api(API_USER_URL, accessToken)
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

}
