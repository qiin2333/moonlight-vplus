package com.limelight.preferences

import android.app.Dialog
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayCutout
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.ui.theme.AppShapes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compose implementation of the custom resolutions dialog.
 *
 * 窗口与持久化在此;UI 组件见 CustomResolutionsDialogUi.kt,纯逻辑见
 * ResolutionFormat.kt 与 CustomResolutionsStore.kt。
 */
internal interface CustomDialogController {
    fun dispatchAxes(event: MotionEvent): Boolean
}

internal var activeCustomDialogController: CustomDialogController? = null

object CustomResolutionsDialog {

    fun show(
        context: Context,
        onClosed: () -> Unit,
        requestInitialFocus: Boolean = false
    ): Dialog {
        return show(
            context,
            CustomResolutionsStore.load(context),
            { CustomResolutionsStore.save(context, it) },
            onClosed,
            requestInitialFocus = requestInitialFocus
        )
    }

    fun show(
        context: Context,
        initial: List<Resolution>,
        onCommit: (List<Resolution>) -> Unit,
        onClosed: () -> Unit,
        frameRateMode: Boolean = false,
        requestInitialFocus: Boolean = false
    ): Dialog {
        val pixelsText = pixelsTextFactory(context)
        val dialog = ComponentDialog(context, R.style.AppComposeDialogStyle)
        // 取消(返回/点外部/取消按钮)丢弃本次会话的全部改动,恢复进入时的快照
        var cancelled = false
        var current = initial
        val controllerFocusRequest = mutableIntStateOf(if (requestInitialFocus) 1 else 0)
        val touchNavigationActive = mutableStateOf(!requestInitialFocus)
        val axisNavigation = MenuAxisNavigationState()
        fun discardChanges() {
            cancelled = true
            dialog.cancel()
        }
        val composeView = ComposeView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CustomResolutionsDialogContent(
                    initial = initial,
                    pixelsText = pixelsText,
                    frameRateMode = frameRateMode,
                    nativePresets = if (frameRateMode) {
                        nativeFrameRatePresets(context).map { Resolution(it, 0) }
                    } else {
                        nativeResolutionPresets(context)
                    },
                    requestInitialFocus = requestInitialFocus,
                    controllerFocusRequest = controllerFocusRequest.intValue,
                    touchNavigationActive = touchNavigationActive.value,
                    onTouchNavigation = { touchNavigationActive.value = true },
                    onCommit = { current = it },
                    onCancel = { discardChanges() },
                    onConfirm = {
                        dialog.dismiss()
                    }
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
        fun handleDialogKey(keyCode: Int, event: KeyEvent): Boolean {
            val controllerSource = event.source and (
                InputDevice.SOURCE_GAMEPAD or
                    InputDevice.SOURCE_JOYSTICK or
                    InputDevice.SOURCE_CLASS_JOYSTICK or
                    InputDevice.SOURCE_DPAD or
                    InputDevice.SOURCE_KEYBOARD
                ) != 0
            if (controllerSource && event.action == KeyEvent.ACTION_DOWN &&
                touchNavigationActive.value
            ) {
                touchNavigationActive.value = false
                controllerFocusRequest.intValue++
            }
            return com.limelight.ui.UiDismissKeyHandler.handle(
                event.action,
                keyCode,
                onDismiss = ::discardChanges,
                dismissOnBack = false
            )
        }
        fun dispatchAxisPairs(axisPairs: List<Pair<Float, Float>>): Boolean {
            val transition = axisNavigation.update(axisPairs)
            if (transition.changed && transition.pressedKeyCode != null &&
                touchNavigationActive.value
            ) {
                touchNavigationActive.value = false
                controllerFocusRequest.intValue++
            }
            return false
        }
        val dialogController = object : CustomDialogController {
            override fun dispatchAxes(event: MotionEvent): Boolean {
                if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
                val axisPairs = (context as? Game)?.controllerHandler
                    ?.getGameMenuNavigationAxisPairs(event)
                    ?: defaultDialogAxisPairs(event)
                return dispatchAxisPairs(axisPairs)
            }
        }
        activeCustomDialogController = dialogController
        dialog.window?.decorView?.setOnGenericMotionListener { _, event ->
            dialogController.dispatchAxes(event)
        }
        dialog.setOnKeyListener { _, keyCode, event -> handleDialogKey(keyCode, event) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val hostWindow = (context as? Activity)?.window
            if (hostWindow != null) {
                decorView.systemUiVisibility = hostWindow.decorView.systemUiVisibility
                if (hostWindow.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
                    addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
            }
        }
        dialog.setOnCancelListener { cancelled = true }
        dialog.setOnDismissListener {
            if (activeCustomDialogController === dialogController) {
                activeCustomDialogController = null
            }
            if (cancelled) onCommit(initial)
            else onCommit(current)
            onClosed()
        }
        dialog.setOnShowListener {
            composeView.doOnLayout {
                if (dialog.isShowing) composeView.requestFocus()
            }
        }
        dialog.show()
        applyDialogWidth(dialog, context)
        return dialog
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

    /**
     * 像素总数的本地化文案:中文模板以「万」为单位(传 像素数/10000),
     * 其他语言以 MP 为单位(传 像素数/1000000)。
     *
     * 单位判断必须在 Composable 之外完成:LocalConfiguration.locales 与资源解析
     * 使用的配置可能不同步,而读 resources.configuration 又会触发 Compose lint。
     * 这里模板与进位取自同一个 context 配置,天然一致。
     */
    internal fun nativeResolutionPresets(context: Context): List<Resolution> {
        val display = targetDisplay(context) ?: return emptyList()
        val presets = linkedSetOf<Resolution>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cutout = displayCutout(display, context)
            if (cutout != null) {
                val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop
                if (widthInsets != 0 || heightInsets != 0) {
                    val metrics = DisplayMetrics()
                    display.getRealMetrics(metrics)
                    val width = max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                    val height = min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                    addResolutionOrientations(presets, width, height)
                }
            }
            val television = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            for (mode in display.supportedModes) {
                val width = max(mode.physicalWidth, mode.physicalHeight)
                val height = min(mode.physicalWidth, mode.physicalHeight)
                if (!television || width > 3840 || height > 2160) {
                    addResolutionOrientations(presets, width, height)
                }
            }
        }
        if (presets.isEmpty()) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val width = metrics.widthPixels.takeIf { it > 0 }
                ?: context.resources.displayMetrics.widthPixels
            val height = metrics.heightPixels.takeIf { it > 0 }
                ?: context.resources.displayMetrics.heightPixels
            addResolutionOrientations(presets, max(width, height), min(width, height))
        }
        return presets.toList()
    }

    internal fun nativeFrameRatePresets(context: Context): List<Int> {
        val display = targetDisplay(context) ?: return emptyList()
        var maxRate = display.refreshRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (mode in display.supportedModes) {
                if (mode.refreshRate > maxRate) maxRate = mode.refreshRate
            }
        }
        val fps = maxRate.roundToInt()
        return if (fps > 0) listOf(fps) else emptyList()
    }

