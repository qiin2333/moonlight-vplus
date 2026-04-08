package com.limelight

import android.content.Context
import org.json.JSONArray

/**
 * 快捷按钮动作注册表。
 * 管理所有可用的快捷按钮动作，支持内置动作和用户自定义快捷键。
 */
object QuickActionRegistry {

    private const val PREF_FILE = "quick_button_config"
    private const val PREF_KEY = "button_ids"

    /** 默认按钮配置（与旧版 6 按钮一致） */
    @JvmField
    val DEFAULT_IDS = arrayOf(
        "send_win", "send_esc", "toggle_hdr",
        "toggle_mic", "send_sleep", "quit"
    )

    data class QuickAction(
        @JvmField val id: String,
        @JvmField val label: String,
        @JvmField val iconRes: Int,
        @JvmField val iconDisabledRes: Int = 0,
        @JvmField val labelRes: Int = 0   // @StringRes, 非 0 时优先使用
    )

    /** 所有内置动作（有序） */
    private val BUILTIN = linkedMapOf(
        "send_win"    to QuickAction("send_win",    "WIN",     R.drawable.ic_btn_win),
        "send_esc"    to QuickAction("send_esc",    "ESC",     R.drawable.ic_btn_esc),
        "toggle_hdr"  to QuickAction("toggle_hdr",  "HDR",     R.drawable.ic_btn_hdr),
        "toggle_mic"  to QuickAction("toggle_mic",  "Mic",     R.drawable.ic_mic_gm, R.drawable.ic_mic_gm_disabled, R.string.quick_btn_mic),
        "send_sleep"  to QuickAction("send_sleep",  "Sleep",   R.drawable.ic_btn_sleep, 0, R.string.quick_btn_sleep),
        "quit"        to QuickAction("quit",        "Quit",    R.drawable.ic_btn_quit, 0, R.string.quick_btn_quit),
        // ── 扩展内置动作 ──
        "send_tab"          to QuickAction("send_tab",          "Tab",     R.drawable.ic_btn_keyboard),
        "send_alt_tab"      to QuickAction("send_alt_tab",      "Alt+Tab", R.drawable.ic_btn_keyboard),
        "send_alt_f4"       to QuickAction("send_alt_f4",       "Alt+F4",  R.drawable.ic_btn_esc),
        "toggle_keyboard"   to QuickAction("toggle_keyboard",   "KB",      R.drawable.ic_keyboard_cute, 0, R.string.quick_btn_keyboard),
        "toggle_controller" to QuickAction("toggle_controller", "Pad",     R.drawable.ic_controller_cute, 0, R.string.quick_btn_controller),
        "toggle_perf"       to QuickAction("toggle_perf",       "Perf",    R.drawable.ic_performance_cute, 0, R.string.quick_btn_perf),
    )

    /**
     * 获取所有可用动作（内置 + 用户自定义快捷键）。
     *
     * @param customKeys 自定义快捷键列表，每项 [0]=name, [1]=reserved
     */
    @JvmStatic
    fun getAllActions(customKeys: List<Array<String>>?): LinkedHashMap<String, QuickAction> {
        val all = LinkedHashMap(BUILTIN)
        customKeys?.forEach { ck ->
            val id = "custom_${ck[0]}"
            all[id] = QuickAction(id, ck[0], 0)
        }
        return all
    }

    /** 获取内置动作 */
    @JvmStatic
    fun getBuiltin(id: String): QuickAction? = BUILTIN[id]

    // ── 配置持久化 ──────────────────────────────────────────────

    /** 读取用户配置的按钮 ID 列表，若无则返回默认 */
    @JvmStatic
    fun loadConfig(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(PREF_KEY, null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                return MutableList(arr.length()) { arr.getString(it) }
            } catch (_: Exception) { }
        }
        return DEFAULT_IDS.toMutableList()
    }

    /** 保存用户配置 */
    @JvmStatic
    fun saveConfig(context: Context, ids: List<String>) {
        val arr = JSONArray().apply { ids.forEach { put(it) } }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, arr.toString())
            .apply()
    }
}
