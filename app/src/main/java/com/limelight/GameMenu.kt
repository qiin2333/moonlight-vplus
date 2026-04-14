package com.limelight

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonArray
import com.limelight.binding.input.GameInputDevice
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.KeyCodeMapper
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.LinkedHashMap

/** Int → Short 快捷转换 */
private fun Int.s(): Short = this.toShort()

/**
 * 提供游戏流媒体进行中的选项菜单
 * 在游戏活动中按返回键时显示
 */
class GameMenu(
    private val game: Game,
    private val app: NvApp,
    private val conn: NvConnection,
    private val device: GameInputDevice?
) {
    // 当前激活的对话框（如果有）
    private var activeDialog: AlertDialog? = null
    // 当前激活对话框所用的自定义视图引用（便于内部替换）
    private var activeCustomView: View? = null
    // 标志：上一次运行的选项是否打开了子菜单（由 showSubMenu 设置）
    private var lastActionOpenedSubmenu = false
    // 菜单历史栈，用于二级/多级菜单的回退
    private val menuStack: ArrayDeque<MenuState> = ArrayDeque()
    private val handler = Handler(Looper.getMainLooper())
    // 快捷按钮编辑模式
    private var quickButtonEditMode = false

    init {
        showMenu()
    }

    /**
     * 菜单选项类
     */
    class MenuOption(
        val label: String,
        val isWithGameFocus: Boolean,
        val runnable: Runnable?,
        val iconKey: String?,
        val isShowIcon: Boolean,
        val isKeepDialog: Boolean
    ) {
        constructor(label: String, runnable: Runnable?) :
                this(label, false, runnable, null, true, false)

        constructor(label: String, withGameFocus: Boolean, runnable: Runnable?) :
                this(label, withGameFocus, runnable, null, true, false)

        constructor(label: String, withGameFocus: Boolean, runnable: Runnable?, iconKey: String?) :
                this(label, withGameFocus, runnable, iconKey, true, false)

        constructor(label: String, withGameFocus: Boolean, runnable: Runnable?, iconKey: String?, showIcon: Boolean) :
                this(label, withGameFocus, runnable, iconKey, showIcon, false)
    }

    /**
     * 菜单状态，用于回退
     */
    private class MenuState(val title: String?, val normalOptions: Array<MenuOption>)

    /**
     * 获取字符串资源
     */
    private fun getString(id: Int): String = game.resources.getString(id)

    /**
     * 键盘修饰符枚举
     */
    private enum class KeyModifier(val keyCode: Short, val modifier: Byte) {
        SHIFT(KeyboardTranslator.VK_LSHIFT.s(), KeyboardPacket.MODIFIER_SHIFT),
        CTRL(KeyboardTranslator.VK_LCONTROL.s(), KeyboardPacket.MODIFIER_CTRL),
        META(KeyboardTranslator.VK_LWIN.s(), KeyboardPacket.MODIFIER_META),
        ALT(KeyboardTranslator.VK_MENU.s(), KeyboardPacket.MODIFIER_ALT);

        companion object {
            fun getModifier(key: Short): Byte =
                entries.find { it.keyCode == key }?.modifier ?: 0
        }
    }

    /**
     * 断开连接并退出
     */
    private fun disconnectAndQuit() {
        try {
            game.disconnect()
            conn.doStopAndQuit()
        } catch (e: Exception) {
            Toast.makeText(game, game.getString(R.string.toast_disconnect_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 发送键盘按键序列
     */
    private fun sendKeys(keys: ShortArray) {
        if (keys.isEmpty()) return

        var modifier: Byte = 0

        // 按下所有按键
        for (key in keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier, 0)
            modifier = (modifier.toInt() or KeyModifier.getModifier(key).toInt()).toByte()
        }

        // 延迟后释放按键
        val finalModifier = modifier
        handler.postDelayed({
            var mod = finalModifier
            for (pos in keys.indices.reversed()) {
                val key = keys[pos]
                mod = (mod.toInt() and KeyModifier.getModifier(key).toInt().inv()).toByte()
                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, mod, 0)
            }
        }, KEY_UP_DELAY)
    }

    /**
     * 在游戏获得焦点时运行任务
     */
    private fun runWithGameFocus(runnable: Runnable) {
        if (game.isFinishing) return

        if (!game.hasWindowFocus()) {
            handler.postDelayed({ runWithGameFocus(runnable) }, TEST_GAME_FOCUS_DELAY)
            return
        }

        runnable.run()
    }

    /**
     * 执行菜单选项
     */
    private fun run(option: MenuOption?) {
        if (option?.runnable == null) return

        if (option.isWithGameFocus) {
            runWithGameFocus(option.runnable)
        } else {
            option.runnable.run()
        }
    }

    /**
     * 显示触控模式菜单
     */
    private fun showTouchModeMenu() {
        val isEnhancedTouch = game.prefConfig.enableEnhancedTouch
        val isTouchscreenTrackpad = game.prefConfig.touchscreenTrackpad
        val isNativeMousePointer = game.prefConfig.enableNativeMousePointer

        val touchModeOptionsList = mutableListOf<MenuOption>()

        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_touch_mode_enhanced),
            isEnhancedTouch && !isTouchscreenTrackpad && !isNativeMousePointer,
            {
                game.prefConfig.enableEnhancedTouch = true
                game.prefConfig.enableNativeMousePointer = false
                game.enableNativeMousePointer(false)
                game.setTouchMode(false)
                updateEnhancedTouchSetting(true)
                updateTouchModeSetting(false)
                Toast.makeText(game, getString(R.string.toast_touch_mode_enhanced_on), Toast.LENGTH_SHORT).show()
            },
            null, false
        ))
        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_touch_mode_classic),
            !isEnhancedTouch && !isTouchscreenTrackpad && !isNativeMousePointer,
            {
                game.prefConfig.enableEnhancedTouch = false
                game.prefConfig.enableNativeMousePointer = false
                game.enableNativeMousePointer(false)
                game.setTouchMode(false)
                updateEnhancedTouchSetting(false)
                updateTouchModeSetting(false)
                Toast.makeText(game, getString(R.string.toast_touch_mode_classic_on), Toast.LENGTH_SHORT).show()
            },
            null, false
        ))
        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_touch_mode_trackpad),
            isTouchscreenTrackpad && !isNativeMousePointer,
            {
                game.prefConfig.enableNativeMousePointer = false
                game.enableNativeMousePointer(false)
                game.setTouchMode(true)
                updateTouchModeSetting(true)
                Toast.makeText(game, getString(R.string.toast_touch_mode_trackpad_on), Toast.LENGTH_SHORT).show()
            },
            null, false
        ))
        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_touch_mode_trackpad) + " - " +
                    if (game.prefConfig.enableDoubleClickDrag) getString(R.string.game_menu_disable_double_click_drag) else getString(R.string.game_menu_enable_double_click_drag),
            false,
            {
                game.prefConfig.enableDoubleClickDrag = !game.prefConfig.enableDoubleClickDrag
                Toast.makeText(game,
                    if (game.prefConfig.enableDoubleClickDrag) getString(R.string.toast_double_click_drag_enabled) else getString(R.string.toast_double_click_drag_disabled),
                    Toast.LENGTH_SHORT).show()
            },
            null, false
        ))

        // 本地光标渲染选项（仅在触屏触控板模式下显示）
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(MenuOption(
                getString(R.string.game_menu_local_cursor_rendering) + " - " +
                        if (game.prefConfig.enableLocalCursorRendering) getString(R.string.game_menu_on) else getString(R.string.game_menu_off),
                false,
                {
                    game.prefConfig.enableLocalCursorRendering = !game.prefConfig.enableLocalCursorRendering
                    game.refreshLocalCursorState(game.prefConfig.enableLocalCursorRendering)
                    val message = if (game.prefConfig.enableLocalCursorRendering) getString(R.string.toast_local_cursor_enabled) else getString(R.string.toast_local_cursor_disabled)
                    Toast.makeText(game, message, Toast.LENGTH_SHORT).show()
                },
                null, false
            ))
        }

        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_touch_mode_native_mouse),
            isNativeMousePointer,
            {
                game.prefConfig.enableNativeMousePointer = true
                game.prefConfig.enableEnhancedTouch = false
                game.setTouchMode(false)
                game.enableNativeMousePointer(true)
                updateTouchModeSetting(false)
                Toast.makeText(game, getString(R.string.toast_touch_mode_native_mouse_on), Toast.LENGTH_SHORT).show()
            },
            null, false
        ))

        touchModeOptionsList.add(MenuOption(
            getString(R.string.game_menu_toggle_remote_mouse),
            false,
            {
                sendKeys(shortArrayOf(
                    KeyboardTranslator.VK_LCONTROL.s(),
                    KeyboardTranslator.VK_MENU.s(),
                    KeyboardTranslator.VK_LSHIFT.s(),
                    KeyboardTranslator.VK_N.s()
                ))
                Toast.makeText(game, getString(R.string.toast_remote_mouse_toast), Toast.LENGTH_SHORT).show()
            },
            null, false
        ))

        showSubMenu(getString(R.string.game_menu_switch_touch_mode), touchModeOptionsList.toTypedArray())
    }

    private fun updateTouchModeSetting(isTrackpadMode: Boolean) {
        val controllerManager = game.controllerManager ?: run {
            LimeLog.warning("ControllerManager is null, cannot update touch mode setting")
            return
        }

        val contentValues = ContentValues()
        val currentConfigId = controllerManager.pageConfigController?.currentConfigId ?: return

        contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, isTrackpadMode.toString())
        controllerManager.superConfigDatabaseHelper?.updateConfig(currentConfigId, contentValues)
    }

    private fun updateEnhancedTouchSetting(isEnabled: Boolean) {
        val controllerManager = game.controllerManager ?: run {
            LimeLog.warning("ControllerManager is null, cannot update touch mode setting")
            return
        }

        val contentValues = ContentValues()
        val currentConfigId = controllerManager.pageConfigController?.currentConfigId ?: return

        contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, isEnabled.toString())
        controllerManager.superConfigDatabaseHelper?.updateConfig(currentConfigId, contentValues)
    }

    /**
     * 切换麦克风开关
     */
    private fun toggleMicrophone() {
        game.toggleMicrophoneButton()
    }

    /**
     * 切换王冠功能并即时刷新菜单内容
     */
    private fun toggleCrownFeature() {
        game.isCrownFeatureEnabled = !game.isCrownFeatureEnabled

        Toast.makeText(game,
            if (game.isCrownFeatureEnabled) getString(R.string.crown_switch_to_crown)
            else getString(R.string.crown_switch_to_normal),
            Toast.LENGTH_SHORT).show()

        activeDialog?.let { dialog ->
            if (dialog.isShowing) {
                updateCrownToggleButton()
                rebuildAndReplaceMenu()
            }
        }
    }

    private fun updateCrownToggleButton() {
        activeCustomView?.let { view ->
            val crownToggleButton = view.findViewById<TextView>(R.id.btnCrownToggle) ?: return
            val crownText = if (game.isCrownFeatureEnabled)
                getString(R.string.crown_switch_to_normal)
            else
                getString(R.string.crown_switch_to_crown)
            @Suppress("DEPRECATION")
            crownToggleButton.text = Html.fromHtml("<u>$crownText</u>")
        }
    }

    private fun rebuildAndReplaceMenu() {
        val dialog = activeDialog ?: return
        val customView = activeCustomView ?: return

        val normalOptions = mutableListOf<MenuOption>()
        buildNormalMenuOptions(normalOptions)

        val normalListView = customView.findViewById<ListView>(R.id.gameMenuList) ?: return
        val adapter = GameMenuAdapter(game, normalOptions.toTypedArray())
        normalListView.adapter = adapter
        setupMenu(normalListView, adapter, dialog)
    }

    /**
     * 显示"王冠功能"的二级菜单
     */
    private fun showCrownFunctionMenu() {
        val controllerManager = game.controllerManager

        if (!game.isCrownFeatureEnabled) {
            Toast.makeText(game, getString(R.string.toast_crown_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val crownFunctionOptions = arrayOf(
            MenuOption(
                getString(R.string.game_menu_toggle_elements_visibility),
                false,
                { game.toggleVirtualControllerVisibility() },
                "crown_function_menu", true
            ),
            MenuOption(
                getString(R.string.game_menu_toggle_touch),
                false,
                {
                    controllerManager?.touchController?.enableTouch(mouse_enable_switch)
                    Toast.makeText(game,
                        if (mouse_enable_switch) getString(R.string.toast_touch_enabled) else getString(R.string.toast_touch_disabled),
                        Toast.LENGTH_SHORT).show()
                    mouse_enable_switch = !mouse_enable_switch
                },
                "crown_function_menu", true
            ),
            MenuOption(
                getString(R.string.game_menu_configure_settings),
                false,
                {
                    controllerManager?.let { cm ->
                        game.toggleBackKeyMenuType()
                        game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.NO_MENU)
                        cm.pageConfigController?.open()
                    }
                },
                "crown_function_menu", true
            ),
            MenuOption(
                getString(R.string.game_menu_edit_mode),
                false,
                {
                    controllerManager?.let { cm ->
                        game.toggleBackKeyMenuType()
                        cm.elementController?.changeMode(ElementController.Mode.Edit)
                        cm.elementController?.open()
                    }
                },
                "crown_function_menu", true
            ),
            MenuOption(
                getString(R.string.game_menu_configure_crown_function),
                false,
                { game.toggleBackKeyMenuType() },
                "crown_function_menu", true
            )
        )

        showSubMenu(getString(R.string.game_menu_crown_function), crownFunctionOptions)
    }

    /**
     * 本地测试震动
     */
    private fun testLocalRumbleAll() {
        try {
            val ch = game.controllerHandler

            val on: Short = 0xFFFF.toShort()
            val off: Short = 0
            for (n in 0.toShort()..3.toShort()) {
                ch.handleRumble(n.toShort(), on, on)
            }

            handler.postDelayed({
                try {
                    for (n in 0.toShort()..3.toShort()) {
                        ch.handleRumble(n.toShort(), off, off)
                    }
                } catch (_: Exception) {}
            }, 1000)

            Toast.makeText(game, getString(R.string.toast_vibration_test_sent), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(game, game.getString(R.string.toast_vibration_test_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示分辨率选择菜单
     */
    private fun showResolutionMenu() {
        val options = mutableListOf<MenuOption>()
        val currentResStr = "${game.prefConfig.width}x${game.prefConfig.height}"

        // 预设分辨率
        for (res in PreferenceConfiguration.RESOLUTIONS) {
            val label = if (res == currentResStr) "$res (Current)" else res
            options.add(MenuOption(label, false, { changeResolution(res) }, null, false))
        }

        // 自定义分辨率
        val customPrefs = game.getSharedPreferences("custom_resolutions", Context.MODE_PRIVATE)
        val customResolutions = customPrefs.getStringSet("custom_resolutions", null)

        if (!customResolutions.isNullOrEmpty()) {
            val sortedCustom = customResolutions.sortedWith(Comparator { s1, s2 ->
                val parts1 = s1.split("x")
                val parts2 = s2.split("x")
                if (parts1.size != 2 || parts2.size != 2) return@Comparator s1.compareTo(s2)
                try {
                    val w1 = parts1[0].toInt(); val h1 = parts1[1].toInt()
                    val w2 = parts2[0].toInt(); val h2 = parts2[1].toInt()
                    if (w1 != w2) w1.compareTo(w2) else h1.compareTo(h2)
                } catch (e: NumberFormatException) {
                    s1.compareTo(s2)
                }
            })

            for (res in sortedCustom) {
                if (PreferenceConfiguration.RESOLUTIONS.contains(res)) continue

                var label = "$res (Custom)"
                if (res == currentResStr) label += " (Current)"

                options.add(MenuOption(label, false, { changeResolution(res) }, null, false))
            }
        }

        showSubMenu("Change Resolution", options.toTypedArray())
    }

    private fun changeResolution(resString: String) {
        @Suppress("DEPRECATION")
        android.preference.PreferenceManager.getDefaultSharedPreferences(game)
            .edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resString)
            .apply()

        Toast.makeText(game, "Resolution changed to $resString. Restarting...", Toast.LENGTH_SHORT).show()

        game.changeResolution()
        activeDialog?.dismiss()
    }

    /**
     * 调整码率
     */
    private fun adjustBitrate(bitrateKbps: Int) {
        try {
            Toast.makeText(game, getString(R.string.toast_adjusting_bitrate), Toast.LENGTH_SHORT).show()

            conn.setBitrate(bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        try {
                            val successMessage = String.format(getString(R.string.game_menu_bitrate_adjustment_success), newBitrate / 1000)
                            Toast.makeText(game, successMessage, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show success toast: ${e.message}")
                        }
                    }
                }

                override fun onFailure(errorMessage: String) {
                    game.runOnUiThread {
                        try {
                            val errorMsg = getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage
                            Toast.makeText(game, errorMsg, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show error toast: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            game.runOnUiThread {
                try {
                    Toast.makeText(game, getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.message, Toast.LENGTH_SHORT).show()
                } catch (toastException: Exception) {
                    LimeLog.warning("Failed to show error toast: ${toastException.message}")
                }
            }
        }
    }

    /**
     * 显示菜单对话框
     */
    private fun showMenuDialog(title: String, normalOptions: Array<MenuOption>, superOptions: Array<MenuOption>) {
        val builder = AlertDialog.Builder(game, R.style.GameMenuDialogStyle)

        val customView = createCustomView(builder)
        val dialog = builder.create()
        this.activeDialog = dialog
        this.activeCustomView = customView

        setupCustomTitleBar(customView, title)
        setupAppNameDisplay(customView)
        setupQuickButtons(customView, dialog)
        setupNormalMenu(customView, normalOptions, dialog)
        setupSuperMenu(customView, superOptions, dialog)

        // 设置码率调整区域
        if (game.prefConfig.showBitrateCard) {
            BitrateCardController(game, conn).setup(customView, dialog)
        } else {
            customView.findViewById<View>(R.id.bitrateAdjustmentContainer)?.visibility = View.GONE
        }

        // 设置陀螺仪控制卡片
        if (game.prefConfig.showGyroCard) {
            GyroCardController(game).setup(customView, dialog)
        } else {
            customView.findViewById<View>(R.id.gyroAdjustmentContainer)?.visibility = View.GONE
        }

        // 快捷指令卡片
        setupCustomKeysCard(customView)

        // 卡片编辑入口
        customView.findViewById<View>(R.id.cardEditorButton)?.setOnClickListener { showCardEditorDialog() }

        setupDialogProperties(dialog)

        // 返回键监听器
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (menuStack.isNotEmpty()) {
                    val previousState = menuStack.pop()
                    replaceNormalMenuInDialog(dialog, previousState.title, previousState.normalOptions, false)
                    return@setOnKeyListener true
                }
                return@setOnKeyListener false
            }
            false
        }

        // 关闭时清理状态
        dialog.setOnDismissListener {
            if (this.activeDialog == dialog) this.activeDialog = null
            this.activeCustomView = null
            menuStack.clear()
        }

        dialog.show()
    }

    private fun showCardEditorDialog() {
        val items = arrayOf(
            getString(R.string.game_menu_tab_bitrate),
            getString(R.string.game_menu_tab_gyro),
            getString(R.string.game_menu_tab_shortcuts)
        )

        val checked = booleanArrayOf(
            game.prefConfig.showBitrateCard,
            game.prefConfig.showGyroCard,
            game.prefConfig.showQuickKeyCard
        )

        AlertDialog.Builder(game, R.style.AppDialogStyle)
            .setTitle(getString(R.string.game_menu_card_config_title))
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { d, _ ->
                game.prefConfig.showBitrateCard = checked[0]
                game.prefConfig.showGyroCard = checked[1]
                game.prefConfig.showQuickKeyCard = checked[2]

                game.prefConfig.writePreferences(game)

                val root = activeCustomView

                if (root != null) {
                    root.findViewById<View>(R.id.bitrateAdjustmentContainer)?.visibility =
                        if (game.prefConfig.showBitrateCard) View.VISIBLE else View.GONE

                    root.findViewById<View>(R.id.gyroAdjustmentContainer)?.visibility =
                        if (game.prefConfig.showGyroCard) View.VISIBLE else View.GONE

                    root.findViewById<View>(R.id.customKeysCardContainer)?.let { keysCard ->
                        if (game.prefConfig.showQuickKeyCard) {
                            setupCustomKeysCard(root)
                        } else {
                            keysCard.visibility = View.GONE
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createCustomView(builder: AlertDialog.Builder): View {
        val customView = game.layoutInflater.inflate(R.layout.custom_dialog, null)
        builder.setView(customView)
        return customView
    }

    // --- 简单的按键数据模型 ---
    private class CustomKeyData(val name: String, val keys: ShortArray)

    /**
     * 从存储或默认资源中获取解析好的按键数据列表
     */
    private fun getSavedCustomKeys(): List<CustomKeyData> {
        val resultList = mutableListOf<CustomKeyData>()

        val preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
        var value = preferences.getString(KEY_NAME, "")

        // 如果 SharedPreferences 中没有数据，则从 raw 资源文件加载默认按键
        if (value.isNullOrEmpty()) {
            value = readRawResourceAsString(R.raw.default_special_keys)
            if (!value.isNullOrEmpty()) {
                preferences.edit().putString(KEY_NAME, value).apply()
            }
        }

        if (value.isNullOrEmpty()) return resultList

        try {
            val root = JSONObject(value)
            val dataArray = root.optJSONArray("data")

            if (dataArray != null && dataArray.length() > 0) {
                for (i in 0 until dataArray.length()) {
                    val keyObject = dataArray.getJSONObject(i)
                    val name = keyObject.optString("name")
                    val codesArray = keyObject.getJSONArray("data")

                    val datas = ShortArray(codesArray.length()) { j ->
                        val code = codesArray.getString(j)
                        code.substring(2).toInt(16).toShort()
                    }
                    resultList.add(CustomKeyData(name, datas))
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Exception while loading keys from SharedPreferences: ${e.message}")
            Toast.makeText(game, getString(R.string.toast_load_custom_keys_corrupted), Toast.LENGTH_SHORT).show()
        }

        return resultList
    }

    /**
     * 设置自定义按键卡片
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun setupCustomKeysCard(customView: View) {
        val cardContainer = customView.findViewById<View>(R.id.customKeysCardContainer) ?: return
        val listLayout = customView.findViewById<LinearLayout>(R.id.customKeysListLayout) ?: return

        if (!game.prefConfig.showQuickKeyCard) {
            cardContainer.visibility = View.GONE
            return
        }

        val keys = getSavedCustomKeys()
        if (keys.isEmpty()) {
            cardContainer.visibility = View.GONE
            return
        }

        cardContainer.visibility = View.VISIBLE
        listLayout.removeAllViews()

        for (i in keys.indices) {
            val keyData = keys[i]

            val itemView = TextView(game).apply {
                text = keyData.name
                setTextColor(0xFF333333.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER

                val paddingVertical = dpToPx(7f)
                val paddingHorizontal = dpToPx(10f)
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                background = game.getDrawable(R.drawable.button_selector_background)

                setOnClickListener { v ->
                    sendKeys(keyData.keys)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }

            listLayout.addView(itemView)

            // 添加分割线（除了最后一个）
            if (i < keys.size - 1) {
                val divider = View(game).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(0, 0, 0, 0) }
                    setBackgroundColor(0x33000000)
                }
                listLayout.addView(divider)
            }
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * game.resources.displayMetrics.density + 0.5f).toInt()

    private fun dpToPx(dp: Int): Int = (dp * game.resources.displayMetrics.density).toInt()

    /**
     * 设置自定义标题
     */
    private fun setupCustomTitleBar(customView: View, title: String) {
        customView.findViewById<TextView>(R.id.customTitleTextView)?.text = title

        val crownToggleButton = customView.findViewById<TextView>(R.id.btnCrownToggle)
        if (crownToggleButton != null) {
            val crownText = if (game.isCrownFeatureEnabled)
                getString(R.string.crown_switch_to_normal)
            else
                getString(R.string.crown_switch_to_crown)
            @Suppress("DEPRECATION")
            crownToggleButton.text = Html.fromHtml("<u>$crownText</u>")
            crownToggleButton.setOnClickListener {
                val wasEnabled = game.isCrownFeatureEnabled
                toggleCrownFeature()
                val newCrownText = if (!wasEnabled)
                    getString(R.string.crown_switch_to_normal)
                else
                    getString(R.string.crown_switch_to_crown)
                @Suppress("DEPRECATION")
                crownToggleButton.text = Html.fromHtml("<u>$newCrownText</u>")
            }
        }
    }

    /**
     * 设置当前串流应用信息
     */
    @SuppressLint("SetTextI18n")
    private fun setupAppNameDisplay(customView: View) {
        try {
            val appName = app.appName
            val hdrSupported = app.isHdrSupported()
            val appNameTextView = customView.findViewById<TextView>(R.id.appNameTextView)
            appNameTextView.text = "$appName (${if (hdrSupported) "HDR: Supported" else "HDR: Unknown"})"
        } catch (e: Exception) {
            customView.findViewById<TextView>(R.id.appNameTextView)?.text = "Moonlight V+"
        }
    }

    /**
     * 设置快捷按钮（动态生成，根据用户配置）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupQuickButtons(customView: View, dialog: AlertDialog) {
        val container = customView.findViewById<LinearLayout>(R.id.quickButtonContainer) ?: return
        container.removeAllViews()
        container.clipChildren = false
        container.clipToPadding = false
        (container.parent as? android.view.ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }

        val scrollView = container.parent as? android.widget.HorizontalScrollView
        val actionIds = QuickActionRegistry.loadConfig(game).toMutableList()

        if (quickButtonEditMode) {
            buildEditModeButtons(container, actionIds, customView, dialog)
            container.addView(makeToolButton("✓") {
                quickButtonEditMode = false
                setupQuickButtons(customView, dialog)
            })
            container.addView(makeToolButton("＋") { showQuickButtonEditor() })
        } else {
            buildNormalModeButtons(container, actionIds, customView, dialog)
            container.addView(makeToolButton("＋") {
                quickButtonEditMode = true
                setupQuickButtons(customView, dialog)
            })
        }
    }

    /** 解析 action id → Pair(label, iconRes)，无效返回 null */
    private fun resolveAction(id: String): Pair<String, Int>? {
        val action = QuickActionRegistry.getBuiltin(id)
        return when {
            action != null -> {
                val label = if (action.labelRes != 0) getString(action.labelRes) else action.label
                label to action.iconRes
            }
            id.startsWith("custom_") -> id.substring("custom_".length) to 0
            else -> null
        }
    }

    /** 创建小型工具按钮（✓ / ＋） */
    private fun makeToolButton(label: String, onClick: () -> Unit) =
        Button(game, null, 0, R.style.GameMenuButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(4) }
            text = label; textSize = 14f
            minWidth = dpToPx(36); minimumWidth = dpToPx(36)
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setOnClickListener { onClick() }
        }

    /** 正常模式：可点击按钮 + 末尾编辑入口 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildNormalModeButtons(
        container: LinearLayout, actionIds: List<String>,
        customView: View, dialog: AlertDialog
    ) {
        val scaleDown = AnimationUtils.loadAnimation(game, R.anim.button_scale_animation)
        val scaleUp = AnimationUtils.loadAnimation(game, R.anim.button_scale_restore)
        val customKeys = getSavedCustomKeys()

        val buttons = mutableListOf<Button>()

        for (id in actionIds) {
            val (label, iconRes) = resolveAction(id) ?: continue

            val btn = Button(game, null, 0, R.style.GameMenuButtonStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = label
                setUniformIcon(iconRes)
            }

            val action = QuickActionRegistry.getBuiltin(id)
            if (id == "toggle_mic" && !game.prefConfig.enableMic) {
                btn.isEnabled = false; btn.alpha = 0.5f
                if (action != null && action.iconDisabledRes != 0)
                    btn.setUniformIcon(action.iconDisabledRes)
                btn.setOnClickListener {
                    Toast.makeText(game, getString(R.string.toast_enable_mic_redirect), Toast.LENGTH_SHORT).show()
                }
            } else {
                val clickListener = createQuickActionListener(id, customKeys)
                if (clickListener != null) {
                    setupButtonWithAnimation(btn, scaleDown, scaleUp, clickListener)
                }
            }

            container.addView(btn)
            buttons.add(btn)
        }

        // 布局完成后，按 scrollView 可见宽度均分功能按钮
        distributeButtonsEvenly(container, buttons)
    }

    /** 编辑模式：抖动 + ×删除 + 拖拽排序 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildEditModeButtons(
        container: LinearLayout, actionIds: MutableList<String>,
        customView: View, dialog: AlertDialog
    ) {
        val wiggle = android.view.animation.RotateAnimation(
            -1.5f, 1.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 80
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
        }

        val wrappers = mutableListOf<FrameLayout>()

        for ((index, id) in actionIds.withIndex()) {
            val (label, iconRes) = resolveAction(id) ?: continue

            val wrapper = FrameLayout(game).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(2) }
                clipChildren = false
                clipToPadding = false
            }

            val btn = Button(game, null, 0, R.style.GameMenuButtonStyle).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                text = label
                setUniformIcon(iconRes)
                startAnimation(wiggle)
                setOnClickListener { }
            }

            // 删除角标
            val deleteBtn = TextView(game).apply {
                text = "✕"; setTextColor(0xFFFFFFFF.toInt()); textSize = 9f
                gravity = android.view.Gravity.CENTER
                val sz = dpToPx(16)
                layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.TOP
                    topMargin = dpToPx(2); marginEnd = dpToPx(2)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xCCE53935.toInt())
                }
                elevation = 4f
                setOnClickListener {
                    if (actionIds.size <= 1) return@setOnClickListener
                    actionIds.removeAt(index)
                    QuickActionRegistry.saveConfig(game, actionIds)
                    setupQuickButtons(customView, dialog)
                }
            }

            wrapper.addView(btn); wrapper.addView(deleteBtn)

            // 拖拽
            btn.setOnLongClickListener { v ->
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    v.startDragAndDrop(null, View.DragShadowBuilder(wrapper), index, 0)
                else v.startDrag(null, View.DragShadowBuilder(wrapper), index, 0)
                wrapper.alpha = 0.3f; true
            }
            wrapper.setOnDragListener { v, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.scaleX = 1.15f; v.scaleY = 1.15f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { v.scaleX = 1f; v.scaleY = 1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        v.scaleX = 1f; v.scaleY = 1f
                        val from = event.localState as Int
                        if (from != index) {
                            actionIds.add(index, actionIds.removeAt(from))
                            QuickActionRegistry.saveConfig(game, actionIds)
                            setupQuickButtons(customView, dialog)
                        }; true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        for (i in 0 until container.childCount) container.getChildAt(i).alpha = 1f; true
                    }
                    else -> true
                }
            }
            container.addView(wrapper)
            wrappers.add(wrapper)
        }

        // 布局完成后，按 scrollView 可见宽度均分编辑态按钮
        distributeButtonsEvenly(container, wrappers)
    }

    /**
     * 根据动作 ID 创建点击监听器
     */
    private fun createQuickActionListener(id: String, customKeys: List<CustomKeyData>?): View.OnClickListener? {
        return when (id) {
            "send_win" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.s())) }
            "send_esc" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_ESCAPE.s())) }
            "toggle_hdr" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.s(), KeyboardTranslator.VK_MENU.s(), KeyboardTranslator.VK_B.s())) }
            "toggle_mic" -> View.OnClickListener { toggleMicrophone() }
            "send_sleep" -> View.OnClickListener {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.s(), 88.s()))
                handler.postDelayed({ sendKeys(shortArrayOf(85.s(), 83.s())) }, SLEEP_DELAY)
            }
            "quit" -> View.OnClickListener {
                if (game.prefConfig.swapQuitAndDisconnect) game.disconnect()
                else disconnectAndQuit()
            }
            "send_tab" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_TAB.s())) }
            "send_alt_tab" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_MENU.s(), KeyboardTranslator.VK_TAB.s())) }
            "send_alt_f4" -> View.OnClickListener { sendKeys(shortArrayOf(KeyboardTranslator.VK_MENU.s(), (KeyboardTranslator.VK_F1 + 3).s())) }
            "toggle_keyboard" -> View.OnClickListener { game.toggleKeyboard() }
            "toggle_controller" -> View.OnClickListener { game.toggleVirtualController() }
            "toggle_perf" -> View.OnClickListener { game.togglePerformanceOverlay() }
            else -> {
                if (id.startsWith("custom_") && customKeys != null) {
                    val name = id.substring("custom_".length)
                    customKeys.find { it.name == name }?.let { ck ->
                        return View.OnClickListener { sendKeys(ck.keys) }
                    }
                }
                null
            }
        }
    }

    /**
     * 快捷按钮配置编辑器
     */
    private fun showQuickButtonEditor() {
        val currentIds = QuickActionRegistry.loadConfig(game)
        val customKeys = getSavedCustomKeys()
        val customKeyPairs = customKeys.map { arrayOf(it.name, "") }

        val allActions = QuickActionRegistry.getAllActions(customKeyPairs)

        val allIds = allActions.keys.toTypedArray()
        val allLabels = allIds.map { id ->
            val a = allActions[id]!!
            if (a.labelRes != 0) getString(a.labelRes) else a.label
        }.toTypedArray()
        val checked = BooleanArray(allIds.size) { currentIds.contains(allIds[it]) }

        AlertDialog.Builder(game, R.style.AppDialogStyle)
            .setTitle(getString(R.string.quick_button_editor_title))
            .setMultiChoiceItems(allLabels, checked) { dlg, which, isChecked ->
                val selectedCount = checked.count { it }
                if (isChecked && selectedCount > MAX_QUICK_BUTTONS) {
                    checked[which] = false
                    (dlg as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(game, getString(R.string.quick_btn_max_reached), Toast.LENGTH_SHORT).show()
                } else {
                    checked[which] = isChecked
                }
            }
            .setPositiveButton(getString(R.string.game_menu_ok)) { _, _ ->
                val newIds = allIds.filterIndexed { i, _ -> checked[i] }.toMutableList()
                if (newIds.isEmpty()) newIds.add("quit")
                QuickActionRegistry.saveConfig(game, newIds)
                val cv = activeCustomView
                val ad = activeDialog
                if (cv != null && ad != null) setupQuickButtons(cv, ad)
            }
            .setNegativeButton(getString(R.string.game_menu_cancel), null)
            .setNeutralButton(getString(R.string.quick_button_reset_default)) { _, _ ->
                QuickActionRegistry.saveConfig(game, QuickActionRegistry.DEFAULT_IDS.toMutableList())
                val cv = activeCustomView
                val ad = activeDialog
                if (cv != null && ad != null) setupQuickButtons(cv, ad)
            }
            .show()
    }

    /** 布局完成后将按钮均分到 ScrollView 可见宽度 */
    private fun distributeButtonsEvenly(container: LinearLayout, items: List<View>) {
        if (items.isEmpty()) return
        val scrollView = container.parent as? android.widget.HorizontalScrollView
        val apply = {
            val svWidth = scrollView?.width ?: 0
            if (svWidth > 0) {
                val margin = dpToPx(2)
                val totalMargins = margin * (items.size - 1)
                val w = (svWidth - totalMargins) / items.size
                for ((i, v) in items.withIndex()) {
                    v.layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { if (i < items.size - 1) marginEnd = margin }
                }
                container.requestLayout()
            }
        }
        if (scrollView != null && scrollView.width > 0) apply() else container.post { apply() }
    }

    /** 为按钮设置统一大小的左侧图标 */
    private fun Button.setUniformIcon(@android.annotation.SuppressLint("SupportAnnotationUsage") iconRes: Int) {
        if (iconRes == 0) return
        val drawable = androidx.core.content.ContextCompat.getDrawable(game, iconRes) ?: return
        val size = dpToPx(20)
        drawable.setBounds(0, 0, size, size)
        setCompoundDrawables(drawable, null, null, null)
    }

    /**
     * 为按钮设置动画效果
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtonWithAnimation(
        button: View,
        scaleDown: android.view.animation.Animation,
        scaleUp: android.view.animation.Animation,
        listener: View.OnClickListener
    ) {
        if (button is Button) {
            @Suppress("DEPRECATION")
            button.setTextAppearance(game, R.style.GameMenuButtonStyle)
        }

        button.isFocusable = true
        button.isClickable = true
        button.isFocusableInTouchMode = true

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(scaleDown)
                    v.alpha = 0.8f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.startAnimation(scaleUp)
                    v.alpha = 1.0f
                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener.onClick(v)
                    }
                }
            }
            true
        }

        setupButtonKeyListener(button, scaleDown, scaleUp, listener)
    }

    private fun setupButtonKeyListener(
        button: View,
        scaleDown: android.view.animation.Animation,
        scaleUp: android.view.animation.Animation,
        listener: View.OnClickListener
    ) {
        button.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.startAnimation(scaleDown)
                    v.postDelayed({
                        v.startAnimation(scaleUp)
                        listener.onClick(v)
                    }, 100)
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    /**
     * 通用菜单设置方法
     */
    private fun setupMenu(listView: ListView, adapter: ArrayAdapter<MenuOption>, dialog: AlertDialog) {
        listView.itemsCanFocus = true

        listView.setOnItemClickListener { _, _, pos, _ ->
            val option = adapter.getItem(pos)
            lastActionOpenedSubmenu = false
            if (option != null) run(option)

            val shouldKeep = (option != null && option.isKeepDialog) || lastActionOpenedSubmenu
            if (!shouldKeep) dialog.dismiss()
            lastActionOpenedSubmenu = false
        }
    }

    private fun setupNormalMenu(customView: View, normalOptions: Array<MenuOption>, dialog: AlertDialog) {
        val normalAdapter = GameMenuAdapter(game, normalOptions)
        val normalListView = customView.findViewById<ListView>(R.id.gameMenuList)
        normalListView.adapter = normalAdapter
        setupMenu(normalListView, normalAdapter, dialog)
    }

    private fun replaceNormalMenuInDialog(
        dialog: AlertDialog,
        title: String?,
        newNormalOptions: Array<MenuOption>,
        pushToStack: Boolean
    ) {
        if (dialog.window == null) return
        var customView = this.activeCustomView
        if (customView == null) customView = dialog.findViewById(android.R.id.content)
        if (customView == null) customView = dialog.window!!.decorView.findViewById(android.R.id.content)
        if (customView == null) return

        if (title != null) {
            customView.findViewById<TextView>(R.id.customTitleTextView)?.text = title
        }

        val normalListView = customView.findViewById<ListView>(R.id.gameMenuList)
        if (normalListView != null) {
            val adapter = GameMenuAdapter(game, newNormalOptions)
            normalListView.adapter = adapter
            setupMenu(normalListView, adapter, dialog)
        }

        if (pushToStack) {
            menuStack.push(MenuState(title, newNormalOptions))
        }
    }

    /**
     * 在当前打开的 dialog 中显示一个子菜单
     */
    private fun showSubMenu(title: String, subOptions: Array<MenuOption>) {
        val dialog = activeDialog
        if (dialog != null && dialog.isShowing) {
            lastActionOpenedSubmenu = true
            val cv = this.activeCustomView
            if (cv != null) {
                val titleTextView = cv.findViewById<TextView>(R.id.customTitleTextView)
                val currentTitle = titleTextView?.text?.toString()

                val normalListView = cv.findViewById<ListView>(R.id.gameMenuList)
                if (normalListView?.adapter != null) {
                    val count = normalListView.adapter.count
                    val currentOptions = Array(count) {
                        normalListView.adapter.getItem(it) as MenuOption
                    }
                    menuStack.push(MenuState(currentTitle, currentOptions))
                }
            }

            replaceNormalMenuInDialog(dialog, title, subOptions, false)
        } else {
            showMenuDialog(title, subOptions, emptyArray())
        }
    }

    private fun setupSuperMenu(customView: View, superOptions: Array<MenuOption>, dialog: AlertDialog) {
        val superListView = customView.findViewById<ListView>(R.id.superMenuList)

        if (superOptions.isNotEmpty()) {
            val superAdapter = SuperMenuAdapter(game, superOptions)
            superListView.adapter = superAdapter
            setupMenu(superListView, superAdapter, dialog)
        } else {
            setupEmptySuperMenu(superListView)
        }
    }

    private fun setupEmptySuperMenu(superListView: ListView) {
        var visibleCardCount = 0
        if (game.prefConfig.showBitrateCard) visibleCardCount++
        if (game.prefConfig.showGyroCard) visibleCardCount++
        if (game.prefConfig.showQuickKeyCard) visibleCardCount++

        val layoutRes = if (visibleCardCount >= 2 || game.prefConfig.showQuickKeyCard)
            R.layout.game_menu_super_empty_text_only
        else
            R.layout.game_menu_super_empty

        val emptyView = LayoutInflater.from(game).inflate(layoutRes, superListView, false)
        val parent = superListView.parent as ViewGroup
        parent.addView(emptyView)
        superListView.emptyView = emptyView
        superListView.adapter = SuperMenuAdapter(game, emptyArray())
    }

    private fun setupDialogProperties(dialog: AlertDialog) {
        dialog.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.alpha = DIALOG_ALPHA
            layoutParams.dimAmount = DIALOG_DIM_AMOUNT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            window.attributes = layoutParams
            window.setBackgroundDrawableResource(R.drawable.game_menu_dialog_bg)
        }
    }

    /**
     * 显示特殊按键菜单
     */
    private fun showSpecialKeysMenu() {
        val options = mutableListOf<MenuOption>()

        val hasKeys = loadAndAddAllKeys(options)

        options.add(MenuOption(getString(R.string.game_menu_add_custom_key), false,
            { showAddCustomKeyDialog() }, null, false))

        if (hasKeys) {
            options.add(MenuOption(getString(R.string.game_menu_delete_custom_key), false,
                { showDeleteKeysDialog() }, null, false))
        }

        options.add(MenuOption(getString(R.string.game_menu_cancel), false, null, null, false))

        showSubMenu(getString(R.string.game_menu_send_keys), options.toTypedArray())
    }

    private fun loadAndAddAllKeys(options: MutableList<MenuOption>): Boolean {
        val loadedKeys = getSavedCustomKeys()
        if (loadedKeys.isEmpty()) return false

        for (keyData in loadedKeys) {
            options.add(MenuOption(keyData.name, false, { sendKeys(keyData.keys) }, null, false))
        }
        return true
    }

    private fun readRawResourceAsString(resourceId: Int): String {
        try {
            game.resources.openRawResource(resourceId).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val builder = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        builder.append(line)
                    }
                    return builder.toString()
                }
            }
        } catch (e: IOException) {
            LimeLog.warning("Failed to read raw resource file: $resourceId: $e")
            return ""
        }
    }

    private fun saveCustomKey(name: String, keysString: String) {
        val preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
        val value = preferences.getString(KEY_NAME, "{\"data\":[]}") ?: "{\"data\":[]}"

        try {
            val keyParts = keysString.split(",")
            val keyCodesArray = JSONArray()
            for (part in keyParts) {
                val trimmedPart = part.trim()
                if (!trimmedPart.startsWith("0x")) {
                    Toast.makeText(game, R.string.toast_key_code_format_error, Toast.LENGTH_LONG).show()
                    return
                }
                keyCodesArray.put(trimmedPart)
            }

            val root = JSONObject(value)
            val dataArray = root.getJSONArray("data")

            val newKeyEntry = JSONObject()
            newKeyEntry.put("name", name)
            newKeyEntry.put("data", keyCodesArray)
            dataArray.put(newKeyEntry)

            preferences.edit().putString(KEY_NAME, root.toString()).apply()

            Toast.makeText(game, game.getString(R.string.toast_custom_key_saved, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LimeLog.warning("Exception while saving custom key${e.message}")
            Toast.makeText(game, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddCustomKeyDialog() {
        val builder = AlertDialog.Builder(game, R.style.VirtualKeyboardDialogStyle)

        val dialogView = LayoutInflater.from(game).inflate(R.layout.dialog_add_custom_key, null)
        builder.setView(dialogView)

        val dialogContent = dialogView.findViewById<LinearLayout>(R.id.dialog_content)
        val nameInput = dialogView.findViewById<EditText>(R.id.edit_text_key_name)
        val keysDisplay = dialogView.findViewById<TextView>(R.id.text_view_key_codes)
        val clearButton = dialogView.findViewById<Button>(R.id.button_clear_keys)
        val closeButton = dialogView.findViewById<Button>(R.id.button_close_dialog)
        val saveButton = dialogView.findViewById<Button>(R.id.button_save_key)

        val dialog = builder.create()

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                dialog.dismiss()
                return@setOnKeyListener true
            }
            false
        }

        closeButton?.setOnClickListener { dialog.dismiss() }

        // 点击背景关闭对话框
        if (dialogView is FrameLayout) {
            dialogView.setOnClickListener { dialog.dismiss() }

            // 防止内容区域的点击事件传播到背景
            val contentArea = dialogView.getChildAt(0) // ScrollView
            contentArea?.setOnClickListener { /* block propagation */ }
        }

        // 初始化 TextView 的数据存储 (tag) 和显示 (text)
        keysDisplay.tag = ""
        keysDisplay.text = ""
        keysDisplay.setHint(R.string.dialog_hint_key_codes)

        // 清空按钮
        clearButton?.setOnClickListener {
            keysDisplay.tag = ""
            keysDisplay.text = ""
        }

        // 递归设置键盘监听器
        setupCompactKeyboardListeners(dialogView.findViewById(R.id.keyboard_drawing), keysDisplay)

        // 保存按钮事件
        saveButton?.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val androidKeyCodesStr = keysDisplay.tag.toString()

            if (name.isEmpty() || androidKeyCodesStr.isEmpty()) {
                Toast.makeText(game, R.string.toast_name_and_codes_cannot_be_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val androidCodes = androidKeyCodesStr.split(",")
            val windowsCodesBuilder = StringBuilder()
            for (i in androidCodes.indices) {
                try {
                    val code = androidCodes[i].toInt()
                    val windowsCode = KeyCodeMapper.getWindowsKeyCode(code)
                        ?: throw NullPointerException()
                    windowsCodesBuilder.append(windowsCode)
                    if (i < androidCodes.size - 1) windowsCodesBuilder.append(",")
                } catch (e: Exception) {
                    Toast.makeText(game, "error: invalid key code", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            saveCustomKey(name, windowsCodesBuilder.toString())
            dialog.dismiss()
        }

        dialog.show()
        dialogContent?.minimumHeight = game.resources.displayMetrics.heightPixels
    }

    private fun setupCompactKeyboardListeners(parent: ViewGroup?, keysDisplay: TextView) {
        if (parent == null) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ViewGroup) {
                setupCompactKeyboardListeners(child, keysDisplay)
            } else if (child is TextView && child.tag != null) {
                child.setOnClickListener { v ->
                    val androidKeyCode = v.tag.toString()
                    val currentTag = keysDisplay.tag.toString()

                    val newTag = if (currentTag.isEmpty()) androidKeyCode else "$currentTag,$androidKeyCode"
                    keysDisplay.tag = newTag

                    val currentText = keysDisplay.text.toString()
                    val displayName = KeyCodeMapper.getDisplayName(androidKeyCode.toInt())
                    val newText = if (currentText.isEmpty()) displayName else "$currentText + $displayName"
                    keysDisplay.text = newText
                }
            }
        }
    }

    private fun showDeleteKeysDialog() {
        val preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
        val value = preferences.getString(KEY_NAME, "")

        if (value.isNullOrEmpty()) {
            Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val root = JSONObject(value)
            val dataArray = root.optJSONArray("data")

            if (dataArray == null || dataArray.length() == 0) {
                Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show()
                return
            }

            val keyNames = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                keyNames.add(dataArray.getJSONObject(i).optString("name"))
            }
            val checkedItems = BooleanArray(keyNames.size)

            AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setTitle(R.string.dialog_title_select_keys_to_delete)
                .setMultiChoiceItems(keyNames.toTypedArray<CharSequence>(), checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(R.string.dialog_button_delete) { _, _ ->
                    try {
                        for (i in checkedItems.indices.reversed()) {
                            if (checkedItems[i]) dataArray.remove(i)
                        }
                        root.put("data", dataArray)
                        preferences.edit().putString(KEY_NAME, root.toString()).apply()
                        Toast.makeText(game, R.string.toast_selected_keys_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LimeLog.warning("Exception while deleting keys${e.message}")
                        Toast.makeText(game, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create()
                .show()
        } catch (e: Exception) {
            LimeLog.warning("Exception while loading key list${e.message}")
            Toast.makeText(game, R.string.toast_load_key_list_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示主菜单
     */
    private fun showMenu() {
        val normalOptions = mutableListOf<MenuOption>()
        val superOptions = mutableListOf<MenuOption>()

        buildNormalMenuOptions(normalOptions)
        buildSuperMenuOptions(superOptions)

        showMenuDialog(GAME_MENU_TITLE, normalOptions.toTypedArray(), superOptions.toTypedArray())
    }

    /**
     * 构建普通菜单选项
     */
    private fun buildNormalMenuOptions(normalOptions: MutableList<MenuOption>) {
        normalOptions.add(MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
            { game.toggleKeyboard() }, "game_menu_toggle_keyboard", true))

        normalOptions.add(MenuOption(getString(R.string.game_menu_toggle_host_keyboard), true,
            { sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.s(), KeyboardTranslator.VK_LCONTROL.s(), KeyboardTranslator.VK_O.s())) },
            "game_menu_toggle_host_keyboard", true))

        normalOptions.add(MenuOption(
            touchModeDescription, false,
            { showTouchModeMenu() }, "mouse_mode", true, true))

        normalOptions.add(MenuOption(
            if (game.getisTouchOverrideEnabled()) getString(R.string.game_menu_disable_pan_zoom) else getString(R.string.game_menu_enable_pan_zoom),
            false,
            {
                Toast.makeText(game,
                    if (game.getisTouchOverrideEnabled()) getString(R.string.toast_pan_zoom_disabled) else getString(R.string.toast_pan_zoom_enabled),
                    Toast.LENGTH_SHORT).show()
                game.setisTouchOverrideEnabled(!game.getisTouchOverrideEnabled())
            },
            "game_menu_mouse_emulation", true
        ))

        // 王冠功能
        if (game.isCrownFeatureEnabled) {
            normalOptions.add(MenuOption(
                getString(R.string.game_menu_crown_function), false,
                { showCrownFunctionMenu() }, "crown_function_menu", true, true
            ))
        }

        if (device != null) {
            normalOptions.addAll(device.getGameMenuOptions())
        }

        // 性能显示
        normalOptions.add(MenuOption(
            perfOverlayMenuLabel, false,
            {
                game.togglePerformanceOverlay()
                rebuildAndReplaceMenu()
            },
            "game_menu_toggle_performance_overlay", true, true
        ))

        normalOptions.add(MenuOption(
            getString(R.string.game_menu_change_resolution), false,
            { showResolutionMenu() }, "game_menu_change_resolution", true, true
        ))

        if (game.prefConfig.onscreenController) {
            normalOptions.add(MenuOption(getString(R.string.game_menu_toggle_virtual_controller),
                false, { game.toggleVirtualController() }, "game_menu_toggle_virtual_controller", true))
        }

        normalOptions.add(MenuOption(getString(R.string.game_menu_send_keys),
            false, { showSpecialKeysMenu() }, "game_menu_send_keys", true, true))

        normalOptions.add(MenuOption(getString(R.string.game_menu_disconnect), true,
            { game.disconnect() }, "game_menu_disconnect", true))

        normalOptions.add(MenuOption(getString(R.string.game_menu_disconnect_and_quit), true,
            {
                if (game.prefConfig.lockScreenAfterDisconnect) lockAndDisconnectWithDelay()
                else disconnectAndQuit()
            }, "game_menu_disconnect_and_quit", true))
    }

    private val touchModeDescription: String
        get() {
            val prefix = getString(R.string.game_menu_switch_touch_mode) + ": "
            return prefix + when {
                game.prefConfig.enableNativeMousePointer -> getString(R.string.game_menu_touch_mode_native_mouse)
                game.prefConfig.touchscreenTrackpad -> getString(R.string.game_menu_touch_mode_trackpad)
                game.prefConfig.enableEnhancedTouch -> getString(R.string.game_menu_touch_mode_enhanced)
                else -> getString(R.string.game_menu_touch_mode_classic)
            }
        }

    private val perfOverlayMenuLabel: String
        get() {
            val status = when {
                !game.prefConfig.enablePerfOverlay -> getString(R.string.perf_overlay_hidden)
                game.prefConfig.perfOverlayLocked -> getString(R.string.perf_overlay_locked)
                else -> getString(R.string.perf_overlay_floating)
            }
            return getString(R.string.game_menu_toggle_performance_overlay) + ": " + status
        }

    fun lockAndDisconnectWithDelay() {
        sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.s(), KeyboardTranslator.VK_L.s()))
        disconnectAndQuit()
    }

    /**
     * 构建超级菜单选项
     */
    private fun buildSuperMenuOptions(superOptions: MutableList<MenuOption>) {
        val cmdList: JsonArray? = app.cmdList
        if (cmdList != null) {
            for (i in 0 until cmdList.size()) {
                val cmd = cmdList[i].asJsonObject
                superOptions.add(MenuOption(cmd["name"].asString, true, {
                    try {
                        conn.sendSuperCmd(cmd["id"].asString)
                    } catch (e: Exception) {
                        Toast.makeText(game, game.getString(R.string.toast_super_command_error, e.message), Toast.LENGTH_SHORT).show()
                    }
                }, null, false))
            }
        }
    }

    /**
     * 自定义适配器用于显示美化的菜单项
     */
    private class GameMenuAdapter(
        context: Context,
        private val options: Array<MenuOption>
    ) : ArrayAdapter<MenuOption>(context, 0, options) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false)

            val option = getItem(position)
            if (option != null) {
                val textView = view.findViewById<TextView>(R.id.menu_item_text)
                val iconView = view.findViewById<ImageView>(R.id.menu_item_icon)

                textView.text = option.label

                if (option.isShowIcon) {
                    iconView.setImageResource(getIconForMenuOption(option.iconKey))
                    iconView.visibility = View.VISIBLE
                } else {
                    iconView.visibility = View.GONE
                }
            }

            return view
        }
    }

    /**
     * 超级菜单适配器
     */
    private class SuperMenuAdapter(
        context: Context,
        private val options: Array<MenuOption>
    ) : ArrayAdapter<MenuOption>(context, 0, options) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false)

            val option = getItem(position)
            if (option != null) {
                val textView = view.findViewById<TextView>(R.id.menu_item_text)
                val iconView = view.findViewById<ImageView>(R.id.menu_item_icon)

                textView.text = option.label

                if (option.isShowIcon) {
                    iconView.setImageResource(R.drawable.ic_cmd_cute)
                    iconView.visibility = View.VISIBLE
                } else {
                    iconView.visibility = View.GONE
                }
            }

            return view
        }
    }

    companion object {
        private const val TEST_GAME_FOCUS_DELAY = 10L
        private const val KEY_UP_DELAY = 25L
        private const val SLEEP_DELAY = 200L
        private const val MAX_QUICK_BUTTONS = 6
        private const val DIALOG_ALPHA = 0.7f
        private const val DIALOG_DIM_AMOUNT = 0.3f
        private const val GAME_MENU_TITLE = "🍥🍬 V+ GAME MENU"

        private const val PREF_NAME = "custom_special_keys"
        private const val KEY_NAME = "data"

        private var mouse_enable_switch = false

        private val ICON_MAP = mapOf(
            "game_menu_change_resolution" to R.drawable.ic_resolution_cute,
            "game_menu_toggle_keyboard" to R.drawable.ic_keyboard_cute,
            "game_menu_toggle_performance_overlay" to R.drawable.ic_performance_cute,
            "game_menu_toggle_virtual_controller" to R.drawable.ic_controller_cute,
            "game_menu_disconnect" to R.drawable.ic_disconnect_cute,
            "game_menu_send_keys" to R.drawable.ic_send_keys_cute,
            "game_menu_toggle_host_keyboard" to R.drawable.ic_host_keyboard,
            "game_menu_disconnect_and_quit" to R.drawable.ic_btn_quit,
            "game_menu_cancel" to R.drawable.ic_cancel_cute,
            "mouse_mode" to R.drawable.ic_mouse_cute,
            "game_menu_mouse_emulation" to R.drawable.ic_mouse_emulation_cute,
            "crown_function_menu" to R.drawable.ic_super_crown,
            "game_menu_test_local_rumble" to R.drawable.ic_rumble_cute
        )

        fun getIconForMenuOption(iconKey: String?): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return ICON_MAP.getOrDefault(iconKey, R.drawable.ic_menu_item_default)
            }
            return -1
        }
    }
}
