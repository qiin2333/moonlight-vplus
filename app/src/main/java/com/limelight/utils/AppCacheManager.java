package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 应用缓存管理器
 * 提供统一的应用信息缓存管理功能
 */
public class AppCacheManager {
    
    private static final String PREFERENCE_NAME = "app_cache";
    
    private final SharedPreferences preferences;
    
    public AppCacheManager(Context context) {
        this.preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * 保存完整的应用信息到缓存
     * @param pcUuid PC的UUID
     * @param app 应用对象
     */
    public void saveAppInfo(String pcUuid, NvApp app) {
        if (pcUuid == null || app == null) {
            return;
        }
        
        try {
            String nameKey = AppCacheKeys.getAppNameKey(pcUuid, app.getAppId());
            String cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, app.getAppId());
            String hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, app.getAppId());
            
            String cmdList = null;
            if (app.getCmdList() != null) {
                cmdList = app.getCmdList().toString();
            }
            
            preferences.edit()
                .putString(nameKey, app.getAppName())
                .putString(cmdKey, cmdList)
                .putBoolean(hdrKey, app.isHdrSupported())
                .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 从缓存中获取完整的应用信息
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 完整的NvApp对象，如果找不到则返回null
     */
    public NvApp getAppInfo(String pcUuid, int appId) {
        if (pcUuid == null) {
            return null;
        }
        
        try {
            String nameKey = AppCacheKeys.getAppNameKey(pcUuid, appId);
            String cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, appId);
            String hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, appId);
            
            String appName = preferences.getString(nameKey, null);
            if (appName == null) {
                return null;
            }
            
            String cmdList = preferences.getString(cmdKey, null);
            boolean hdrSupported = preferences.getBoolean(hdrKey, false);
            
            NvApp app = new NvApp(appName, appId, hdrSupported);
            if (cmdList != null && !cmdList.isEmpty()) {
                app.setCmdList(cmdList);
            }
            
            return app;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取指定PC的所有缓存应用ID列表
     * @param pcUuid PC的UUID
     * @return 应用ID列表
     */
    public List<Integer> getCachedAppIds(String pcUuid) {
        List<Integer> appIds = new ArrayList<>();
        
        if (pcUuid == null) {
            return appIds;
        }
        
        try {
            Map<String, ?> allPrefs = preferences.getAll();
            String baseKey = AppCacheKeys.getAppBaseKey(pcUuid, 0).replace("_0", "_");
            
            for (String key : allPrefs.keySet()) {
                if (key.startsWith(baseKey) && key.endsWith(AppCacheKeys.APP_NAME_SUFFIX)) {
                    try {
                        String appIdStr = key.substring(baseKey.length(), key.length() - AppCacheKeys.APP_NAME_SUFFIX.length());
                        int appId = Integer.parseInt(appIdStr);
                        appIds.add(appId);
                    } catch (NumberFormatException e) {
                        // 忽略无效的appId
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return appIds;
    }
    
    /**
     * 清除指定PC的所有应用缓存
     * @param pcUuid PC的UUID
     */
    public void clearPcCache(String pcUuid) {
        if (pcUuid == null) {
            return;
        }
        
        try {
            List<Integer> appIds = getCachedAppIds(pcUuid);
            SharedPreferences.Editor editor = preferences.edit();
            
            for (int appId : appIds) {
                String nameKey = AppCacheKeys.getAppNameKey(pcUuid, appId);
                String cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, appId);
                String hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, appId);
                
                editor.remove(nameKey)
                      .remove(cmdKey)
                      .remove(hdrKey);
            }
            
            editor.apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 清除指定应用的所有缓存
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     */
    public void clearAppCache(String pcUuid, int appId) {
        if (pcUuid == null) {
            return;
        }
        
        try {
            String nameKey = AppCacheKeys.getAppNameKey(pcUuid, appId);
            String cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, appId);
            String hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, appId);
            
            preferences.edit()
                .remove(nameKey)
                .remove(cmdKey)
                .remove(hdrKey)
                .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 清除所有应用缓存
     */
    public void clearAllCache() {
        try {
            preferences.edit().clear().apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存统计信息字符串
     */
    public String getCacheStats() {
        try {
            Map<String, ?> allPrefs = preferences.getAll();
            int totalKeys = allPrefs.size();
            int appCacheKeys = 0;
            
            for (String key : allPrefs.keySet()) {
                if (AppCacheKeys.isAppCacheKey(key)) {
                    appCacheKeys++;
                }
            }
            
            return String.format("总键数: %d, 应用缓存键数: %d", totalKeys, appCacheKeys);
        } catch (Exception e) {
            e.printStackTrace();
            return "获取统计信息失败";
        }
    }
}
