package com.limelight.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.limelight.R
import com.limelight.ui.theme.AppCornerRadii
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppBackgroundMode
import kotlin.math.min
import com.limelight.utils.appAccentColor

data class AppDisplayOption(
    val id: Int,
    val label: String,
    val enabled: Boolean = true
)

data class AppScreenCombinationOption(
    val value: Int,
    val label: String
)

private data class DisplaySegmentOption(
    val id: Int?,
    val label: String,
    val enabled: Boolean = true
)

private enum class BackgroundModeIcon {
    Artwork,
    Acrylic,
    SoftColor
}

internal const val VIRTUAL_DISPLAY_ID = 212333

private object TopTabPanelShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        with(density) {
            val tabHeight = 18.dp.toPx()
            val tabHalfWidth = 38.dp.toPx()
            val shoulderWidth = 12.dp.toPx()
            val tabCorner = 12.dp.toPx()
            val corner = AppCornerRadii.overlay.toPx().coerceAtMost(size.width / 2f)
            val center = size.width / 2f
            val path = Path().apply {
                moveTo(corner, tabHeight)
                lineTo(center - tabHalfWidth - shoulderWidth, tabHeight)
                cubicTo(
                    center - tabHalfWidth - 5.dp.toPx(), tabHeight,
                    center - tabHalfWidth, tabHeight,
                    center - tabHalfWidth, tabCorner
                )
                quadraticTo(
                    center - tabHalfWidth, 0f,
                    center - tabHalfWidth + tabCorner, 0f
                )
                lineTo(center + tabHalfWidth - tabCorner, 0f)
                quadraticTo(
                    center + tabHalfWidth, 0f,
                    center + tabHalfWidth, tabCorner
                )
                cubicTo(
                    center + tabHalfWidth, tabHeight,
                    center + tabHalfWidth + 5.dp.toPx(), tabHeight,
                    center + tabHalfWidth + shoulderWidth, tabHeight
                )
                lineTo(size.width - corner, tabHeight)
                quadraticTo(size.width, tabHeight, size.width, tabHeight + corner)
                lineTo(size.width, size.height - corner)
                quadraticTo(size.width, size.height, size.width - corner, size.height)
                lineTo(corner, size.height)
                quadraticTo(0f, size.height, 0f, size.height - corner)
                lineTo(0f, tabHeight + corner)
                quadraticTo(0f, tabHeight, corner, tabHeight)
                close()
            }
            return Outline.Generic(path)
        }
    }
}

