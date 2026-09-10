package com.limelight.preferences

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.ui.theme.AppShapes

internal data class Preset(val width: Int, val height: Int, val labelRes: Int)

internal val PRESETS = listOf(
    Preset(3840, 2160, R.string.custom_resolution_tag_4k),
    Preset(3440, 1440, R.string.custom_resolution_tag_ultrawide),
    Preset(2560, 1440, R.string.custom_resolution_tag_2k),
    Preset(1920, 1080, R.string.custom_resolution_tag_1080p)
)

@Composable
internal fun ResolutionInputError.text(): String = stringResource(
    when (reason) {
        ResolutionInputReason.EMPTY ->
            if (field == ResolutionField.WIDTH) {
                R.string.custom_resolution_error_empty_width
            } else {
                R.string.custom_resolution_error_empty_height
            }
        ResolutionInputReason.OUT_OF_RANGE ->
            if (field == ResolutionField.WIDTH) {
                R.string.custom_resolution_error_range_width
            } else {
                R.string.custom_resolution_error_range_height
            }
        ResolutionInputReason.ODD ->
            if (field == ResolutionField.WIDTH) {
                R.string.custom_resolution_error_even_width
            } else {
                R.string.custom_resolution_error_even_height
            }
        ResolutionInputReason.DUPLICATE -> R.string.resolution_already_exists
    }
)

private fun Modifier.handleGamepadConfirm(onConfirm: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.keyCode != KeyEvent.KEYCODE_BUTTON_A) {
            false
        } else {
            if (nativeEvent.action == KeyEvent.ACTION_UP) onConfirm()
            true
        }
    }

/** 焦点指示仅在手柄/键盘导航（非触摸模式）下显示，避免触摸打开时出现焦点框。 */
@Composable
private fun focusIndicationVisible(focused: Boolean): Boolean {
    val view = LocalView.current
    var touchMode by remember(view) { mutableStateOf(view.isInTouchMode) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnTouchModeChangeListener { touchMode = it }
        observer.addOnTouchModeChangeListener(listener)
        onDispose { observer.removeOnTouchModeChangeListener(listener) }
    }
    return focused && !touchMode
}

/**
 * 焦点/激活态高亮:粉色软底 + 1.5dp 强调描边。
 * 非高亮时用各组件自己的 fallback(如静态描边)。ResolutionRow 因带颜色动画未使用此封装。
 */
@Composable
private fun focusHighlight(highlighted: Boolean, shape: Shape, fallback: Modifier = Modifier): Modifier =
    if (highlighted) {
        Modifier
            .background(colorResource(R.color.app_dialog_accent_soft), shape)
            .border(1.5.dp, colorResource(R.color.app_dialog_accent_color), shape)
    } else {
        fallback
    }

@Composable
internal fun DialogHeader(infoExpanded: Boolean, onToggleInfo: () -> Unit) {
    val accent = colorResource(R.color.app_dialog_accent_color)
    val accentSoft = colorResource(R.color.app_dialog_accent_soft)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(AppShapes.medium)
                .background(accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_resolution_cute),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = stringResource(R.string.title_custom_resolutions),
            modifier = Modifier.weight(1f),
            color = colorResource(R.color.app_dialog_text_primary),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        InfoToggleButton(
            expanded = infoExpanded,
            onToggle = onToggleInfo
        )
    }
}

@Composable
private fun InfoToggleButton(expanded: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(focusHighlight(showFocus || expanded, CircleShape))
            .handleGamepadConfirm(onToggle)
            .clickable(onClick = onToggle)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_close_stylish else R.drawable.ic_info),
            contentDescription = stringResource(R.string.custom_resolution_info_contentdesc),
            tint = accent,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
