package com.limelight.preferences

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.limelight.R
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppDialogStyler

/**
 * Compose implementation of the custom resolutions dialog.
 *
 * 窗口与持久化在此;UI 组件见 CustomResolutionsDialogUi.kt,纯逻辑见
 * ResolutionFormat.kt 与 CustomResolutionsStore.kt。
 */
object CustomResolutionsDialog {

    fun show(context: Context, onClosed: () -> Unit): Dialog {
        val initial = CustomResolutionsStore.load(context)
        val pixelsText = pixelsTextFactory(context)
        val dialog = ComponentDialog(context, R.style.AppComposeDialogStyle)
        // 取消(返回/点外部/取消按钮)丢弃本次会话的全部改动,恢复进入时的快照
        var cancelled = false
        val composeView = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CustomResolutionsDialogContent(
                    initial = initial,
                    pixelsText = pixelsText,
                    onCommit = { CustomResolutionsStore.save(context, it) },
                    onCancel = {
                        cancelled = true
                        dialog.cancel()
                    },
                    onConfirm = dialog::dismiss
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
        dialog.setOnCancelListener { cancelled = true }
        dialog.setOnDismissListener {
            if (cancelled) {
                CustomResolutionsStore.save(context, initial)
            }
            onClosed()
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
    val rowFocus = remember(resolutions) {
        resolutions.associate { keyOf(it) to FocusRequester() }
    }
    val firstRowFocus = rowFocus[resolutions.firstOrNull()?.let(::keyOf)]

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
    LaunchedEffect(Unit) {
        if (!hostView.isInTouchMode) {
            widthFocus.requestFocus()
        }
    }

    fun commit(list: List<Resolution>) {
        resolutions = list
        onCommit(list)
    }

    fun addResolution() {
        val width = widthText.trim().toIntOrNull()
        val height = heightText.trim().toIntOrNull()
        val error = validateResolutionInput(width, height, resolutions)
        if (error != null) {
            inputError = error
            return
        }
        val resolution = Resolution(width!!, height!!)
        commit((resolutions + resolution).sortedWith(resolutionOrder))
        justAdded = resolution
        widthText = ""
        heightText = ""
        inputError = null
        if (!hostView.isInTouchMode) widthFocus.requestFocus()
    }

    fun deleteResolution(resolution: Resolution) {
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
                            pixelsText = pixelsText,
                            focusFor = { rowFocus.getValue(keyOf(it)) },
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
                        inputError = null
                    }
                )
                ResolutionList(
                    resolutions = resolutions,
                    justAdded = justAdded,
                    compact = false,
                    pixelsText = pixelsText,
                    focusFor = { rowFocus.getValue(keyOf(it)) },
                    rightNeighbor = null,
                    onDelete = ::deleteResolution,
                    modifier = Modifier.heightIn(max = (configuration.screenHeightDp * 0.42f).dp)
                )
            }

            FooterRow(
                onCancel = onCancel,
                onConfirm = onConfirm
            )
        }
    }
}
