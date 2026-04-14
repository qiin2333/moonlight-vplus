package com.limelight.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.limelight.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * 统计分析管理器
 * 负责记录应用使用时长等统计事件
 */
class AnalyticsManager private constructor(context: Context) {

    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var applicationContext: Context? = null
    private var scheduler: ScheduledExecutorService? = null
    private var sessionStartTime: Long = 0
    private var isSessionActive = false

    init {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Analytics disabled in debug build")
        } else {
            try {
                applicationContext = context.applicationContext
                firebaseAnalytics = FirebaseAnalytics.getInstance(context)
                scheduler = Executors.newScheduledThreadPool(1)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize Firebase Analytics: ${e.message}")
            }
        }
    }

    private fun canExecuteAnalytics(): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Analytics disabled in debug build")
            return false
        }

        if (firebaseAnalytics == null) {
            Log.w(TAG, "Firebase Analytics not initialized")
            return false
        }

        try {
            if (applicationContext != null) {
                @Suppress("DEPRECATION")
                val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val analyticsEnabled = prefs.getBoolean("checkbox_enable_analytics", true)
                if (!analyticsEnabled) {
                    Log.d(TAG, "Analytics disabled by user preference")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check analytics preference: ${e.message}")
        }

        return true
    }

    @SuppressLint("InvalidAnalyticsName")
    fun startUsageTracking() {
        if (!canExecuteAnalytics()) return

        if (isSessionActive) {
            Log.w(TAG, "Usage tracking already active")
            return
        }

        sessionStartTime = System.currentTimeMillis()
        isSessionActive = true

        val bundle = Bundle()
        bundle.putString("session_type", "app_usage")
        firebaseAnalytics?.logEvent("session_start", bundle)

        Log.d(TAG, "Usage tracking started")
    }

    fun stopUsageTracking() {
        if (!canExecuteAnalytics()) return

        if (!isSessionActive) {
            Log.w(TAG, "Usage tracking not active")
            return
        }

        val sessionDuration = System.currentTimeMillis() - sessionStartTime
        isSessionActive = false

        val bundle = Bundle()
        bundle.putString("session_type", "app_usage")
        bundle.putLong("session_duration_ms", sessionDuration)
        bundle.putLong("session_duration_minutes", sessionDuration / (1000 * 60))
        firebaseAnalytics?.logEvent("session_end", bundle)

        Log.d(TAG, "Usage tracking stopped, duration: ${sessionDuration / 1000} seconds")
    }

    fun logGameStreamStart(computerName: String, appName: String?) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream start disabled: $computerName, app: $appName")
            return
        }

        val bundle = Bundle()
        bundle.putString("computer_name", computerName)
        bundle.putString("app_name", appName ?: "unknown")
        bundle.putString("stream_type", "game")
        firebaseAnalytics?.logEvent("game_stream_start", bundle)

        Log.d(TAG, "Game stream started for: $computerName, app: $appName")
    }

    fun logGameStreamEnd(computerName: String, appName: String?, durationMs: Long) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream end disabled: $computerName, app: $appName, duration: ${durationMs / 1000} seconds")
            return
        }

        val bundle = Bundle()
        bundle.putString("computer_name", computerName)
        bundle.putString("app_name", appName ?: "unknown")
        bundle.putString("stream_type", "game")
        bundle.putLong("stream_duration_ms", durationMs)
        bundle.putLong("stream_duration_minutes", durationMs / (1000 * 60))
        firebaseAnalytics?.logEvent("game_stream_end", bundle)
        markFirstStreamCompletedIfNeeded()
        Log.d(TAG, "Game stream ended for: $computerName, app: $appName, duration: ${durationMs / 1000} seconds")
    }

    private fun markFirstStreamCompletedIfNeeded() {
        if (!canExecuteAnalytics() || applicationContext == null) return
        try {
            @Suppress("DEPRECATION")
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
            if (!prefs.getBoolean(PREF_FIRST_STREAM_DONE, false)) {
                prefs.edit().putBoolean(PREF_FIRST_STREAM_DONE, true).apply()
                setUserProperty(USER_PROP_FIRST_STREAM_DONE, "true")
                Log.d(TAG, "Retention: has_completed_first_stream set")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark first stream completed: ${e.message}")
        }
    }

    fun logGameStreamEnd(
        computerName: String, appName: String?, effectiveDurationMs: Long,
        decoderMessage: String?, resolutionWidth: Int, resolutionHeight: Int,
        averageEndToEndLatency: Int, averageDecoderLatency: Int
    ) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream end disabled: $computerName, app: $appName, effective duration: ${effectiveDurationMs / 1000} seconds")
            return
        }

        val bundle = Bundle()
        bundle.putString("computer_name", computerName)
        bundle.putString("app_name", appName ?: "unknown")
        bundle.putString("stream_type", "game")
        bundle.putLong("effective_stream_duration_ms", effectiveDurationMs)
        bundle.putLong("effective_stream_duration_seconds", effectiveDurationMs / 1000)
        bundle.putLong("effective_stream_duration_minutes", effectiveDurationMs / (1000 * 60))
        bundle.putLong("stream_duration_ms", effectiveDurationMs)
        bundle.putLong("stream_duration_minutes", effectiveDurationMs / (1000 * 60))
        bundle.putString("decoder", decoderMessage ?: "unknown")
        bundle.putString("resolution", "${resolutionWidth}x$resolutionHeight")
        bundle.putInt("average_end_to_end_latency_ms", averageEndToEndLatency)
        bundle.putInt("average_decoder_latency_ms", averageDecoderLatency)

        firebaseAnalytics?.logEvent("game_stream_end", bundle)
        markFirstStreamCompletedIfNeeded()
        Log.d(TAG, "Game stream ended for: $computerName, app: $appName, effective duration: ${effectiveDurationMs / 1000} seconds")
    }

    private fun updateRetentionUserProperties() {
        if (applicationContext == null || firebaseAnalytics == null) return
        try {
            @Suppress("DEPRECATION")
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val firstOpen = prefs.getString(PREF_FIRST_OPEN_DATE, null)
            if (firstOpen == null) {
                prefs.edit().putString(PREF_FIRST_OPEN_DATE, dateStr).apply()
                setUserProperty(USER_PROP_FIRST_OPEN_DATE, dateStr)
                Log.d(TAG, "Retention: first_open_date set to $dateStr")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update retention user properties: ${e.message}")
        }
    }

    fun logAppLaunch() {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "App launch disabled")
            return
        }
        updateRetentionUserProperties()
        val bundle = Bundle()
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.APP_OPEN, bundle)
        Log.d(TAG, "App launch logged")
    }

    fun logCustomEvent(eventName: String, parameters: Bundle?) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Custom event disabled: $eventName")
            return
        }

        firebaseAnalytics?.logEvent(eventName, parameters)
        Log.d(TAG, "Custom event logged: $eventName")
    }

    fun setUserProperty(propertyName: String, propertyValue: String) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "User property disabled: $propertyName = $propertyValue")
            return
        }

        firebaseAnalytics?.setUserProperty(propertyName, propertyValue)
        Log.d(TAG, "User property set: $propertyName = $propertyValue")
    }

    fun isSessionActive(): Boolean = isSessionActive

    fun getCurrentSessionDuration(): Long {
        if (!isSessionActive) return 0
        return System.currentTimeMillis() - sessionStartTime
    }

    fun cleanup() {
        if (scheduler != null && scheduler?.isShutdown != true) {
            scheduler?.shutdown()
        }
    }

    companion object {
        private const val TAG = "AnalyticsManager"
        private const val PREF_FIRST_OPEN_DATE = "analytics_first_open_date"
        private const val PREF_FIRST_STREAM_DONE = "analytics_first_stream_done"
        private const val USER_PROP_FIRST_OPEN_DATE = "first_open_date"
        private const val USER_PROP_FIRST_STREAM_DONE = "has_completed_first_stream"

        @Volatile
        private var instance: AnalyticsManager? = null

        @Synchronized
        fun getInstance(context: Context): AnalyticsManager {
            if (instance == null) {
                instance = AnalyticsManager(context.applicationContext)
            }
            return instance!!
        }
    }
}