internal fun InfoTooltip() {
    val accent = colorResource(R.color.app_dialog_accent_color)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(colorResource(R.color.app_dialog_accent_soft))
            .border(1.dp, colorResource(R.color.app_dialog_outline), AppShapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = stringResource(R.string.custom_resolution_info_tooltip),
            color = colorResource(R.color.app_dialog_text_secondary),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
internal fun Composer(
    widthText: String,
    onWidthChange: (String) -> Unit,
    heightText: String,
    onHeightChange: (String) -> Unit,
    error: ResolutionInputError?,
    pixelsText: (Int) -> String,
    widthFocus: FocusRequester,
    heightFocus: FocusRequester,
    addFocus: FocusRequester,
    downFromHeight: FocusRequester,
    leftTarget: FocusRequester?,
    onAdd: () -> Unit,
    onHeightDone: () -> Unit
) {
    val accent = colorResource(R.color.app_dialog_accent_color)
    val accentSoft = colorResource(R.color.app_dialog_accent_soft)
    val secondary = colorResource(R.color.app_dialog_text_secondary)
    val danger = colorResource(R.color.app_action_sheet_danger)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.large)
            .background(colorResource(R.color.app_dialog_surface_elevated))
            .border(1.dp, colorResource(R.color.app_dialog_outline), AppShapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            NumberField(
                label = stringResource(R.string.custom_resolution_field_width),
                value = widthText,
                onValueChange = onWidthChange,
                placeholder = stringResource(R.string.width_hint),
                invalid = error?.field == ResolutionField.WIDTH,
                imeAction = ImeAction.Next,
                onImeAction = { heightFocus.requestFocus() },
                focus = widthFocus,
                downTarget = heightFocus,
                upTarget = widthFocus,
                leftTarget = leftTarget,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .padding(bottom = 7.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "×", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            NumberField(
                label = stringResource(R.string.custom_resolution_field_height),
                value = heightText,
                onValueChange = onHeightChange,
                placeholder = stringResource(R.string.height_hint),
                invalid = error?.field == ResolutionField.HEIGHT,
                imeAction = ImeAction.Done,
                onImeAction = onHeightDone,
                focus = heightFocus,
                downTarget = downFromHeight,
                upTarget = widthFocus,
                leftTarget = leftTarget,
                modifier = Modifier.weight(1f)
            )
        }

        val width = widthText.toIntOrNull()
        val height = heightText.toIntOrNull()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val readout: @Composable () -> Unit = when {
                    error != null -> {
                        {
                            Text(
                                text = error.text(),
                                color = danger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    width != null && height != null && width > 0 && height > 0 -> {
                        {
                            val pixels = pixelsText(width * height)
                            Text(
                                text = "≈ ${ratioText(width, height)}",
                                color = accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = " · $pixels",
                                color = secondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    else -> {
                        {
                            Text(
                                text = stringResource(R.string.custom_resolution_readout_hint),
                                color = secondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { readout() }
            }
            AddButton(onAdd = onAdd, focus = addFocus, upTarget = heightFocus)
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    invalid: Boolean,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    focus: FocusRequester,
    downTarget: FocusRequester,
    upTarget: FocusRequester,
    leftTarget: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline = colorResource(R.color.app_dialog_outline)
    val accent = colorResource(R.color.app_dialog_accent_color)
    val danger = colorResource(R.color.app_action_sheet_danger)
    val borderSpec = when {
        invalid -> 1.5.dp to danger
        focused -> 1.5.dp to accent
        else -> 1.dp to outline
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = colorResource(R.color.app_dialog_text_secondary),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colorResource(R.color.app_dialog_text_primary),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            ),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() }
            ),
            interactionSource = interaction,
            modifier = Modifier
                .focusRequester(focus)
                .focusProperties {
                    down = downTarget
                    up = upTarget
                    left = leftTarget ?: FocusRequester.Default
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(AppShapes.small)
                        .background(colorResource(R.color.app_dialog_surface))
                        .border(borderSpec.first, borderSpec.second, AppShapes.small)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colorResource(R.color.app_dialog_text_disabled),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun AddButton(onAdd: () -> Unit, focus: FocusRequester, upTarget: FocusRequester) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val ink = colorResource(R.color.add_pc_on_accent)
    val gradient = Brush.linearGradient(
        listOf(
            colorResource(R.color.theme_pink_gradient_start),
            colorResource(R.color.theme_pink_gradient_center),
            colorResource(R.color.theme_pink_gradient_end)
        )
    )
    Row(
        modifier = Modifier
            .focusRequester(focus)
            .focusProperties { up = upTarget }
            .clip(CircleShape)
            .background(gradient)
            .then(
                if (showFocus) {
                    Modifier.border(2.dp, colorResource(R.color.theme_blue_primary), CircleShape)
                } else {
                    Modifier.border(1.dp, colorResource(R.color.theme_pink_dark), CircleShape)
                }
            )
            .handleGamepadConfirm(onAdd)
            .clickable(onClick = onAdd)
            .focusable(interactionSource = interaction)
            .padding(start = 12.dp, end = 15.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.custom_resolution_add_short),
            color = ink,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun PresetRow(onPreset: (Preset) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESETS.forEach { preset ->
            PresetChip(preset = preset, onClick = { onPreset(preset) })
        }
    }
}

@Composable
private fun PresetChip(preset: Preset, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val outline = colorResource(R.color.app_dialog_outline)
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                focusHighlight(
                    highlighted = showFocus,
                    shape = CircleShape,
                    fallback = Modifier.border(1.dp, outline, CircleShape)
                )
            )
            .handleGamepadConfirm(onClick)
            .clickable(onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(preset.labelRes),
            color = if (showFocus) accent else colorResource(R.color.app_dialog_text_secondary),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 等比小图:按真实宽高比画一个圆角矩形,16:9 是普通横条、3440×1440 一眼是带鱼屏。 */
@Composable
private fun RatioGlyph(width: Int, height: Int, modifier: Modifier = Modifier) {
    val (glyphWidth, glyphHeight) = ratioGlyphSize(width, height)
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(modifier = modifier.size(width = 38.dp, height = 28.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = glyphWidth.dp, height = glyphHeight.dp)
                .background(colorResource(R.color.app_dialog_accent_soft), AppShapes.extraSmall)
                .border(1.5.dp, accent, AppShapes.extraSmall)
        )
    }
}

@Composable
internal fun ResolutionList(
    resolutions: List<Resolution>,
    justAdded: Resolution?,
    compact: Boolean,
    pixelsText: (Int) -> String,
    focusFor: (Resolution) -> FocusRequester,
    rightNeighbor: FocusRequester?,
    onDelete: (Resolution) -> Unit,
    modifier: Modifier = Modifier
) {
    if (resolutions.isEmpty()) {
        EmptyState(modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(resolutions, key = { "${it.width}x${it.height}" }) { resolution ->
            ResolutionRow(
                resolution = resolution,
                isJustAdded = justAdded == resolution,
                compact = compact,
                pixelsText = pixelsText,
                focusRequester = focusFor(resolution),
                rightNeighbor = rightNeighbor,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun ResolutionRow(
    resolution: Resolution,
    isJustAdded: Boolean,
    compact: Boolean,
    pixelsText: (Int) -> String,
    focusRequester: FocusRequester,
    rightNeighbor: FocusRequester?,
    onDelete: (Resolution) -> Unit
) {
    val rowShape = AppShapes.medium
    val outline = colorResource(R.color.app_dialog_outline)
    val accent = colorResource(R.color.app_dialog_accent_color)
    val deleteFocus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)

    val targetBg = when {
        showFocus || isJustAdded -> colorResource(R.color.app_dialog_accent_soft)
        else -> colorResource(R.color.app_dialog_surface_elevated)
    }
    val bg by animateColorAsState(targetValue = targetBg, label = "rowBg")
    val targetBorder = if (showFocus || isJustAdded) accent else outline
    val borderColor by animateColorAsState(targetValue = targetBorder, label = "rowBorder")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { right = deleteFocus }
            .clip(rowShape)
            .background(bg)
            .border(if (showFocus || isJustAdded) 1.5.dp else 1.dp, borderColor, rowShape)
            .handleGamepadConfirm { deleteFocus.requestFocus() }
            .clickable { deleteFocus.requestFocus() }
            .focusable(interactionSource = interaction)
            .padding(
                start = if (compact) 9.dp else 11.dp,
                end = if (compact) 5.dp else 7.dp,
                top = if (compact) 4.dp else 7.dp,
                bottom = if (compact) 4.dp else 7.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        RatioGlyph(width = resolution.width, height = resolution.height)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${resolution.width}×${resolution.height}",
                color = colorResource(R.color.app_dialog_text_primary),
                fontSize = if (compact) 13.5.sp else 15.5.sp,
                fontWeight = FontWeight.Bold
            )
            if (!compact) {
                val pixels = pixelsText(resolution.width * resolution.height)
                Text(
                    text = "${ratioText(resolution.width, resolution.height)} · $pixels",
                    color = colorResource(R.color.app_dialog_text_secondary),
                    fontSize = 11.sp
                )
            }
        }
        resolutionTag(resolution.width, resolution.height)?.let { tagRes ->
            Box(
                modifier = Modifier
                    .clip(AppShapes.extraSmall)
                    .background(colorResource(R.color.app_dialog_accent_soft))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = stringResource(tagRes),
                    color = accent,
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        DeleteButton(
            resolution = resolution,
            focus = deleteFocus,
            compact = compact,
            rightNeighbor = rightNeighbor,
            onDelete = onDelete
        )
    }
}

@Composable
private fun DeleteButton(
    resolution: Resolution,
    focus: FocusRequester,
    compact: Boolean,
    rightNeighbor: FocusRequester?,
    onDelete: (Resolution) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(
        modifier = Modifier
            .focusRequester(focus)
            .focusProperties { right = rightNeighbor ?: FocusRequester.Default }
            .size(if (compact) 30.dp else 34.dp)
            .clip(CircleShape)
            .then(focusHighlight(showFocus, CircleShape))
            .handleGamepadConfirm { onDelete(resolution) }
            .clickable { onDelete(resolution) }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(
                R.string.custom_resolution_delete_contentdesc,
                resolution.width,
                resolution.height
            ),
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val accent = colorResource(R.color.app_dialog_accent_color)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.app_dialog_accent_soft)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_resolution_cute),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp)
            )
        }
        Text(
            text = stringResource(R.string.custom_resolution_empty_title),
            color = colorResource(R.color.app_dialog_text_primary),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.custom_resolution_empty_hint),
            color = colorResource(R.color.app_dialog_text_secondary),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun FooterRow(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FooterButton(
            label = stringResource(R.string.game_menu_cancel),
            onClick = onCancel
        )
        Spacer(Modifier.width(4.dp))
        FooterButton(
            label = stringResource(R.string.game_menu_ok),
            onClick = onConfirm
        )
    }
}

@Composable
private fun FooterButton(
    label: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(
        modifier = Modifier
            .clip(AppShapes.small)
            .then(focusHighlight(showFocus, AppShapes.small))
            .handleGamepadConfirm(onClick)
            .clickable(onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
