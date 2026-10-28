package com.limelight.gamemenu

// In-stream tuning cards and their shared slider/focus behavior.

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.CustomKeyData
import com.limelight.R
import com.limelight.binding.audio.AudioVibrationService
import com.limelight.ui.theme.AppShapes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.limelight.preferences.compactResolutionLabel
import com.limelight.utils.appAccentSoftColor
import com.limelight.utils.appAccentColor


@Composable
internal fun GameMenuCards(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks,
    modifier: Modifier = Modifier,
    onSliderGesture: (Boolean) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
    ) {
        if (state.visibleCards.display) {
            DisplayStatusCard(
                state = state.bitrate,
                callbacks = callbacks,
                onConfigure = callbacks.onEditCards,
                onSliderGesture = onSliderGesture
            )
        }
        if (state.visibleCards.bitrate) {
            BitrateCard(state.bitrate, callbacks, onSliderGesture, callbacks.onEditCards)
        }
        if (state.visibleCards.audioHaptics) {
            AudioHapticsCard(
                state.audioHaptics,
                callbacks,
                onSliderGesture,
                callbacks.onEditCards
            )
        }
        if (state.visibleCards.gyro) {
            GyroCard(state.gyro, callbacks, onSliderGesture, callbacks.onEditCards)
        }
        if (state.visibleCards.shortcuts && state.customKeys.isNotEmpty()) {
            ShortcutCard(state.customKeys, callbacks.onCustomKey, callbacks.onEditCards)
        }
        if (state.visibleCards.waveformHaptics && state.waveformHaptics.routes.isNotEmpty()) {
            WaveformHapticsCard(state.waveformHaptics, callbacks)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameMenuCard(
    title: String,
    status: String? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val longClickModifier = if (onLongClick != null) {
        Modifier
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    hapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                }
            )
    } else {
        Modifier
    }
    val cardFocusModifier = if (onLongClick != null) {
        Modifier.focusProperties { canFocus = false }
    } else {
        Modifier
    }
    Surface(
        color = colorResource(R.color.game_menu_card_background),
        shape = GameMenuCardShape,
        border = BorderStroke(GameMenuDimens.surfaceStroke, appAccentSoftColor()),
        modifier = modifier
            .fillMaxWidth()
            // Keep long-press configuration on the card without making the whole
            // card consume a controller focus slot before its child controls.
            .then(cardFocusModifier)
            .then(longClickModifier)
    ) {
        Column(
            modifier = Modifier.padding(GameMenuDimens.section),
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = colorResource(R.color.game_menu_text_primary),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    titleAccessory?.let {
                        Spacer(Modifier.width(GameMenuDimens.tight))
                        it()
                    }
                }
                if (trailing != null) {
                    trailing()
                } else status?.let {
                    Text(
                        text = it,
                        color = appAccentColor(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactGameMenuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    val colors = SliderDefaults.colors()
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                thumbSize = GameMenuSliderSpec.thumbSize
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(GameMenuSliderSpec.trackHeight),
                colors = colors,
                thumbTrackGapSize = GameMenuSliderSpec.thumbTrackGap,
                trackInsideCornerSize = GameMenuSliderSpec.trackInsideCorner
            )
        },
        modifier = modifier
    )
}

internal data class SensitivityParameter(
    val label: Int,
    val percent: Int,
    val min: Int,
    val max: Int,
    val onChange: (Float) -> Boolean,
    val onFinished: () -> Unit,
    val tag: String
)

@Composable
internal fun TouchPointerSensitivityControl(
    state: TouchPointerSensitivityState,
    onValueChange: (Float) -> Boolean,
    onValueChangeFinished: () -> Unit,
    onSavePreset: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onManagePresets: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    leftExitRequester: FocusRequester = FocusRequester.Default,
    rightExitRequester: FocusRequester = FocusRequester.Default,
    upExitRequester: FocusRequester = FocusRequester.Default,
    downExitRequester: FocusRequester = FocusRequester.Default,
    parameters: List<SensitivityParameter>? = null,
    modifier: Modifier = Modifier,
    onSliderGesture: (Boolean) -> Unit
) {
    if (!state.applicable) return

    val ownedInitialFocusRequester = remember { FocusRequester() }
    val saveRequester = initialFocusRequester ?: ownedInitialFocusRequester
    val sliders = parameters ?: listOf(SensitivityParameter(
        R.string.game_menu_touch_pointer_speed, state.percent,
        TouchPointerSensitivityPolicy.MIN_PERCENT, TouchPointerSensitivityPolicy.MAX_PERCENT,
        onValueChange, onValueChangeFinished, "touchPointerSensitivity"
    ))
    val sliderRequesters = remember(sliders.size) { List(sliders.size) { FocusRequester() } }
    val sliderRequester = sliderRequesters.first()
    val manageRequester = remember { FocusRequester() }
    val presetIds = state.presets.map(TouchPointerSensitivityPreset::id)
    val presetRequesters = remember(presetIds) {
        List(presetIds.size) { FocusRequester() }
    }
    GameMenuCard(
        title = stringResource(R.string.game_menu_touch_pointer_saved_presets),
        trailing = {
            SensitivityPresetActions(
                hasPresets = state.presets.isNotEmpty(),
                saveRequester = saveRequester,
                manageRequester = manageRequester,
                firstPresetRequester = presetRequesters.firstOrNull() ?: sliderRequester,
                managePresetRequester = presetRequesters.getOrNull(1)
                    ?: presetRequesters.firstOrNull()
                    ?: sliderRequester,
                leftExitRequester = leftExitRequester,
                rightExitRequester = rightExitRequester,
                upExitRequester = upExitRequester,
                onSavePreset = onSavePreset,
                onManagePresets = onManagePresets
            )
        }
    ) {
        SensitivityPresetSection(
            state = state,
            saveRequester = saveRequester,
            manageRequester = manageRequester,
            presetRequesters = presetRequesters,
            sliderRequester = sliderRequester,
            leftExitRequester = leftExitRequester,
            rightExitRequester = rightExitRequester,
            onApplyPreset = onApplyPreset
        )
        sliders.forEachIndexed { index, parameter ->
            SensitivityParameterControl(
                parameter = parameter,
                showSummary = parameters == null,
                modifier = modifier
                    .focusRequester(sliderRequesters[index])
                    .focusProperties {
                        canFocus = true
                        up = sliderRequesters.getOrNull(index - 1)
                            ?: presetRequesters.lastOrNull() ?: saveRequester
                        down = sliderRequesters.getOrNull(index + 1) ?: downExitRequester
                    },
                onSliderGesture = onSliderGesture
            )
        }
    }
}

@Composable
private fun SensitivityParameterControl(
    parameter: SensitivityParameter,
    showSummary: Boolean,
    modifier: Modifier,
    onSliderGesture: (Boolean) -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(parameter.label),
            color = colorResource(R.color.game_menu_text_primary),
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(GameMenuDimens.compact))
        Text(
            text = stringResource(
                R.string.game_menu_touch_pointer_speed_value,
                parameter.percent
            ),
            color = appAccentColor(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
    if (showSummary) Text(
        text = stringResource(R.string.game_menu_touch_pointer_speed_summary),
        color = colorResource(R.color.game_menu_text_secondary),
        fontSize = 10.sp,
        lineHeight = 14.sp
    )
    Box(
        modifier = modifier.testTag(parameter.tag)
            .fillMaxWidth()
            .height(GameMenuSliderSpec.height)
            .gamepadFocusOutline(GameMenuControlShape)
            .handleSliderDpad(
                value = parameter.percent.toFloat(),
                step = TouchPointerSensitivityPolicy.DPAD_STEP_PERCENT.toFloat(),
                valueRange = parameter.min.toFloat()..parameter.max.toFloat(),
                onValueChange = { value ->
                    if (parameter.onChange(value)) {
                        hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                },
                onValueChangeFinished = parameter.onFinished
            )
            .focusable()
            .lockParentScrollDuringGesture(onSliderGesture)
    ) {
        CompactGameMenuSlider(
            value = parameter.percent.toFloat(),
            onValueChange = { value ->
                if (parameter.onChange(value)) {
                    hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            },
            onValueChangeFinished = parameter.onFinished,
            valueRange = parameter.min.toFloat()..parameter.max.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(GameMenuSliderSpec.height)
                .focusProperties { canFocus = false }
        )
    }
    Row {
        Text(
            text = "${parameter.min}%",
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 9.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${parameter.max}%",
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun SensitivityPresetActions(
    hasPresets: Boolean,
    saveRequester: FocusRequester,
    manageRequester: FocusRequester,
    firstPresetRequester: FocusRequester,
    managePresetRequester: FocusRequester,
    leftExitRequester: FocusRequester,
    rightExitRequester: FocusRequester,
    upExitRequester: FocusRequester,
    onSavePreset: () -> Unit,
    onManagePresets: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SensitivityPresetActionButton(
            iconRes = R.drawable.ic_add,
            contentDescription = stringResource(R.string.game_menu_touch_pointer_save_preset),
            enabled = true,
            modifier = Modifier
                .testTag("touchPointerPresetSave")
                .focusRequester(saveRequester)
                .focusProperties {
                    up = upExitRequester
                    down = firstPresetRequester
                    left = leftExitRequester
                    right = if (hasPresets) manageRequester else rightExitRequester
                },
            onClick = onSavePreset
        )
        if (hasPresets) {
            Spacer(Modifier.width(GameMenuDimens.tight))
            SensitivityPresetActionButton(
                iconRes = R.drawable.phc_action_edit,
                contentDescription = stringResource(
                    R.string.game_menu_touch_pointer_manage_presets
                ),
                enabled = true,
                modifier = Modifier
                    .testTag("touchPointerPresetManage")
                    .focusRequester(manageRequester)
                    .focusProperties {
                        up = upExitRequester
                        down = managePresetRequester
                        left = saveRequester
                        right = rightExitRequester
                    },
                onClick = onManagePresets
            )
        }
    }
}

@Composable
private fun SensitivityPresetSection(
    state: TouchPointerSensitivityState,
    saveRequester: FocusRequester,
    manageRequester: FocusRequester,
    presetRequesters: List<FocusRequester>,
    sliderRequester: FocusRequester,
    leftExitRequester: FocusRequester,
    rightExitRequester: FocusRequester,
    onApplyPreset: (String) -> Unit
) {
    val actionRequesters = if (state.presets.isEmpty()) {
        listOf(saveRequester)
    } else {
        listOf(saveRequester, manageRequester)
    }
    if (state.presets.isEmpty()) {
        Text(
            text = stringResource(R.string.game_menu_touch_pointer_no_presets),
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 10.sp
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)) {
        state.presets.chunked(3).forEachIndexed { rowIndex, rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
            ) {
                rowPresets.forEachIndexed { columnIndex, preset ->
                    val index = rowIndex * 3 + columnIndex
                    val rowStart = rowIndex * 3
                    val rowEnd = minOf(rowStart + 3, state.presets.size)
                    val nextRowStart = rowStart + 3
                    val upRequester = if (rowIndex == 0) {
                        actionRequesters[minOf(columnIndex, actionRequesters.lastIndex)]
                    } else {
                        presetRequesters[index - 3]
                    }
                    val downRequester = if (nextRowStart < state.presets.size) {
                        presetRequesters[minOf(nextRowStart + columnIndex, state.presets.lastIndex)]
                    } else {
                        sliderRequester
                    }
                    SensitivityPresetButton(
                        name = preset.name,
                        selected = preset.id == state.activePresetId,
                        onClick = { onApplyPreset(preset.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("touchPointerPreset:${preset.id}")
                            .focusRequester(presetRequesters[index])
                            .focusProperties {
                                up = upRequester
                                down = downRequester
                                left = if (columnIndex > 0) {
                                    presetRequesters[index - 1]
                                } else {
                                    leftExitRequester
                                }
                                right = if (index + 1 < rowEnd) {
                                    presetRequesters[index + 1]
                                } else {
                                    rightExitRequester
                                }
                            }
                    )
                }
                repeat(3 - rowPresets.size) {
                    Spacer(Modifier.weight(1f).height(36.dp))
                }
            }
        }
    }
}

@Composable
private fun SensitivityPresetButton(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val shape = GameMenuControlShape
    val accent = appAccentColor()
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val inactiveBorder = colorResource(R.color.game_menu_text_secondary).copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.14f)
                else colorResource(R.color.game_menu_list_item_normal)
            )
            .border(
                GameMenuDimens.surfaceStroke,
                if (selected) accent else inactiveBorder,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, if (focused) accent else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button) {
                hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = when {
                !enabled -> colorResource(R.color.game_menu_text_secondary).copy(alpha = 0.45f)
                selected -> accent
                else -> colorResource(R.color.game_menu_text_primary)
            },
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SensitivityPresetActionButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val shape = CircleShape
    val accent = appAccentColor()
    Box(
        modifier = modifier
            .size(32.dp)
            .focusProperties { canFocus = enabled }
            .clip(shape)
            .background(accent.copy(alpha = if (enabled) 0.10f else 0.04f))
            .border(
                GameMenuDimens.surfaceStroke,
                accent.copy(alpha = if (enabled) 0.24f else 0.08f),
                shape
            )
            .gamepadFocusOutline(shape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, role = Role.Button) {
                hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = accent.copy(alpha = if (enabled) 1f else 0.36f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 显示设置状态卡:紧凑分组入口(分辨率/帧率/屏幕组合),下方按需提供主机显示缩放快捷入口。
 * 三项分别打开公共选择面板，主机支持时在卡片内调整显示缩放。
 */
@Composable
internal fun DisplayStatusCard(
    state: BitrateCardState,
    callbacks: GameMenuCallbacks,
    onConfigure: () -> Unit,
    onSliderGesture: (Boolean) -> Unit
) {
    val screenLabel = state.screenModeLabel
    val resolutionValue = compactResolutionLabel(state.resolutionLabel)
    val specValues = listOf(resolutionValue, state.fpsLabel, screenLabel)
    // 三列字号取一致,由最长值决定,避免某列挤缩其余列宽松
    val specFontSize = when ((specValues.maxOfOrNull { it.length } ?: 0)) {
        in 0..6 -> 13.sp
        in 7..9 -> 11.sp
        else -> 10.sp
    }
    val accent = appAccentColor()
    GameMenuCard(
        title = stringResource(R.string.game_menu_display_settings),
        titleAccessory = {
            GameMenuInfoButton(
                contentDescription = stringResource(R.string.display_settings_info),
                symbol = "?",
                onClick = callbacks.onDisplaySettingsInfo
            )
        },
        onLongClick = onConfigure
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DisplaySpecCell(
                label = stringResource(R.string.display_spec_resolution),
                value = resolutionValue,
                fontSize = specFontSize,
                accent = accent,
                onClick = callbacks.onOpenResolutionSheet,
                modifier = Modifier.weight(1f)
            )
            DisplaySpecCell(
                label = stringResource(R.string.display_spec_fps),
                value = state.fpsLabel,
                fontSize = specFontSize,
                accent = accent,
                onClick = callbacks.onOpenFrameRateSheet,
                modifier = Modifier.weight(1f)
            )
            DisplaySpecCell(
                label = stringResource(R.string.display_spec_screen),
                value = screenLabel,
                fontSize = specFontSize,
                accent = accent,
                onClick = callbacks.onOpenScreenModeSheet,
                modifier = Modifier.weight(1f)
            )
        }
        if (state.displayScale.loading || state.displayScale.available || state.displayScale.errorCode != null) {
            val scale = state.displayScale
            val supported = scale.supportedPercents
            val statePercent = scale.pendingPercent ?: scale.currentPercent
            val stateIndex = supported.indices.minByOrNull { index ->
                abs(supported[index] - (statePercent ?: supported.first()))
            } ?: 0
            var sliderIndex by remember(supported, statePercent, scale.loading) {
                mutableStateOf(stateIndex.toFloat())
            }
            var editing by remember(supported, statePercent, scale.loading) { mutableStateOf(false) }
            val previewScale = { value: Float ->
                if (!scale.loading) {
                    sliderIndex = value
                    editing = true
                }
            }
            val applyScale = {
                if (!scale.loading) {
                    supported.getOrNull(sliderIndex.roundToInt())?.let(callbacks.onSetDisplayScale)
                }
                editing = false
            }
            val selectedPercent = if (editing) supported.getOrNull(sliderIndex.roundToInt()) else statePercent
            val scaleSummary = when {
                scale.loading && scale.pendingPercent != null ->
                    stringResource(R.string.display_scale_applying, scale.pendingPercent)
                scale.loading -> stringResource(R.string.display_scale_loading)
                selectedPercent != null && selectedPercent == scale.recommendedPercent ->
                    stringResource(R.string.display_scale_value_recommended, selectedPercent)
                selectedPercent != null -> stringResource(R.string.display_scale_value, selectedPercent)
                scale.available -> stringResource(R.string.display_scale_unknown)
                else -> stringResource(R.string.display_scale_unavailable)
            }
            val currentScalePercent = scale.currentPercent
            val scaleDescription = if (currentScalePercent != null) {
                stringResource(R.string.display_scale_content_description, currentScalePercent)
            } else {
                stringResource(R.string.display_scale_title)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.small)
                    .gamepadFocusOutline(AppShapes.small)
                    .padding(horizontal = GameMenuDimens.section, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.compact),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.display_scale_title),
                        color = colorResource(R.color.game_menu_text_primary),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 80.dp)
                    )
                    CompactGameMenuSlider(
                        value = sliderIndex,
                        onValueChange = previewScale,
                        onValueChangeFinished = applyScale,
                        valueRange = 0f..((supported.size - 1).coerceAtLeast(0).toFloat()),
                        steps = (supported.size - 2).coerceAtLeast(0),
                        // Keep hardware focus while applying; ignore edits until the reply arrives.
                        enabled = scale.available && supported.size > 1,
                        modifier = Modifier
                            .weight(1f)
                            .height(GameMenuSliderSpec.height)
                            .testTag("displayScaleSlider")
                            .gamepadFocusOutline(GameMenuControlShape)
                            .semantics {
                                contentDescription = scaleDescription
                                stateDescription = scaleSummary
                            }
                            .handleSliderDpad(
                                value = sliderIndex,
                                enabled = scale.available && supported.size > 1 && !scale.loading,
                                step = 1f,
                                valueRange = 0f..((supported.size - 1).coerceAtLeast(0).toFloat()),
                                onValueChange = previewScale,
                                onValueChangeFinished = applyScale
                            )
                            .lockParentScrollDuringGesture(onSliderGesture)
                    )
                    Column(
                        modifier = Modifier.width(54.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (scale.loading) {
                                CircularProgressIndicator(
                                    color = accent,
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = selectedPercent?.let { stringResource(R.string.display_scale_value, it) } ?: "—",
                                color = accent,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = if (!scale.loading && selectedPercent != null && selectedPercent == scale.recommendedPercent) {
                                stringResource(R.string.display_scale_recommended)
                            } else "",
                            color = colorResource(R.color.game_menu_text_secondary),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (scale.errorCode != null && !scale.loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                if (scale.available) R.string.display_scale_apply_failed
                                else R.string.display_scale_load_failed
                            ),
                            color = colorResource(R.color.game_menu_text_secondary),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = callbacks.onRefreshDisplayScale) {
                            Text(stringResource(R.string.display_scale_refresh), color = accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplaySpecCell(
    label: String,
    value: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(AppShapes.small)
            .background(appAccentSoftColor().copy(alpha = 0.12f))
            .gamepadFocusOutline(AppShapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label, $value" }
            .heightIn(min = 44.dp)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = label,
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = accent,
            fontSize = if (fontSize.value > 12f) 12.sp else fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun BitrateCard(
    state: BitrateCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit,
    onConfigure: () -> Unit
) {
    val accent = appAccentColor()
    val secondary = colorResource(R.color.game_menu_text_secondary)
    GameMenuCard(
        title = stringResource(R.string.game_menu_tab_bitrate),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Reserve status space so starting/completing a request does not move the value.
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    if (state.bitrateUpdating) {
                        val description = stringResource(R.string.toast_adjusting_bitrate)
                        CircularProgressIndicator(
                            color = accent,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier
                                .size(12.dp)
                                .testTag("bitrateUpdating")
                                .semantics { contentDescription = description }
                        )
                    }
                }
                Spacer(Modifier.width(GameMenuDimens.tight))
                Text(
                    text = BitrateCardController.formatBitrateMbps(state.selectedBitrateKbps),
                    color = accent,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp).testTag("bitrateReadout")
                )
            }
        },
        onLongClick = onConfigure
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = GameMenuDimens.compact, bottom = GameMenuDimens.section),
            horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.tight),
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_adaptive_bitrate),
                    color = secondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(GameMenuDimens.tight))
                GameMenuInfoButton(
                    contentDescription = stringResource(R.string.game_menu_adaptive_bitrate_tip),
                    symbol = "?",
                    targetSize = 16.dp,
                    onClick = callbacks.onAdaptiveBitrateInfo,
                    onLongClick = callbacks.onBitrateHapticMode
                )
                Spacer(Modifier.width(GameMenuDimens.compact))
                InlineToggle(
                    checked = state.adaptiveBitrate,
                    contentDescription = stringResource(R.string.title_adaptive_bitrate),
                    onToggle = { callbacks.onBitrateAdaptive(!state.adaptiveBitrate) }
                )
            }
            if (state.adaptiveBitrate) {
                Box(Modifier.width(IntrinsicSize.Max).align(Alignment.CenterVertically)) {
                    AbrModeMenu(state.abrMode, callbacks.onAbrMode)
                }
            }
        }
        BitrateSlider(
            state = state,
            callbacks = callbacks,
            onSliderGesture = onSliderGesture,
            modifier = Modifier.testTag("bitrateSlider")
        )
        Row {
            Text(
                text = BitrateCardController.formatBitrateMbps(
                    BitrateCardController.progressToBitrateKbps(0, state.maxProgress)
                ),
                color = secondary,
                fontSize = 9.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = BitrateCardController.formatBitrateMbps(state.maxBitrateKbps),
                color = secondary,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun BitrateSlider(
    state: BitrateCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    CompactGameMenuSlider(
        value = state.progress,
        onValueChange = { value ->
            if (callbacks.onBitrateProgress(value)) {
                hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = callbacks.onBitrateApply,
        valueRange = 0f..state.maxProgress.toFloat(),
        modifier = modifier
            .fillMaxWidth()
            .height(GameMenuSliderSpec.height)
            .gamepadFocusOutline(GameMenuControlShape)
            .handleSliderDpad(
                value = state.progress,
                step = 1f,
                valueRange = 0f..state.maxProgress.toFloat(),
                onValueChange = { value ->
                    if (callbacks.onBitrateProgress(value)) {
                        hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                },
                onValueChangeFinished = callbacks.onBitrateApply
            )
            .lockParentScrollDuringGesture(onSliderGesture)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameMenuInfoButton(
    contentDescription: String,
    symbol: String = "i",
    targetSize: Dp = 28.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val accent = appAccentColor()
    Box(
        modifier = Modifier
            .size(targetSize)
            .clip(CircleShape)
            .focusProperties { canFocus = true }
            .gamepadFocusOutline(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .border(1.dp, accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            VisuallyCenteredBadgeText(
                text = symbol,
                color = accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AbrModeMenu(
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = appAccentColor()
    val names = stringArrayResource(R.array.abr_mode_names)
    val values = stringArrayResource(R.array.abr_mode_values)
    val selectedLabel = names.getOrElse(values.indexOf(selectedMode)) { names.firstOrNull().orEmpty() }
    Box {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(AppShapes.small)
                .focusProperties { canFocus = true }
                .gamepadFocusOutline(AppShapes.small)
                .clickable { expanded = true }
                .padding(horizontal = GameMenuDimens.compact),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedLabel,
                color = accent,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(GameMenuDimens.tight))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colorResource(R.color.game_menu_text_secondary),
                modifier = Modifier.size(10.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = names.getOrElse(index) { value },
                            color = if (value == selectedMode) accent else colorResource(R.color.game_menu_text_primary),
                            fontSize = 12.sp
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

private data class AudioHapticsChoice(
    val value: String,
    val label: String,
    val selected: Boolean
)

@Composable
private fun AudioHapticsCard(
    state: AudioHapticsCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit,
    onConfigure: () -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val title = stringResource(R.string.game_menu_tab_audio_haptics)
    val modeNames = stringArrayResource(R.array.audio_vibration_mode_names)
    val modeValues = stringArrayResource(R.array.audio_vibration_mode_values)
    val sceneNames = stringArrayResource(R.array.audio_vibration_scene_names)
    val sceneValues = stringArrayResource(R.array.audio_vibration_scene_values)
    val modeChoices = modeValues.mapIndexed { index, value ->
        AudioHapticsChoice(
            value = value,
            label = when (value) {
                AudioVibrationService.MODE_DEVICE_ONLY ->
                    stringResource(R.string.game_menu_audio_haptics_route_device_short)
                AudioVibrationService.MODE_GAMEPAD_ONLY ->
                    stringResource(R.string.game_menu_audio_haptics_route_gamepad_short)
                AudioVibrationService.MODE_BOTH ->
                    stringResource(R.string.game_menu_audio_haptics_route_both_short)
                else -> modeNames.getOrElse(index) { value }
            },
            selected = value == state.mode
        )
    }
    val sceneChoices = sceneValues.mapIndexed { index, value ->
        AudioHapticsChoice(
            value = value,
            label = compactSegmentLabel(sceneNames.getOrElse(index) { value }),
            selected = value.toIntOrNull() == state.scene
        )
    }

    GameMenuCard(
        title = title,
        trailing = {
            AudioHapticsResetButton(callbacks.onAudioHapticsReset)
            Spacer(Modifier.width(GameMenuDimens.tight))
            InlineToggle(
                checked = state.enabled,
                contentDescription = title,
                onToggle = {
                    callbacks.onAudioHapticsEnabled(!state.enabled)
                }
            )
        },
        onLongClick = onConfigure
    ) {
        if (state.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.title_seekbar_audio_vibration_strength),
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.game_menu_audio_haptics_strength_value,
                        state.strength
                    ),
                    color = appAccentColor(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            CompactGameMenuSlider(
                value = state.strength.toFloat(),
                onValueChange = { value ->
                    if (callbacks.onAudioHapticsStrength(value)) {
                        hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                },
                onValueChangeFinished = callbacks.onAudioHapticsStrengthFinished,
                valueRange = 0f..AudioVibrationService.MAX_STRENGTH.toFloat(),
                modifier = Modifier
                    .focusProperties { canFocus = true }
                    .fillMaxWidth()
                    .height(GameMenuSliderSpec.height)
                    .gamepadFocusOutline(GameMenuControlShape)
                    .handleSliderDpad(
                        value = state.strength.toFloat(),
                        step = AudioHapticsCardController.HAPTIC_STEP.toFloat(),
                        valueRange = 0f..AudioVibrationService.MAX_STRENGTH.toFloat(),
                        onValueChange = { value ->
                            if (callbacks.onAudioHapticsStrength(value)) {
                                hapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onValueChangeFinished = callbacks.onAudioHapticsStrengthFinished
                    )
                    .lockParentScrollDuringGesture(onSliderGesture)
            )
            AudioHapticsChoiceRow(
                label = stringResource(R.string.title_list_audio_vibration_mode),
                choices = modeChoices,
                onChoice = callbacks.onAudioHapticsMode
            )
            AudioHapticsChoiceRow(
                label = stringResource(R.string.title_list_audio_vibration_scene),
                choices = sceneChoices,
                onChoice = { value ->
                    value.toIntOrNull()?.let(callbacks.onAudioHapticsScene)
                }
            )
        }
        if (state.pendingRestart) {
            Text(
                text = stringResource(R.string.game_menu_audio_haptics_pending_restart),
                color = appAccentColor(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AudioHapticsResetButton(onReset: () -> Unit) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val description = stringResource(R.string.game_menu_audio_haptics_reset)
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .focusProperties { canFocus = true }
            .gamepadFocusOutline(shape)
            .semantics { contentDescription = description }
            .clickable {
                hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onReset()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.phc_action_reset),
            contentDescription = null,
            tint = colorResource(R.color.game_menu_text_secondary),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun AudioHapticsChoiceRow(
    label: String,
    choices: List<AudioHapticsChoice>,
    onChoice: (String) -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val accent = appAccentColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.28f)
        )
        Row(
            modifier = Modifier
                .weight(0.72f)
                .fillMaxHeight()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            choices.forEach { choice ->
                val shape = AppShapes.small
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(
                            if (choice.selected) accent.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .focusProperties { canFocus = true }
                        .gamepadFocusOutline(shape)
                        .selectable(
                            selected = choice.selected,
                            role = Role.RadioButton,
                            onClick = {
                                hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onChoice(choice.value)
                            }
                        )
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = choice.label,
                        color = if (choice.selected) {
                            accent
                        } else {
                            colorResource(R.color.game_menu_text_secondary)
                        },
                        fontSize = 10.sp,
                        fontWeight = if (choice.selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GyroCard(
    state: GyroCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit,
    onConfigure: () -> Unit
) {
    val title = stringResource(R.string.game_menu_tab_gyro)
    GameMenuCard(
        title = title,
        trailing = {
            Text(
                text = if (state.enabled) "ON" else "OFF",
                color = appAccentColor(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(GameMenuDimens.section))
            InlineToggle(
                checked = state.enabled,
                contentDescription = title,
                onToggle = {
                    callbacks.onGyroEnabled(!state.enabled)
                }
            )
        },
        onLongClick = onConfigure
    ) {
        if (state.enabled) {
            SettingSwitchRow(
                label = stringResource(R.string.gyro_mouse_mode_label),
                checked = state.mouseMode,
                onCheckedChange = callbacks.onGyroMouseMode
            )
            SettingValueRow(
                label = stringResource(R.string.gyro_activation_method),
                value = state.activationKeyLabel,
                onClick = callbacks.onGyroActivationKey
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.gyro_sensitivity),
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.US, "%.1fx", state.sensitivity),
                    color = appAccentColor(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = state.sensitivity,
                onValueChange = callbacks.onGyroSensitivity,
                onValueChangeFinished = callbacks.onGyroSensitivityFinished,
                valueRange = 0.5f..10.0f,
                modifier = Modifier
                    .focusProperties { canFocus = true }
                    .fillMaxWidth()
                    .gamepadFocusOutline(GameMenuControlShape)
                    .handleSliderDpad(
                        value = state.sensitivity,
                        step = 0.1f,
                        valueRange = 0.5f..10.0f,
                        onValueChange = callbacks.onGyroSensitivity,
                        onValueChangeFinished = callbacks.onGyroSensitivityFinished
                    )
                    .lockParentScrollDuringGesture(onSliderGesture)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.outer)) {
                SettingSwitchRow(
                    label = stringResource(R.string.gyro_invert_x_axis),
                    checked = state.invertX,
                    onCheckedChange = callbacks.onGyroInvertX,
                    modifier = Modifier.weight(1f)
                )
                SettingSwitchRow(
                    label = stringResource(R.string.gyro_invert_y_axis),
                    checked = state.invertY,
                    onCheckedChange = callbacks.onGyroInvertY,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

internal enum class SliderGestureDirection {
    Undecided,
    Horizontal,
    Vertical
}

internal fun classifySliderGesture(
    dragOffset: Offset,
    touchSlop: Float
): SliderGestureDirection {
    if (dragOffset.getDistance() < touchSlop) return SliderGestureDirection.Undecided
    return if (abs(dragOffset.x) > abs(dragOffset.y)) {
        SliderGestureDirection.Horizontal
    } else {
        SliderGestureDirection.Vertical
    }
}

internal fun Modifier.lockParentScrollDuringGesture(
    onGestureActive: (Boolean) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        var horizontalGesture = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                if (!change.pressed) break

                if (!horizontalGesture) {
                    val dragOffset = change.position - down.position
                    when (classifySliderGesture(dragOffset, viewConfiguration.touchSlop)) {
                        SliderGestureDirection.Undecided -> continue
                        SliderGestureDirection.Vertical -> break
                        SliderGestureDirection.Horizontal -> {
                            horizontalGesture = true
                            onGestureActive(true)
                        }
                    }
                }
            }
        } finally {
            if (horizontalGesture) onGestureActive(false)
        }
    }
}

@Composable
internal fun Modifier.gamepadFocusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val hardwareInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focusColor = appAccentColor()
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused && hardwareInput) Modifier.border(2.dp, focusColor, shape) else Modifier)
}

private fun Modifier.handleSliderDpad(
    value: Float,
    enabled: Boolean = true,
    step: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionLeft -> -1f
        Key.DirectionRight -> 1f
        else -> return@onPreviewKeyEvent false
    }
    if (!enabled) return@onPreviewKeyEvent true
    when (event.type) {
        KeyEventType.KeyDown -> {
            val adjusted = (value + direction * step).coerceIn(valueRange.start, valueRange.endInclusive)
            if (adjusted != value) onValueChange(adjusted)
            true
        }
        KeyEventType.KeyUp -> {
            onValueChangeFinished()
            true
        }
        else -> false
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = colorResource(R.color.game_menu_text_primary),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        InlineToggle(
            checked = checked,
            contentDescription = label,
            onToggle = {
                onCheckedChange(!checked)
            }
        )
    }
}

@Composable
private fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = true }
            .clip(GameMenuControlShape)
            .gamepadFocusOutline(GameMenuControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = GameMenuDimens.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colorResource(R.color.game_menu_text_secondary), fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = appAccentColor(), fontSize = 10.sp)
        Spacer(Modifier.width(GameMenuDimens.tight))
        Text("›", color = colorResource(R.color.game_menu_text_secondary), fontSize = 14.sp)
    }
}

@Composable
private fun ShortcutCard(
    keys: List<CustomKeyData>,
    onKey: (CustomKeyData) -> Unit,
    onConfigure: () -> Unit
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    GameMenuCard(
        title = stringResource(R.string.game_menu_tab_shortcuts),
        onLongClick = onConfigure
    ) {
        keys.forEachIndexed { index, key ->
            Text(
                text = key.name,
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = true }
                    .clip(GameMenuControlShape)
                    .gamepadFocusOutline(GameMenuControlShape)
                    .clickable {
                        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onKey(key)
                    }
                    .padding(
                        horizontal = GameMenuDimens.section,
                        vertical = GameMenuDimens.compact
                    )
            )
            if (index < keys.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(appAccentSoftColor())
                )
            }
        }
    }
}

@Composable
private fun WaveformHapticsCard(
    state: WaveformHapticsCardState,
    callbacks: GameMenuCallbacks
) {
    GameMenuCard(
        title = stringResource(R.string.waveform_card_title),
        onLongClick = callbacks.onEditCards
    ) {
        state.routes.forEach { route ->
            Text(
                text = route.label + "\n" + route.status,
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 10.sp
            )
            if (route.canTest || route.testing) {
                androidx.compose.material3.TextButton(
                    onClick = { callbacks.onWaveformTest(route.id, route.testing) }
                ) {
                    Text(
                        stringResource(
                            if (route.testing) R.string.waveform_test_cancel
                            else R.string.waveform_test_channels
                        )
                    )
                }
            }
        }
    }
}
