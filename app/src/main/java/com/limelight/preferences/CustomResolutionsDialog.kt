package com.limelight.preferences

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppDialogStyler
import kotlin.math.roundToLong

/**
 * Compose implementation of the custom resolutions dialog.
 *
 * Replaces CustomResolutionsPreferenceDialogFragment + custom_resolutions_form.xml.
 * Storage contract is unchanged: a StringSet of "WxH" values in the
 * custom_resolutions SharedPreferences file, read by StreamSettings and GameMenu.
 */
object CustomResolutionsDialog {

    fun show(context: Context, onClosed: () -> Unit): Dialog {
        val storage = context.getSharedPreferences(
            CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE,
            Context.MODE_PRIVATE
        )
        val dialog = ComponentDialog(context, R.style.AppComposeDialogStyle)
        val composeView = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CustomResolutionsDialogContent(
                    initial = loadResolutions(storage),
                    onCommit = { list ->
                        storage.edit()
                            .putStringSet(
                                CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY,
                                list.map { "${it.width}x${it.height}" }.toSet()
                            )
                            .apply()
                    },
                    onClose = dialog::cancel
                )
            }
        }
        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.setCanceledOnTouchOutside(true)
        AppDialogStyler.installDismissKeys(dialog)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener { onClosed() }
        dialog.show()
        applyDialogWidth(dialog, context)
        return dialog
    }

    private fun loadResolutions(storage: android.content.SharedPreferences): List<ResolutionValidator.Resolution> {
        val stored = storage.getStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, null).orEmpty()
        return stored
            .mapNotNull { ResolutionValidator.parseResolution(it) }
            .sortedWith(compareBy({ it.width }, { it.height }))
    }

    private fun applyDialogWidth(dialog: Dialog, context: Context) {
        val configuration = context.resources.configuration
        val density = context.resources.displayMetrics.density
        val maxWidthDp = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 720 else 440
        val maxWidthPx = (maxWidthDp * density).toInt()
        val availablePx = ((configuration.screenWidthDp - 24) * density).toInt()
        dialog.window?.setLayout(
            minOf(maxWidthPx, availablePx).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}

private data class Preset(val width: Int, val height: Int, @StringRes val label: Int)

private val PRESETS = listOf(
    Preset(3840, 2160, R.string.custom_resolution_tag_4k),
    Preset(3440, 1440, R.string.custom_resolution_tag_ultrawide),
    Preset(2560, 1440, R.string.custom_resolution_tag_2k),
    Preset(1920, 1080, R.string.custom_resolution_tag_1080p)
)

@StringRes
private fun tagFor(width: Int, height: Int): Int? = when {
    height > width -> R.string.custom_resolution_tag_portrait
    width >= 7680 -> R.string.custom_resolution_tag_8k
    width >= 3840 || height >= 2160 -> R.string.custom_resolution_tag_4k
    width.toFloat() / height >= 2.2f -> R.string.custom_resolution_tag_ultrawide
    height >= 1440 -> R.string.custom_resolution_tag_2k
    height >= 1080 -> R.string.custom_resolution_tag_1080p
    else -> null
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** "16:9"; ratios that don't simplify cleanly (3440×1440 → 43:18) are shown relative to 9. */
private fun ratioText(width: Int, height: Int): String {
    val g = gcd(width, height)
    val a = width / g
    val b = height / g
    if (a > 40 || b > 40) {
        val scaled = ((width * 9f / height) * 10).roundToLong().toInt()
        if (scaled % 10 == 0) {
            return "${scaled / 10}:9"
        }
        return String.format("%.1f:9", scaled / 10f)
    }
    return "$a:$b"
}

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

@Composable
private fun CustomResolutionsDialogContent(
    initial: List<ResolutionValidator.Resolution>,
    onCommit: (List<ResolutionValidator.Resolution>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var resolutions by remember { mutableStateOf(initial) }
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var errorField by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var justAdded by remember { mutableStateOf<ResolutionValidator.Resolution?>(null) }
    var infoExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val twoPane = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp <= 480

    val widthFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }

    fun keyOf(r: ResolutionValidator.Resolution) = "${r.width}x${r.height}"
    val rowFocus = remember(resolutions) {
        resolutions.associate { keyOf(it) to FocusRequester() }
    }
    val firstRowFocus = rowFocus[resolutions.firstOrNull()?.let(::keyOf)]

    // 删除行后把焦点还给相邻行；行节点在重组后才存在，经 pending key 延迟请求
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingRowFocusKey, resolutions) {
        val key = pendingRowFocusKey ?: return@LaunchedEffect
        rowFocus[key]?.requestFocus()
        pendingRowFocusKey = null
    }

    LaunchedEffect(justAdded) {
        if (justAdded != null) {
            kotlinx.coroutines.delay(900)
            justAdded = null
        }
    }

    // 触摸打开时不抢焦点，避免一打开就弹出输入法
    val hostView = LocalView.current
    LaunchedEffect(Unit) {
        if (!hostView.isInTouchMode) {
            widthFocus.requestFocus()
        }
    }

    fun commit(list: List<ResolutionValidator.Resolution>) {
        resolutions = list
        onCommit(list)
    }

    fun addResolution() {
        val width = widthText.trim().toIntOrNull()
        val height = heightText.trim().toIntOrNull()
        val error: Pair<Int, String>? = when {
            width == null -> 0 to context.getString(R.string.custom_resolution_error_empty_width)
            height == null -> 1 to context.getString(R.string.custom_resolution_error_empty_height)
            !ResolutionValidator.isValidWidth(width) ->
                0 to context.getString(R.string.custom_resolution_error_range_width)
            !ResolutionValidator.isValidHeight(height) ->
                1 to context.getString(R.string.custom_resolution_error_range_height)
            !ResolutionValidator.isEven(width) ->
                0 to context.getString(R.string.custom_resolution_error_even_width)
            !ResolutionValidator.isEven(height) ->
                1 to context.getString(R.string.custom_resolution_error_even_height)
            resolutions.any { it.width == width && it.height == height } ->
                1 to context.getString(R.string.resolution_already_exists)
            else -> null
        }
        if (error != null) {
            errorField = error.first
            errorMessage = error.second
            return
        }
        val resolution = ResolutionValidator.Resolution(width!!, height!!)
        commit((resolutions + resolution).sortedWith(compareBy({ it.width }, { it.height })))
        justAdded = resolution
        widthText = ""
        heightText = ""
        errorField = null
        errorMessage = null
        if (!hostView.isInTouchMode) widthFocus.requestFocus()
    }

    fun deleteResolution(resolution: ResolutionValidator.Resolution) {
        val index = resolutions.indexOf(resolution)
        val remaining = resolutions - resolution
        commit(remaining)
        justAdded = null
        if (!hostView.isInTouchMode) {
            if (remaining.isEmpty()) {
                widthFocus.requestFocus()
            } else {
                pendingRowFocusKey = keyOf(remaining[index.coerceAtMost(remaining.lastIndex)])
            }
        }
    }

    val surfaceShape = AppShapes.overlay
    val outline = colorResource(R.color.app_dialog_outline)
    val gradient = Brush.linearGradient(
        listOf(
            colorResource(R.color.app_dialog_surface_gradient_start),
            colorResource(R.color.app_dialog_surface_gradient_center),
            colorResource(R.color.app_dialog_surface_gradient_end)
        )
    )
    val maxSurfaceHeight = (configuration.screenHeightDp * 0.86f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxSurfaceHeight)
            .clip(surfaceShape)
            .background(gradient)
            .border(1.dp, outline, surfaceShape)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogHeader(
                infoExpanded = infoExpanded,
                onToggleInfo = { infoExpanded = !infoExpanded }
            )
            AnimatedVisibility(
                visible = infoExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                InfoTooltip()
            }

            val composer: @Composable () -> Unit = {
                val leftTarget = if (twoPane) firstRowFocus else null
                Composer(
                    widthText = widthText,
                    onWidthChange = {
                        widthText = it.filter(Char::isDigit).take(5)
                        errorField = null
                        errorMessage = null
                    },
                    heightText = heightText,
                    onHeightChange = {
                        heightText = it.filter(Char::isDigit).take(5)
                        errorField = null
                        errorMessage = null
                    },
                    errorField = errorField,
                    errorMessage = errorMessage,
                    widthFocus = widthFocus,
                    heightFocus = heightFocus,
                    addFocus = addFocus,
                    downFromHeight = firstRowFocus ?: addFocus,
                    leftTarget = leftTarget,
                    onAdd = ::addResolution,
                    onHeightDone = ::addResolution
                )
            }

            if (twoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (configuration.screenHeightDp * 0.6f).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(0.85f)) {
                        ResolutionList(
                            resolutions = resolutions,
                            justAdded = justAdded,
                            compact = true,
                            focusFor = { rowFocus[keyOf(it)] ?: FocusRequester() },
                            rightNeighbor = widthFocus,
                            onDelete = ::deleteResolution
                        )
                    }
                    Box(modifier = Modifier.weight(1.15f)) { composer() }
                }
            } else {
                composer()
                PresetRow(
                    onPreset = { preset ->
                        widthText = preset.width.toString()
                        heightText = preset.height.toString()
                        errorField = null
                        errorMessage = null
                    }
                )
                ResolutionList(
                    resolutions = resolutions,
                    justAdded = justAdded,
                    compact = false,
                    focusFor = { rowFocus[keyOf(it)] ?: FocusRequester() },
                    rightNeighbor = null,
                    onDelete = ::deleteResolution,
                    modifier = Modifier.heightIn(max = (configuration.screenHeightDp * 0.42f).dp)
                )
            }

            FooterRow(
                onCancel = onClose,
                onConfirm = onClose
            )
        }
    }
}

