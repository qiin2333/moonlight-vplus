package com.limelight

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

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
    val quickEditMode: Boolean = false,
    val isSubmenu: Boolean = false
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
    val createLegacyCards: () -> View,
    val releaseLegacyCards: () -> Unit
)

@Composable
internal fun GameMenuScreen(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks
) {
    val border = colorResource(R.color.game_menu_dialog_border)

    MaterialTheme {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, border),
            modifier = Modifier.widthIn(max = 960.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 576.dp && !state.isSubmenu
                    if (wide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MenuOptionColumn(
                                    state.superOptions,
                                    callbacks.iconForOption,
                                    callbacks.onOptionClick
                                )
                                LegacyCards(callbacks.createLegacyCards, callbacks.releaseLegacyCards)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MenuOptionColumn(
                                state.options,
                                callbacks.iconForOption,
                                callbacks.onOptionClick
                            )
                            if (!state.isSubmenu) {
                                MenuOptionColumn(
                                    state.superOptions,
                                    callbacks.iconForOption,
                                    callbacks.onOptionClick
                                )
                                LegacyCards(callbacks.createLegacyCards, callbacks.releaseLegacyCards)
                            }
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
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(8.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = state.title,
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!state.isSubmenu) {
                Text(
                    text = state.appName,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!state.isSubmenu) {
            Text(
                text = state.crownToggleText,
                color = colorResource(R.color.theme_pink_primary),
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCrownToggle)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
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
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
    val shape = RoundedCornerShape(10.dp)
    val contentAlpha = if (action.enabled) 1f else 0.45f
    Column(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .clip(shape)
            .background(colorResource(R.color.game_menu_card_background).copy(alpha = contentAlpha))
            .border(1.dp, colorResource(R.color.game_menu_button_border), shape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (editMode) onRemove() else onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (action.iconRes != 0) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(5.dp))
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
    Box(
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colorResource(R.color.game_menu_card_background))
            .border(1.dp, colorResource(R.color.game_menu_button_border), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = colorResource(R.color.theme_pink_primary), fontSize = 16.sp)
    }
}

@Composable
private fun MenuOptionColumn(
    options: List<GameMenu.MenuOption>,
    iconForOption: (String?) -> Int,
    onOptionClick: (GameMenu.MenuOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    val shape = RoundedCornerShape(11.dp)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.isShowIcon && iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                color = if (danger) Color(0xFFD13B3B) else colorResource(R.color.game_menu_text_primary),
                fontSize = 14.sp,
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
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun LegacyCards(factory: () -> View, onRelease: () -> Unit) {
    AndroidView(
        factory = { factory() },
        onRelease = { onRelease() },
        modifier = Modifier.fillMaxWidth()
    )
}
