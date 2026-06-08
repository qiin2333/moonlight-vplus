package com.limelight.preferences

import android.content.SharedPreferences
import androidx.core.content.edit

object DeveloperUnlockSettings {
    const val PREF_ENTRY = "pref_developer_unlock"
    const val PREF_UNLOCKED = "pref_developer_features_unlocked"
    const val PREF_ACCESS_TOKEN = "pref_developer_features_access_token"
    const val PREF_USER_LOGIN = "pref_developer_features_user_login"
    const val PREF_VERIFIED_AT_MS = "pref_developer_features_verified_at_ms"
    const val PREF_PENDING_DEVICE_CODE = "pref_developer_features_pending_device_code"
    const val PREF_PENDING_USER_CODE = "pref_developer_features_pending_user_code"
    const val PREF_PENDING_VERIFICATION_URI = "pref_developer_features_pending_verification_uri"
    const val PREF_PENDING_VERIFICATION_URI_COMPLETE = "pref_developer_features_pending_verification_uri_complete"
    const val PREF_PENDING_EXPIRES_AT_MS = "pref_developer_features_pending_expires_at_ms"
    const val PREF_PENDING_INTERVAL_SECONDS = "pref_developer_features_pending_interval_seconds"

    const val GITHUB_REPO_URL = "https://github.com/qiin2333/moonlight-vplus"

    private const val VERIFICATION_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    private const val LEGACY_PREF_STAR_UNLOCKED = "pref_framegen_star_unlocked"
    private const val LEGACY_PREF_STAR_ACCESS_TOKEN = "pref_framegen_star_access_token"
    private const val LEGACY_PREF_STAR_USER_LOGIN = "pref_framegen_star_user_login"
    private const val LEGACY_PREF_STAR_VERIFIED_AT_MS = "pref_framegen_star_verified_at_ms"
    private const val LEGACY_PREF_STAR_PENDING_DEVICE_CODE = "pref_framegen_star_pending_device_code"
    private const val LEGACY_PREF_STAR_PENDING_USER_CODE = "pref_framegen_star_pending_user_code"
    private const val LEGACY_PREF_STAR_PENDING_VERIFICATION_URI = "pref_framegen_star_pending_verification_uri"
    private const val LEGACY_PREF_STAR_PENDING_VERIFICATION_URI_COMPLETE =
        "pref_framegen_star_pending_verification_uri_complete"
    private const val LEGACY_PREF_STAR_PENDING_EXPIRES_AT_MS = "pref_framegen_star_pending_expires_at_ms"
    private const val LEGACY_PREF_STAR_PENDING_INTERVAL_SECONDS = "pref_framegen_star_pending_interval_seconds"

    fun migrateLegacyPrefs(prefs: SharedPreferences) {
        if (!prefs.contains(LEGACY_PREF_STAR_UNLOCKED) && !prefs.contains(LEGACY_PREF_STAR_ACCESS_TOKEN)) {
            return
        }

        prefs.edit {
            if (!prefs.contains(PREF_UNLOCKED)) {
                putBoolean(PREF_UNLOCKED, prefs.getBoolean(LEGACY_PREF_STAR_UNLOCKED, false))
            }
            if (!prefs.contains(PREF_ACCESS_TOKEN)) {
                prefs.getString(LEGACY_PREF_STAR_ACCESS_TOKEN, null)?.let {
                    putString(PREF_ACCESS_TOKEN, it)
                }
            }
            if (!prefs.contains(PREF_USER_LOGIN)) {
                prefs.getString(LEGACY_PREF_STAR_USER_LOGIN, null)?.let {
                    putString(PREF_USER_LOGIN, it)
                }
            }
            if (!prefs.contains(PREF_VERIFIED_AT_MS)) {
                putLong(PREF_VERIFIED_AT_MS, prefs.getLong(LEGACY_PREF_STAR_VERIFIED_AT_MS, 0L))
            }
            if (!prefs.contains(PREF_PENDING_DEVICE_CODE)) {
                prefs.getString(LEGACY_PREF_STAR_PENDING_DEVICE_CODE, null)?.let {
                    putString(PREF_PENDING_DEVICE_CODE, it)
                }
            }
            if (!prefs.contains(PREF_PENDING_USER_CODE)) {
                prefs.getString(LEGACY_PREF_STAR_PENDING_USER_CODE, null)?.let {
                    putString(PREF_PENDING_USER_CODE, it)
                }
            }
            if (!prefs.contains(PREF_PENDING_VERIFICATION_URI)) {
                prefs.getString(LEGACY_PREF_STAR_PENDING_VERIFICATION_URI, null)?.let {
                    putString(PREF_PENDING_VERIFICATION_URI, it)
                }
            }
            if (!prefs.contains(PREF_PENDING_VERIFICATION_URI_COMPLETE)) {
                prefs.getString(LEGACY_PREF_STAR_PENDING_VERIFICATION_URI_COMPLETE, null)?.let {
                    putString(PREF_PENDING_VERIFICATION_URI_COMPLETE, it)
                }
            }
            if (!prefs.contains(PREF_PENDING_EXPIRES_AT_MS)) {
                putLong(PREF_PENDING_EXPIRES_AT_MS, prefs.getLong(LEGACY_PREF_STAR_PENDING_EXPIRES_AT_MS, 0L))
            }
            if (!prefs.contains(PREF_PENDING_INTERVAL_SECONDS)) {
                putInt(
                    PREF_PENDING_INTERVAL_SECONDS,
                    prefs.getInt(LEGACY_PREF_STAR_PENDING_INTERVAL_SECONDS, 5)
                )
            }

            remove(LEGACY_PREF_STAR_PENDING_DEVICE_CODE)
            remove(LEGACY_PREF_STAR_PENDING_USER_CODE)
            remove(LEGACY_PREF_STAR_PENDING_VERIFICATION_URI)
            remove(LEGACY_PREF_STAR_PENDING_VERIFICATION_URI_COMPLETE)
            remove(LEGACY_PREF_STAR_PENDING_EXPIRES_AT_MS)
            remove(LEGACY_PREF_STAR_PENDING_INTERVAL_SECONDS)
        }
    }

    fun isUnlocked(prefs: SharedPreferences): Boolean {
        migrateLegacyPrefs(prefs)
        if (!GitHubStarVerifier.isConfigured() || !prefs.getBoolean(PREF_UNLOCKED, false)) {
            return false
        }
        val verifiedAtMs = prefs.getLong(PREF_VERIFIED_AT_MS, 0L)
        val ageMs = System.currentTimeMillis() - verifiedAtMs
        return verifiedAtMs > 0L && ageMs in 0L..VERIFICATION_MAX_AGE_MS
    }
}
