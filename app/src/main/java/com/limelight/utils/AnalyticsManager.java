package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.limelight.BuildConfig;

import java.util.Map;
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
     * 上报设备信息
     * 在应用首次启动或设备信息发生变化时调用
     */
    public void reportDeviceInfo() {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Device info reporting disabled");
            return;
        }
        
        try {
            // 收集设备信息
            Map<String, String> deviceInfo = DeviceInfoCollector.collectAllDeviceInfo(applicationContext);
            
            // 设置用户属性（设备指纹）
            String deviceFingerprint = DeviceInfoCollector.generateDeviceFingerprint();
            setUserProperty("device_fingerprint", deviceFingerprint);
            
            // 记录设备信息事件
            Bundle bundle = new Bundle();
            for (Map.Entry<String, String> entry : deviceInfo.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                
                // Firebase Analytics对参数名和值有长度限制
                if (key.length() > 40) {
                    key = key.substring(0, 40);
                }
                if (value != null && value.length() > 100) {
                    value = value.substring(0, 100);
                }
                
                bundle.putString(key, value);
            }
            
            firebaseAnalytics.logEvent("device_info", bundle);
            
            // 单独上报SOC信息（如果可用）
            if (deviceInfo.containsKey("soc_manufacturer") || deviceInfo.containsKey("soc_model")) {
                Bundle socBundle = new Bundle();
                if (deviceInfo.containsKey("soc_manufacturer")) {
                    socBundle.putString("soc_manufacturer", deviceInfo.get("soc_manufacturer"));
                }
                if (deviceInfo.containsKey("soc_model")) {
                    socBundle.putString("soc_model", deviceInfo.get("soc_model"));
                }
                if (deviceInfo.containsKey("media_performance_class")) {
                    socBundle.putString("performance_class", deviceInfo.get("media_performance_class"));
                }
                firebaseAnalytics.logEvent("soc_info", socBundle);
            }
            
            Log.d(TAG, "Device info reported successfully");
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to report device info: " + e.getMessage());
        }
    }
    
    /**
     * 上报设备性能信息
     * 包括内存、CPU核心数等性能相关数据
     */
    public void reportDevicePerformance() {
        if (!canExecuteAnalytics()) {
            Log.d(TAG, "Device performance reporting disabled");
            return;
        }
        
        try {
            Map<String, String> deviceInfo = DeviceInfoCollector.collectAllDeviceInfo(applicationContext);
            
            Bundle bundle = new Bundle();
            
            // 内存信息
            if (deviceInfo.containsKey("total_memory_mb")) {
                bundle.putString("total_memory_mb", deviceInfo.get("total_memory_mb"));
            }
            
            // CPU信息
            if (deviceInfo.containsKey("processor_count")) {
                bundle.putString("cpu_cores", deviceInfo.get("processor_count"));
            }
            if (deviceInfo.containsKey("processor_model")) {
                bundle.putString("cpu_model", deviceInfo.get("processor_model"));
            }
            
            // GPU信息
            if (deviceInfo.containsKey("gpu_type")) {
                bundle.putString("gpu_type", deviceInfo.get("gpu_type"));
            }
            
            // 屏幕信息
            if (deviceInfo.containsKey("screen_density")) {
                bundle.putString("screen_density", deviceInfo.get("screen_density"));
            }
            
            firebaseAnalytics.logEvent("device_performance", bundle);
            
            Log.d(TAG, "Device performance reported successfully");
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to report device performance: " + e.getMessage());
        }
    }
    
    /**
     * 检查是否需要重新上报设备信息
     * 基于SharedPreferences中的时间戳判断
     */
    public boolean shouldReportDeviceInfo() {
        if (applicationContext == null) {
            return false;
        }
        
        try {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext);
            long lastReportTime = prefs.getLong("last_device_info_report", 0);
            long currentTime = System.currentTimeMillis();
            
            // 如果从未上报过，或者距离上次上报超过7天，则需要重新上报
            return lastReportTime == 0 || (currentTime - lastReportTime) > (7 * 24 * 60 * 60 * 1000);
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to check device info report status: " + e.getMessage());
            return true; // 出错时默认需要上报
        }
    }
    
    /**
     * 标记设备信息已上报
     */
    private void markDeviceInfoReported() {
        if (applicationContext == null) {
            return;
        }
        
        try {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext);
            prefs.edit().putLong("last_device_info_report", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to mark device info as reported: " + e.getMessage());
        }
    }
    
    /**
     * 智能上报设备信息
     * 自动判断是否需要上报，避免频繁上报
     */
    public void smartReportDeviceInfo() {
        if (shouldReportDeviceInfo()) {
            reportDeviceInfo();
            reportDevicePerformance();
            markDeviceInfoReported();
        } else {
            Log.d(TAG, "Device info reporting skipped (recently reported)");
        }
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