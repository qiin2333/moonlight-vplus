package com.limelight.gamemenu

import androidx.annotation.DrawableRes
import com.limelight.CustomKeyData

internal data class GameMenuQuickAction(
    val id: String,
    val label: String,
    @param:DrawableRes val iconRes: Int,
    val iconText: String? = null,
    val enabled: Boolean = true
)

internal data class GameMenuOpacityAnchor(
    val centerX: Int,
    val bottomY: Int
)

internal data class GameMenuComposeUiState(
    val title: String,
    val options: List<GameMenu.MenuOption>,
    val superOptions: List<GameMenu.MenuOption>,
    val appName: String,
    val crownToggleText: String,
    val gameMenuOpacity: Int,
    val deviceQuickOptions: List<GameMenu.MenuOption>,
    val quickActions: List<GameMenuQuickAction>,
    val visibleCards: GameMenuVisibleCards,
    val bitrate: BitrateCardState,
    val audioHaptics: AudioHapticsCardState,
    val gyro: GyroCardState,
    val touchPointerSensitivity: TouchPointerSensitivityState,
    val customKeys: List<CustomKeyData>,
    val quickEditMode: Boolean = false,
    val isSubmenu: Boolean = false,
    val pageLayout: GameMenuPageLayout = GameMenuPageLayout.STANDARD
)

internal enum class GameMenuPageLayout {
    STANDARD,
    TOUCH_MODE
}

enum class GameMenuOptionPresentation {
    DEFAULT,
    PRIMARY_MODE,
    COMPATIBLE_ACTION
}

internal fun gameMenuChildDialogOption(
    label: String,
    action: Runnable
) = GameMenu.MenuOption(
    label = label,
    isWithGameFocus = false,
    runnable = action,
    iconKey = null,
    isShowIcon = false,
    isKeepDialog = true
)

internal fun threeFingerPanZoomOption(label: String, checked: Boolean, toggle: Runnable) = GameMenu.MenuOption(
    label = label,
    isWithGameFocus = false,
    runnable = toggle,
    iconKey = "game_menu_enable_three_finger_pan_zoom",
    isShowIcon = true,
    isKeepDialog = true,
    inlineControl = GameMenu.InlineControl.Toggle(checked, toggle),
    presentation = GameMenuOptionPresentation.COMPATIBLE_ACTION
)

internal class GameMenuGuideDismissController {
    private var dismissAction: (() -> Unit)? = null

    fun register(action: () -> Unit) {
        dismissAction = action
    }

    fun clear() {
        dismissAction = null
    }

    fun dismissIfShowing(): Boolean {
        val action = dismissAction ?: return false
        dismissAction = null
        action()
        return true
    }
}

internal data class GameMenuVisibleCards(
    val bitrate: Boolean,
    val audioHaptics: Boolean,
    val gyro: Boolean,
    val shortcuts: Boolean
)

internal data class GameMenuCallbacks(
    val onDismiss: () -> Unit,
    val onHapticFeedback: (Int) -> Unit,
    val iconForOption: (String?) -> Int,
    val onBack: () -> Unit,
    val onCrownToggle: () -> Unit,
    val onEditOpacity: (GameMenuOpacityAnchor) -> Unit,
    val onOptionClick: (GameMenu.MenuOption) -> Unit,
    val onInlineToggle: (GameMenu.InlineControl.Toggle) -> Unit,
    val onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    val onEmptySuperCommandClick: () -> Unit,
    val onQuickAction: (String) -> Unit,
    val onToggleQuickEdit: () -> Unit,
    val onAddQuickAction: () -> Unit,
    val onRemoveQuickAction: (String) -> Unit,
    val onMoveQuickAction: (String, String) -> Unit,
    val onEditCards: () -> Unit,
    val onBitrateProgress: (Float) -> Boolean,
    val onBitrateApply: () -> Unit,
    val onBitrateHapticMode: () -> Unit,
    val onAudioHapticsEnabled: (Boolean) -> Unit,
    val onAudioHapticsStrength: (Float) -> Boolean,
    val onAudioHapticsStrengthFinished: () -> Unit,
    val onAudioHapticsMode: (String) -> Unit,
    val onAudioHapticsScene: (Int) -> Unit,
    val onAudioHapticsReset: () -> Unit,
    val onGyroEnabled: (Boolean) -> Unit,
    val onGyroMouseMode: (Boolean) -> Unit,
    val onGyroActivationKey: () -> Unit,
    val onGyroSensitivity: (Float) -> Unit,
    val onGyroSensitivityFinished: () -> Unit,
    val onGyroInvertX: (Boolean) -> Unit,
    val onGyroInvertY: (Boolean) -> Unit,
    val onTouchPointerSensitivity: (Float) -> Boolean,
    val onTouchPointerSensitivityFinished: () -> Unit,
    val onSaveTouchPointerSensitivityPreset: () -> Unit,
    val onApplyTouchPointerSensitivityPreset: (String) -> Unit,
    val onManageTouchPointerSensitivityPresets: () -> Unit,
    val onCustomKey: (CustomKeyData) -> Unit
)
