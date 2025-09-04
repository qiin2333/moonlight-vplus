package com.limelight.utils;

/**
 * 应用缓存相关的SharedPreferences键名管理类
 */
public class AppCacheKeys {
    
    // 应用缓存的基础前缀
    private static final String APP_CACHE_PREFIX = "app_cache_";
    
    // 应用信息的各个字段
    public static final String APP_NAME_SUFFIX = "_name";
    public static final String APP_CMD_SUFFIX = "_cmd";
    public static final String APP_HDR_SUFFIX = "_hdr";
    
    /**
     * 生成应用名称的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用名称的缓存key
     */
    public static String getAppNameKey(String pcUuid, int appId) {
        return APP_CACHE_PREFIX + pcUuid + "_" + appId + APP_NAME_SUFFIX;
    }
    
    /**
     * 生成应用命令列表的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用命令列表的缓存key
     */
    public static String getAppCmdKey(String pcUuid, int appId) {
        return APP_CACHE_PREFIX + pcUuid + "_" + appId + APP_CMD_SUFFIX;
    }
    
    /**
     * 生成应用HDR支持的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用HDR支持的缓存key
     */
    public static String getAppHdrKey(String pcUuid, int appId) {
        return APP_CACHE_PREFIX + pcUuid + "_" + appId + APP_HDR_SUFFIX;
    }
    
    /**
     * 生成应用信息的基础key（不包含具体字段）
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用信息的基础key
     */
    public static String getAppBaseKey(String pcUuid, int appId) {
        return APP_CACHE_PREFIX + pcUuid + "_" + appId;
    }
    
    /**
     * 检查key是否属于应用缓存
     * @param key 要检查的key
     * @return 如果是应用缓存key则返回true
     */
    public static boolean isAppCacheKey(String key) {
        return key != null && key.startsWith(APP_CACHE_PREFIX);
    }
    
    /**
     * 从key中提取PC UUID和应用ID
     * @param key 应用缓存key
     * @return 包含pcUuid和appId的数组，如果解析失败则返回null
     */
    public static String[] parseAppCacheKey(String key) {
        if (!isAppCacheKey(key)) {
            return null;
        }
        
        try {
            // 移除前缀
            String withoutPrefix = key.substring(APP_CACHE_PREFIX.length());
            
            // 移除后缀
            String withoutSuffix = withoutPrefix;
            if (withoutSuffix.endsWith(APP_NAME_SUFFIX)) {
                withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - APP_NAME_SUFFIX.length());
            } else if (withoutSuffix.endsWith(APP_CMD_SUFFIX)) {
                withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - APP_CMD_SUFFIX.length());
            } else if (withoutSuffix.endsWith(APP_HDR_SUFFIX)) {
                withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - APP_HDR_SUFFIX.length());
            }
            
            // 分割UUID和appId
            int lastUnderscoreIndex = withoutSuffix.lastIndexOf('_');
            if (lastUnderscoreIndex == -1) {
                return null;
            }
            
            String pcUuid = withoutSuffix.substring(0, lastUnderscoreIndex);
            String appId = withoutSuffix.substring(lastUnderscoreIndex + 1);
            
            return new String[]{pcUuid, appId};
        } catch (Exception e) {
            return null;
        }
    }
}
