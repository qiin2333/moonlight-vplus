package com.limelight.utils

/**
 * 应用缓存相关的SharedPreferences键名管理类
 */
object AppCacheKeys {

    // 应用缓存的基础前缀
    private const val APP_CACHE_PREFIX = "app_cache_"

    // 应用信息的各个字段
    const val APP_NAME_SUFFIX = "_name"
    const val APP_CMD_SUFFIX = "_cmd"
    const val APP_HDR_SUFFIX = "_hdr"

    /**
     * 生成应用名称的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用名称的缓存key
     */
    @JvmStatic
    fun getAppNameKey(pcUuid: String, appId: Int): String {
        return "${APP_CACHE_PREFIX}${pcUuid}_${appId}${APP_NAME_SUFFIX}"
    }

    /**
     * 生成应用命令列表的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用命令列表的缓存key
     */
    @JvmStatic
    fun getAppCmdKey(pcUuid: String, appId: Int): String {
        return "${APP_CACHE_PREFIX}${pcUuid}_${appId}${APP_CMD_SUFFIX}"
    }

    /**
     * 生成应用HDR支持的缓存key
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用HDR支持的缓存key
     */
    @JvmStatic
    fun getAppHdrKey(pcUuid: String, appId: Int): String {
        return "${APP_CACHE_PREFIX}${pcUuid}_${appId}${APP_HDR_SUFFIX}"
    }

    /**
     * 生成应用信息的基础key（不包含具体字段）
     * @param pcUuid PC的UUID
     * @param appId 应用ID
     * @return 应用信息的基础key
     */
    @JvmStatic
    fun getAppBaseKey(pcUuid: String, appId: Int): String {
        return "${APP_CACHE_PREFIX}${pcUuid}_${appId}"
    }

    /**
     * 检查key是否属于应用缓存
     * @param key 要检查的key
     * @return 如果是应用缓存key则返回true
     */
    @JvmStatic
    fun isAppCacheKey(key: String?): Boolean {
        return key != null && key.startsWith(APP_CACHE_PREFIX)
    }

    /**
     * 从key中提取PC UUID和应用ID
     * @param key 应用缓存key
     * @return 包含pcUuid和appId的数组，如果解析失败则返回null
     */
    @JvmStatic
    fun parseAppCacheKey(key: String): Array<String>? {
        if (!isAppCacheKey(key)) {
            return null
        }

        return try {
            // 移除前缀
            val withoutPrefix = key.substring(APP_CACHE_PREFIX.length)

            // 移除后缀
            val withoutSuffix = when {
                withoutPrefix.endsWith(APP_NAME_SUFFIX) ->
                    withoutPrefix.substring(0, withoutPrefix.length - APP_NAME_SUFFIX.length)
                withoutPrefix.endsWith(APP_CMD_SUFFIX) ->
                    withoutPrefix.substring(0, withoutPrefix.length - APP_CMD_SUFFIX.length)
                withoutPrefix.endsWith(APP_HDR_SUFFIX) ->
                    withoutPrefix.substring(0, withoutPrefix.length - APP_HDR_SUFFIX.length)
                else -> withoutPrefix
            }

            // 分割UUID和appId
            val lastUnderscoreIndex = withoutSuffix.lastIndexOf('_')
            if (lastUnderscoreIndex == -1) {
                return null
            }

            val pcUuid = withoutSuffix.substring(0, lastUnderscoreIndex)
            val appId = withoutSuffix.substring(lastUnderscoreIndex + 1)

            arrayOf(pcUuid, appId)
        } catch (e: Exception) {
            null
        }
    }
}
