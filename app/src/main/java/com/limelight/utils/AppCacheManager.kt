package com.limelight.utils

import android.content.Context
import com.limelight.nvstream.http.NvApp

/**
 * 应用缓存管理器
 * 提供统一的应用信息缓存管理功能
 */
class AppCacheManager(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    fun saveAppInfo(pcUuid: String?, app: NvApp?) {
        if (pcUuid == null || app == null) return

        try {
            val nameKey = AppCacheKeys.getAppNameKey(pcUuid, app.appId)
            val cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, app.appId)
            val hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, app.appId)

            preferences.edit()
                .putString(nameKey, app.appName)
                .putString(cmdKey, app.cmdList?.toString())
                .putBoolean(hdrKey, app.isHdrSupported())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAppInfo(pcUuid: String?, appId: Int): NvApp? {
        if (pcUuid == null) return null

        return try {
            val nameKey = AppCacheKeys.getAppNameKey(pcUuid, appId)
            val cmdKey = AppCacheKeys.getAppCmdKey(pcUuid, appId)
            val hdrKey = AppCacheKeys.getAppHdrKey(pcUuid, appId)

            val appName = preferences.getString(nameKey, null) ?: return null
            val cmdList = preferences.getString(cmdKey, null)
            val hdrSupported = preferences.getBoolean(hdrKey, false)

            NvApp(appName, appId, hdrSupported).also { app ->
                if (!cmdList.isNullOrEmpty()) {
                    app.setCmdList(cmdList)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getCachedAppIds(pcUuid: String?): List<Int> {
        if (pcUuid == null) return emptyList()

        return try {
            val allPrefs = preferences.all
            val baseKey = AppCacheKeys.getAppBaseKey(pcUuid, 0).replace("_0", "_")

            allPrefs.keys
                .filter { it.startsWith(baseKey) && it.endsWith(AppCacheKeys.APP_NAME_SUFFIX) }
                .mapNotNull { key ->
                    try {
                        key.substring(baseKey.length, key.length - AppCacheKeys.APP_NAME_SUFFIX.length).toInt()
                    } catch (_: NumberFormatException) {
                        null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun clearPcCache(pcUuid: String?) {
        if (pcUuid == null) return

        try {
            val appIds = getCachedAppIds(pcUuid)
            val editor = preferences.edit()

            for (appId in appIds) {
                editor.remove(AppCacheKeys.getAppNameKey(pcUuid, appId))
                    .remove(AppCacheKeys.getAppCmdKey(pcUuid, appId))
                    .remove(AppCacheKeys.getAppHdrKey(pcUuid, appId))
            }

            editor.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAppCache(pcUuid: String?, appId: Int) {
        if (pcUuid == null) return

        try {
            preferences.edit()
                .remove(AppCacheKeys.getAppNameKey(pcUuid, appId))
                .remove(AppCacheKeys.getAppCmdKey(pcUuid, appId))
                .remove(AppCacheKeys.getAppHdrKey(pcUuid, appId))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAllCache() {
        try {
            preferences.edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCacheStats(): String {
        return try {
            val allPrefs = preferences.all
            val totalKeys = allPrefs.size
            val appCacheKeys = allPrefs.keys.count { AppCacheKeys.isAppCacheKey(it) }
            String.format("总键数: %d, 应用缓存键数: %d", totalKeys, appCacheKeys)
        } catch (e: Exception) {
            e.printStackTrace()
            "获取统计信息失败"
        }
    }

    companion object {
        private const val PREFERENCE_NAME = "app_cache"
    }
}
