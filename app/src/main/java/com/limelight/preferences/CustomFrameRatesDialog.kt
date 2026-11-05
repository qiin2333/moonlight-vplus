package com.limelight.preferences

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.ui.isGamepadNavigationSource
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.appAccentColor
import com.limelight.utils.appAccentSoftColor

/**
 * 常用帧率弹窗:列出全部候选帧率,每项一个开关,打开即加入游戏菜单的常用帧率。
 * 窗口外壳与 [CustomResolutionsDialog] 一致,但没有输入框与预设。
 */
object CustomFrameRatesDialog {

    /**
     * @param frameRates 全部候选帧率(整数字符串,升序)
     * @param initialSelected 进入时已加入常用的帧率
     * @param onCommit 点击保存时回调最终的常用集合;取消不会调用
     */
    fun show(
        context: Context,
        frameRates: List<String>,
        initialSelected: Set<String>,
        onCommit: (Set<String>) -> Unit,
        onClosed: () -> Unit,
        requestInitialFocus: Boolean = false
    ): Dialog {
        val dialog = ComponentDialog(context, R.style.AppComposeDialogStyle)
        var confirmedByUser = false
        var current = initialSelected
        val controllerFocusRequest = mutableIntStateOf(0)
        val touchNavigationActive = mutableStateOf(!requestInitialFocus)
        val axisNavigation = MenuAxisNavigationState()
        val composeView = ComposeView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CustomFrameRatesDialogContent(
                    frameRates = frameRates,
                    initialSelected = initialSelected,
                    controllerFocusRequest = controllerFocusRequest.intValue,
                    onTouchNavigation = { touchNavigationActive.value = true },
                    onChanged = { current = it },
                    onCancel = { dialog.cancel() },
                    onConfirm = {
                        confirmedByUser = true
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
        dialog.setCanceledOnTouchOutside(false)
        val dialogController = object : CustomDialogController {
            override fun dispatchAxes(event: MotionEvent): Boolean {
                if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
                val transition = axisNavigation.update(CustomResolutionsDialog.defaultDialogAxisPairs(event))
                if (transition.changed && transition.pressedKeyCode != null &&
                    touchNavigationActive.value
                ) {
                    touchNavigationActive.value = false
                    controllerFocusRequest.intValue++
                }
                return false
            }
        }
        activeCustomDialogController = dialogController
        dialog.window?.decorView?.setOnGenericMotionListener { _, event ->
            dialogController.dispatchAxes(event)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.isGamepadNavigationSource() && event.action == KeyEvent.ACTION_DOWN &&
                touchNavigationActive.value
            ) {
                touchNavigationActive.value = false
                controllerFocusRequest.intValue++
            }
            com.limelight.ui.UiDismissKeyHandler.handle(
                event.action,
                keyCode,
                onDismiss = { dialog.cancel() },
                dismissOnBack = false
            )
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val hostWindow = (context as? Activity)?.window
            if (hostWindow != null) {
                decorView.systemUiVisibility = hostWindow.decorView.systemUiVisibility
                if (hostWindow.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
                    addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
            }
        }
        dialog.setOnDismissListener {
            if (activeCustomDialogController === dialogController) {
                activeCustomDialogController = null
            }
            // 取消、返回或宿主销毁导致的 dismiss 都不保存
            if (confirmedByUser) onCommit(current)
            onClosed()
        }
        dialog.show()
        CustomResolutionsDialog.applyDialogWidth(dialog, context)
        composeView.post {
            if (dialog.isShowing && requestInitialFocus && !touchNavigationActive.value) {
                controllerFocusRequest.intValue++
            }
        }
        return dialog
    }
}

@Composable
private fun CustomFrameRatesDialogContent(
    frameRates: List<String>,
    initialSelected: Set<String>,
    controllerFocusRequest: Int,
    onTouchNavigation: () -> Unit,
    onChanged: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }
    var handledControllerFocusRequest by remember { mutableIntStateOf(0) }
    val firstRowFocus = remember { FocusRequester() }
    var firstRowPlaced by remember { mutableStateOf(false) }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(controllerFocusRequest, firstRowPlaced) {
        if (!firstRowPlaced || controllerFocusRequest <= handledControllerFocusRequest) return@LaunchedEffect
        inputModeManager.requestInputMode(InputMode.Keyboard)
        runCatching { firstRowFocus.requestFocus() }
        handledControllerFocusRequest = controllerFocusRequest
    }

    val configuration = LocalConfiguration.current
    val surfaceShape = AppShapes.overlay
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
            .border(1.dp, colorResource(R.color.app_dialog_outline), surfaceShape)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(AppShapes.medium)
                        .background(appAccentSoftColor()),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_resolution_cute),
                        contentDescription = null,
                        tint = appAccentColor(),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.title_custom_frame_rates),
                    color = colorResource(R.color.app_dialog_text_primary),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(R.string.custom_frame_rate_hint),
                color = colorResource(R.color.app_dialog_text_secondary),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(frameRates, key = { it }) { fps ->
                    val isFirst = fps == frameRates.first()
                    FrameRateRow(
                        fps = fps,
                        checked = fps in selected,
                        modifier = if (isFirst) {
                            Modifier
                                .focusRequester(firstRowFocus)
                                .onGloballyPositioned { firstRowPlaced = true }
                        } else Modifier,
                        onToggle = {
                            selected = if (fps in selected) selected - fps else selected + fps
                            onChanged(selected)
                        }
                    )
                }
            }
            FooterRow(onCancel = onCancel, onConfirm = onConfirm)
        }
    }
}

@Composable
private fun FrameRateRow(
    fps: String,
    checked: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit
) {
    val rowShape = AppShapes.medium
    val accent = appAccentColor()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showFocus = focusIndicationVisible(focused)
    val bg by animateColorAsState(
        targetValue = if (showFocus) appAccentSoftColor() else colorResource(R.color.app_dialog_surface_elevated),
        label = "fpsRowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (showFocus) accent else colorResource(R.color.app_dialog_outline),
        label = "fpsRowBorder"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(bg)
            .border(if (showFocus) 1.5.dp else 1.dp, borderColor, rowShape)
            .handleGamepadConfirm(onToggle)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onToggle)
            .padding(start = 11.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fps,
                color = colorResource(R.color.app_dialog_text_primary),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.custom_frame_rate_add_favorite),
                color = colorResource(R.color.app_dialog_text_secondary),
                fontSize = 11.sp
            )
        }
        // 点击由整行处理,开关本身只显示状态
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent)
        )
    }
}
