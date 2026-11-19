package com.limelight.gamemenu

// Primary menu options and reusable inline controls.

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.PerfOverlayAnchor
import com.limelight.ui.gamepadFocusOutline
import com.limelight.utils.AppSwitch
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.appAccentColor


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
    inlineControlsFocusable: Boolean = true,
    initialFocusIconKey: String? = null,
    initialFocusOptionKey: String? = null,
    linearNavigation: Boolean = false,
    topFocusRequester: FocusRequester? = null
) {
    val initialFocusIndex = options.indexOfFirst { it.focusKey == initialFocusOptionKey }
        .takeIf { initialFocusOptionKey != null && it >= 0 }
        ?: options.indexOfFirst { it.iconKey == initialFocusIconKey }
            .takeIf { initialFocusIconKey != null && it >= 0 } ?: 0
    val focusRequesters = remember(options.size, initialFocusIndex, initialFocusRequester) {
        List(options.size) { index ->
            initialFocusRequester?.takeIf { index == initialFocusIndex } ?: FocusRequester()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)) {
        options.forEachIndexed { index, option ->
            val navigation = if (linearNavigation) Modifier.focusProperties {
                up = focusRequesters.getOrNull(index - 1) ?: topFocusRequester ?: FocusRequester.Cancel
                down = focusRequesters.getOrNull(index + 1) ?: FocusRequester.Cancel
            } else Modifier
            MenuOptionRow(
                option = option,
                iconRes = iconForOption(option.iconKey),
                onClick = { onOptionClick(option) },
                onInlineToggle = onInlineToggle,
                onSegmentClick = onSegmentClick,
                modifier = optionFocusModifier,
                initialFocusRequester = if (linearNavigation) focusRequesters[index]
                    else initialFocusRequester.takeIf { index == initialFocusIndex },
                inlineControlsFocusable = inlineControlsFocusable,
                linearNavigation = linearNavigation,
                navigationModifier = navigation
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
    inlineControlsFocusable: Boolean = true,
    linearNavigation: Boolean = false,
    navigationModifier: Modifier = Modifier
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val shape = GameMenuCardShape
    val inlineControl = option.inlineControl
    val focusModifier = if (linearNavigation) navigationModifier else modifier
    val performanceEntryFocusRequester = remember { FocusRequester() }
    val performanceSegmentsFocusRequester = remember { FocusRequester() }
    if (linearNavigation && inlineControl is GameMenu.InlineControl.Segmented) {
        PerformanceSegmentedOption(option, inlineControl, onSegmentClick,
            initialFocusRequester, modifier = navigationModifier)
        return
    }
    val emphasizedCrownAction = option.isCrownControl &&
        (option.iconKey == "crown_profiles" || option.iconKey == "crown_layout")
    val isPerformanceEntry = option.focusKey == PERF_ENTRY_FOCUS_KEY
    val showChevronAfterTitle = option.showChevron && inlineControl != null
    val hasDedicatedToggleAction = inlineControl is GameMenu.InlineControl.Toggle &&
        inlineControl.toggleAction != null
    val danger = option.iconKey == "game_menu_disconnect" ||
        option.iconKey == "game_menu_disconnect_and_quit"
    val borderColor = when {
        option.selected -> appAccentColor().copy(alpha = 0.70f)
        option.isCrownControl -> appAccentColor().copy(alpha = 0.55f)
        else -> colorResource(R.color.game_menu_list_item_border)
    }
    val activate = {
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
    val initialFocusModifier = initialFocusRequester?.let {
        Modifier.focusRequester(it).then(focusModifier).then(if (linearNavigation) modifier else Modifier)
    } ?: Modifier
    val rowInteraction = when {
        inlineControl is GameMenu.InlineControl.Toggle &&
            hasDedicatedToggleAction &&
            !inlineControlsFocusable ->
            focusModifier
                .then(initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .gamepadFocusOutline(shape)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = if (inlineControl.checked) ToggleableState.On else ToggleableState.Off
                }
                .gameMenuActivatable(
                    hapticFeedback = hapticFeedback,
                    action = { onInlineToggle(inlineControl) },
                    inputKey = inlineControl
                )
        inlineControl is GameMenu.InlineControl.Toggle && !hasDedicatedToggleAction ->
            initialFocusModifier
                .gamepadFocusOutline(shape)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = if (inlineControl.checked) ToggleableState.On else ToggleableState.Off
                }
                .gameMenuActivatable(hapticFeedback, activate, option)
        inlineControl !is GameMenu.InlineControl.Segmented && option.runnable != null ->
            initialFocusModifier
                .gamepadFocusOutline(shape)
                .gameMenuActivatable(hapticFeedback, activate, option)
        else -> Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(colorResource(R.color.game_menu_list_item_normal))
            .border(GameMenuDimens.surfaceStroke, borderColor, shape)
            .then(if (linearNavigation) Modifier else modifier)
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
                tint = if (emphasizedCrownAction) appAccentColor() else Color.Unspecified,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(GameMenuDimens.section))
        }

        val labelModifier = if (
            inlineControl is GameMenu.InlineControl.Segmented && option.runnable != null
        ) {
            initialFocusModifier
                .then(if (option.focusKey == PERF_ENTRY_FOCUS_KEY) {
                    Modifier.focusRequester(performanceEntryFocusRequester).focusProperties {
                        right = performanceSegmentsFocusRequester
                    }
                } else Modifier)
                .gamepadFocusOutline(GameMenuControlShape)
                .gameMenuActivatable(hapticFeedback, activate, option)
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .weight(
                    when {
                        inlineControl is GameMenu.InlineControl.Segmented && isPerformanceEntry -> 0.58f
                        inlineControl is GameMenu.InlineControl.Segmented -> 0.42f
                        else -> 1f
                    }
                )
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
                    fontWeight = when {
                        emphasizedCrownAction -> FontWeight.Bold
                        option.isCrownControl -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    maxLines = if (linearNavigation) Int.MAX_VALUE else if (emphasizedCrownAction) 2 else 1,
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
                    maxLines = if (linearNavigation) Int.MAX_VALUE else if (option.selected || !option.showChevron) 3 else 1,
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
                if (isPerformanceEntry) {
                    Box(
                        modifier = Modifier
                            .weight(0.42f)
                            .height(36.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        InlineSegmentedControl(
                            segments = inlineControl.segments,
                            onSegmentClick = onSegmentClick,
                            modifier = Modifier.fillMaxSize(),
                            denseLabels = true,
                            initialFocusRequester = performanceSegmentsFocusRequester,
                            leftExitRequester = performanceEntryFocusRequester
                        )
                    }
                } else {
                    InlineSegmentedControl(
                        segments = inlineControl.segments,
                        onSegmentClick = onSegmentClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        denseLabels = option.focusKey == PERF_MODE_FOCUS_KEY,
                        initialFocusRequester = null,
                        leftExitRequester = null
                    )
                }
            }
            null -> if (option.showChevron) MenuChevron()
        }
        if (option.selected) {
            Text(
                text = "✓",
                color = appAccentColor(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = GameMenuDimens.compact)
            )
        }
    }
}

/**
 * Keep controller focus, keyboard confirmation, touch, and semantics on one
 * focus target. Higher-level click/toggle modifiers create delegated targets
 * that can diverge from the node checked by tests and focus restoration.
 */
internal fun Modifier.gameMenuActivatable(
    hapticFeedback: (Int) -> Unit,
    action: () -> Unit,
    inputKey: Any?
): Modifier = semantics(mergeDescendants = true) {
    onClick(label = null) {
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
        true
    }
}.focusable().onKeyEvent { event ->
    val confirmed = event.type == KeyEventType.KeyUp && event.key in setOf(
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Spacebar,
        Key.ButtonA
    )
    if (!confirmed) {
        false
    } else {
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
        true
    }
}.pointerInput(inputKey) {
    detectTapGestures {
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }
}

@Composable
private fun PerformanceSegmentedOption(
    option: GameMenu.MenuOption,
    control: GameMenu.InlineControl.Segmented,
    onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GameMenuCardShape)
            .background(colorResource(R.color.game_menu_list_item_normal))
            .border(GameMenuDimens.surfaceStroke, colorResource(R.color.game_menu_list_item_border), GameMenuCardShape)
            .padding(horizontal = GameMenuDimens.section, vertical = GameMenuDimens.compact)
    ) {
        Text(option.label, color = colorResource(R.color.game_menu_text_primary), fontSize = 13.sp)
        option.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = colorResource(R.color.game_menu_text_secondary), fontSize = 11.sp)
        }
        InlineSegmentedControl(
            segments = control.segments,
            onSegmentClick = onSegmentClick,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            initialFocusRequester = focusRequester,
            verticalNavigationModifier = modifier,
            compactLabels = false
        )
    }
}

