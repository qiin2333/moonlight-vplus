package com.limelight.preferences

import android.content.Context
import androidx.preference.PreferenceManager

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
    private const val CURRENT_MIGRATION_VERSION = 2

    /** 读取全部自定义分辨率,按宽、高升序;无法解析的脏数据会被丢弃。 */
    @Synchronized
    fun load(context: Context): List<Resolution> {
        val preferences = prefs(context)
        val stored = preferences.getStringSet(PREFS_KEY, null)
        val version = preferences.getInt(MIGRATION_VERSION_KEY, 0)
        val global = PreferenceManager.getDefaultSharedPreferences(context)
        val includeLegacyLowPresets = global.getBoolean(
            PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING, false
        )
        val baseResolutions = stored?.mapNotNull(ResolutionValidator::parseResolution) ?: DEFAULT_RESOLUTIONS
        val lowPresets = if (includeLegacyLowPresets) LOW_RESOLUTIONS else emptyList()
        val resolutions = (baseResolutions + lowPresets)
            .distinct()
            .sortedWith(resolutionOrder)
        if (stored == null || version < CURRENT_MIGRATION_VERSION || includeLegacyLowPresets) {
            save(context, resolutions)
        } else {
            removeLegacyLowResolutionPreference(context)
        }
        return resolutions
    }

    /** 尚未保存过列表时的出厂基线；显式保存的空列表保持为空。 */
    private val DEFAULT_RESOLUTIONS = listOf(
        Resolution(1280, 720),
        Resolution(1920, 1080),
        Resolution(2560, 1440),
        Resolution(3840, 2160)
    )

    private val LOW_RESOLUTIONS = listOf(Resolution(640, 360), Resolution(854, 480))

    /** Older backups can restore this retired switch after this device has already migrated. */
    @Synchronized
    fun migrateLegacyLowResolutionPresets(context: Context) {
        val global = PreferenceManager.getDefaultSharedPreferences(context)
        if (global.getBoolean(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING, false)) {
            load(context)
        } else {
            removeLegacyLowResolutionPreference(context)
        }
    }

    @Synchronized
    fun save(context: Context, resolutions: List<Resolution>) {
        prefs(context).edit()
            .putStringSet(PREFS_KEY, resolutions.map(Resolution::toString).toSet())
            .putInt(MIGRATION_VERSION_KEY, CURRENT_MIGRATION_VERSION)
            .apply()
        removeLegacyLowResolutionPreference(context)
    }

    /** 添加一条(已存在时忽略),返回是否实际写入。 */
    @Synchronized
    fun add(context: Context, resolution: Resolution): Boolean {
        val existing = load(context)
        if (resolution in existing) return false
        save(context, existing + resolution)
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun removeLegacyLowResolutionPreference(context: Context) {
        val global = PreferenceManager.getDefaultSharedPreferences(context)
        if (global.contains(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING)) {
            global.edit().remove(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING).apply()
        }
    }
}
