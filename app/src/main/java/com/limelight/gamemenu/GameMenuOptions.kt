package com.limelight.gamemenu

// Primary menu options and reusable inline controls.

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.ui.theme.AppShapes


@Composable
internal fun MenuOptionColumn(
    options: List<GameMenu.MenuOption>,
    iconForOption: (String?) -> Int,
    onOptionClick: (GameMenu.MenuOption) -> Unit,
    onInlineToggle: (GameMenu.InlineControl.Toggle) -> Unit,
    onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    optionFocusModifier: Modifier = Modifier,
    inlineControlsFocusable: Boolean = true
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)) {
        options.forEachIndexed { index, option ->
            MenuOptionRow(
                option = option,
                iconRes = iconForOption(option.iconKey),
                onClick = { onOptionClick(option) },
                onInlineToggle = onInlineToggle,
                onSegmentClick = onSegmentClick,
                modifier = optionFocusModifier,
                initialFocusRequester = initialFocusRequester.takeIf { index == 0 },
                inlineControlsFocusable = inlineControlsFocusable
            )
        }
    }
}

@Composable
private fun MenuOptionRow(
    option: GameMenu.MenuOption,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    onInlineToggle: (GameMenu.InlineControl.Toggle) -> Unit,
    onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    inlineControlsFocusable: Boolean = true
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val shape = GameMenuCardShape
    val inlineControl = option.inlineControl
    val showChevronAfterTitle = option.showChevron && inlineControl != null
    val hasDedicatedToggleAction = inlineControl is GameMenu.InlineControl.Toggle &&
        inlineControl.toggleAction != null
    val danger = option.iconKey == "game_menu_disconnect" ||
        option.iconKey == "game_menu_disconnect_and_quit"
    val borderColor = when {
        option.selected -> colorResource(R.color.game_menu_accent).copy(alpha = 0.70f)
        option.isCrownControl -> colorResource(R.color.game_menu_accent).copy(alpha = 0.55f)
        else -> colorResource(R.color.game_menu_list_item_border)
    }
    val activate = {
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
    val initialFocusModifier = initialFocusRequester?.let {
        Modifier.focusRequester(it)
    } ?: Modifier
    val rowInteraction = when {
        inlineControl is GameMenu.InlineControl.Toggle &&
            hasDedicatedToggleAction &&
            !inlineControlsFocusable ->
            initialFocusModifier
                .gamepadFocusOutline(shape)
                .toggleable(
                    value = inlineControl.checked,
                    role = Role.Switch,
                    onValueChange = {
                        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onInlineToggle(inlineControl)
                    }
                )
                .focusable()
        inlineControl is GameMenu.InlineControl.Toggle && !hasDedicatedToggleAction ->
            initialFocusModifier
                .gamepadFocusOutline(shape)
                .toggleable(
                    value = inlineControl.checked,
                    role = Role.Switch,
                    onValueChange = { activate() }
                )
        inlineControl !is GameMenu.InlineControl.Segmented && option.runnable != null ->
            initialFocusModifier
                .gamepadFocusOutline(shape)
                .clickable(onClick = activate)
        else -> Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(colorResource(R.color.game_menu_list_item_normal))
            .border(GameMenuDimens.surfaceStroke, borderColor, shape)
            .then(modifier)
            .then(rowInteraction)
            .padding(horizontal = GameMenuDimens.section),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A missing/unsupported icon must not be passed to painterResource().
        // This matters on pre-N devices, where menu icons are disabled.
        if (option.isShowIcon && iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(GameMenuDimens.section))
        }

        val labelModifier = if (
            inlineControl is GameMenu.InlineControl.Segmented && option.runnable != null
        ) {
            initialFocusModifier
                .gamepadFocusOutline(GameMenuControlShape)
                .clickable(onClick = activate)
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .weight(if (inlineControl is GameMenu.InlineControl.Segmented) 0.42f else 1f)
                .then(labelModifier)
                .padding(vertical = GameMenuDimens.compact)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.label,
                    color = if (danger) {
                        colorResource(R.color.game_menu_danger_text)
                    } else {
                        colorResource(R.color.game_menu_text_primary)
                    },
                    fontSize = 13.sp,
                    fontWeight = if (option.isCrownControl) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (showChevronAfterTitle) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
                if (showChevronAfterTitle) {
                    Spacer(Modifier.width(GameMenuDimens.tight))
                    MenuChevron(size = 12.dp)
                }
            }
            option.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when (inlineControl) {
            is GameMenu.InlineControl.Toggle -> {
                InlineToggle(
                    checked = inlineControl.checked,
                    contentDescription = option.label,
                    modifier = if (inlineControlsFocusable) {
                        Modifier
                    } else {
                        Modifier.focusProperties { canFocus = false }
                    },
                    onToggle = if (hasDedicatedToggleAction && inlineControlsFocusable) {
                        {
                            hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onInlineToggle(inlineControl)
                        }
                    } else {
                        null
                    }
                )
            }
            is GameMenu.InlineControl.Segmented -> {
                Spacer(Modifier.width(GameMenuDimens.section))
                InlineSegmentedControl(
                    segments = inlineControl.segments,
                    onSegmentClick = onSegmentClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (inlineControl.segments.size > 3) 72.dp else 36.dp)
                )
            }
            null -> if (option.showChevron) MenuChevron()
        }
        if (option.selected) {
            Text(
                text = "✓",
                color = colorResource(R.color.game_menu_accent),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = GameMenuDimens.compact)
            )
        }
    }
}