@Composable
internal fun PerformancePositionGrid(
    options: List<GameMenu.MenuOption>,
    onOptionClick: (GameMenu.MenuOption) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val accent = appAccentColor()
    val anchorOptions = options.filter { option ->
        PerfOverlayAnchor.entries.any { it.name == option.focusKey }
    }
    val resetOption = options.firstOrNull { it.focusKey == PERF_POSITION_RESET_FOCUS_KEY }
    val selectedIndex = anchorOptions.indexOfFirst { it.selected }
    val initialIndex = if (selectedIndex >= 0) selectedIndex else 4
    val focusRequesters = remember(anchorOptions.size) {
        List(anchorOptions.size) { FocusRequester() }
    }
    val resetFocusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        anchorOptions.chunked(3).forEachIndexed { rowIndex, rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEachIndexed { columnIndex, option ->
                    val index = rowIndex * 3 + columnIndex
                    val selected = option.selected
                    val borderColor = if (selected) accent.copy(alpha = 0.70f) else {
                        colorResource(R.color.game_menu_list_item_border)
                    }
                    val targets = segmentedFocusTargets(
                        itemCount = anchorOptions.size,
                        index = index,
                        columnCount = 3
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .focusRequester(focusRequesters[index])
                            .then(
                                initialFocusRequester.takeIf { index == initialIndex }
                                    ?.let { Modifier.focusRequester(it) }
                                    ?: Modifier
                            )
                            .focusProperties {
                                left = targets.left?.let(focusRequesters::get) ?: FocusRequester.Default
                                right = targets.right?.let(focusRequesters::get) ?: FocusRequester.Default
                                targets.up?.let { up = focusRequesters[it] }
                                targets.down?.let { down = focusRequesters[it] }
                            }
                            .clip(GameMenuControlShape)
                            .background(
                                if (selected) accent.copy(alpha = 0.12f) else {
                                    colorResource(R.color.game_menu_list_item_normal)
                                }
                            )
                            .border(
                                GameMenuDimens.surfaceStroke,
                                borderColor,
                                GameMenuControlShape
                            )
                            .gamepadFocusOutline(GameMenuControlShape)
                            .gameMenuActivatable(
                                hapticFeedback = hapticFeedback,
                                action = { onOptionClick(option) },
                                inputKey = option
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            color = if (selected) accent else {
                                colorResource(R.color.game_menu_text_primary)
                            },
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        resetOption?.let { option ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .focusRequester(resetFocusRequester)
                    .focusProperties { up = focusRequesters[4] }
                    .clip(GameMenuControlShape)
                    .background(colorResource(R.color.game_menu_list_item_normal))
                    .border(
                        GameMenuDimens.surfaceStroke,
                        colorResource(R.color.game_menu_list_item_border),
                        GameMenuControlShape
                    )
                    .gamepadFocusOutline(GameMenuControlShape)
                    .gameMenuActivatable(
                        hapticFeedback = hapticFeedback,
                        action = { onOptionClick(option) },
                        inputKey = option
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    color = colorResource(R.color.game_menu_text_primary),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
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
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun InlineSegmentedControl(
    segments: List<GameMenu.SegmentOption>,
    onSegmentClick: (GameMenu.SegmentOption) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    verticalNavigationModifier: Modifier = Modifier,
    leftExitRequester: FocusRequester? = null,
    compactLabels: Boolean = true,
    denseLabels: Boolean = false
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val accent = appAccentColor()
    val initialIndex = segments.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
    val focusRequesters = remember(segments.size, initialIndex, initialFocusRequester) {
        List(segments.size) { index ->
            initialFocusRequester?.takeIf { index == initialIndex } ?: FocusRequester()
        }
    }
    val usesDenseLabels = denseLabels || segments.size >= 4
    val labelHorizontalPadding = if (usesDenseLabels) 0.dp else 2.dp
    val labelAutoSize = remember(usesDenseLabels) {
        if (usesDenseLabels) {
            TextAutoSize.StepBased(
                minFontSize = 7.5.sp,
                maxFontSize = 10.sp,
                stepSize = 0.5.sp
            )
        } else {
            null
        }
    }
    Row(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, segment ->
            val targets = segmentedFocusTargets(segments.size, index, segments.size)
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
                    .semantics(mergeDescendants = true) {
                        contentDescription = segment.label
                        role = Role.RadioButton
                        selected = segment.selected
                        onClick(label = null) {
                            hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSegmentClick(segment)
                            true
                        }
                    }
                    .focusRequester(focusRequesters[index])
                    .then(verticalNavigationModifier)
                    .segmentedFocusNavigation(targets, focusRequesters, leftExitRequester)
                    .clip(segmentShape)
                    .background(background)
                    .gamepadFocusOutline(segmentShape)
                    .focusable()
                    .onKeyEvent { event ->
                        val confirmed = event.type == KeyEventType.KeyUp && event.key in setOf(
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.NumPadEnter,
                            Key.Spacebar,
                            Key.ButtonA
                        )
                        if (!confirmed) {
                            false
                        } else {
                            hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSegmentClick(segment)
                            true
                        }
                    }
                    .pointerInput(segment) {
                        detectTapGestures {
                            hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSegmentClick(segment)
                        }
                    }
                    .padding(horizontal = labelHorizontalPadding),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = if (compactLabels) compactSegmentLabel(segment.label) else segment.label,
                    style = TextStyle(
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
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = labelAutoSize,
                    modifier = Modifier.testTag("inlineSegmentLabel$index")
                )
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
    focusRequesters: List<FocusRequester>,
    leftExitRequester: FocusRequester? = null
): Modifier = focusProperties {
    left = targets.left?.let(focusRequesters::get) ?: leftExitRequester ?: FocusRequester.Default
    right = targets.right?.let(focusRequesters::get) ?: FocusRequester.Default
    targets.up?.let { up = focusRequesters[it] }
    targets.down?.let { down = focusRequesters[it] }
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
    val accent = appAccentColor()
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
    val accent = appAccentColor()
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
    val accent = appAccentColor()
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
