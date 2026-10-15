package com.limelight.preferences

import android.content.Context

/**
 * 自定义分辨率的统一存储入口。
 *
 * 数据保存在独立 SharedPreferences 文件的 StringSet 里("WxH" 字符串)。
 * 设置对话框、串流设置列表、GameMenu、PcView 的推荐写入都经由这里读写,
 * 避免各处重复实现解析与排序。
 */
object CustomResolutionsStore {
    private const val PREFS_FILE = "custom_resolutions"
    private const val PREFS_KEY = "custom_resolutions"
    private const val MIGRATION_VERSION_KEY = "migration_version"
    private const val CURRENT_MIGRATION_VERSION = 1

    /** 读取全部自定义分辨率,按宽、高升序;无法解析的脏数据会被丢弃。 */
    fun load(context: Context): List<Resolution> {
        val preferences = prefs(context)
        val stored = preferences.getStringSet(PREFS_KEY, null)
        if (preferences.getInt(MIGRATION_VERSION_KEY, 0) < CURRENT_MIGRATION_VERSION) {
            val merged = (defaultResolutions() + stored.orEmpty().mapNotNull(ResolutionValidator::parseResolution))
                .distinct()
                .sortedWith(resolutionOrder)
            preferences.edit()
                .putStringSet(PREFS_KEY, merged.map(Resolution::toString).toSet())
                .putInt(MIGRATION_VERSION_KEY, CURRENT_MIGRATION_VERSION)
                .apply()
            return merged
        }
        return stored?.mapNotNull(ResolutionValidator::parseResolution)?.sortedWith(resolutionOrder)
            ?: defaultResolutions()
    }

    private fun defaultResolutions(): List<Resolution> {
        return listOf(
            Resolution(1280, 720),
            Resolution(1920, 1080),
            Resolution(2560, 1440),
            Resolution(3840, 2160)
        )
    }

    fun save(context: Context, resolutions: List<Resolution>) {
        prefs(context).edit()
            .putStringSet(PREFS_KEY, resolutions.map(Resolution::toString).toSet())
            .apply()
    }

    /** 添加一条(已存在时忽略),返回是否实际写入。 */
    fun add(context: Context, resolution: Resolution): Boolean {
        val existing = load(context)
        if (resolution in existing) return false
        save(context, existing + resolution)
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