@Composable
private fun DialogHeader(infoExpanded: Boolean, onToggleInfo: () -> Unit) {
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
            .then(
                if (showFocus || expanded) {
                    Modifier
                        .background(colorResource(R.color.app_dialog_accent_soft))
                        .border(1.5.dp, accent, CircleShape)
                } else {
                    Modifier
                }
            )
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
private fun InfoTooltip() {
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
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
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
private fun Composer(
    widthText: String,
    onWidthChange: (String) -> Unit,
    heightText: String,
    onHeightChange: (String) -> Unit,
    errorField: Int?,
    errorMessage: String?,
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
                invalid = errorField == 0,
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
                invalid = errorField == 1,
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
                    errorMessage != null -> {
                        {
                            Text(
                                text = errorMessage!!,
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
                            val pixels = stringResource(
                                R.string.custom_resolution_pixels_format,
                                width * height / 1_000_000f
                            )
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
private fun PresetRow(onPreset: (Preset) -> Unit) {
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
                if (showFocus) {
                    Modifier
                        .background(colorResource(R.color.app_dialog_accent_soft))
                        .border(1.5.dp, accent, CircleShape)
                } else {
                    Modifier.border(1.dp, outline, CircleShape)
                }
            )
            .handleGamepadConfirm(onClick)
            .clickable(onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(preset.label),
            color = if (showFocus) accent else colorResource(R.color.app_dialog_text_secondary),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 等比小图：按真实宽高比画一个圆角矩形，16:9 是普通横条、3440×1440 一眼是带鱼屏。 */
@Composable
private fun RatioGlyph(width: Int, height: Int, modifier: Modifier = Modifier) {
    val ratio = width.toFloat() / height
    val boxRatio = 36f / 24f
    val glyphWidth: Float
    val glyphHeight: Float
    if (ratio > boxRatio) {
        glyphWidth = 36f
        glyphHeight = 36f / ratio
    } else {
        glyphHeight = 24f
        glyphWidth = 24f * ratio
    }
    val minSide = minOf(glyphWidth, glyphHeight)
    val scale = if (minSide < 6f) 6f / minSide else 1f
    val accent = colorResource(R.color.app_dialog_accent_color)
    Box(modifier = modifier.size(width = 38.dp, height = 28.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = (glyphWidth * scale).dp, height = (glyphHeight * scale).dp)
                .background(colorResource(R.color.app_dialog_accent_soft), AppShapes.extraSmall)
                .border(1.5.dp, accent, AppShapes.extraSmall)
        )
    }
}

@Composable
private fun ResolutionList(
    resolutions: List<ResolutionValidator.Resolution>,
    justAdded: ResolutionValidator.Resolution?,
    compact: Boolean,
    focusFor: (ResolutionValidator.Resolution) -> FocusRequester,
    rightNeighbor: FocusRequester?,
    onDelete: (ResolutionValidator.Resolution) -> Unit,
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
                focusRequester = focusFor(resolution),
                rightNeighbor = rightNeighbor,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun ResolutionRow(
    resolution: ResolutionValidator.Resolution,
    isJustAdded: Boolean,
    compact: Boolean,
    focusRequester: FocusRequester,
    rightNeighbor: FocusRequester?,
    onDelete: (ResolutionValidator.Resolution) -> Unit
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
                val pixels = stringResource(
                    R.string.custom_resolution_pixels_format,
                    resolution.width * resolution.height / 1_000_000f
                )
                Text(
                    text = "${ratioText(resolution.width, resolution.height)} · $pixels",
                    color = colorResource(R.color.app_dialog_text_secondary),
                    fontSize = 11.sp
                )
            }
        }
        tagFor(resolution.width, resolution.height)?.let { tagRes ->
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
    resolution: ResolutionValidator.Resolution,
    focus: FocusRequester,
    compact: Boolean,
    rightNeighbor: FocusRequester?,
    onDelete: (ResolutionValidator.Resolution) -> Unit
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
            .then(
                if (showFocus) {
                    Modifier
                        .background(colorResource(R.color.app_dialog_accent_soft))
                        .border(1.5.dp, accent, CircleShape)
                } else {
                    Modifier
                }
            )
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun FooterRow(
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
            .then(
                if (showFocus) {
                    Modifier
                        .background(colorResource(R.color.app_dialog_accent_soft))
                        .border(1.5.dp, accent, AppShapes.small)
                } else {
                    Modifier
                }
            )
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
