package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.limelight.BuildConfig;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 统计分析管理器
 * 负责记录应用使用时长等统计事件
 */
public class AnalyticsManager {
    private static final String TAG = "AnalyticsManager";
    /** 留存分析：首次打开日期（用于 GA4 按获客日期分群） */
    private static final String PREF_FIRST_OPEN_DATE = "analytics_first_open_date";
    /** 留存分析：是否已完成首次串流（用于「已激活用户」留存分群） */
    private static final String PREF_FIRST_STREAM_DONE = "analytics_first_stream_done";
    /** 用户属性：首次打开日期，格式 yyyy-MM-dd，便于在 Firebase 中按获客日/周看留存 */
    private static final String USER_PROP_FIRST_OPEN_DATE = "first_open_date";
    /** 用户属性：是否已完成首次游戏串流，用于区分「仅打开过应用」与「真正用过串流」的留存 */
    private static final String USER_PROP_FIRST_STREAM_DONE = "has_completed_first_stream";

    private static AnalyticsManager instance;
    private FirebaseAnalytics firebaseAnalytics;
    private Context applicationContext;
    private ScheduledExecutorService scheduler;
    private long sessionStartTime;
    private boolean isSessionActive = false;
    
    private AnalyticsManager(Context context) {
        // 在debug版本中不初始化Firebase Analytics
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Analytics disabled in debug build");
            return;
        }
        