@Composable
fun AppSettingsPanel(
    isOpen: Boolean,
    backgroundMode: AppBackgroundMode,
    screenCombinationOptions: List<AppScreenCombinationOption>,
    selectedScreenCombinationMode: Int,
    displayOptions: List<AppDisplayOption>,
    selectedDisplayId: Int?,
    onOpenSettings: () -> Unit,
    onBackgroundModeSelected: (AppBackgroundMode) -> Unit,
    onScreenCombinationClick: () -> Unit,
    onScreenCombinationSelected: (Int) -> Unit,
    onDisplaySelected: (Int) -> Unit,
    onClearDisplaySelection: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val availableWidth = (screenWidth - 32).coerceAtLeast(240)
    val preferredWidth = if (isLandscape) {
        (screenWidth * 0.72f).toInt()
    } else if (screenWidth >= 600) {
        (screenWidth * 0.68f).toInt()
    } else {
        availableWidth
    }
    val panelWidth = min(preferredWidth, if (isLandscape) 720 else 600)
        .coerceAtMost(availableWidth)
    val displayColumns = when {
        panelWidth >= 340 -> 3
        panelWidth >= 280 -> 2
        else -> 1
    }
    val displaySegments = listOf(
        DisplaySegmentOption(
            id = null,
            label = stringResource(R.string.appview_display_selection_default)
        )
    ) + displayOptions.map {
        DisplaySegmentOption(
            id = it.id,
            label = if (it.id == VIRTUAL_DISPLAY_ID) {
                stringResource(R.string.appview_display_selection_virtual)
            } else {
                it.label
            },
            enabled = it.enabled
        )
    }
    val displayRows = displaySegments.chunked(displayColumns)
    val selectedScreenCombinationLabel = screenCombinationOptions
        .firstOrNull { it.value == selectedScreenCombinationMode }
        ?.label
        .orEmpty()
    val panelMaxHeight = if (isLandscape) {
        min((screenHeight * 0.84f).toInt(), 520)
    } else {
        min((screenHeight * 0.78f).toInt(), 640)
    }
    val firstItemFocusRequester = remember { FocusRequester() }
    var isHeaderLaidOut by remember { mutableStateOf(false) }
    var isRevealDelayComplete by remember { mutableStateOf(false) }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isDarkTheme = isSystemInDarkTheme()
    val panelSurface = colorResource(R.color.appview_quick_menu_background)
    val colorScheme = if (isDarkTheme) {
        darkColorScheme(
            primary = appAccentColor(),
            onPrimary = colorResource(R.color.ui_shell_text_primary),
            surface = panelSurface,
            surfaceVariant = colorResource(R.color.ui_shell_surface_pressed),
            onSurface = colorResource(R.color.ui_shell_text_primary),
            onSurfaceVariant = colorResource(R.color.ui_shell_text_secondary),
            outline = colorResource(R.color.ui_shell_outline_strong)
        )
    } else {
        lightColorScheme(
            primary = appAccentColor(),
            onPrimary = colorResource(R.color.ui_shell_text_primary),
            surface = panelSurface,
            surfaceVariant = colorResource(R.color.ui_shell_surface_pressed),
            onSurface = colorResource(R.color.ui_shell_text_primary),
            onSurfaceVariant = colorResource(R.color.ui_shell_text_secondary),
            outline = colorResource(R.color.ui_shell_outline)
        )
    }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            isRevealDelayComplete = false
            hasRequestedInitialFocus = false
            // Let the 240 ms reveal finish before scrolling or transferring
            // focus; both operations can trigger extra Compose layout passes.
            delay(260)
            listState.scrollToItem(0)
            isRevealDelayComplete = true
        } else {
            isRevealDelayComplete = false
            hasRequestedInitialFocus = false
            isHeaderLaidOut = false
        }
    }

    LaunchedEffect(isOpen, isRevealDelayComplete, isHeaderLaidOut) {
        if (isOpen && isRevealDelayComplete && isHeaderLaidOut && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = firstItemFocusRequester.requestFocus()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme
    ) {
        Surface(
            modifier = Modifier
                .width(panelWidth.dp)
                .heightIn(max = panelMaxHeight.dp),
            shape = TopTabPanelShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(
                    alpha = MaterialTheme.colorScheme.outline.alpha * 0.55f
                )
            )
        ) {
            LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        top = 26.dp,
                        end = 14.dp,
                        bottom = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                item(key = "panel-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { isHeaderLaidOut = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.appview_quick_settings_title),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                        SettingsShortcut(
                            label = stringResource(R.string.appview_full_settings),
                            modifier = Modifier.focusRequester(firstItemFocusRequester),
                            onClick = onOpenSettings
                        )
                    }
                }
                item(key = "background-title") {
                    SectionTitle(stringResource(R.string.appview_background_mode_title))
                }
                item(key = "background-options") {
                    BackgroundModeSegmentedControl(
                        selectedMode = backgroundMode,
                        onModeSelected = onBackgroundModeSelected
                    )
                }

                item(key = "screen-divider") { PanelDivider() }
                item(key = "screen-header") {
                    SectionNavigationTitle(
                        title = stringResource(R.string.appview_screen_combination_title),
                        value = selectedScreenCombinationLabel,
                        onClick = onScreenCombinationClick
                    )
                }
                item(key = "screen-options") {
                    ScreenCombinationSegmentedControl(
                        options = screenCombinationOptions,
                        selectedMode = selectedScreenCombinationMode,
                        isDarkTheme = isDarkTheme,
                        onModeSelected = onScreenCombinationSelected
                    )
                }

                if (displayOptions.isNotEmpty()) {
                    item(key = "display-divider") { PanelDivider() }
                    item(key = "display-header") {
                        SectionTitle(stringResource(R.string.appview_display_selection_title))
                    }
                    item(key = "display-options") {
                        DisplaySelectionRows(
                            rows = displayRows,
                            columns = displayColumns,
                            selectedDisplayId = selectedDisplayId,
                            isDarkTheme = isDarkTheme,
                            onDisplaySelected = onDisplaySelected,
                            onClearDisplaySelection = onClearDisplaySelection
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplaySelectionRows(
    rows: List<List<DisplaySegmentOption>>,
    columns: Int,
    selectedDisplayId: Int?,
    isDarkTheme: Boolean,
    onDisplaySelected: (Int) -> Unit,
    onClearDisplaySelection: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(AppShapes.medium)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isDarkTheme) 0.06f else 0.035f
                        )
                    )
                    .padding(2.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                row.forEach { option ->
                    SegmentButton(
                        label = option.label,
                        selected = selectedDisplayId == option.id,
                        enabled = option.enabled,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        onClick = {
                            option.id?.let(onDisplaySelected) ?: onClearDisplaySelection()
                        }
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SettingsShortcut(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AppShapes.small
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_A) {
                    false
                } else {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onClick()
                    true
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.phc_settings),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun BackgroundModeSegmentedControl(
    selectedMode: AppBackgroundMode,
    onModeSelected: (AppBackgroundMode) -> Unit
) {
    val options = listOf(
        Triple(
            AppBackgroundMode.Artwork,
            stringResource(R.string.appview_background_mode_artwork),
            BackgroundModeIcon.Artwork
        ),
        Triple(
            AppBackgroundMode.Acrylic,
            stringResource(R.string.appview_background_mode_acrylic),
            BackgroundModeIcon.Acrylic
        ),
        Triple(
            AppBackgroundMode.SoftColor,
            stringResource(R.string.appview_background_mode_soft_color),
            BackgroundModeIcon.SoftColor
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(AppShapes.medium)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isSystemInDarkTheme()) 0.06f else 0.035f
                )
            )
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (mode, label, icon) ->
            SegmentButton(
                label = label,
                selected = mode == selectedMode,
                icon = { tint, modifier -> BackgroundModeIconView(icon, tint, modifier) },
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    icon: @Composable ((Color, Modifier) -> Unit)? = null,
    verticalIcon: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val shape = AppShapes.small
    Box(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = if (verticalIcon) 50.dp else if (maxLines > 1) 42.dp else 36.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        val contentColor = if (!enabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
        } else if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        val labelText: @Composable () -> Unit = {
            Text(
                text = label,
                color = contentColor,
                fontSize = if (verticalIcon) 11.sp else if (maxLines > 1) 12.sp else 13.sp,
                lineHeight = if (verticalIcon) 13.sp else if (maxLines > 1) 16.sp else 17.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (verticalIcon) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                icon?.invoke(contentColor, Modifier.size(17.dp))
                labelText()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                icon?.invoke(contentColor, Modifier.size(16.dp))
                labelText()
            }
        }
    }
}

@Composable
private fun BackgroundModeIconView(
    icon: BackgroundModeIcon,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.35.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val radius = CornerRadius(2.2.dp.toPx())

        fun display(left: Float, top: Float, width: Float, height: Float, color: Color = tint) {
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width * left, size.height * top),
                size = Size(size.width * width, size.height * height),
                cornerRadius = radius,
                style = stroke
            )
        }

        when (icon) {
            BackgroundModeIcon.Artwork -> {
                display(0.08f, 0.12f, 0.84f, 0.76f)
                drawCircle(tint, size.minDimension * 0.08f, Offset(size.width * 0.7f, size.height * 0.34f))
                val landscape = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.74f)
                    lineTo(size.width * 0.4f, size.height * 0.48f)
                    lineTo(size.width * 0.56f, size.height * 0.63f)
                    lineTo(size.width * 0.7f, size.height * 0.52f)
                    lineTo(size.width * 0.84f, size.height * 0.74f)
                }
                drawPath(landscape, tint, style = stroke)
            }
            BackgroundModeIcon.Acrylic -> {
                repeat(3) { index ->
                    drawRoundRect(
                        tint.copy(alpha = 1f - index * 0.22f),
                        topLeft = Offset(size.width * (0.16f + index * 0.12f), size.height * (0.14f + index * 0.12f)),
                        size = Size(size.width * 0.58f, size.height * 0.5f),
                        cornerRadius = radius,
                        style = stroke
                    )
                }
            }
            BackgroundModeIcon.SoftColor -> {
                val drop = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.08f)
                    cubicTo(
                        size.width * 0.44f, size.height * 0.25f,
                        size.width * 0.24f, size.height * 0.47f,
                        size.width * 0.24f, size.height * 0.64f
                    )
                    cubicTo(
                        size.width * 0.24f, size.height * 0.88f,
                        size.width * 0.76f, size.height * 0.88f,
                        size.width * 0.76f, size.height * 0.64f
                    )
                    cubicTo(
                        size.width * 0.76f, size.height * 0.47f,
                        size.width * 0.56f, size.height * 0.25f,
                        size.width * 0.5f, size.height * 0.08f
                    )
                    close()
                }
                drawPath(drop, tint, style = stroke)
            }
        }
    }
}

