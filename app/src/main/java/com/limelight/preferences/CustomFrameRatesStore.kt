package com.limelight.preferences

import android.content.Context

/**
 * 游戏菜单「常用帧率」的统一存储入口。
 *
 * 只保存整数 FPS 字符串。空集合表示尚未配置，游戏菜单按原行为展示全部帧率。
 * 常用项只影响游戏菜单的展示，不改变当前串流帧率，也不影响设置页的完整列表。
 */
object CustomFrameRatesStore {
    private const val PREFS_FILE = "custom_frame_rates"
    private const val PREFS_KEY = "favorite_frame_rates"

    @Synchronized
    fun loadFavorites(context: Context): Set<String> =
        prefs(context).getStringSet(PREFS_KEY, null)
            ?.filter { it.toIntOrNull()?.let { fps -> fps > 0 } == true }
            ?.toSet()
            ?: emptySet()

    @Synchronized
    fun saveFavorites(context: Context, favorites: Set<String>) {
        prefs(context).edit().putStringSet(PREFS_KEY, HashSet(favorites)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
