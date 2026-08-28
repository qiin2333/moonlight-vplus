package com.limelight.gamemenu

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.preference.PreferenceManager
import com.google.gson.JsonArray
import com.limelight.CustomKeyData
import com.limelight.CustomKeyRepository
import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.QuickActionRegistry
import com.limelight.R
import com.limelight.StreamActionExecutor
import com.limelight.binding.input.GameInputDevice
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.TouchModePreset
import com.limelight.ui.UiDismissKeyHandler
import com.limelight.utils.AppActionSheet
import com.limelight.utils.KeyCodeMapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque

/** Int → Short 快捷转换 */
private fun Int.s(): Short = this.toShort()

// Diagonal D-pad key codes were added in API 24. Keep protocol values for minSdk 22.
internal const val GAME_MENU_KEYCODE_DPAD_UP_LEFT = 268
internal const val GAME_MENU_KEYCODE_DPAD_UP_RIGHT = 269
internal const val GAME_MENU_KEYCODE_DPAD_DOWN_LEFT = 270
internal const val GAME_MENU_KEYCODE_DPAD_DOWN_RIGHT = 271

internal fun mapGameMenuConfirmKeyCode(keyCode: Int): Int {
    return when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
        GAME_MENU_KEYCODE_DPAD_UP_LEFT,
        GAME_MENU_KEYCODE_DPAD_UP_RIGHT -> KeyEvent.KEYCODE_DPAD_UP
        GAME_MENU_KEYCODE_DPAD_DOWN_LEFT,
        GAME_MENU_KEYCODE_DPAD_DOWN_RIGHT -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> keyCode
    }
}

internal fun isGameMenuNavigationKey(keyCode: Int): Boolean {
    return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_UP_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_UP_RIGHT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_RIGHT ||
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A ||
        keyCode == KeyEvent.KEYCODE_TAB
}

internal fun nearestFocusIndex(sourceCenter: Int, targetCenters: List<Int>): Int {
    require(targetCenters.isNotEmpty())
    return targetCenters.indices.minBy { index ->
        kotlin.math.abs(targetCenters[index] - sourceCenter)
    }
}

internal fun resolveCustomKeyName(enteredName: String, selectedKeysName: String): String {
    return enteredName.trim().ifEmpty { selectedKeysName.trim() }
}

internal fun createGameMenuBackOption(
    label: String,
    onBack: () -> Unit
) = GameMenu.MenuOption(
    label = label,
    isWithGameFocus = false,
    runnable = Runnable(onBack),
    iconKey = null,
    isShowIcon = false,
    isKeepDialog = true
)

internal fun isGameMenuDirectionalKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_UP_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_UP_RIGHT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_RIGHT

internal fun shouldIgnoreGameMenuDirectionalRepeat(
    action: Int,
    repeatCount: Int,
    alreadyHeld: Boolean
): Boolean = action == KeyEvent.ACTION_DOWN && repeatCount > 0 && alreadyHeld

internal fun canActivateGameMenuAxisSource(
    activeSourceId: Int?,
    reportingSourceId: Int
): Boolean = activeSourceId == null || activeSourceId == reportingSourceId

private fun isGameMenuDiagonalKey(keyCode: Int): Boolean =
    keyCode == GAME_MENU_KEYCODE_DPAD_UP_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_UP_RIGHT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_LEFT ||
        keyCode == GAME_MENU_KEYCODE_DPAD_DOWN_RIGHT

private fun mapGameMenuConfirmKeyEvent(event: KeyEvent): KeyEvent {
    val mappedKeyCode = mapGameMenuConfirmKeyCode(event.keyCode)
    return if (mappedKeyCode != event.keyCode) {
        KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            mappedKeyCode,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source
        )
    } else {
        event
    }
}

/**
 * 提供游戏流媒体进行中的选项菜单
 * 在游戏活动中按返回键时显示
 */
