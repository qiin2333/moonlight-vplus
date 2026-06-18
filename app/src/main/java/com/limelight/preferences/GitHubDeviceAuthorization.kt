package com.limelight.preferences

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.R

object GitHubDeviceAuthorization {
    private const val LOG_TAG = "DeveloperUnlock"

    fun savePendingDeviceCode(ctx: Context, deviceCode: GitHubStarVerifier.DeviceCode) {
        val expiresAtMs = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
        PreferenceManager.getDefaultSharedPreferences(ctx).edit {
            putString(DeveloperUnlockSettings.PREF_PENDING_DEVICE_CODE, deviceCode.deviceCode)
            putString(DeveloperUnlockSettings.PREF_PENDING_USER_CODE, deviceCode.userCode)
            putString(DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI, deviceCode.verificationUri)
            putString(
                DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI_COMPLETE,
                deviceCode.verificationUriComplete
            )
            putString(
                DeveloperUnlockSettings.PREF_PENDING_SCOPE,
                deviceCode.scope.preferenceValue
            )
            putLong(DeveloperUnlockSettings.PREF_PENDING_EXPIRES_AT_MS, expiresAtMs)
            putInt(DeveloperUnlockSettings.PREF_PENDING_INTERVAL_SECONDS, deviceCode.intervalSeconds)
        }
    }

    fun loadPendingDeviceCode(ctx: Context): GitHubStarVerifier.DeviceCode? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val expiresAtMs = prefs.getLong(DeveloperUnlockSettings.PREF_PENDING_EXPIRES_AT_MS, 0L)
        val remainingSeconds = ((expiresAtMs - System.currentTimeMillis()) / 1000L).toInt()
        if (remainingSeconds <= 0) {
            clearPendingDeviceCode(ctx)
            return null
        }

        val deviceCode = prefs.getString(DeveloperUnlockSettings.PREF_PENDING_DEVICE_CODE, null)
        val userCode = prefs.getString(DeveloperUnlockSettings.PREF_PENDING_USER_CODE, null)
        val verificationUri = prefs.getString(DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI, null)
        if (deviceCode.isNullOrBlank() || userCode.isNullOrBlank() || verificationUri.isNullOrBlank()) {
            clearPendingDeviceCode(ctx)
            return null
        }

        Log.i(LOG_TAG, "GitHub device authorization restored pending device code: userCode=$userCode")
        return GitHubStarVerifier.DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = prefs.getString(
                DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI_COMPLETE,
                null
            )?.takeIf { it.isNotBlank() },
            expiresInSeconds = remainingSeconds,
            intervalSeconds = prefs.getInt(DeveloperUnlockSettings.PREF_PENDING_INTERVAL_SECONDS, 5)
                .coerceAtLeast(1),
            scope = GitHubStarVerifier.OAuthScope.fromPreference(
                prefs.getString(DeveloperUnlockSettings.PREF_PENDING_SCOPE, null)
            ) ?: GitHubStarVerifier.OAuthScope.STAR_VERIFICATION
        )
    }

    fun clearPendingDeviceCode(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit {
            remove(DeveloperUnlockSettings.PREF_PENDING_DEVICE_CODE)
            remove(DeveloperUnlockSettings.PREF_PENDING_USER_CODE)
            remove(DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI)
            remove(DeveloperUnlockSettings.PREF_PENDING_VERIFICATION_URI_COMPLETE)
            remove(DeveloperUnlockSettings.PREF_PENDING_SCOPE)
            remove(DeveloperUnlockSettings.PREF_PENDING_EXPIRES_AT_MS)
            remove(DeveloperUnlockSettings.PREF_PENDING_INTERVAL_SECONDS)
        }
    }

    fun authorizationUrl(deviceCode: GitHubStarVerifier.DeviceCode): String {
        return deviceCode.verificationUriComplete ?: deviceCode.verificationUri
    }

    fun copyDeviceCodeToClipboard(
        ctx: Context,
        deviceCode: GitHubStarVerifier.DeviceCode,
        showToast: Boolean = true
    ) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    ctx.getString(R.string.developer_device_code_clip_label),
                    deviceCode.userCode
                )
            )
        }.onSuccess {
            if (showToast) {
                Toast.makeText(ctx, R.string.toast_developer_device_code_copied, Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Log.w(LOG_TAG, "Failed to copy GitHub device code", it)
        }
    }

    fun saveAuthorizedAccount(
        ctx: Context,
        accessToken: String,
        starCheck: GitHubStarVerifier.StarCheck,
        scope: GitHubStarVerifier.OAuthScope
    ) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit {
            putString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, accessToken)
            putString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN_SCOPE, scope.preferenceValue)
            starCheck.login?.let { putString(DeveloperUnlockSettings.PREF_USER_LOGIN, it) }
            if (starCheck.starred) {
                putBoolean(DeveloperUnlockSettings.PREF_UNLOCKED, true)
                putLong(DeveloperUnlockSettings.PREF_VERIFIED_AT_MS, System.currentTimeMillis())
            } else {
                putBoolean(DeveloperUnlockSettings.PREF_UNLOCKED, false)
                putLong(DeveloperUnlockSettings.PREF_VERIFIED_AT_MS, 0L)
            }
        }
    }
}
