package com.limelight.preferences

import android.content.Context

/**
 * 被用户禁用的帧率集合的统一存储入口。
 *
 * 只记录「禁用」的值(整数 FPS 字符串),新增的候选帧率默认启用。
 * 设置页与 GameMenu 的候选列表都经由 [DisplayChoiceLists.frameRateValues] 过滤。
 */
object CustomFrameRatesStore {
    private const val PREFS_FILE = "custom_frame_rates"
    private const val PREFS_KEY = "disabled_frame_rates"

    @Synchronized
    fun loadDisabled(context: Context): Set<String> =
        prefs(context).getStringSet(PREFS_KEY, null)
            ?.filter { it.toIntOrNull()?.let { fps -> fps > 0 } == true }
            ?.toSet()
            ?: emptySet()

    @Synchronized
    fun saveDisabled(context: Context, disabled: Set<String>) {
        prefs(context).edit().putStringSet(PREFS_KEY, HashSet(disabled)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