    private fun targetDisplay(context: Context): Display? {
        return (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun displayCutout(display: Display, context: Context): DisplayCutout? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return display.cutout
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return (context as? Activity)?.window?.decorView?.rootWindowInsets?.displayCutout
    }

    private fun addResolutionOrientations(presets: LinkedHashSet<Resolution>, width: Int, height: Int) {
        val evenWidth = width / 2 * 2
        val evenHeight = height / 2 * 2
        if (evenWidth <= 0 || evenHeight <= 0) return
        if (PreferenceConfiguration.isSquarishScreen(evenWidth, evenHeight)) {
            presets += Resolution(evenHeight, evenWidth)
        }
        presets += Resolution(evenWidth, evenHeight)
    }

    private fun defaultDialogAxisPairs(event: MotionEvent): List<Pair<Float, Float>> = listOf(
        event.getAxisValue(MotionEvent.AXIS_HAT_X) to event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        event.getAxisValue(MotionEvent.AXIS_X) to event.getAxisValue(MotionEvent.AXIS_Y),
        event.getAxisValue(MotionEvent.AXIS_Z) to event.getAxisValue(MotionEvent.AXIS_RZ)
    )

    private fun pixelsTextFactory(context: Context): (Int) -> String {
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
        val useWan = locale?.language.equals("zh", ignoreCase = true)
        val template = context.getString(R.string.custom_resolution_pixels_format)
        return { pixelCount ->
            val scaled = if (useWan) pixelCount / 10_000f else pixelCount / 1_000_000f
            String.format(locale, template, scaled)
        }
    }
}

@Composable
private fun CustomResolutionsDialogContent(
    initial: List<Resolution>,
    pixelsText: (Int) -> String,
    frameRateMode: Boolean,
    nativePresets: List<Resolution>,
    requestInitialFocus: Boolean,
    controllerFocusRequest: Int,
    touchNavigationActive: Boolean,
    onTouchNavigation: () -> Unit,
    onCommit: (List<Resolution>) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var resolutions by remember { mutableStateOf(initial) }
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<ResolutionInputError?>(null) }
    var justAdded by remember { mutableStateOf<Resolution?>(null) }
    var infoExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val twoPane = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp <= 480