@Composable
private fun MenuChevron(size: Dp = 13.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_arrow_right),
        contentDescription = null,
        tint = colorResource(R.color.game_menu_text_secondary),
        modifier = Modifier.size(size)
    )
}

@Composable
internal fun InlineToggle(
    checked: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null
) {
    val accent = colorResource(R.color.game_menu_accent)
    val track = if (checked) accent.copy(alpha = 0.82f) else colorResource(R.color.game_menu_button_border)
    val outline = if (checked) accent.copy(alpha = 0.46f) else colorResource(R.color.game_menu_list_item_border)
    val interactionModifier = if (onToggle != null) {
        Modifier
            .semantics { this.contentDescription = contentDescription }
            .gamepadFocusOutline(CircleShape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onToggle() }
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .height(36.dp)
            .then(interactionModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(CircleShape)
                .background(track)
                .border(GameMenuDimens.surfaceStroke, outline, CircleShape)
                .padding(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(
                        GameMenuDimens.surfaceStroke,
                        if (checked) accent.copy(alpha = 0.28f) else Color.White,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun InlineSegmentedControl(
    segments: List<GameMenu.SegmentOption>,
    onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val accent = colorResource(R.color.game_menu_accent)
    val columnCount = if (segments.size > 3) 3 else segments.size.coerceAtLeast(1)
    val focusRequesters = remember(segments.size) { List(segments.size) { FocusRequester() } }
    Column(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        segments.chunked(columnCount).forEachIndexed { rowIndex, rowSegments ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowSegments.forEachIndexed { columnIndex, segment ->
                    val index = rowIndex * columnCount + columnIndex
                    val targets = segmentedFocusTargets(segments.size, index, columnCount)
                    val segmentShape = AppShapes.small
                    val background = if (segment.selected) {
                        accent.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRequester(focusRequesters[index])
                            .segmentedFocusNavigation(targets, focusRequesters)
                            .clip(segmentShape)
                            .background(background)
                            .gamepadFocusOutline(segmentShape)
                            .selectable(
                                selected = segment.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    onSegmentClick(segment)
                                }
                            )
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = compactSegmentLabel(segment.label),
                            color = if (segment.selected) {
                                accent
                            } else {
                                colorResource(R.color.game_menu_text_secondary)
                            },
                            fontSize = 10.sp,
                            fontWeight = if (segment.selected) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(columnCount - rowSegments.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal data class SegmentedFocusTargets(
    val left: Int?,
    val right: Int?,
    val up: Int?,
    val down: Int?
)

internal fun segmentedFocusTargets(
    itemCount: Int,
    index: Int,
    columnCount: Int
): SegmentedFocusTargets {
    require(itemCount > 0)
    require(columnCount > 0)
    require(index in 0 until itemCount)
    val row = index / columnCount
    val column = index % columnCount
    val rowStart = row * columnCount
    val rowEnd = minOf(rowStart + columnCount, itemCount)
    val nextRowStart = rowStart + columnCount
    return SegmentedFocusTargets(
        left = (index - 1).takeIf { column > 0 },
        right = (index + 1).takeIf { it < rowEnd },
        up = (index - columnCount).takeIf { row > 0 },
        down = if (nextRowStart < itemCount) {
            minOf(nextRowStart + column, itemCount - 1)
        } else {
            null
        }
    )
}

internal fun Modifier.segmentedFocusNavigation(
    targets: SegmentedFocusTargets,
    focusRequesters: List<FocusRequester>
): Modifier = focusProperties {
    left = targets.left?.let(focusRequesters::get) ?: FocusRequester.Default
    right = targets.right?.let(focusRequesters::get) ?: FocusRequester.Default
    up = targets.up?.let(focusRequesters::get) ?: FocusRequester.Default
    down = targets.down?.let(focusRequesters::get) ?: FocusRequester.Default
}

internal fun compactSegmentLabel(label: String): String {
    val text = label.trim()
    if (' ' in text) return compactActionLabel(text.substringBefore(' '), maxCodePoints = 8)

    val codePointCount = text.codePointCount(0, text.length)
    if (!text.containsHanCodePoint() || codePointCount <= 4) {
        return compactActionLabel(text, maxCodePoints = 8)
    }
    val prefixEnd = text.offsetByCodePoints(0, 2)
    val suffixStart = text.offsetByCodePoints(0, codePointCount - 2)
    return text.substring(0, prefixEnd) + text.substring(suffixStart)
}

private fun String.containsHanCodePoint(): Boolean {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2EBEF
        ) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

@Composable
internal fun ActionTextBadge(text: String) {
    val accent = colorResource(R.color.game_menu_accent)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        VisuallyCenteredBadgeText(
            text = text,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun VisuallyCenteredBadgeText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight
) {
    Layout(
        content = {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, -1)
        }
    }
}

@Composable
internal fun SuperOptionChip(
    option: GameMenu.MenuOption,
    onClick: () -> Unit
) {
    val accent = colorResource(R.color.game_menu_accent)
    ActionPill(
        backgroundColor = accent.copy(alpha = 0.07f),
        borderColor = accent.copy(alpha = 0.20f),
        onClick = onClick
    ) {
        ActionTextBadge(firstActionCharacter(option.label))
        Spacer(Modifier.width(GameMenuDimens.compact))
        Text(
            text = compactActionLabel(option.label),
            color = colorResource(R.color.game_menu_text_primary),
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
internal fun EmptySuperCommandChip(onClick: () -> Unit) {
    val accent = colorResource(R.color.game_menu_accent)
    ActionPill(
        backgroundColor = Color.Transparent,
        borderColor = accent.copy(alpha = 0.34f),
        dashedBorder = true,
        onClick = onClick
    ) {
        ActionTextBadge("+")
        Spacer(Modifier.width(GameMenuDimens.compact))
        Text(
            text = stringResource(R.string.game_menu_super_commands),
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}

internal fun compactActionLabel(label: String, maxCodePoints: Int = 8): String {
    val text = label.trim()
    if (text.codePointCount(0, text.length) <= maxCodePoints) return text
    val visibleCodePoints = (maxCodePoints - 1).coerceAtLeast(0)
    val endIndex = text.offsetByCodePoints(0, visibleCodePoints)
    return text.substring(0, endIndex) + "…"
}

internal fun firstActionCharacter(label: String): String {
    val text = label.trim()
    if (text.isEmpty()) return "?"
    return text.substring(0, text.offsetByCodePoints(0, 1))
}
