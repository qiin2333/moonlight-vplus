package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.limelight.BuildConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 统计分析管理器
 * 负责记录应用使用时长等统计事件
 */
public class AnalyticsManager {
    private static final String TAG = "AnalyticsManager";
    
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
        
        Log.d(TAG, "Game stream ended for: " + computerName + ", app: " + appName + ", duration: " + (durationMs / 1000) + " seconds");
    }
    
    /**
     * 记录游戏流媒体结束事件（包含性能数据）
     */
    public void logGameStreamEnd(String computerName, String appName, long durationMs, 
                                String decoderMessage, int resolutionWidth, int resolutionHeight,
                                int averageEndToEndLatency, int averageDecoderLatency) {
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
        
        // 性能数据
        bundle.putString("decoder", decoderMessage != null ? decoderMessage : "unknown");
        bundle.putString("resolution", resolutionWidth + "x" + resolutionHeight);
        bundle.putInt("average_end_to_end_latency_ms", averageEndToEndLatency);
        bundle.putInt("average_decoder_latency_ms", averageDecoderLatency);
            
        firebaseAnalytics.logEvent("game_stream_end", bundle);
    }
    
    /**
     * 记录应用启动事件
     */
    public void logAppLaunch() {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "App launch disabled");
            return;
        }
        
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