@Composable
private fun screenModeCompactLabel(option: AppScreenCombinationOption): String =
    screenCombinationModeShortLabelRes(option.value)?.let { stringResource(it) } ?: option.label

/** Compact labels shared by quick settings and title accessibility descriptions. */
@StringRes
internal fun screenCombinationModeShortLabelRes(mode: Int): Int? = when (mode) {
    -1 -> R.string.appview_screen_mode_host_short
    0 -> R.string.appview_screen_mode_noop_short
    1 -> R.string.appview_screen_mode_activate_short
    2 -> R.string.appview_screen_mode_primary_short
    4 -> R.string.appview_screen_mode_secondary_short
    3 -> R.string.appview_screen_mode_exclusive_short
    else -> null
}

@DrawableRes
internal fun screenCombinationModeIconRes(mode: Int): Int? = when (mode) {
    -1 -> R.drawable.ic_screen_mode_host
    0 -> R.drawable.ic_screen_mode_noop
    1 -> R.drawable.ic_screen_mode_activate
    2 -> R.drawable.ic_screen_mode_primary
    4 -> R.drawable.ic_screen_mode_secondary
    3 -> R.drawable.ic_screen_mode_exclusive
    else -> null
}

@Composable
internal fun ScreenCombinationSegmentedControl(
    options: List<AppScreenCombinationOption>,
    selectedMode: Int,
    isDarkTheme: Boolean,
    onModeSelected: (Int) -> Unit
) {
    val rows = options.chunked(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isDarkTheme) 0.06f else 0.035f
                )
            )
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { option ->
                    SegmentButton(
                        label = screenModeCompactLabel(option),
                        selected = selectedMode == option.value,
                        icon = { tint, modifier ->
                            screenCombinationModeIconRes(option.value)?.let { iconRes ->
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = modifier
                                )
                            }
                        },
                        verticalIcon = true,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        onClick = { onModeSelected(option.value) }
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionNavigationTitle(title: String, value: String, onClick: () -> Unit) {
    val pickerContentDescription = stringResource(
        R.string.appview_open_screen_combination_picker
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(AppShapes.small)
                .semantics {
                    contentDescription = pickerContentDescription
                    role = Role.Button
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(18.dp)) {
                val strokeWidth = 1.6.dp.toPx()
                drawLine(
                    color = arrowColor,
                    start = Offset(size.width * 0.36f, size.height * 0.22f),
                    end = Offset(size.width * 0.62f, size.height * 0.5f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = arrowColor,
                    start = Offset(size.width * 0.62f, size.height * 0.5f),
                    end = Offset(size.width * 0.36f, size.height * 0.78f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun PanelDivider() {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        val outline = MaterialTheme.colorScheme.outline
        HorizontalDivider(color = outline.copy(alpha = outline.alpha * 0.28f))
    }
}