    val widthFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }

    fun keyOf(r: Resolution) = "${r.width}x${r.height}"
    val visibleResolutions = resolutions
    val rowFocus = remember(visibleResolutions) {
        visibleResolutions.associate { keyOf(it) to FocusRequester() }
    }
    val firstRowFocus = rowFocus[visibleResolutions.firstOrNull()?.let(::keyOf)]
    val presetCount = if (frameRateMode) {
        (nativePresets.map { it.width } + FRAME_RATE_PRESETS).distinct().size
    } else {
        (nativePresets.map { it.width to it.height } + PRESETS.map { it.width to it.height }).distinct().size
    }
    val presetFocus = remember(presetCount) { List(presetCount) { FocusRequester() } }
    val firstPresetFocus = presetFocus.firstOrNull()

    // 删除行后把焦点还给相邻行;行节点在 LazyColumn 组合后才存在,未挂载时
    // FocusRequester.requestFocus 会抛 IllegalStateException,需短暂重试
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingRowFocusKey, resolutions) {
        val key = pendingRowFocusKey ?: return@LaunchedEffect
        repeat(10) {
            val focused = rowFocus[key]?.let { requester ->
                runCatching { requester.requestFocus() }.isSuccess
            } == true
            if (focused) {
                pendingRowFocusKey = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(16)
        }
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
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(requestInitialFocus, controllerFocusRequest) {
        if (!requestInitialFocus && controllerFocusRequest == 0) return@LaunchedEffect
        inputModeManager.requestInputMode(InputMode.Keyboard)
        repeat(10) {
            if (runCatching { addFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }

    fun commit(list: List<Resolution>) {
        resolutions = list
        onCommit(list)
    }

    fun addPreset(preset: Resolution) {
        if (preset in resolutions) {
            inputError = ResolutionInputError(null, ResolutionInputReason.DUPLICATE)
            return
        }
        commit((resolutions + preset).sortedWith(resolutionOrder))
        justAdded = if (hostView.isInTouchMode) null else preset
        inputError = null
    }

    fun addResolution() {
        val resolution = if (frameRateMode) {
            val fps = widthText.trim().toIntOrNull()
            val error = validateFrameRateInput(fps, resolutions)
            if (error != null) {
                inputError = error
                return
            }
            Resolution(fps!!, 0)
        } else {
            val width = widthText.trim().toIntOrNull()
            val height = heightText.trim().toIntOrNull()
            val error = validateResolutionInput(width, height, resolutions)
            if (error != null) {
                inputError = error
                return
            }
            Resolution(width!!, height!!)
        }
        commit((resolutions + resolution).sortedWith(resolutionOrder))
        justAdded = if (hostView.isInTouchMode) null else resolution
        widthText = ""
        heightText = ""
        inputError = null
        if (!hostView.isInTouchMode) widthFocus.requestFocus()
    }

    fun deleteResolution(resolution: Resolution) {
        if (frameRateMode && resolution.width == 60) return
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
            .wrapContentHeight(unbounded = true)
            .clip(surfaceShape)
            .background(gradient)
            .border(1.dp, outline, surfaceShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTouchNavigation()
                    waitForUpOrCancellation()
                }
            }
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.heightIn(max = maxSurfaceHeight - 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DialogHeader(
                frameRateMode = frameRateMode,
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
                if (frameRateMode) {
                    FrameRateComposer(
                        fpsText = widthText,
                        onFpsChange = {
                            widthText = it.filter(Char::isDigit).take(3)
                            inputError = null
                        },
                        error = inputError,
                        fpsFocus = widthFocus,
                        addFocus = addFocus,
                        downTarget = firstPresetFocus ?: firstRowFocus ?: addFocus,
                        leftTarget = leftTarget,
                        onAdd = ::addResolution
                    )
                } else {
                    Composer(
                        widthText = widthText,
                        onWidthChange = {
                            widthText = it.filter(Char::isDigit).take(5)
                            inputError = null
                        },
                        heightText = heightText,
                        onHeightChange = {
                            heightText = it.filter(Char::isDigit).take(5)
                            inputError = null
                        },
                        error = inputError,
                        pixelsText = pixelsText,
                        widthFocus = widthFocus,
                        heightFocus = heightFocus,
                        addFocus = addFocus,
                        downFromHeight = firstPresetFocus ?: firstRowFocus ?: addFocus,
                        leftTarget = leftTarget,
                        onAdd = ::addResolution,
                        onHeightDone = ::addResolution
                    )
                }
            }

            if (twoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(0.85f).fillMaxHeight()) {
                        ResolutionList(
                            resolutions = visibleResolutions,
                            justAdded = justAdded,
                            compact = true,
                            pixelsText = pixelsText,
                            focusFor = { rowFocus.getValue(keyOf(it)) },
                            rightNeighbor = widthFocus,
                            onDelete = ::deleteResolution
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        composer()
                        if (frameRateMode) {
                            FrameRatePresetRow(
                                nativeFrameRates = nativePresets.map { it.width },
                                focusFor = { presetFocus[it] },
                                upTarget = addFocus
                            ) { fps ->
                                addPreset(Resolution(fps, 0))
                            }
                        } else {
                            PresetRow(
                                nativePresets = nativePresets,
                                focusFor = { presetFocus[it] },
                                upTarget = addFocus
                            ) { preset ->
                                addPreset(Resolution(preset.width, preset.height))
                            }
                        }
                    }
                }
            } else {
                composer()
                if (frameRateMode) {
                    FrameRatePresetRow(
                        nativeFrameRates = nativePresets.map { it.width },
                        focusFor = { presetFocus[it] },
                        upTarget = addFocus
                    ) { fps ->
                        addPreset(Resolution(fps, 0))
                    }
                } else {
                    PresetRow(
                        nativePresets = nativePresets,
                        focusFor = { presetFocus[it] },
                        upTarget = addFocus
                    ) { preset ->
                        addPreset(Resolution(preset.width, preset.height))
                    }
                }
                ResolutionList(
                    resolutions = visibleResolutions,
                    justAdded = justAdded,
                    compact = false,
                    pixelsText = pixelsText,
                    focusFor = { rowFocus.getValue(keyOf(it)) },
                    rightNeighbor = null,
                    onDelete = ::deleteResolution,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            FooterRow(
                onCancel = onCancel,
                onConfirm = onConfirm
            )
        }
    }
}