class GameMenu(
    private val game: Game,
    private val app: NvApp,
    private val conn: NvConnection,
    private val device: GameInputDevice?,
    private val onDismiss: (GameMenu) -> Unit = {}
) {
    // 当前激活的对话框（如果有）
    private var activeDialog: ComponentDialog? = null
    private var activeChildDialog: Dialog? = null
    private var activeChildDismissKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var activeComposeView: ComposeView? = null
    private var parentFocusRestoreRequestState: MutableIntState? = null
    private var composeUiState: MutableState<GameMenuComposeUiState>? = null
    private val guideDismissController = GameMenuGuideDismissController()
    // 标志：上一次运行的选项是否打开了子菜单（由 showSubMenu 设置）
    private var lastActionOpenedSubmenu = false
    // 菜单历史栈，用于二级/多级菜单的回退
    private val menuStack: ArrayDeque<MenuPage> = ArrayDeque()
    private val handler = Handler(Looper.getMainLooper())
    private data class HeldControllerDirection(
        val targetDialog: Dialog,
        val downEvent: KeyEvent
    )

    private val axisNavigationStates = mutableMapOf<Int, MenuAxisNavigationState>()
    private val axisSourcesAwaitingNeutral = mutableSetOf<Int>()
    private val heldControllerDirections = linkedMapOf<Pair<Int, Int>, HeldControllerDirection>()
    private val controllerDirectionsAwaitingRelease = mutableSetOf<Pair<Int, Int>>()
    private var repeatingControllerDirection: Pair<Int, Int>? = null
    private var controllerDirectionRepeatCount = 0
    private val controllerDirectionRepeatRunnable = object : Runnable {
        override fun run() {
            val identity = repeatingControllerDirection ?: return
            val held = heldControllerDirections[identity] ?: return
            if (!held.targetDialog.isShowing) {
                repeatingControllerDirection = null
                controllerDirectionRepeatCount = 0
                return
            }

            controllerDirectionRepeatCount++
            held.targetDialog.dispatchKeyEvent(
                KeyEvent.changeTimeRepeat(
                    held.downEvent,
                    SystemClock.uptimeMillis(),
                    controllerDirectionRepeatCount
                )
            )
            handler.postDelayed(this, AXIS_REPEAT_INTERVAL_MS)
        }
    }
    private var activeAxisSourceId: Int? = null
    private var activeAxisKeyCode: Int? = null
    private var activeAxisTargetDialog: Dialog? = null
    private var activeAxisDownTime = 0L
    private var activeAxisRepeatCount = 0
    private val axisRepeatRunnable = object : Runnable {
        override fun run() {
            val keyCode = activeAxisKeyCode ?: return
            val dialog = activeAxisTargetDialog ?: return
            if (!dialog.isShowing) return
            activeAxisRepeatCount++
            val now = SystemClock.uptimeMillis()
            dialog.dispatchKeyEvent(
                KeyEvent(activeAxisDownTime, now, KeyEvent.ACTION_DOWN, keyCode, activeAxisRepeatCount)
            )
            handler.postDelayed(this, AXIS_REPEAT_INTERVAL_MS)
        }
    }
    private val gameFocusActionRunner = GameFocusActionRunner(
        canRun = { !game.isFinishing },
        hasGameFocus = game::hasWindowFocus,
        scheduleRetry = { action -> handler.postDelayed(action, GAME_FOCUS_RETRY_DELAY_MS) }
    )
    private val actionExecutor = StreamActionExecutor(game, { conn }, handler)
    private val bitrateCardController = BitrateCardController(game, conn)
    private val audioHapticsCardController = AudioHapticsCardController(game)
    private val gyroCardController = GyroCardController(game)
    private val touchPointerSensitivityController = TouchPointerSensitivityController(game)
    private val renderingProfile = GameMenuRenderingProfile.from(game)
    private val systemHapticsEnabled = Settings.System.getInt(
        game.contentResolver,
        HAPTIC_FEEDBACK_SETTING,
        1
    ) != 0

    init {
        showMenu()
    }

    fun dismiss() {
        activeDialog?.dismiss()
    }

    fun isShowing(): Boolean {
        return activeDialog?.isShowing == true
    }

    fun dispatchControllerKeyEvent(event: KeyEvent): Boolean {
        val mappedEvent = mapGameMenuConfirmKeyEvent(event)
        if (isGameMenuDirectionalKey(mappedEvent.keyCode)) {
            return dispatchControllerDirectionKeyEvent(mappedEvent)
        }
        return dispatchControllerKeyEventToCurrentOwner(mappedEvent)
    }

    private fun dispatchControllerDirectionKeyEvent(event: KeyEvent): Boolean {
        val identity = event.deviceId to event.keyCode
        if (identity in controllerDirectionsAwaitingRelease) {
            if (event.action == KeyEvent.ACTION_UP) {
                controllerDirectionsAwaitingRelease.remove(identity)
            }
            return true
        }
        val held = heldControllerDirections[identity]
        if (shouldIgnoreGameMenuDirectionalRepeat(event.action, event.repeatCount, held != null)) {
            return true
        }
        val target = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (held == null) {
                    val currentTarget = currentInputDialog() ?: return false
                    heldControllerDirections[identity] = HeldControllerDirection(
                        targetDialog = currentTarget,
                        downEvent = KeyEvent(event)
                    )
                    startControllerDirectionRepeat(identity)
                    currentTarget
                } else {
                    held.targetDialog
                }
            }
            KeyEvent.ACTION_UP -> {
                val released = heldControllerDirections.remove(identity)
                if (repeatingControllerDirection == identity) {
                    stopControllerDirectionRepeat()
                    heldControllerDirections.keys.lastOrNull()?.let(::startControllerDirectionRepeat)
                }
                released?.targetDialog ?: return true
            }
            else -> return true
        }

        return dispatchControllerKeyEventToOwner(target, event)
    }

    private fun startControllerDirectionRepeat(identity: Pair<Int, Int>) {
        handler.removeCallbacks(controllerDirectionRepeatRunnable)
        repeatingControllerDirection = identity
        controllerDirectionRepeatCount = 0
        handler.postDelayed(controllerDirectionRepeatRunnable, AXIS_REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopControllerDirectionRepeat() {
        handler.removeCallbacks(controllerDirectionRepeatRunnable)
        repeatingControllerDirection = null
        controllerDirectionRepeatCount = 0
    }

    private fun dispatchControllerKeyEventToCurrentOwner(event: KeyEvent): Boolean {
        val dialog = currentInputDialog() ?: return false
        return dispatchControllerKeyEventToOwner(dialog, event)
    }

    private fun dispatchControllerKeyEventToOwner(dialog: Dialog, event: KeyEvent): Boolean {
        if (dialog === activeChildDialog) {
            if (!dialog.isShowing) return true
            if (activeChildDismissKeyHandler?.invoke(event) == true) return true
            if (UiDismissKeyHandler.handle(event.action, event.keyCode) {
                    prepareForInputOwnerChange()
                    dialog.cancel()
                }
            ) {
                return true
            }
            dialog.dispatchKeyEvent(event)
            return true
        }

        if (dialog === activeDialog) {
            if (!dialog.isShowing) return false
            dialog.dispatchKeyEvent(event)
            return activeDialog === dialog && dialog.isShowing
        }

        // Direction releases stay with the Dialog that received their DOWN instead of
        // leaking into whichever child surface is currently top-most.
        if (event.action == KeyEvent.ACTION_UP) {
            dialog.dispatchKeyEvent(event)
        }
        return activeDialog?.isShowing == true
    }

    fun dispatchControllerAxes(
        sourceId: Int,
        axisPairs: List<Pair<Float, Float>>
    ): Boolean {
        val dialog = currentInputDialog() ?: return false
        if (!dialog.isShowing) return false
        val state = axisNavigationStates.getOrPut(sourceId) { MenuAxisNavigationState() }

        if (sourceId in axisSourcesAwaitingNeutral) {
            state.reset()
            if (state.isNeutral(axisPairs)) {
                axisSourcesAwaitingNeutral.remove(sourceId)
            }
            return true
        }

        val transition = state.update(axisPairs)
        if (!transition.changed) return true

        if (transition.pressedKeyCode != null &&
            canActivateGameMenuAxisSource(activeAxisSourceId, sourceId)
        ) {
            activateAxisSource(sourceId, transition.pressedKeyCode, dialog)
        } else if (activeAxisSourceId == sourceId) {
            releaseActiveAxisKey()
            activateFallbackAxisSource(dialog)
        }
        return true
    }

    fun releaseControllerAxisSource(sourceId: Int) {
        axisSourcesAwaitingNeutral.remove(sourceId)
        axisNavigationStates.remove(sourceId)
        controllerDirectionsAwaitingRelease.removeAll { it.first == sourceId }
        val removedDirectionIds = heldControllerDirections.keys.filter { it.first == sourceId }
        removedDirectionIds.forEach { identity ->
            heldControllerDirections.remove(identity)?.let(::releaseHeldControllerDirection)
        }
        if (repeatingControllerDirection?.first == sourceId) {
            stopControllerDirectionRepeat()
            heldControllerDirections.keys.lastOrNull()?.let(::startControllerDirectionRepeat)
        }
        if (activeAxisSourceId == sourceId) {
            releaseActiveAxisKey()
            currentInputDialog()?.takeIf(Dialog::isShowing)?.let(::activateFallbackAxisSource)
        }
    }

    private fun activateAxisSource(sourceId: Int, keyCode: Int, dialog: Dialog) {
        if (activeAxisSourceId == sourceId &&
            activeAxisKeyCode == keyCode &&
            activeAxisTargetDialog === dialog
        ) {
            return
        }
        releaseActiveAxisKey()
        activeAxisSourceId = sourceId
        activeAxisKeyCode = keyCode
        activeAxisTargetDialog = dialog
        activeAxisDownTime = SystemClock.uptimeMillis()
        activeAxisRepeatCount = 0
        dialog.dispatchKeyEvent(
            KeyEvent(activeAxisDownTime, activeAxisDownTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        handler.postDelayed(axisRepeatRunnable, AXIS_REPEAT_INITIAL_DELAY_MS)
    }

    private fun activateFallbackAxisSource(dialog: Dialog) {
        val fallback = axisNavigationStates.entries.firstOrNull { (sourceId, state) ->
            sourceId !in axisSourcesAwaitingNeutral && state.activeKeyCode != null
        } ?: return
        activateAxisSource(fallback.key, fallback.value.activeKeyCode!!, dialog)
    }

    private fun releaseActiveAxisKey() {
        handler.removeCallbacks(axisRepeatRunnable)
        val keyCode = activeAxisKeyCode
        val targetDialog = activeAxisTargetDialog
        if (keyCode != null && targetDialog != null) {
            val now = SystemClock.uptimeMillis()
            targetDialog.dispatchKeyEvent(
                KeyEvent(activeAxisDownTime, now, KeyEvent.ACTION_UP, keyCode, 0)
            )
        }
        activeAxisSourceId = null
        activeAxisKeyCode = null
        activeAxisTargetDialog = null
        activeAxisRepeatCount = 0
    }

    private fun prepareForInputOwnerChange() {
        releaseHeldControllerDirections(awaitForPhysicalRelease = true)
        axisNavigationStates.forEach { (sourceId, state) ->
            if (state.activeKeyCode != null) {
                axisSourcesAwaitingNeutral.add(sourceId)
            }
            state.reset()
        }
        releaseActiveAxisKey()
    }

    private fun resetAxisNavigation() {
        releaseHeldControllerDirections(awaitForPhysicalRelease = false)
        controllerDirectionsAwaitingRelease.clear()
        releaseActiveAxisKey()
        axisNavigationStates.clear()
        axisSourcesAwaitingNeutral.clear()
    }

    private fun releaseHeldControllerDirections(awaitForPhysicalRelease: Boolean) {
        stopControllerDirectionRepeat()
        val heldSnapshot = heldControllerDirections.toMap()
        heldControllerDirections.clear()
        heldSnapshot.forEach { (identity, held) ->
            releaseHeldControllerDirection(held)
            if (awaitForPhysicalRelease) {
                controllerDirectionsAwaitingRelease.add(identity)
            }
        }
    }

    private fun releaseHeldControllerDirection(held: HeldControllerDirection) {
        val down = held.downEvent
        val now = SystemClock.uptimeMillis()
        held.targetDialog.dispatchKeyEvent(
            KeyEvent(
                down.downTime,
                now,
                KeyEvent.ACTION_UP,
                down.keyCode,
                0,
                down.metaState,
                down.deviceId,
                down.scanCode,
                down.flags,
                down.source
            )
        )
    }

    private fun currentInputDialog(): Dialog? {
        return activeChildDialog?.takeIf(Dialog::isShowing)
            ?: activeDialog?.takeIf(ComponentDialog::isShowing)
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
        val showChevron: Boolean = false,
        val inlineControl: InlineControl? = null,
        val selected: Boolean = false,
        val presentation: GameMenuOptionPresentation = GameMenuOptionPresentation.DEFAULT
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

    sealed interface InlineControl {
        data class Toggle(
            val checked: Boolean,
            val toggleAction: Runnable? = null
        ) : InlineControl
        data class Segmented(
            val segments: List<SegmentOption>,
            val smallScreenColumnCount: Int? = null
        ) : InlineControl
    }

    data class SegmentOption(
        val label: String,
        val selected: Boolean,
        val runnable: Runnable,
        val subtitle: String? = null
    )

    /**
     * 菜单状态，用于回退
     */
    private data class MenuPage(
        val title: String,
        val options: List<MenuOption>,
        val layout: GameMenuPageLayout = GameMenuPageLayout.STANDARD
    )

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
     * 执行菜单选项
     */
    private fun run(option: MenuOption?) {
        if (option?.runnable == null) return

        if (option.isWithGameFocus) {
            gameFocusActionRunner.run(option.runnable)
        } else {
            option.runnable.run()
        }
    }

    /**
     * 显示触控模式菜单
     */
    private fun showTouchModeMenu() {
        // While the DS5 touchpad captures screen touches, the trackpad-specific
        // options below are inert, so treat trackpad mode as inactive.
        val isTouchscreenTrackpad = game.prefConfig.touchscreenTrackpad &&
            !game.prefConfig.screenDs5Touchpad
        val touchModeOptionsList = buildTouchModeSegments().mapTo(mutableListOf()) { segment ->
            MenuOption(
                label = segment.label,
                isWithGameFocus = false,
                runnable = segment.runnable,
                iconKey = null,
                isShowIcon = false,
                isKeepDialog = true,
                subtitle = segment.subtitle,
                selected = segment.selected,
                presentation = GameMenuOptionPresentation.PRIMARY_MODE
            )
        }

        //触控板双击功能
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(
                MenuOption(
                    getString(R.string.game_menu_trackpad_double_click_drag),
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
                    null,
                    false,
                    true,
                    if (game.prefConfig.enableDoubleClickDrag) {
                        getString(R.string.game_menu_option_enabled)
                    } else {
                        getString(R.string.game_menu_option_disabled)
                    },
                    presentation = GameMenuOptionPresentation.COMPATIBLE_ACTION
                )
            )
        }

        if (isTouchscreenTrackpad) {
            val localCursorToggleAction = Runnable { toggleLocalCursorRendering() }
            touchModeOptionsList.add(
                MenuOption(
                    label = getString(R.string.game_menu_local_cursor_rendering),
                    isWithGameFocus = false,
                    runnable = localCursorToggleAction,
                    iconKey = null,
                    isShowIcon = false,
                    isKeepDialog = true,
                    subtitle = getString(R.string.summary_local_cursor_rendering),
                    inlineControl = InlineControl.Toggle(
                        checked = game.prefConfig.enableLocalCursorRendering,
                        toggleAction = localCursorToggleAction
                    ),
                    presentation = GameMenuOptionPresentation.COMPATIBLE_ACTION
                )
            )
        }

        touchModeOptionsList.add(
            MenuOption(
                label = getString(R.string.game_menu_toggle_remote_mouse),
                isWithGameFocus = false,
                runnable = Runnable { actionExecutor.execute("toggle_remote_mouse") },
                iconKey = null,
                isShowIcon = false,
                isKeepDialog = true,
                subtitle = getString(R.string.game_menu_toggle_remote_mouse_summary),
                presentation = GameMenuOptionPresentation.COMPATIBLE_ACTION
            )
        )

        //触控板仅移动
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(
                MenuOption(
                    getString(R.string.game_menu_trackpad_tap_behavior),
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
                    null,
                    false,
                    true,
                    if (game.isMouseMoveOnlyEnabled) {
                        getString(R.string.layout_page_device_text_mmo_true_text)
                    } else {
                        getString(R.string.layout_page_device_text_mmo_false_text)
                    },
                    presentation = GameMenuOptionPresentation.COMPATIBLE_ACTION
                )
            )
        }

        val title = getString(R.string.game_menu_switch_touch_mode)
        if (composeUiState?.value?.pageLayout == GameMenuPageLayout.TOUCH_MODE) {
            showMenuPage(MenuPage(title, touchModeOptionsList, GameMenuPageLayout.TOUCH_MODE))
        } else {
            showSubMenu(
                title,
                touchModeOptionsList.toTypedArray(),
                GameMenuPageLayout.TOUCH_MODE
            )
        }
    }

    private fun toggleLocalCursorRendering() {
        game.prefConfig.enableLocalCursorRendering =
            !game.prefConfig.enableLocalCursorRendering
        game.cursorServiceManager.refreshCursorMode()
        game.prefConfig.writePreferences(game)
        Toast.makeText(
            game,
            getString(
                if (game.prefConfig.enableLocalCursorRendering) {
                    R.string.toast_local_cursor_enabled
                } else {
                    R.string.toast_local_cursor_disabled
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun buildTouchModeSegments(compactLabels: Boolean = false): List<SegmentOption> {
        val isEnhancedTouch = game.prefConfig.enableEnhancedTouch
        val isTrackpad = game.prefConfig.touchscreenTrackpad
        val isNativePointer = game.prefConfig.enableNativeMousePointer
        val isScreenDs5Touchpad = game.prefConfig.screenDs5Touchpad

        val segments = mutableListOf(
            SegmentOption(
                label = getString(if (compactLabels) R.string.game_menu_touch_mode_enhanced_short else R.string.game_menu_touch_mode_enhanced),
                selected = isEnhancedTouch && !isTrackpad && !isNativePointer && !isScreenDs5Touchpad,
                runnable = Runnable {
                    game.setScreenDs5TouchpadEnabled(false)
                    game.prefConfig.enableEnhancedTouch = true
                    game.prefConfig.enableNativeMousePointer = false
                    game.enableNativeMousePointer(false)
                    game.setTouchMode(false)
                    updateEnhancedTouchSetting(true)
                    updateTouchModeSetting(false)
                    persistTouchModeSelection(TouchModePreset.ENHANCED)
                },
                subtitle = getString(R.string.game_menu_touch_mode_enhanced_summary)
            ),
            SegmentOption(
                label = getString(if (compactLabels) R.string.game_menu_touch_mode_classic_short else R.string.game_menu_touch_mode_classic),
                selected = !isEnhancedTouch && !isTrackpad && !isNativePointer && !isScreenDs5Touchpad,
                runnable = Runnable {
                    game.setScreenDs5TouchpadEnabled(false)
                    game.prefConfig.enableEnhancedTouch = false
                    game.prefConfig.enableNativeMousePointer = false
                    game.enableNativeMousePointer(false)
                    game.setTouchMode(false)
                    updateEnhancedTouchSetting(false)
                    updateTouchModeSetting(false)
                    persistTouchModeSelection(TouchModePreset.CLASSIC)
                },
                subtitle = getString(R.string.game_menu_touch_mode_classic_summary)
            ),
            SegmentOption(
                label = getString(if (compactLabels) R.string.game_menu_touch_mode_trackpad_short else R.string.game_menu_touch_mode_trackpad),
                selected = isTrackpad && !isNativePointer && !isScreenDs5Touchpad,
                runnable = Runnable {
                    game.setScreenDs5TouchpadEnabled(false)
                    game.prefConfig.enableEnhancedTouch = false
                    game.prefConfig.enableNativeMousePointer = false
                    game.enableNativeMousePointer(false)
                    game.setTouchMode(true)
                    updateEnhancedTouchSetting(false)
                    updateTouchModeSetting(true)
                    persistTouchModeSelection(TouchModePreset.TRACKPAD)
                },
                subtitle = getString(R.string.game_menu_touch_mode_trackpad_summary)
            ),
            SegmentOption(
                label = getString(if (compactLabels) R.string.game_menu_touch_mode_native_mouse_short else R.string.game_menu_touch_mode_native_mouse),
                selected = isNativePointer && !isScreenDs5Touchpad,
                runnable = Runnable {
                    game.setScreenDs5TouchpadEnabled(false)
                    game.prefConfig.enableNativeMousePointer = true
                    game.prefConfig.enableEnhancedTouch = false
                    game.setTouchMode(false)
                    game.enableNativeMousePointer(true)
                    updateEnhancedTouchSetting(false)
                    updateTouchModeSetting(false)
                    persistTouchModeSelection(TouchModePreset.NATIVE)
                },
                subtitle = getString(R.string.game_menu_touch_mode_native_mouse_summary)
            )
        )

        // Only offer the segment on touchscreen devices, unless the host already
        // rejected controller touch this stream and the feature is off; keep it
        // visible when enabled so it can be turned off. Unsupported hosts are
        // surfaced by a one-time toast instead.
        val hasTouchscreen = game.packageManager.hasSystemFeature(
            PackageManager.FEATURE_TOUCHSCREEN
        )
        if (hasTouchscreen &&
            (isScreenDs5Touchpad ||
                game.screenDs5TouchpadHostSupport != Game.ScreenDs5HostSupport.UNSUPPORTED)
        ) {
            segments.add(
                SegmentOption(
                    label = getString(if (compactLabels) R.string.game_menu_touch_mode_ds5_short else R.string.game_menu_touch_mode_ds5),
                    selected = isScreenDs5Touchpad,
                    runnable = Runnable {
                        game.setScreenDs5TouchpadEnabled(true)
                        touchPointerSensitivityController.refreshApplicability()
                    },
                    subtitle = getString(R.string.game_menu_touch_mode_ds5_summary)
                )
            )
        }
        return segments
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

    private fun persistTouchModeSelection(preset: TouchModePreset) {
        game.prefConfig.writePreferences(game)
        PreferenceManager.getDefaultSharedPreferences(game).edit {
            putString(
                PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
                preset.preferenceValue
            )
        }
        touchPointerSensitivityController.refreshApplicability()
    }

    /**
     * 切换王冠功能并即时刷新菜单内容
     */
    private fun toggleCrownFeature() {
        setCrownFeatureEnabled(!game.isCrownFeatureEnabled)
        rebuildAndReplaceMenu()
    }

    private fun getCrownToggleText(): String {
        return if (game.isCrownFeatureEnabled)
            getString(R.string.crown_switch_to_normal)
        else
            getString(R.string.crown_switch_to_crown)
    }

    private fun rebuildAndReplaceMenu() {
        activeDialog ?: return

        menuStack.clear()

        val normalOptions = mutableListOf<MenuOption>()
        buildNormalMenuOptions(normalOptions)
        composeUiState?.let { state ->
            state.value = state.value.copy(
                title = getString(R.string.game_menu_title),
                options = normalOptions,
                deviceQuickOptions = device?.getGameMenuQuickOptions().orEmpty(),
                crownToggleText = getCrownToggleText(),
                isSubmenu = false,
                pageLayout = GameMenuPageLayout.STANDARD
            )
        }
    }

    private fun refreshCurrentMenuPage() {
        if (composeUiState?.value?.pageLayout == GameMenuPageLayout.TOUCH_MODE) {
            showTouchModeMenu()
        } else {
            rebuildAndReplaceMenu()
        }
    }

    /**
     * 显示"王冠功能"的二级菜单
     */
    private fun showCrownFunctionMenu() {
        if (!game.isCrownFeatureEnabled) return
        showSubMenu(
            getString(R.string.game_menu_crown_function_title),
            buildEnabledCrownFunctionOptions(game.controllerManager)
        )
    }

    private fun createCrownOption(
        label: String,
        iconKey: String,
        subtitle: String,
        action: () -> Unit
    ): MenuOption {
        return MenuOption(
            label = label,
            isWithGameFocus = false,
            runnable = Runnable { action() },
            iconKey = iconKey,
            isShowIcon = true,
            isKeepDialog = false,
            subtitle = subtitle,
            isCrownControl = true
        )
    }

    private fun setCrownFeatureEnabled(enabled: Boolean) {
        game.isCrownFeatureEnabled = enabled
        val message = if (game.isCrownFeatureEnabled) {
            getString(R.string.crown_mode_crown)
        } else {
            getString(R.string.crown_mode_normal)
        }
        Toast.makeText(game, message, Toast.LENGTH_SHORT).show()
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
                ch.handleTestRumble(n.toShort(), on, on)
            }

            handler.postDelayed({
                try {
                    for (n in 0.toShort()..3.toShort()) {
                        ch.handleTestRumble(n.toShort(), off, off)
                    }
                } catch (_: Exception) {}
            }, 1000)

            Toast.makeText(game, getString(R.string.toast_vibration_test_sent), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(game, game.getString(R.string.toast_vibration_test_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** Routes menu touch feedback through the non-blocking phone-vibration coordinator. */
    private fun dispatchHapticFeedback(feedbackConstant: Int) {
        if (!systemHapticsEnabled) return

        val (motor, durationMs) = when (feedbackConstant) {
            HapticFeedbackConstants.LONG_PRESS -> 0x4800.toShort() to 35
            HapticFeedbackConstants.CLOCK_TICK -> 0x1800.toShort() to 12
            else -> 0x2800.toShort() to 20
        }
        game.controllerHandler.playDeviceTouchHaptic(motor, motor, durationMs)
    }

    /**
     * 显示分辨率选择菜单
     */
    private fun showResolutionMenu() {
        val options = mutableListOf<MenuOption>()
        val currentResStr = "${game.prefConfig.width}x${game.prefConfig.height}"
        val prefs = PreferenceManager.getDefaultSharedPreferences(game)
        val showLowResolutionPresets = prefs.getBoolean(
            PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING,
            false
        ) || PreferenceConfiguration.isLowResolutionPreset(currentResStr)

        // 预设分辨率
        for (res in PreferenceConfiguration.RESOLUTIONS) {
            if (!showLowResolutionPresets && PreferenceConfiguration.isLowResolutionPreset(res)) {
                continue
            }
            val label = if (res == currentResStr) {
                game.getString(R.string.game_menu_resolution_current, res)
            } else {
                res
            }
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

                val label = if (res == currentResStr) {
                    game.getString(R.string.game_menu_resolution_custom_current, res)
                } else {
                    game.getString(R.string.game_menu_resolution_custom, res)
                }

                options.add(MenuOption(label, false, { changeResolution(res) }, null, false))
            }
        }

        showSubMenu(getString(R.string.game_menu_change_resolution), options.toTypedArray())
    }

    private fun changeResolution(resString: String) {
        @Suppress("DEPRECATION")
        android.preference.PreferenceManager.getDefaultSharedPreferences(game)
            .edit {
                putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resString)
            }

        Toast.makeText(
            game,
            game.getString(R.string.game_menu_resolution_restarting, resString),
            Toast.LENGTH_SHORT
        ).show()

        game.changeResolution()
        activeDialog?.dismiss()
    }

    /**
     * 显示菜单对话框
     */
    private fun showMenuDialog(
        title: String,
        normalOptions: Array<MenuOption>,
        superOptions: Array<MenuOption>,
        pageLayout: GameMenuPageLayout = GameMenuPageLayout.STANDARD
    ) {
        lateinit var dialog: ComponentDialog

        val state = mutableStateOf(
            GameMenuComposeUiState(
                title = title,
                options = normalOptions.toList(),
                superOptions = superOptions.toList(),
                appName = getAppNameDisplay(),
                crownToggleText = getCrownToggleText(),
                gameMenuOpacity = game.prefConfig.gameMenuOpacity,
                deviceQuickOptions = device?.getGameMenuQuickOptions().orEmpty(),
                quickActions = buildComposeQuickActions(),
                visibleCards = readVisibleCards(),
                bitrate = bitrateCardController.snapshot(),
                audioHaptics = audioHapticsCardController.snapshot(),
                gyro = gyroCardController.snapshot(),
                touchPointerSensitivity = touchPointerSensitivityController.snapshot(),
                customKeys = getSavedCustomKeys(),
                pageLayout = pageLayout
            )
        )
        val hardwareFocusRequest = mutableIntStateOf(0)
        val parentFocusRestoreRequest = mutableIntStateOf(0)
        composeUiState = state
        parentFocusRestoreRequestState = parentFocusRestoreRequest
        bitrateCardController.start { bitrate ->
            composeUiState?.let { it.value = it.value.copy(bitrate = bitrate) }
        }
        audioHapticsCardController.start { audioHaptics ->
            composeUiState?.let { it.value = it.value.copy(audioHaptics = audioHaptics) }
        }
        gyroCardController.start { gyro ->
            composeUiState?.let { it.value = it.value.copy(gyro = gyro) }
        }
        touchPointerSensitivityController.start { sensitivity ->
            composeUiState?.let {
                it.value = it.value.copy(touchPointerSensitivity = sensitivity)
            }
        }

        val callbacks = GameMenuCallbacks(
            onDismiss = { handleDismissRequest(dialog) },
            onHapticFeedback = ::dispatchHapticFeedback,
            iconForOption = ::getIconForMenuOption,
            onBack = { navigateBack() },
            onCrownToggle = ::toggleCrownFeature,
            onEditOpacity = ::showOpacityDialog,
            onOptionClick = { handleComposeOptionClick(it, dialog) },
            onInlineToggle = ::handleInlineToggle,
            onSegmentClick = ::handleInlineSegmentClick,
            onEmptySuperCommandClick = ::showSuperCommandHint,
            onQuickAction = ::runComposeQuickAction,
            onToggleQuickEdit = ::toggleComposeQuickEdit,
            onAddQuickAction = ::showQuickButtonEditor,
            onRemoveQuickAction = ::removeComposeQuickAction,
            onMoveQuickAction = ::moveComposeQuickAction,
            onEditCards = ::showCardEditorDialog,
            onBitrateProgress = bitrateCardController::previewProgress,
            onBitrateApply = bitrateCardController::applySelectedBitrate,
            onBitrateHapticMode = bitrateCardController::cycleHapticMode,
            onAudioHapticsEnabled = audioHapticsCardController::setEnabled,
            onAudioHapticsStrength = audioHapticsCardController::previewStrength,
            onAudioHapticsStrengthFinished = audioHapticsCardController::persistStrength,
            onAudioHapticsMode = audioHapticsCardController::setMode,
            onAudioHapticsScene = audioHapticsCardController::setScene,
            onAudioHapticsReset = audioHapticsCardController::resetTuning,
            onGyroEnabled = gyroCardController::setEnabled,
            onGyroMouseMode = gyroCardController::setMouseMode,
            onGyroActivationKey = {
                gyroCardController.showActivationKeyDialog { childDialog ->
                    registerChildDialog(childDialog)
                }
            },
            onGyroSensitivity = gyroCardController::previewSensitivity,
            onGyroSensitivityFinished = gyroCardController::persistSensitivity,
            onGyroInvertX = gyroCardController::setInvertX,
            onGyroInvertY = gyroCardController::setInvertY,
            onTouchPointerSensitivity = touchPointerSensitivityController::preview,
            onTouchPointerSensitivityFinished = touchPointerSensitivityController::persist,
            onSaveTouchPointerSensitivityPreset = { showTouchPointerPresetEditor() },
            onApplyTouchPointerSensitivityPreset = { id ->
                val preset = touchPointerSensitivityController.preset(id)
                if (touchPointerSensitivityController.applyPreset(id) && preset != null) {
                    Toast.makeText(
                        game,
                        game.getString(R.string.toast_preset_applied, preset.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onManageTouchPointerSensitivityPresets = ::showTouchPointerSensitivityPresetManager,
            onCustomKey = { sendKeys(it.keys) }
        )

        val composeView = ComposeView(game).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(
                    LocalGameMenuHapticFeedback provides callbacks.onHapticFeedback
                ) {
                    GameMenuScreen(
                        state = state.value,
                        callbacks = callbacks,
                        useFabricTexture = renderingProfile.useFabricTexture,
                        hardwareFocusRequestToken = hardwareFocusRequest.intValue,
                        restoreFocusRequestToken = parentFocusRestoreRequest.intValue,
                        guideDismissController = guideDismissController
                    )
                }
            }
        }
        dialog = object : ComponentDialog(game, R.style.GameMenuDialogStyle) {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0) {
                    val axisPairs = game.controllerHandler.getGameMenuNavigationAxisPairs(event)
                    if (axisPairs != null && dispatchControllerAxes(event.deviceId, axisPairs)) {
                        return true
                    }
                }
                return super.dispatchGenericMotionEvent(event)
            }
        }.apply {
            setContentView(composeView)
            setCanceledOnTouchOutside(true)
        }
        this.activeDialog = dialog
        this.activeComposeView = composeView

        setupDialogProperties(dialog)

        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleDismissRequest(dialog)
            }
        })

        dialog.setOnShowListener {
            composeView.doOnLayout {
                if (dialog.isShowing) {
                    composeView.requestFocus()
                    hardwareFocusRequest.intValue++
                }
            }
        }

        // 返回键监听器
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && isGameMenuNavigationKey(keyCode)) {
                hardwareFocusRequest.intValue++
            }
            if (UiDismissKeyHandler.handle(
                    event.action,
                    keyCode,
                    onDismiss = { handleDismissRequest(dialog) },
                    dismissOnBack = false
                )
            ) {
                return@setOnKeyListener true
            }
            if (mapGameMenuConfirmKeyCode(keyCode) != keyCode) {
                dialog.dispatchKeyEvent(mapGameMenuConfirmKeyEvent(event))
                return@setOnKeyListener true
            }
            false
        }

        // 关闭时清理状态
        dialog.setOnDismissListener {
            prepareForInputOwnerChange()
            activeChildDialog?.dismiss()
            activeChildDialog = null
            activeChildDismissKeyHandler = null
            resetAxisNavigation()
            if (this.activeDialog == dialog) this.activeDialog = null
            if (this.activeComposeView === composeView) this.activeComposeView = null
            if (this.parentFocusRestoreRequestState === parentFocusRestoreRequest) {
                this.parentFocusRestoreRequestState = null
            }
            this.composeUiState = null
            guideDismissController.clear()
            bitrateCardController.dispose()
            audioHapticsCardController.dispose()
            gyroCardController.dispose()
            touchPointerSensitivityController.dispose()
            menuStack.clear()
            onDismiss(this)
        }

        dialog.show()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        applyDialogSize(dialog)
    }

    private fun handleDismissRequest(dialog: ComponentDialog) {
        if (guideDismissController.dismissIfShowing()) return
        if (!navigateBack()) dialog.cancel()
    }

    private fun handleComposeOptionClick(option: MenuOption, dialog: ComponentDialog) {
        lastActionOpenedSubmenu = false

        // Focus-dependent actions must wait until the dialog has released the game window.
        // Dismissing first also preserves the interaction order of the legacy menu.
        if (option.isWithGameFocus && !option.isKeepDialog) {
            val action = option.runnable
            if (action != null) {
                gameFocusActionRunner.dismissThenRun(Runnable(dialog::dismiss), action)
            } else {
                dialog.dismiss()
            }
            return
        }

        run(option)
        val shouldKeep = option.isKeepDialog || lastActionOpenedSubmenu
        if (!shouldKeep) dialog.dismiss()
        if (option.inlineControl is InlineControl.Toggle &&
            option.inlineControl.toggleAction == null &&
            dialog.isShowing
        ) {
            refreshCurrentMenuPage()
        } else if (option.presentation != GameMenuOptionPresentation.DEFAULT &&
            dialog.isShowing
        ) {
            refreshCurrentMenuPage()
        }
        lastActionOpenedSubmenu = false
    }

    private fun handleInlineSegmentClick(segment: SegmentOption) {
        if (segment.selected) return
        segment.runnable.run()
        refreshCurrentMenuPage()
    }

    private fun handleInlineToggle(toggle: InlineControl.Toggle) {
        val action = toggle.toggleAction ?: return
        action.run()
        refreshCurrentMenuPage()
    }

    private fun showSuperCommandHint() {
        Toast.makeText(
            game,
            getString(R.string.layout_game_menu_super_empty_text_fac9d),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun getAppNameDisplay(): String {
        return try {
            val version = conn.serverVersion?.takeIf { it.isNotBlank() }
            if (version != null) {
                game.getString(R.string.game_menu_server_version, app.appName, version)
            } else {
                app.appName
            }
        } catch (_: Exception) {
            "Moonlight V+"
        }
    }

    private fun readVisibleCards(): GameMenuVisibleCards {
        return GameMenuVisibleCards(
            bitrate = game.prefConfig.showBitrateCard,
            audioHaptics = game.prefConfig.showAudioHapticsCard,
            gyro = game.prefConfig.showGyroCard,
            shortcuts = game.prefConfig.showQuickKeyCard
        )
    }

    private fun showCardEditorDialog() {
        registerChildDialog(
            GameMenuCardVisibilityEditor.show(
                game,
                game.prefConfig,
                forceInitialFocus = true
            ) {
                composeUiState?.let { state ->
                    state.value = state.value.copy(
                        visibleCards = readVisibleCards(),
                        customKeys = getSavedCustomKeys()
                    )
                }
            }
        )
    }

    private fun showOpacityDialog(anchor: GameMenuOpacityAnchor) {
        val currentOpacity = game.prefConfig.gameMenuOpacity.coerceIn(
            PreferenceConfiguration.MIN_GAME_MENU_OPACITY,
            PreferenceConfiguration.MAX_GAME_MENU_OPACITY
        )
        var pendingOpacity = currentOpacity
        var persistedOpacity = currentOpacity

        registerChildDialog(
            GameMenuOpacityEditor.show(
                context = game,
                anchor = anchor,
                initialOpacity = currentOpacity,
                onOpacityChange = { opacity ->
                    pendingOpacity = opacity
                    previewGameMenuOpacity(opacity)
                },
                onOpacityChangeFinished = { opacity ->
                    pendingOpacity = opacity
                    persistGameMenuOpacity(opacity)
                    persistedOpacity = opacity
                }
            ),
            onDismiss = {
                if (pendingOpacity != persistedOpacity) {
                    persistGameMenuOpacity(pendingOpacity)
                }
            }
        )
    }

    private fun previewGameMenuOpacity(opacity: Int) {
        val boundedOpacity = opacity.coerceIn(
            PreferenceConfiguration.MIN_GAME_MENU_OPACITY,
            PreferenceConfiguration.MAX_GAME_MENU_OPACITY
        )
        game.prefConfig.gameMenuOpacity = boundedOpacity
        composeUiState?.let { state ->
            state.value = state.value.copy(gameMenuOpacity = boundedOpacity)
        }
        activeDialog?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.alpha = renderingProfile.windowAlpha(boundedOpacity)
            window.attributes = layoutParams
        }
    }

    private fun persistGameMenuOpacity(opacity: Int) {
        previewGameMenuOpacity(opacity)
        game.prefConfig.writePreferences(game)
    }

    private fun showTouchPointerSensitivityPresetManager() {
        val presets = touchPointerSensitivityController.snapshot().presets
        if (presets.isEmpty()) return

        registerChildDialog(
            AppActionSheet.show(
                context = game,
                title = getString(R.string.game_menu_touch_pointer_manage_presets),
                actions = presets.mapIndexed { index, preset ->
                    AppActionSheet.Action(
                        id = index,
                        title = preset.name,
                        opensSubmenu = true,
                        trailingText = game.getString(
                            R.string.game_menu_touch_pointer_preset_field_count,
                            preset.values.keys.count { key ->
                                TouchPointerPresetField.entries.any { it.storageKey == key }
                            }
                        )
                    )
                } + AppActionSheet.Action(
                    id = DELETE_PRESETS_ACTION_ID,
                    title = getString(R.string.game_menu_touch_pointer_delete_presets),
                    destructive = true
                ),
                onAction = { action ->
                    game.window.decorView.post {
                        if (action.id == DELETE_PRESETS_ACTION_ID) {
                            showTouchPointerPresetDeleteDialog()
                        } else {
                            presets.getOrNull(action.id)?.let { preset ->
                                showTouchPointerPresetEditor(preset.id)
                            }
                        }
                    }
                }
            )
        )
    }

    private fun showTouchPointerPresetEditor(presetId: String? = null) {
        val preset = presetId?.let(touchPointerSensitivityController::preset)
        val selectedFields = if (preset == null) {
            TouchPointerPresetField.entries
                .filterNotTo(linkedSetOf()) { it == TouchPointerPresetField.POINTER_ZONE_SIDE }
        } else {
            touchPointerSensitivityController.selectedFields(preset)
        }
        val fields = TouchPointerPresetField.entries.map { field ->
            TouchPointerPresetEditor.FieldOption(
                field = field,
                label = touchPointerPresetFieldLabel(field),
                value = touchPointerSensitivityController.fieldValue(field, preset),
                checked = field in selectedFields
            )
        }
        val inputState = TouchPointerPresetEditor.InputState()
        registerChildDialog(
            TouchPointerPresetEditor.show(
                context = game,
                title = getString(
                    if (preset == null) R.string.game_menu_touch_pointer_create_preset
                    else R.string.game_menu_touch_pointer_edit_preset
                ),
                initialName = preset?.name ?: touchPointerSensitivityController.defaultName(),
                fields = fields,
                inputState = inputState,
                onSave = { name, values ->
                    touchPointerSensitivityController.savePreset(preset?.id, name, values)
                }
            ),
            onDismissKey = inputState::handleDismissKey
        )
    }

    private fun showTouchPointerPresetDeleteDialog() {
        val presets = touchPointerSensitivityController.snapshot().presets
        if (presets.isEmpty()) return
        registerChildDialog(
            AppActionSheet.showMultiSelect(
                context = game,
                title = getString(R.string.game_menu_touch_pointer_delete_presets),
                actions = presets.mapIndexed { index, preset ->
                    AppActionSheet.Action(index, preset.name, checked = false, destructive = true)
                },
                confirmLabel = getString(R.string.dialog_button_delete),
                cancelLabel = getString(R.string.dialog_button_cancel),
                minimumSelectionCount = 1,
                forceInitialFocus = true,
                onConfirm = { selected ->
                    val ids = selected.mapNotNullTo(linkedSetOf()) { index ->
                        presets.getOrNull(index)?.id
                    }
                    val removed = touchPointerSensitivityController.removePresets(ids)
                    if (removed > 0) {
                        Toast.makeText(
                            game,
                            game.getString(
                                R.string.game_menu_touch_pointer_presets_deleted,
                                removed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        )
    }

    private fun touchPointerPresetFieldLabel(field: TouchPointerPresetField): String = getString(
        when (field) {
            TouchPointerPresetField.POINTER_SPEED -> R.string.title_pointer_velocity_factor
            TouchPointerPresetField.INITIAL_STABLE_ZONE ->
                R.string.title_seekbar_long_press_flat_region
            TouchPointerPresetField.ZONE_DIVIDER -> R.string.title_enhanced_touch_zone_divider
            TouchPointerPresetField.POINTER_ZONE_SIDE ->
                R.string.game_menu_touch_pointer_zone_position
        }
    )

    private fun registerChildDialog(
        dialog: Dialog,
        onDismiss: () -> Unit = {},
        onDismissKey: ((KeyEvent) -> Boolean)? = null
    ) {
        activeChildDialog?.takeIf { it !== dialog && it.isShowing }?.dismiss()
        prepareForInputOwnerChange()
        activeChildDialog = dialog
        activeChildDismissKeyHandler = onDismissKey

        val decorView = dialog.window?.decorView
        decorView?.setOnKeyListener { _, keyCode, event ->
            if (activeChildDialog === dialog && dialog.isShowing &&
                isGameMenuDiagonalKey(keyCode)
            ) {
                dialog.dispatchKeyEvent(mapGameMenuConfirmKeyEvent(event))
                true
            } else {
                false
            }
        }
        decorView?.setOnGenericMotionListener { _, event ->
            if (activeChildDialog !== dialog || !dialog.isShowing ||
                event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0
            ) {
                false
            } else {
                val axisPairs = game.controllerHandler.getGameMenuNavigationAxisPairs(event)
                axisPairs != null && dispatchControllerAxes(event.deviceId, axisPairs)
            }
        }

        dialog.setOnDismissListener {
            if (activeChildDialog !== dialog) return@setOnDismissListener
            prepareForInputOwnerChange()
            activeChildDialog = null
            activeChildDismissKeyHandler = null
            onDismiss()
            decorView?.setOnKeyListener(null)
            decorView?.setOnGenericMotionListener(null)
            val parentDialog = activeDialog
            val parentComposeView = activeComposeView
            parentComposeView?.post {
                if (parentDialog?.isShowing == true && activeChildDialog == null) {
                    parentComposeView.requestFocus()
                    parentFocusRestoreRequestState?.let { request ->
                        request.intValue++
                    }
                }
            }
        }
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

    /** 解析 action id → Triple(label, iconRes, iconText)，无效返回 null。 */
    private fun resolveAction(id: String): Triple<String, Int, String?>? {
        val action = QuickActionRegistry.getBuiltin(id)
        return when {
            action != null -> {
                val label = if (action.labelRes != 0) getString(action.labelRes) else action.label
                Triple(label, action.iconRes, action.iconText)
            }
            id.startsWith("custom_") -> Triple(id.substring("custom_".length), 0, null)
            else -> null
        }
    }

    private fun buildComposeQuickActions(): List<GameMenuQuickAction> {
        return QuickActionRegistry.loadConfig(game).mapNotNull { id ->
            val (label, iconRes, iconText) = resolveAction(id) ?: return@mapNotNull null
            GameMenuQuickAction(
                id = id,
                label = label,
                iconRes = if (id == "toggle_mic" && !game.prefConfig.enableMic) {
                    QuickActionRegistry.getBuiltin(id)?.iconDisabledRes ?: iconRes
                } else {
                    iconRes
                },
                iconText = iconText,
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

        if (QuickActionRegistry.getBuiltin(id)?.isWithGameFocus == true) {
            gameFocusActionRunner.dismissThenRun(
                dismiss = Runnable { activeDialog?.dismiss() },
                action = Runnable { actionExecutor.execute(id) }
            )
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

    private fun moveComposeQuickAction(id: String, targetId: String) {
        val ids = QuickActionRegistry.loadConfig(game).toMutableList()
        val from = ids.indexOf(id)
        val target = ids.indexOf(targetId)
        if (from < 0 || target < 0 || from == target) return
        ids.add(target.coerceAtMost(ids.size), ids.removeAt(from))
        composeUiState?.let { state ->
            val actionsById = state.value.quickActions.associateBy(GameMenuQuickAction::id)
            state.value = state.value.copy(
                quickActions = ids.mapNotNull(actionsById::get)
            )
        }
        QuickActionRegistry.saveConfig(game, ids)
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
        registerChildDialog(AppActionSheet.showMultiSelect(
            context = game,
            title = getString(R.string.quick_button_editor_title),
            actions = allLabels.mapIndexed { index, label ->
                AppActionSheet.Action(index, label, checked = allIds[index] in currentIds)
            },
            confirmLabel = getString(R.string.game_menu_ok).trim(),
            cancelLabel = getString(R.string.game_menu_cancel).trim(),
            resetLabel = getString(R.string.quick_button_reset_default).trim(),
            minimumSelectionCount = 1,
            onConfirm = { selectedPositions ->
                val selectedIds = selectedPositions.mapTo(linkedSetOf()) { allIds[it] }
                val newIds = currentIds.filterTo(mutableListOf()) { it in selectedIds }
                allIds.filterTo(newIds) { it in selectedIds && it !in newIds }
                if (newIds.isEmpty()) newIds.add("quit")
                QuickActionRegistry.saveConfig(game, newIds)
                refreshComposeQuickActions()
            },
            onReset = {
                QuickActionRegistry.saveConfig(game, QuickActionRegistry.defaultIds(game))
                refreshComposeQuickActions()
            },
            forceInitialFocus = true
        ))
    }

    private fun currentMenuPage(): MenuPage? {
        return composeUiState?.value?.let { MenuPage(it.title, it.options, it.pageLayout) }
    }

    private fun showMenuPage(page: MenuPage, pushCurrent: Boolean = false) {
        val state = composeUiState ?: return
        if (pushCurrent) currentMenuPage()?.let(menuStack::push)
        state.value = state.value.copy(
            title = page.title,
            options = page.options,
            isSubmenu = menuStack.isNotEmpty(),
            pageLayout = page.layout
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
    private fun showSubMenu(
        title: String,
        subOptions: Array<MenuOption>,
        pageLayout: GameMenuPageLayout = GameMenuPageLayout.STANDARD
    ) {
        val dialog = activeDialog
        if (dialog != null && dialog.isShowing) {
            lastActionOpenedSubmenu = true
            showMenuPage(MenuPage(title, subOptions.toList(), pageLayout), pushCurrent = true)
        } else {
            showMenuDialog(title, subOptions, emptyArray(), pageLayout)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupDialogProperties(dialog: ComponentDialog) {
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Mirror immersive flags before the dialog can take focus and reveal system bars.
            window.decorView.systemUiVisibility = game.window.decorView.systemUiVisibility
            if (game.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
            ) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            val layoutParams = window.attributes
            layoutParams.alpha = renderingProfile.windowAlpha(game.prefConfig.gameMenuOpacity)
            layoutParams.dimAmount = DIALOG_DIM_AMOUNT
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.gravity = android.view.Gravity.FILL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = layoutParams
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            window.setWindowAnimations(renderingProfile.dialogAnimationStyle)
        }
    }

    private fun applyDialogSize(dialog: ComponentDialog) {
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * 显示特殊按键菜单
     */
    private fun showSpecialKeysMenu() {
        val options = mutableListOf<MenuOption>()

        val hasKeys = loadAndAddAllKeys(options)

        options.add(gameMenuChildDialogOption(
            label = getString(R.string.game_menu_add_custom_key),
            action = Runnable { showAddCustomKeyDialog() }
        ))

        if (hasKeys) {
            options.add(gameMenuChildDialogOption(
                label = getString(R.string.game_menu_delete_custom_key),
                action = Runnable { showDeleteKeysDialog() }
            ))
        }

        options.add(
            createGameMenuBackOption(getString(R.string.game_menu_cancel)) {
                if (!navigateBack()) activeDialog?.cancel()
            }
        )

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
        var nameEditing = false

        fun leaveNameEditing() {
            if (!nameEditing) return
            nameEditing = false
            val inputMethodManager = game.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            inputMethodManager?.hideSoftInputFromWindow(nameInput.windowToken, 0)
        }

        fun enterNameEditing() {
            nameEditing = true
            nameInput.requestFocus()
            nameInput.setSelection(nameInput.text.length)
            nameInput.post {
                val inputMethodManager = game.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                inputMethodManager?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val dialog = builder.create()
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            val editingDismissKey = keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B
            if (nameEditing && editingDismissKey) {
                if (event.action == KeyEvent.ACTION_UP) leaveNameEditing()
                event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            } else if (UiDismissKeyHandler.handle(event.action, keyCode, dialog::cancel)) {
                true
            } else if (mapGameMenuConfirmKeyCode(keyCode) != keyCode) {
                dialog.dispatchKeyEvent(mapGameMenuConfirmKeyEvent(event))
                true
            } else {
                false
            }
        }

        closeButton?.setOnClickListener { dialog.dismiss() }
        nameInput.setOnClickListener { enterNameEditing() }
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) leaveNameEditing()
        }

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

        val keyboardDrawing = dialogView.findViewById<ViewGroup>(R.id.keyboard_drawing)
        setupCompactKeyboardListeners(keyboardDrawing, keysDisplay)

        // 保存按钮事件
        saveButton?.setOnClickListener {
            val name = resolveCustomKeyName(
                nameInput.text.toString(),
                keysDisplay.text.toString()
            )
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
        registerChildDialog(dialog)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogContent?.minimumHeight = game.resources.displayMetrics.heightPixels
        setupCompactKeyboardControllerNavigation(
            keyboardDrawing,
            controlRows = listOf(
                listOfNotNull(saveButton, closeButton),
                listOfNotNull(nameInput, clearButton)
            ),
            editableView = nameInput,
            isEditing = { nameEditing },
            onEnterEditing = ::enterNameEditing
        )
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

    private fun setupCompactKeyboardControllerNavigation(
        keyboard: ViewGroup?,
        controlRows: List<List<View>>,
        editableView: EditText? = null,
        isEditing: () -> Boolean = { false },
        onEnterEditing: () -> Unit = {}
    ) {
        if (keyboard == null) return
        val keyboardRows = mutableListOf<List<View>>()
        for (index in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(index) as? ViewGroup ?: continue
            val keys = mutableListOf<View>()
            collectCompactKeyboardKeys(row, keys)
            if (keys.isNotEmpty()) keyboardRows.add(keys)
        }
        if (keyboardRows.isEmpty()) return

        val focusRows = buildList {
            addAll(controlRows.filter { it.isNotEmpty() })
            addAll(keyboardRows)
        }
        focusRows.flatten().forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            view.isFocusable = true
            view.isFocusableInTouchMode = true
        }

        keyboard.post {
            val laidOutRows = focusRows.map { row ->
                row.sortedBy(::viewHorizontalCenterOnScreen)
            }
            laidOutRows.forEachIndexed { rowIndex, row ->
                val upRow = laidOutRows[(rowIndex - 1 + laidOutRows.size) % laidOutRows.size]
                val downRow = laidOutRows[(rowIndex + 1) % laidOutRows.size]
                val upCenters = upRow.map(::viewHorizontalCenterOnScreen)
                val downCenters = downRow.map(::viewHorizontalCenterOnScreen)
                row.forEachIndexed { columnIndex, view ->
                    val center = viewHorizontalCenterOnScreen(view)
                    val leftTarget = row[(columnIndex - 1 + row.size) % row.size]
                    val rightTarget = row[(columnIndex + 1) % row.size]
                    val upTarget = upRow[nearestFocusIndex(center, upCenters)]
                    val downTarget = downRow[nearestFocusIndex(center, downCenters)]
                    view.nextFocusLeftId = leftTarget.id
                    view.nextFocusRightId = rightTarget.id
                    view.nextFocusUpId = upTarget.id
                    view.nextFocusDownId = downTarget.id
                    view.setOnKeyListener { _, keyCode, event ->
                        if (view === editableView) {
                            val confirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                                keyCode == KeyEvent.KEYCODE_BUTTON_A
                            if (confirmKey) {
                                if (event.action == KeyEvent.ACTION_UP) onEnterEditing()
                                return@setOnKeyListener event.action == KeyEvent.ACTION_DOWN ||
                                    event.action == KeyEvent.ACTION_UP
                            }
                            if (isEditing()) {
                                if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                ) {
                                    return@setOnKeyListener event.action == KeyEvent.ACTION_DOWN ||
                                        event.action == KeyEvent.ACTION_UP
                                }
                                return@setOnKeyListener false
                            }
                        }
                        val target = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> leftTarget
                            KeyEvent.KEYCODE_DPAD_RIGHT -> rightTarget
                            KeyEvent.KEYCODE_DPAD_UP -> upTarget
                            KeyEvent.KEYCODE_DPAD_DOWN -> downTarget
                            else -> return@setOnKeyListener false
                        }
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            target.requestFocus()
                        }
                        event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
                    }
                }
            }
            val initialTarget = keyboardRows.first().first()
            initialTarget.requestFocus()
            keyboard.post {
                if (laidOutRows.flatten().none(View::hasFocus)) {
                    initialTarget.requestFocus()
                }
            }
        }
    }

    private fun collectCompactKeyboardKeys(parent: ViewGroup, output: MutableList<View>) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is ViewGroup) {
                collectCompactKeyboardKeys(child, output)
            } else if (child is TextView && child.tag != null && child.visibility == View.VISIBLE) {
                output.add(child)
            }
        }
    }

    private fun viewHorizontalCenterOnScreen(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0] + view.width / 2
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
            registerChildDialog(AppActionSheet.showMultiSelect(
                context = game,
                title = getString(R.string.dialog_title_select_keys_to_delete),
                actions = keyNames.mapIndexed { index, name ->
                    AppActionSheet.Action(id = index, title = name, checked = false)
                },
                confirmLabel = getString(R.string.dialog_button_delete),
                cancelLabel = getString(R.string.dialog_button_cancel),
                minimumSelectionCount = 1,
                onConfirm = { selectedIds ->
                    try {
                        selectedIds.sortedDescending().forEach(dataArray::remove)
                        root.put("data", dataArray)
                        preferences.edit { putString(KEY_NAME, root.toString()) }
                        Toast.makeText(game, R.string.toast_selected_keys_deleted, Toast.LENGTH_SHORT).show()
                        refreshComposeCustomKeys()
                    } catch (e: Exception) {
                        LimeLog.warning("Exception while deleting keys${e.message}")
                        Toast.makeText(game, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                forceInitialFocus = true
            ))
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

        showMenuDialog(getString(R.string.game_menu_title), normalOptions.toTypedArray(), superOptions.toTypedArray())
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
            label = getString(R.string.game_menu_control).trim(),
            isWithGameFocus = false,
            runnable = Runnable { showTouchModeMenu() },
            iconKey = "mouse_mode",
            isShowIcon = true,
            isKeepDialog = true,
            showChevron = true,
            inlineControl = InlineControl.Segmented(
                segments = buildTouchModeSegments(compactLabels = true),
                smallScreenColumnCount = 3
            )
        ))

        normalOptions.add(MenuOption(
            label = getString(R.string.game_menu_enable_pan_zoom).trim(),
            isWithGameFocus = false,
            runnable = Runnable {
                Toast.makeText(game,
                    if (game.getisTouchOverrideEnabled()) getString(R.string.toast_pan_zoom_disabled) else getString(R.string.toast_pan_zoom_enabled),
                    Toast.LENGTH_SHORT).show()
                game.setisTouchOverrideEnabled(!game.getisTouchOverrideEnabled())
            },
            iconKey = "game_menu_mouse_emulation",
            isShowIcon = true,
            isKeepDialog = true,
            inlineControl = InlineControl.Toggle(game.getisTouchOverrideEnabled())
        ))

        // 王冠功能
        val crownEnabled = game.isCrownFeatureEnabled
        normalOptions.add(MenuOption(
            label = getString(R.string.game_menu_crown_function),
            isWithGameFocus = false,
            runnable = if (crownEnabled) Runnable { showCrownFunctionMenu() } else null,
            iconKey = "crown_function_menu",
            isShowIcon = true,
            isKeepDialog = true,
            showChevron = crownEnabled,
            inlineControl = InlineControl.Toggle(
                checked = crownEnabled,
                toggleAction = Runnable { setCrownFeatureEnabled(!game.isCrownFeatureEnabled) }
            )
        ))

        // 性能显示
        normalOptions.add(MenuOption(
            label = getString(R.string.game_menu_toggle_performance_overlay).trim(),
            isWithGameFocus = false,
            runnable = null,
            iconKey = "game_menu_toggle_performance_overlay",
            isShowIcon = true,
            isKeepDialog = true,
            inlineControl = InlineControl.Segmented(buildPerformanceOverlaySegments())
        ))

        normalOptions.add(MenuOption(
            getString(R.string.game_menu_change_resolution), false,
            { showResolutionMenu() }, "game_menu_change_resolution", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

        if (game.prefConfig.onscreenController) {
            normalOptions.add(MenuOption(
                label = getString(R.string.game_menu_toggle_virtual_controller).trim(),
                isWithGameFocus = false,
                runnable = Runnable { game.toggleVirtualController() },
                iconKey = "game_menu_toggle_virtual_controller",
                isShowIcon = true,
                isKeepDialog = true,
                inlineControl = InlineControl.Toggle(game.isVirtualControllerVisible())
            ))
        }

        normalOptions.add(MenuOption(getString(R.string.game_menu_send_keys),
            false, { showSpecialKeysMenu() }, "game_menu_send_keys", isShowIcon = true, isKeepDialog = true,
            showChevron = true
        ))

        normalOptions.add(MenuOption(getString(R.string.game_menu_disconnect), true,
            { game.disconnect() }, "game_menu_disconnect", true))

        normalOptions.add(MenuOption(getString(R.string.game_menu_disconnect_and_quit), true,
            { disconnectAndQuit() }, "game_menu_disconnect_and_quit", true))
    }

    private fun buildPerformanceOverlaySegments(): List<SegmentOption> {
        val currentMode = game.performanceOverlayMode
        return listOf(
            performanceOverlaySegment(
                label = getString(R.string.perf_overlay_hidden),
                mode = Game.PerformanceOverlayMode.HIDDEN,
                currentMode = currentMode
            ),
            performanceOverlaySegment(
                label = getString(R.string.perf_overlay_floating),
                mode = Game.PerformanceOverlayMode.FLOATING,
                currentMode = currentMode
            ),
            performanceOverlaySegment(
                label = getString(R.string.perf_overlay_locked),
                mode = Game.PerformanceOverlayMode.LOCKED,
                currentMode = currentMode
            )
        )
    }

    private fun performanceOverlaySegment(
        label: String,
        mode: Game.PerformanceOverlayMode,
        currentMode: Game.PerformanceOverlayMode
    ) = SegmentOption(
        label = label,
        selected = mode == currentMode,
        runnable = Runnable { game.setPerformanceOverlayMode(mode) }
    )

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
        private const val GAME_FOCUS_RETRY_DELAY_MS = 10L
        private const val AXIS_REPEAT_INITIAL_DELAY_MS = 350L
        private const val AXIS_REPEAT_INTERVAL_MS = 90L
        private const val DELETE_PRESETS_ACTION_ID = -1
        private const val DIALOG_DIM_AMOUNT = 0.0f
        private const val PREF_NAME = "custom_special_keys"
        private const val KEY_NAME = "data"
        private const val HAPTIC_FEEDBACK_SETTING = "haptic_feedback_enabled"

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
            // Compose's painterResource() rejects negative resource IDs. Older
            // Android versions intentionally hide these vector menu icons.
            return 0
        }
    }
}