        try {
            this.applicationContext = context.getApplicationContext();
            firebaseAnalytics = FirebaseAnalytics.getInstance(context);
            scheduler = Executors.newScheduledThreadPool(1);
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize Firebase Analytics: " + e.getMessage());
        }
    }
    
    public static synchronized AnalyticsManager getInstance(Context context) {
        if (instance == null) {
            instance = new AnalyticsManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 检查是否可以执行Analytics操作
     * @return true如果可以在release版本中执行，false如果在debug版本中或未初始化
     */
    private boolean canExecuteAnalytics() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Analytics disabled in debug build");
            return false;
        }
        
        if (firebaseAnalytics == null) {
            Log.w(TAG, "Firebase Analytics not initialized");
            return false;
        }
        
        // 检查用户是否启用了统计
        try {
            if (applicationContext != null) {
                android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext);
                boolean analyticsEnabled = prefs.getBoolean("checkbox_enable_analytics", true);
                if (!analyticsEnabled) {
                    Log.d(TAG, "Analytics disabled by user preference");
                    return false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check analytics preference: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * 开始记录使用时长
     */
    @SuppressLint("InvalidAnalyticsName")
    public void startUsageTracking() {
        if (!canExecuteAnalytics()) {
            return;
        }
        
        if (isSessionActive) {
            Log.w(TAG, "Usage tracking already active");
            return;
        }
        
        sessionStartTime = System.currentTimeMillis();
        isSessionActive = true;
        
        // 记录会话开始事件
        Bundle bundle = new Bundle();
        bundle.putString("session_type", "app_usage");
        firebaseAnalytics.logEvent("session_start", bundle);
        
        Log.d(TAG, "Usage tracking started");
    }
    
    /**
     * 停止记录使用时长
     */
    public void stopUsageTracking() {
        if (!canExecuteAnalytics()) {
            return;
        }
        
        if (!isSessionActive) {
            Log.w(TAG, "Usage tracking not active");
            return;
        }
        
        long sessionDuration = System.currentTimeMillis() - sessionStartTime;
        isSessionActive = false;
        
        // 记录会话结束事件和使用时长
        Bundle bundle = new Bundle();
        bundle.putString("session_type", "app_usage");
        bundle.putLong("session_duration_ms", sessionDuration);
        bundle.putLong("session_duration_minutes", sessionDuration / (1000 * 60));
        firebaseAnalytics.logEvent("session_end", bundle);
        
        Log.d(TAG, "Usage tracking stopped, duration: " + (sessionDuration / 1000) + " seconds");
    }
    
    /**
     * 记录游戏流媒体开始事件
     */
    public void logGameStreamStart(String computerName, String appName) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream start disabled: " + computerName + ", app: " + appName);
            return;
        }
        
        Bundle bundle = new Bundle();
        bundle.putString("computer_name", computerName);
        bundle.putString("app_name", appName != null ? appName : "unknown");
        bundle.putString("stream_type", "game");
        firebaseAnalytics.logEvent("game_stream_start", bundle);
        
        Log.d(TAG, "Game stream started for: " + computerName + ", app: " + appName);
    }
    
    /**
     * 记录游戏流媒体结束事件
     */
    public void logGameStreamEnd(String computerName, String appName, long durationMs) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream end disabled: " + computerName + ", app: " + appName + ", duration: " + (durationMs / 1000) + " seconds");
            return;
        }
        
        Bundle bundle = new Bundle();
        bundle.putString("computer_name", computerName);
        bundle.putString("app_name", appName != null ? appName : "unknown");
        bundle.putString("stream_type", "game");
        bundle.putLong("stream_duration_ms", durationMs);
        bundle.putLong("stream_duration_minutes", durationMs / (1000 * 60));
        firebaseAnalytics.logEvent("game_stream_end", bundle);
        markFirstStreamCompletedIfNeeded();
        Log.d(TAG, "Game stream ended for: " + computerName + ", app: " + appName + ", duration: " + (durationMs / 1000) + " seconds");
    }

    /**
     * 若用户首次完成游戏串流，则设置用户属性 has_completed_first_stream，便于在 Firebase 中看「已激活用户」的留存。
     */
    private void markFirstStreamCompletedIfNeeded() {
        if (!canExecuteAnalytics() || applicationContext == null) {
            return;
        }
        try {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext);
            if (!prefs.getBoolean(PREF_FIRST_STREAM_DONE, false)) {
                prefs.edit().putBoolean(PREF_FIRST_STREAM_DONE, true).apply();
                setUserProperty(USER_PROP_FIRST_STREAM_DONE, "true");
                Log.d(TAG, "Retention: has_completed_first_stream set");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to mark first stream completed: " + e.getMessage());
        }
    }
    
    /**
     * 记录游戏流媒体结束事件（包含性能数据）
     * 
     * @param computerName 主机名称
     * @param appName 应用名称
     * @param effectiveDurationMs 有效串流时长（排除后台暂停时间）
     * @param decoderMessage 解码器信息
     * @param resolutionWidth 分辨率宽度
     * @param resolutionHeight 分辨率高度
     * @param averageEndToEndLatency 平均端到端延迟
     * @param averageDecoderLatency 平均解码延迟
     */
    public void logGameStreamEnd(String computerName, String appName, long effectiveDurationMs, 
                                String decoderMessage, int resolutionWidth, int resolutionHeight,
                                int averageEndToEndLatency, int averageDecoderLatency) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Game stream end disabled: " + computerName + ", app: " + appName + 
                    ", effective duration: " + (effectiveDurationMs / 1000) + " seconds");
            return;
        }
        
        Bundle bundle = new Bundle();
        bundle.putString("computer_name", computerName);
        bundle.putString("app_name", appName != null ? appName : "unknown");
        bundle.putString("stream_type", "game");
        
        // 有效串流时长（排除后台暂停时间，更准确反映实际使用时间）
        bundle.putLong("effective_stream_duration_ms", effectiveDurationMs);
        bundle.putLong("effective_stream_duration_seconds", effectiveDurationMs / 1000);
        bundle.putLong("effective_stream_duration_minutes", effectiveDurationMs / (1000 * 60));
        
        // 保留原有字段名以保持向后兼容
        bundle.putLong("stream_duration_ms", effectiveDurationMs);
        bundle.putLong("stream_duration_minutes", effectiveDurationMs / (1000 * 60));
        
        // 性能数据
        bundle.putString("decoder", decoderMessage != null ? decoderMessage : "unknown");
        bundle.putString("resolution", resolutionWidth + "x" + resolutionHeight);
        bundle.putInt("average_end_to_end_latency_ms", averageEndToEndLatency);
        bundle.putInt("average_decoder_latency_ms", averageDecoderLatency);
            
        firebaseAnalytics.logEvent("game_stream_end", bundle);
        markFirstStreamCompletedIfNeeded();
        Log.d(TAG, "Game stream ended for: " + computerName + ", app: " + appName + 
                ", effective duration: " + (effectiveDurationMs / 1000) + " seconds");
    }
    
    /**
     * 更新留存分析相关的用户属性（首次打开日期等），便于在 Firebase/GA4 中按获客日看留存。
     */
    private void updateRetentionUserProperties() {
        if (applicationContext == null || firebaseAnalytics == null) {
            return;
        }
        try {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext);
            String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String firstOpen = prefs.getString(PREF_FIRST_OPEN_DATE, null);
            if (firstOpen == null) {
                prefs.edit().putString(PREF_FIRST_OPEN_DATE, dateStr).apply();
                setUserProperty(USER_PROP_FIRST_OPEN_DATE, dateStr);
                Log.d(TAG, "Retention: first_open_date set to " + dateStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update retention user properties: " + e.getMessage());
        }
    }

    /**
     * 记录应用启动事件，并更新留存相关用户属性（如首次打开日期）。
     */
    public void logAppLaunch() {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "App launch disabled");
            return;
        }
        updateRetentionUserProperties();
        Bundle bundle = new Bundle();
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.APP_OPEN, bundle);
        Log.d(TAG, "App launch logged");
    }
    
    /**
     * 记录自定义事件
     */
    public void logCustomEvent(String eventName, Bundle parameters) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Custom event disabled: " + eventName);
            return;
        }
        
        firebaseAnalytics.logEvent(eventName, parameters);
        Log.d(TAG, "Custom event logged: " + eventName);
    }
    
    /**
     * 设置用户属性
     */
    public void setUserProperty(String propertyName, String propertyValue) {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "User property disabled: " + propertyName + " = " + propertyValue);
            return;
        }
        
        firebaseAnalytics.setUserProperty(propertyName, propertyValue);
        Log.d(TAG, "User property set: " + propertyName + " = " + propertyValue);
    }
    
    /**
     * 获取当前会话是否活跃
     */
    public boolean isSessionActive() {
        return isSessionActive;
    }
    
    /**
     * 获取当前会话时长（毫秒）
     */
    public long getCurrentSessionDuration() {
        if (!isSessionActive) {
            return 0;
        }
        return System.currentTimeMillis() - sessionStartTime;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
} 