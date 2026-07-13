package com.limelight

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Locale

private val GameMenuDialogShape = RoundedCornerShape(16.dp)
private val GameMenuCardShape = RoundedCornerShape(10.dp)
private val GameMenuControlShape = RoundedCornerShape(10.dp)
private const val SuperOptionGridColumns = 4

internal data class GameMenuQuickAction(
    val id: String,
    val label: String,
    @param:DrawableRes val iconRes: Int,
    val enabled: Boolean = true
)

internal data class GameMenuComposeUiState(
    val title: String,
    val options: List<GameMenu.MenuOption>,
    val superOptions: List<GameMenu.MenuOption>,
    val appName: String,
    val crownToggleText: String,
    val quickActions: List<GameMenuQuickAction>,
    val visibleCards: GameMenuVisibleCards,
    val bitrate: BitrateCardState,
    val gyro: GyroCardState,
    val customKeys: List<CustomKeyData>,
    val quickEditMode: Boolean = false,
    val isSubmenu: Boolean = false
)

internal data class GameMenuVisibleCards(
    val bitrate: Boolean,
    val gyro: Boolean,
    val shortcuts: Boolean
)

internal data class GameMenuCallbacks(
    val iconForOption: (String?) -> Int,
    val onBack: () -> Unit,
    val onCrownToggle: () -> Unit,
    val onOptionClick: (GameMenu.MenuOption) -> Unit,
    val onQuickAction: (String) -> Unit,
    val onToggleQuickEdit: () -> Unit,
    val onAddQuickAction: () -> Unit,
    val onRemoveQuickAction: (String) -> Unit,
    val onMoveQuickAction: (String, Int) -> Unit,
    val onEditCards: () -> Unit,
    val onBitrateProgress: (Float) -> Boolean,
    val onBitrateApply: () -> Unit,
    val onBitrateHapticMode: () -> Unit,
    val onGyroEnabled: (Boolean) -> Unit,
    val onGyroMouseMode: (Boolean) -> Unit,
    val onGyroActivationKey: () -> Unit,
    val onGyroSensitivity: (Float) -> Unit,
    val onGyroSensitivityFinished: () -> Unit,
    val onGyroInvertX: (Boolean) -> Unit,
    val onGyroInvertY: (Boolean) -> Unit,
    val onCustomKey: (CustomKeyData) -> Unit
)

