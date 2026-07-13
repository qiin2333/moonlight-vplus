package com.limelight

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.activity.ComponentDialog
import com.google.gson.JsonArray
import com.limelight.binding.input.GameInputDevice
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.KeyCodeMapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import androidx.core.content.edit

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
    private var activeDialog: ComponentDialog? = null
    private var composeUiState: MutableState<GameMenuComposeUiState>? = null
    // 标志：上一次运行的选项是否打开了子菜单（由 showSubMenu 设置）
    private var lastActionOpenedSubmenu = false
    // 菜单历史栈，用于二级/多级菜单的回退
    private val menuStack: ArrayDeque<MenuPage> = ArrayDeque()
    private val handler = Handler(Looper.getMainLooper())
    private val actionExecutor = StreamActionExecutor(game, { conn }, handler)
    private val bitrateCardController = BitrateCardController(game, conn)
    private val gyroCardController = GyroCardController(game)
    init {
        showMenu()
    }

    fun dismiss() {
        activeDialog?.dismiss()
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
        val isKeepDialog: Boolean,
        val subtitle: String? = null,
        val isCrownControl: Boolean = false,
        val showChevron: Boolean = false
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
    private data class MenuPage(val title: String, val options: List<MenuOption>)

    /**
     * 获取字符串资源
     */
    private fun getString(id: Int): String = game.resources.getString(id)

    /**
     * 断开连接并退出
     */
    private fun disconnectAndQuit() {
        actionExecutor.disconnectAndQuit()
    }

    /**
     * 发送键盘按键序列
     */
    private fun sendKeys(keys: ShortArray) {
        actionExecutor.sendKeys(keys)
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

        //触控板双击功能
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(
                MenuOption(
                    getString(R.string.game_menu_touch_mode_trackpad) + " - " +
                            if (game.prefConfig.enableDoubleClickDrag) getString(R.string.game_menu_disable_double_click_drag) else getString(
                                R.string.game_menu_enable_double_click_drag
                            ),
                    false,
                    {
                        game.prefConfig.enableDoubleClickDrag =
                            !game.prefConfig.enableDoubleClickDrag
                        Toast.makeText(
                            game,
                            if (game.prefConfig.enableDoubleClickDrag) getString(R.string.toast_double_click_drag_enabled) else getString(
                                R.string.toast_double_click_drag_disabled
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    null, false
                )
            )
        }

        //触控板仅移动
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(
                MenuOption(
                    getString(R.string.game_menu_touch_mode_trackpad) + " - " +
                            if(game.isMouseMoveOnlyEnabled) getString(R.string.layout_page_device_text_mmo_true_text) else getString(R.string.layout_page_device_text_mmo_false_text),
                    false,
                    {
                        game.toggleMouseMoveOnly()
                        Toast.makeText(
                            game,
                            if (game.isMouseMoveOnlyEnabled) getString(R.string.layout_page_device_text_mmo_true_text) else getString(
                                R.string.layout_page_device_text_mmo_false_text
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    null, false
                )
            )
        }

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
     * 切换王冠功能并即时刷新菜单内容
     */
    private fun toggleCrownFeature() {
        setCrownFeatureEnabled(!game.isCrownFeatureEnabled, refreshMenu = true)
    }

    private fun getCrownToggleText(): String {
        return if (game.isCrownFeatureEnabled)
            getString(R.string.crown_switch_to_normal)
        else
            getString(R.string.crown_switch_to_crown)
    }

    private fun updateCrownToggleButton() {
        composeUiState?.let { state ->
            state.value = state.value.copy(crownToggleText = getCrownToggleText())
        }
    }

    private fun rebuildAndReplaceMenu() {
        activeDialog ?: return

        menuStack.clear()

        val normalOptions = mutableListOf<MenuOption>()
        buildNormalMenuOptions(normalOptions)
        composeUiState?.let { state ->
            state.value = state.value.copy(
                title = GAME_MENU_TITLE,
                options = normalOptions,
                crownToggleText = getCrownToggleText(),
                isSubmenu = false
            )
        }
    }

    /**
     * 显示"王冠功能"的二级菜单
     */
    private fun showCrownFunctionMenu() {
        val controllerManager = game.controllerManager

        if (!game.isCrownFeatureEnabled) {
            val disabledOptions = arrayOf(
                createCrownOption(
                    getString(R.string.crown_switch_to_crown),
                    "crown_enable",
                    getString(R.string.crown_control_enable_subtitle),
                    keepDialog = true
                ) {
                    setCrownFeatureEnabled(true)
                    replaceCrownFunctionMenu()
                }
            )
            showSubMenu(getString(R.string.game_menu_crown_function_title), disabledOptions)
            return
        }

        showSubMenu(getString(R.string.game_menu_crown_function_title), buildEnabledCrownFunctionOptions(controllerManager))
    }

    private fun createCrownOption(
        label: String,
        iconKey: String,
        subtitle: String,
        keepDialog: Boolean = false,
        action: () -> Unit
    ): MenuOption {
        return MenuOption(
            label,
            false,
            Runnable { action() },
            iconKey,
            isShowIcon = true,
            isKeepDialog = keepDialog,
            subtitle = subtitle,
            isCrownControl = true
        )
    }

    private fun setCrownFeatureEnabled(enabled: Boolean, refreshMenu: Boolean = false) {
        game.isCrownFeatureEnabled = enabled
        Toast.makeText(game,
            if (game.isCrownFeatureEnabled) getString(R.string.crown_switch_to_crown)
            else getString(R.string.crown_switch_to_normal),
            Toast.LENGTH_SHORT).show()
        updateCrownToggleButton()
        if (refreshMenu && activeDialog?.isShowing == true) {
            rebuildAndReplaceMenu()
        }
    }

    private fun replaceCrownFunctionMenu() {
        if (activeDialog?.isShowing == true) {
            showMenuPage(
                MenuPage(
                    getString(R.string.game_menu_crown_function_title),
                    buildEnabledCrownFunctionOptions(game.controllerManager).toList()
                )
            )
        }
    }

    private fun buildEnabledCrownFunctionOptions(controllerManager: com.limelight.binding.input.advance_setting.ControllerManager?): Array<MenuOption> {
        return arrayOf(
            createCrownOption(
                getString(R.string.game_menu_toggle_elements_visibility),
                "crown_visibility",
                getString(R.string.crown_control_visibility_subtitle)
            ) {
                game.toggleVirtualControllerVisibility()
            },
            createCrownOption(
                getString(R.string.game_menu_toggle_touch),
                "crown_touch",
                getString(R.string.crown_control_touch_subtitle)
            ) {
                controllerManager?.touchController?.enableTouch(mouse_enable_switch)
                Toast.makeText(game,
                    if (mouse_enable_switch) getString(R.string.toast_touch_enabled) else getString(R.string.toast_touch_disabled),
                    Toast.LENGTH_SHORT).show()
                mouse_enable_switch = !mouse_enable_switch
            },
            createCrownOption(
                getString(R.string.game_menu_configure_settings),
                "crown_profiles",
                getString(R.string.crown_control_profiles_subtitle)
            ) {
                controllerManager?.let { cm ->
                    game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.NO_MENU_LOCKED)
                    cm.pageConfigController?.open()
                }
            },
            createCrownOption(
                getString(R.string.game_menu_edit_mode),
                "crown_layout",
                getString(R.string.crown_control_layout_subtitle)
            ) {
                controllerManager?.let { cm ->
                    game.toggleBackKeyMenuType()
                    game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.NO_MENU)
                    cm.elementController?.changeMode(ElementController.Mode.Edit)
                    cm.elementController?.open()
                    cm.superPagesController?.let { spc ->
                        if (spc.pageNow === spc.pageNull) {
                            spc.returnOperation()
                        }
                    }
                }
            },
            createCrownOption(
                getString(R.string.game_menu_configure_crown_function),
                "crown_back_key",
                getString(R.string.crown_control_back_key_subtitle)
            ) {
                game.toggleBackKeyMenuType()
            }
        )
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
                } catch (_: NumberFormatException) {
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
            .edit {
                putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resString)
            }

        Toast.makeText(game, "Resolution changed to $resString. Restarting...", Toast.LENGTH_SHORT).show()

        game.changeResolution()
        activeDialog?.dismiss()
    }

    /**
     * 显示菜单对话框
     */
    private fun showMenuDialog(title: String, normalOptions: Array<MenuOption>, superOptions: Array<MenuOption>) {
        lateinit var dialog: ComponentDialog

        val state = mutableStateOf(
            GameMenuComposeUiState(
                title = title,
                options = normalOptions.toList(),
                superOptions = superOptions.toList(),
                appName = getAppNameDisplay(),
                crownToggleText = getCrownToggleText(),
                quickActions = buildComposeQuickActions(),
                visibleCards = readVisibleCards(),
                bitrate = bitrateCardController.snapshot(),
                gyro = gyroCardController.snapshot(),
                customKeys = getSavedCustomKeys()
            )
        )
        composeUiState = state
        bitrateCardController.start { bitrate ->
            composeUiState?.let { it.value = it.value.copy(bitrate = bitrate) }
        }
        gyroCardController.start { gyro ->
            composeUiState?.let { it.value = it.value.copy(gyro = gyro) }
        }

        val callbacks = GameMenuCallbacks(
            iconForOption = ::getIconForMenuOption,
            onBack = { navigateBack() },
            onCrownToggle = ::toggleCrownFeature,
            onOptionClick = { handleComposeOptionClick(it, dialog) },
            onQuickAction = ::runComposeQuickAction,
            onToggleQuickEdit = ::toggleComposeQuickEdit,
            onAddQuickAction = ::showQuickButtonEditor,
            onRemoveQuickAction = ::removeComposeQuickAction,
            onMoveQuickAction = ::moveComposeQuickAction,
            onEditCards = ::showCardEditorDialog,
            onBitrateProgress = bitrateCardController::previewProgress,
            onBitrateApply = bitrateCardController::applySelectedBitrate,
            onBitrateTip = bitrateCardController::showTip,
            onBitrateHapticMode = bitrateCardController::cycleHapticMode,
            onGyroEnabled = gyroCardController::setEnabled,
            onGyroMouseMode = gyroCardController::setMouseMode,
            onGyroActivationKey = gyroCardController::showActivationKeyDialog,
            onGyroSensitivity = gyroCardController::previewSensitivity,
            onGyroSensitivityFinished = gyroCardController::persistSensitivity,
            onGyroInvertX = gyroCardController::setInvertX,
            onGyroInvertY = gyroCardController::setInvertY,
            onCustomKey = { sendKeys(it.keys) }
        )

        val composeView = ComposeView(game).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                GameMenuScreen(state.value, callbacks)
            }
        }
        dialog = ComponentDialog(game, R.style.GameMenuDialogStyle).apply {
            setContentView(composeView)
        }
        this.activeDialog = dialog

        setupDialogProperties(dialog)

        // 返回键监听器
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (navigateBack()) {
                    return@setOnKeyListener true
                }
                return@setOnKeyListener false
            }
            false
        }

        // 关闭时清理状态
        dialog.setOnDismissListener {
            if (this.activeDialog == dialog) this.activeDialog = null
            this.composeUiState = null
            bitrateCardController.dispose()
            gyroCardController.dispose()
            menuStack.clear()
        }

        dialog.show()
        applyDialogSize(dialog)
    }

    private fun handleComposeOptionClick(option: MenuOption, dialog: ComponentDialog) {
        lastActionOpenedSubmenu = false

        // Focus-dependent actions must wait until the dialog has released the game window.
        // Dismissing first also preserves the interaction order of the legacy menu.
        if (option.isWithGameFocus && !option.isKeepDialog) {
            dialog.dismiss()
            option.runnable?.let(::runWithGameFocus)
            return
        }

        run(option)
        val shouldKeep = option.isKeepDialog || lastActionOpenedSubmenu
        if (!shouldKeep) dialog.dismiss()
        lastActionOpenedSubmenu = false
    }

    private fun getAppNameDisplay(): String {
        return try {
            val version = conn.serverVersion?.takeIf { it.isNotBlank() }
            if (version != null) "${app.appName}  Server $version" else app.appName
        } catch (_: Exception) {
            "Moonlight V+"
        }
    }

    private fun readVisibleCards(): GameMenuVisibleCards {
        return GameMenuVisibleCards(
            bitrate = game.prefConfig.showBitrateCard,
            gyro = game.prefConfig.showGyroCard,
            shortcuts = game.prefConfig.showQuickKeyCard
        )
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

        val dialog = AlertDialog.Builder(game, R.style.AppDialogStyle)
            .setTitle(getString(R.string.game_menu_card_config_title))
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                game.prefConfig.showBitrateCard = checked[0]
                game.prefConfig.showGyroCard = checked[1]
                game.prefConfig.showQuickKeyCard = checked[2]

                game.prefConfig.writePreferences(game)
                composeUiState?.let { state ->
                    state.value = state.value.copy(
                        visibleCards = readVisibleCards(),
                        customKeys = getSavedCustomKeys()
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        AppDialogStyler.applySystemChoiceList(dialog, game)
    }

    // --- 简单的按键数据模型 ---
    /**
     * 从存储或默认资源中获取解析好的按键数据列表
     */
    private fun getSavedCustomKeys(): List<CustomKeyData> {
        return CustomKeyRepository.load(game, showErrorToast = true)
    }

    private fun refreshComposeCustomKeys() {
        composeUiState?.let { state ->
            state.value = state.value.copy(customKeys = getSavedCustomKeys())
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * game.resources.displayMetrics.density).toInt()

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

    private fun buildComposeQuickActions(): List<GameMenuQuickAction> {
        return QuickActionRegistry.loadConfig(game).mapNotNull { id ->
            val (label, iconRes) = resolveAction(id) ?: return@mapNotNull null
            GameMenuQuickAction(
                id = id,
                label = label,
                iconRes = if (id == "toggle_mic" && !game.prefConfig.enableMic) {
                    QuickActionRegistry.getBuiltin(id)?.iconDisabledRes ?: iconRes
                } else {
                    iconRes
                },
                enabled = id != "toggle_mic" || game.prefConfig.enableMic
            )
        }
    }

    private fun refreshComposeQuickActions(editMode: Boolean? = null) {
        composeUiState?.let { state ->
            state.value = state.value.copy(
                quickActions = buildComposeQuickActions(),
                quickEditMode = editMode ?: state.value.quickEditMode
            )
        }
    }

    private fun runComposeQuickAction(id: String) {
        if (id == "toggle_mic" && !game.prefConfig.enableMic) {
            Toast.makeText(game, getString(R.string.toast_enable_mic_redirect), Toast.LENGTH_SHORT).show()
            return
        }
        actionExecutor.execute(id)
    }

    private fun toggleComposeQuickEdit() {
        val state = composeUiState ?: return
        state.value = state.value.copy(quickEditMode = !state.value.quickEditMode)
    }

    private fun removeComposeQuickAction(id: String) {
        val ids = QuickActionRegistry.loadConfig(game).toMutableList()
        if (ids.size <= 1 || !ids.remove(id)) return
        QuickActionRegistry.saveConfig(game, ids)
        refreshComposeQuickActions()
    }

    private fun moveComposeQuickAction(id: String, direction: Int) {
        val ids = QuickActionRegistry.loadConfig(game).toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, ids.lastIndex)
        if (from == to) return
        ids.add(to, ids.removeAt(from))
        QuickActionRegistry.saveConfig(game, ids)
        refreshComposeQuickActions()
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

        val dialog = AlertDialog.Builder(game, R.style.AppDialogStyle)
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
                refreshComposeQuickActions()
            }
            .setNegativeButton(getString(R.string.game_menu_cancel), null)
            .setNeutralButton(getString(R.string.quick_button_reset_default)) { _, _ ->
                QuickActionRegistry.saveConfig(game, QuickActionRegistry.DEFAULT_IDS.toMutableList())
                refreshComposeQuickActions()
            }
            .create()
        dialog.show()
        AppDialogStyler.applySystemChoiceList(dialog, game)
    }

    private fun currentMenuPage(): MenuPage? {
        return composeUiState?.value?.let { MenuPage(it.title, it.options) }
    }

    private fun showMenuPage(page: MenuPage, pushCurrent: Boolean = false) {
        val state = composeUiState ?: return
        if (pushCurrent) currentMenuPage()?.let(menuStack::push)
        state.value = state.value.copy(
            title = page.title,
            options = page.options,
            isSubmenu = menuStack.isNotEmpty()
        )
    }

    private fun navigateBack(): Boolean {
        if (menuStack.isEmpty()) return false
        showMenuPage(menuStack.pop())
        return true
    }

    /**
     * 在当前打开的 dialog 中显示一个子菜单
     */
    private fun showSubMenu(title: String, subOptions: Array<MenuOption>) {
        val dialog = activeDialog
        if (dialog != null && dialog.isShowing) {
            lastActionOpenedSubmenu = true
            showMenuPage(MenuPage(title, subOptions.toList()), pushCurrent = true)
        } else {
            showMenuDialog(title, subOptions, emptyArray())
        }
    }

    private fun setupDialogProperties(dialog: ComponentDialog) {
        dialog.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.alpha = DIALOG_ALPHA
            layoutParams.dimAmount = DIALOG_DIM_AMOUNT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            window.attributes = layoutParams
            window.setBackgroundDrawableResource(R.drawable.game_menu_dialog_bg)
        }
    }

    private fun applyDialogSize(dialog: ComponentDialog) {
        val metrics = game.resources.displayMetrics
        val widthFraction = if (metrics.widthPixels > metrics.heightPixels) 0.72f else 0.94f
        dialog.window?.setLayout(
            (metrics.widthPixels * widthFraction).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
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

            preferences.edit { putString(KEY_NAME, root.toString()) }

            Toast.makeText(game, game.getString(R.string.toast_custom_key_saved, name), Toast.LENGTH_SHORT).show()
            refreshComposeCustomKeys()
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
                } catch (_: Exception) {
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

            val dialog = AlertDialog.Builder(game, R.style.AppDialogStyle)
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
                        preferences.edit { putString(KEY_NAME, root.toString()) }
                        Toast.makeText(game, R.string.toast_selected_keys_deleted, Toast.LENGTH_SHORT).show()
                        refreshComposeCustomKeys()
                    } catch (e: Exception) {
                        LimeLog.warning("Exception while deleting keys${e.message}")
                        Toast.makeText(game, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create()
            dialog.show()
            AppDialogStyler.applySystemChoiceList(dialog, game)
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
            { showTouchModeMenu() }, "mouse_mode", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

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
        normalOptions.add(MenuOption(
            getString(R.string.game_menu_crown_function), false,
            { showCrownFunctionMenu() }, "crown_function_menu", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

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
            "game_menu_toggle_performance_overlay", isShowIcon = true, isKeepDialog = true
        ))

        normalOptions.add(MenuOption(
            getString(R.string.game_menu_change_resolution), false,
            { showResolutionMenu() }, "game_menu_change_resolution", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

        if (game.prefConfig.onscreenController) {
            normalOptions.add(MenuOption(getString(R.string.game_menu_toggle_virtual_controller),
                false, { game.toggleVirtualController() }, "game_menu_toggle_virtual_controller", true))
        }

        normalOptions.add(MenuOption(getString(R.string.game_menu_send_keys),
            false, { showSpecialKeysMenu() }, "game_menu_send_keys", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

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

    companion object {
        private const val TEST_GAME_FOCUS_DELAY = 10L
        private const val MAX_QUICK_BUTTONS = 6
        private const val DIALOG_ALPHA = 1.0f
        private const val DIALOG_DIM_AMOUNT = 0.5f
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
            "crown_enable" to R.drawable.ic_super_crown,
            "crown_visibility" to R.drawable.ic_ui_settings,
            "crown_touch" to R.drawable.ic_touch_settings,
            "crown_profiles" to R.drawable.ic_input_settings,
            "crown_layout" to R.drawable.ic_gamepad_settings,
            "crown_back_key" to R.drawable.ic_keyboard_cute,
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