@Composable
internal fun GameMenuScreen(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks
) {
    val border = colorResource(R.color.game_menu_dialog_border)
    val accent = colorResource(R.color.theme_pink_primary)
    val card = colorResource(R.color.game_menu_card_background)
    val textPrimary = colorResource(R.color.game_menu_text_primary)
    val textSecondary = colorResource(R.color.game_menu_text_secondary)
    val configuration = LocalConfiguration.current
    val maxMenuHeight = (configuration.screenHeightDp * 0.86f).dp
    var sliderGestureActive by remember { mutableStateOf(false) }
    val menuScrollState = rememberScrollState()

    LaunchedEffect(state.title, state.isSubmenu) {
        menuScrollState.scrollTo(0)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.12f),
            onPrimaryContainer = accent,
            secondary = accent,
            onSecondary = Color.White,
            secondaryContainer = accent.copy(alpha = 0.16f),
            onSecondaryContainer = accent,
            tertiary = accent,
            onTertiary = Color.White,
            tertiaryContainer = accent.copy(alpha = 0.12f),
            onTertiaryContainer = accent,
            surface = card,
            onSurface = textPrimary,
            surfaceVariant = card,
            surfaceContainerHighest = accent.copy(alpha = 0.10f),
            onSurfaceVariant = textSecondary,
            outline = border
        )
    ) {
        Surface(
            color = Color.Transparent,
            shape = GameMenuDialogShape,
            border = BorderStroke(1.dp, border),
            modifier = Modifier
                .widthIn(max = 760.dp)
                .heightIn(max = maxMenuHeight)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(
                        state = menuScrollState,
                        enabled = !sliderGestureActive
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GameMenuHeader(state, callbacks.onBack, callbacks.onCrownToggle)

                if (!state.isSubmenu) {
                    QuickActionRow(
                        actions = state.quickActions,
                        editMode = state.quickEditMode,
                        onAction = callbacks.onQuickAction,
                        onToggleEdit = callbacks.onToggleQuickEdit,
                        onAdd = callbacks.onAddQuickAction,
                        onRemove = callbacks.onRemoveQuickAction,
                        onMove = callbacks.onMoveQuickAction
                    )
                }

                val wide = configuration.screenWidthDp >= 576 && !state.isSubmenu
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        MenuOptionColumn(
                            options = state.options,
                            iconForOption = callbacks.iconForOption,
                            onOptionClick = callbacks.onOptionClick,
                            modifier = Modifier.weight(1f)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            GameMenuCards(state, callbacks) { sliderGestureActive = it }
                            SuperOptionGrid(state.superOptions, callbacks.onOptionClick)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MenuOptionColumn(
                            state.options,
                            callbacks.iconForOption,
                            callbacks.onOptionClick
                        )
                        if (!state.isSubmenu) {
                            GameMenuCards(state, callbacks) { sliderGestureActive = it }
                            MenuOptionColumn(
                                state.superOptions,
                                callbacks.iconForOption,
                                callbacks.onOptionClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameMenuHeader(
    state: GameMenuComposeUiState,
    onBack: () -> Unit,
    onCrownToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.isSubmenu) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back_24),
                contentDescription = stringResource(R.string.addpc_back),
                tint = colorResource(R.color.game_menu_text_primary),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .gamepadFocusOutline(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(7.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = state.title,
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = (if (state.isSubmenu) Modifier.weight(1f) else Modifier)
                    .alignByBaseline()
            )
            if (!state.isSubmenu && state.appName.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.appName,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline()
                )
            }
        }
        if (!state.isSubmenu) {
            val crownShape = CircleShape
            Icon(
                painter = painterResource(R.drawable.ic_super_crown),
                contentDescription = state.crownToggleText,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(34.dp)
                    .clip(crownShape)
                    .background(colorResource(R.color.theme_pink_primary).copy(alpha = 0.10f))
                    .border(
                        1.dp,
                        colorResource(R.color.theme_pink_primary).copy(alpha = 0.32f),
                        crownShape
                    )
                    .gamepadFocusOutline(crownShape)
                    .clickable(onClick = onCrownToggle)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    actions: List<GameMenuQuickAction>,
    editMode: Boolean,
    onAction: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        actions.forEachIndexed { index, action ->
            QuickActionChip(
                action = action,
                editMode = editMode,
                canMoveLeft = index > 0,
                canMoveRight = index < actions.lastIndex,
                onClick = { onAction(action.id) },
                onRemove = { onRemove(action.id) },
                onMoveLeft = { onMove(action.id, -1) },
                onMoveRight = { onMove(action.id, 1) }
            )
        }
        ToolChip(if (editMode) "✓" else "✎", onToggleEdit)
        if (editMode) ToolChip("＋", onAdd)
    }
}

@Composable
private fun QuickActionChip(
    action: GameMenuQuickAction,
    editMode: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    val view = LocalView.current
    val shape = GameMenuControlShape
    val contentAlpha = if (action.enabled) 1f else 0.45f
    Column(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .clip(shape)
            .background(colorResource(R.color.game_menu_card_background).copy(alpha = contentAlpha))
            .border(1.dp, colorResource(R.color.game_menu_button_border), shape)
            .gamepadFocusOutline(shape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (editMode) onRemove() else onClick()
            }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (action.iconRes != 0) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = action.label,
                color = colorResource(R.color.game_menu_text_primary).copy(alpha = contentAlpha),
                fontSize = 12.sp,
                maxLines = 1
            )
            if (editMode) {
                Spacer(Modifier.width(5.dp))
                Text("×", color = Color(0xFFD83A3A), fontWeight = FontWeight.Bold)
            }
        }
        if (editMode && (canMoveLeft || canMoveRight)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canMoveLeft) Text("‹", modifier = Modifier.clickable(onClick = onMoveLeft))
                if (canMoveRight) Text("›", modifier = Modifier.clickable(onClick = onMoveRight))
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    val shape = GameMenuControlShape
    Box(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 34.dp)
            .clip(shape)
            .background(colorResource(R.color.game_menu_card_background))
            .border(1.dp, colorResource(R.color.game_menu_button_border), shape)
            .gamepadFocusOutline(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = colorResource(R.color.theme_pink_primary), fontSize = 14.sp)
    }
}

@Composable
private fun MenuOptionColumn(
    options: List<GameMenu.MenuOption>,
    iconForOption: (String?) -> Int,
    onOptionClick: (GameMenu.MenuOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            MenuOptionRow(option, iconForOption(option.iconKey)) { onOptionClick(option) }
        }
    }
}

@Composable
private fun MenuOptionRow(
    option: GameMenu.MenuOption,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val shape = GameMenuCardShape
    val danger = option.iconKey == "game_menu_disconnect" ||
        option.iconKey == "game_menu_disconnect_and_quit"
    val borderColor = when {
        danger -> Color(0x55D83A3A)
        option.isCrownControl -> colorResource(R.color.theme_pink_primary).copy(alpha = 0.55f)
        else -> colorResource(R.color.game_menu_list_item_border)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorResource(R.color.game_menu_list_item_normal))
            .border(1.dp, borderColor, shape)
            .gamepadFocusOutline(shape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.isShowIcon && iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                color = if (danger) Color(0xFFD13B3B) else colorResource(R.color.game_menu_text_primary),
                fontSize = 13.sp,
                fontWeight = if (option.isCrownControl) FontWeight.Medium else FontWeight.Normal
            )
            option.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (option.showChevron) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colorResource(R.color.game_menu_text_secondary),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun SuperOptionGrid(
    options: List<GameMenu.MenuOption>,
    onOptionClick: (GameMenu.MenuOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(SuperOptionGridColumns).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowOptions.forEach { option ->
                    SuperOptionTile(
                        option = option,
                        onClick = { onOptionClick(option) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(SuperOptionGridColumns - rowOptions.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SuperOptionTile(
    option: GameMenu.MenuOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val accent = colorResource(R.color.theme_pink_primary)
    val initial = option.label.trim().firstOrNull()?.toString() ?: "?"

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(GameMenuCardShape)
            .background(colorResource(R.color.game_menu_list_item_normal))
            .border(1.dp, colorResource(R.color.game_menu_list_item_border), GameMenuCardShape)
            .gamepadFocusOutline(GameMenuCardShape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = option.label,
            color = colorResource(R.color.game_menu_text_primary),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GameMenuCards(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.visibleCards.bitrate) {
            BitrateCard(state.bitrate, callbacks, onSliderGesture)
        }
        if (state.visibleCards.gyro) {
            GyroCard(state.gyro, callbacks, onSliderGesture)
        }
        if (state.visibleCards.shortcuts && state.customKeys.isNotEmpty()) {
            ShortcutCard(state.customKeys, callbacks.onCustomKey)
        }
        Text(
            text = "✎ ${stringResource(R.string.game_menu_card_config_title)}",
            color = colorResource(R.color.theme_pink_primary),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.End)
                .clip(GameMenuControlShape)
                .gamepadFocusOutline(GameMenuControlShape)
                .clickable(onClick = callbacks.onEditCards)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun GameMenuCard(
    title: String,
    status: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = colorResource(R.color.game_menu_card_background),
        shape = GameMenuCardShape,
        border = BorderStroke(1.dp, colorResource(R.color.game_menu_button_border)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = colorResource(R.color.game_menu_text_primary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) {
                    trailing()
                } else status?.let {
                    Text(
                        text = it,
                        color = colorResource(R.color.theme_pink_primary),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BitrateCard(
    state: BitrateCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit
) {
    val view = LocalView.current
    var tipVisible by remember { mutableStateOf(false) }
    val currentLabel = stringResource(
        R.string.game_menu_bitrate_current,
        state.currentBitrateKbps / 1000
    )
    GameMenuCard(
        title = stringResource(R.string.game_menu_tab_bitrate),
        status = state.abrStatus
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentLabel,
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Box {
                Text(
                    text = "?",
                    color = colorResource(R.color.theme_pink_primary),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .gamepadFocusOutline(CircleShape)
                        .combinedClickable(
                            onClick = { tipVisible = !tipVisible },
                            onLongClick = callbacks.onBitrateHapticMode
                        )
                        .padding(5.dp)
                )
                if (tipVisible) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        onDismissRequest = { tipVisible = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Surface(
                            color = colorResource(R.color.game_menu_card_background),
                            shape = GameMenuCardShape,
                            border = BorderStroke(
                                1.dp,
                                colorResource(R.color.theme_pink_primary).copy(alpha = 0.32f)
                            ),
                            modifier = Modifier.widthIn(max = 260.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.game_menu_bitrate_tip),
                                color = colorResource(R.color.game_menu_text_primary),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = BitrateCardController.formatBitrateMbps(state.selectedBitrateKbps),
            color = colorResource(R.color.theme_pink_primary),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Slider(
            value = state.progress,
            onValueChange = { value ->
                if (callbacks.onBitrateProgress(value)) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            },
            onValueChangeFinished = callbacks.onBitrateApply,
            valueRange = 0f..BitrateCardController.MAX_PROGRESS.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .gamepadFocusOutline(GameMenuControlShape)
                .handleSliderDpad(
                    value = state.progress,
                    step = 1f,
                    valueRange = 0f..BitrateCardController.MAX_PROGRESS.toFloat(),
                    onValueChange = { value ->
                        if (callbacks.onBitrateProgress(value)) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    },
                    onValueChangeFinished = callbacks.onBitrateApply
                )
                .lockParentScrollDuringGesture(onSliderGesture)
        )
        Row {
            Text("0.5 Mbps", color = colorResource(R.color.game_menu_text_secondary), fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text("200 Mbps", color = colorResource(R.color.game_menu_text_secondary), fontSize = 9.sp)
        }
    }
}

@Composable
private fun GyroCard(
    state: GyroCardState,
    callbacks: GameMenuCallbacks,
    onSliderGesture: (Boolean) -> Unit
) {
    GameMenuCard(
        title = stringResource(R.string.game_menu_tab_gyro),
        trailing = {
            Text(
                text = if (state.enabled) "ON" else "OFF",
                color = colorResource(R.color.theme_pink_primary),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = callbacks.onGyroEnabled,
                modifier = Modifier.gamepadFocusOutline(RoundedCornerShape(18.dp))
            )
        }
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
                    color = colorResource(R.color.theme_pink_primary),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = state.sensitivity,
                onValueChange = callbacks.onGyroSensitivity,
                onValueChangeFinished = callbacks.onGyroSensitivityFinished,
                valueRange = 0.5f..3.0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .gamepadFocusOutline(GameMenuControlShape)
                    .handleSliderDpad(
                        value = state.sensitivity,
                        step = 0.1f,
                        valueRange = 0.5f..3.0f,
                        onValueChange = callbacks.onGyroSensitivity,
                        onValueChangeFinished = callbacks.onGyroSensitivityFinished
                    )
                    .lockParentScrollDuringGesture(onSliderGesture)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

private fun Modifier.lockParentScrollDuringGesture(
    onGestureActive: (Boolean) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onGestureActive(true)
        try {
            waitForUpOrCancellation()
        } finally {
            onGestureActive(false)
        }
    }
}

@Composable
private fun Modifier.gamepadFocusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val focusColor = colorResource(R.color.theme_pink_primary)
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, focusColor, shape) else Modifier)
}

private fun Modifier.handleSliderDpad(
    value: Float,
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.gamepadFocusOutline(RoundedCornerShape(18.dp))
        )
    }
}

@Composable
private fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GameMenuControlShape)
            .gamepadFocusOutline(GameMenuControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colorResource(R.color.game_menu_text_secondary), fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = colorResource(R.color.theme_pink_primary), fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text("›", color = colorResource(R.color.game_menu_text_secondary), fontSize = 14.sp)
    }
}

@Composable
private fun ShortcutCard(keys: List<CustomKeyData>, onKey: (CustomKeyData) -> Unit) {
    val view = LocalView.current
    GameMenuCard(title = stringResource(R.string.game_menu_tab_shortcuts)) {
        keys.forEachIndexed { index, key ->
            Text(
                text = key.name,
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GameMenuControlShape)
                    .gamepadFocusOutline(GameMenuControlShape)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onKey(key)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            if (index < keys.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colorResource(R.color.game_menu_button_border))
                )
            }
        }
    }
}
