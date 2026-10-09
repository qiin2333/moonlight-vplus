package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.gamemenu.GameMenuDialogShell
import com.limelight.gamemenu.GameMenuDimens
import com.limelight.gamemenu.GameMenuVerticalScrollbar
import com.limelight.ui.UiDismissKeyHandler
import com.limelight.utils.AppActionSheet
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.appAccentSoftColor

/** Stream-local input owner. Neither USB snapshots nor framework axes may reach the host. */
internal class VirtualControllerOptionsDialog(
    context: Context,
    private val actions: List<AppActionSheet.Action>,
    private val readAxes: (MotionEvent) -> Pair<List<Pair<Float, Float>>, Float>,
    private val initialOpacity: Int,
    private val initialSizeScale: Float,
    private val onToggleChanged: (Int, Boolean) -> Unit,
    private val onOpacityChanged: (Int) -> Unit,
    private val onSizeScaleChanged: (Float) -> Unit,
    private val onAction: (Int) -> Unit
) : ComponentDialog(context, R.style.GameMenuDialogStyle) {
    private class Source {
        val navigation = MenuAxisNavigationState()
        val scroll = MenuAxisNavigationState()
        val digital = linkedMapOf<Int, Int>()
        var nextStepAt = 0L
    }

    private val sources = mutableMapOf<Int, Source>()
    private val awaitingNeutral = mutableSetOf<Int>()
    private val confirms = mutableMapOf<Pair<Int, Int>, Int?>()
    private val dismissKeys = mutableSetOf<Pair<Int, Int>>()
    private var navigationSource: Int? = null
    private var scrollSource: Int? = null
    private var focusedAction: Int? = null
    private var ownsFocus = false
    private var scrollBy: ((Float) -> Unit)? = null
    private var closeConfirmation: (() -> Boolean)? = null
    private val awaitingDigitalRelease = mutableSetOf<Pair<Int, Int>>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeatScheduled = false
    private var lastTickAt = 0L
    private val repeat = object : Runnable {
        override fun run() {
            repeatScheduled = false
            if (!isShowing || !ownsFocus) return
            val now = SystemClock.uptimeMillis()
            sources[navigationSource]?.let { source ->
                val key = source.digital.values.lastOrNull() ?: source.navigation.activeKeyCode
                if (key != null && now >= source.nextStepAt) {
                    step(key)
                    source.nextStepAt = now + 100L
                }
            }
            val scroll = sources[scrollSource]?.scroll?.activeKeyCode
            if (scroll != null) {
                val elapsed = (now - lastTickAt).coerceIn(0, 50)
                val distance = elapsed * context.resources.displayMetrics.density * 0.4f
                scrollBy?.invoke(if (scroll == KeyEvent.KEYCODE_DPAD_UP) -distance else distance)
            }
            lastTickAt = now
            scheduleRepeat()
        }
    }

    fun showMenu() {
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AppActionSheet.AppActionSheetTheme {
                    var opacity by remember { mutableFloatStateOf(initialOpacity.coerceIn(0, 100).toFloat()) }
                    var sizeScale by remember {
                        mutableFloatStateOf((initialSizeScale * 100f).coerceIn(50f, 200f))
                    }
                    var toggleStates by remember {
                        mutableStateOf(actions.filter { it.toggle }.associate { it.id to (it.checked == true) })
                    }
                    val focusRequester = remember { FocusRequester() }
                    val resetFocusRequester = remember { FocusRequester() }
                    val cancelFocusRequester = remember { FocusRequester() }
                    var confirmingReset by remember { mutableStateOf(false) }
                    var restoreResetFocus by remember { mutableStateOf(false) }
                    var placed by remember { mutableStateOf(false) }
                    var resetPlaced by remember { mutableStateOf(false) }
                    var cancelPlaced by remember { mutableStateOf(false) }
                    var viewportHeightPx by remember { mutableIntStateOf(0) }
                    val inputMode = LocalInputModeManager.current
                    val configuration = LocalConfiguration.current
                    val windowWidth = with(LocalDensity.current) {
                        LocalWindowInfo.current.containerSize.width.toDp()
                    }
                    val scrollState = rememberScrollState()
                    scrollBy = { scrollState.dispatchRawDelta(it) }

                    fun leaveConfirmation() {
                        gateInputUntilRelease()
                        confirmingReset = false
                        restoreResetFocus = true
                    }

                    closeConfirmation = {
                        if (confirmingReset) {
                            leaveConfirmation()
                            true
                        } else {
                            false
                        }
                    }

                    LaunchedEffect(placed) {
                        if (placed && !confirmingReset) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            focusRequester.requestFocus()
                        }
                    }
                    LaunchedEffect(confirmingReset, cancelPlaced) {
                        if (confirmingReset && cancelPlaced) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            cancelFocusRequester.requestFocus()
                        }
                    }
                    LaunchedEffect(restoreResetFocus, resetPlaced) {
                        if (restoreResetFocus && resetPlaced) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            resetFocusRequester.requestFocus()
                            restoreResetFocus = false
                        }
                    }

                    GameMenuDialogShell(
                        widthFraction = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            0.98f
                        } else {
                            0.95f
                        },
                        horizontalInset = if (windowWidth >= 576.dp) {
                            GameMenuDimens.wideScreenInset
                        } else {
                            GameMenuDimens.compactScreenInset
                        },
                        onDismissRequest = {
                            if (closeConfirmation?.invoke() != true) cancel()
                        }
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val panelMaxHeight = maxHeight * 0.90f
                            val listMaxHeight = (panelMaxHeight - 64.dp).coerceAtLeast(44.dp)
                            AppActionSheet.ActionSheetContainer(
                                respectNavigationBars = false,
                                shieldBackgroundTouches = true,
                                outerPadding = PaddingValues(0.dp)
                            ) {
                                AppActionSheet.ActionSheetHeader(context.getString(
                                    if (confirmingReset) R.string.dialog_title_reset_osc else R.string.osc_quick_menu
                                ), null, false)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = listMaxHeight)
                                        .onGloballyPositioned { viewportHeightPx = it.size.height }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = GameMenuDimens.section)
                                            .verticalScroll(scrollState)
                                            .padding(horizontal = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        if (confirmingReset) {
                                            Text(
                                                context.getString(R.string.dialog_text_reset_osc),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                                            )
                                            AppActionSheet.ActionSheetRow(
                                                AppActionSheet.Action(
                                                    ACTION_CANCEL_RESET,
                                                    context.getString(android.R.string.cancel)
                                                ),
                                                { leaveConfirmation() },
                                                Modifier
                                                    .focusRequester(cancelFocusRequester)
                                                    .onGloballyPositioned { cancelPlaced = true }
                                                    .onFocusChanged {
                                                        if (it.isFocused) focusedAction = ACTION_CANCEL_RESET
                                                    }
                                                    .focusProperties {
                                                        left = FocusRequester.Cancel
                                                        right = FocusRequester.Cancel
                                                    }
                                            )
                                            AppActionSheet.ActionSheetRow(
                                                AppActionSheet.Action(
                                                    ACTION_CONFIRM_RESET,
                                                    context.getString(android.R.string.ok)
                                                ),
                                                { onAction(ACTION_RESET_CONTROLLER_LAYOUT); dismiss() },
                                                Modifier
                                                    .onFocusChanged {
                                                        if (it.isFocused) focusedAction = ACTION_CONFIRM_RESET
                                                    }
                                                    .focusProperties {
                                                        left = FocusRequester.Cancel
                                                        right = FocusRequester.Cancel
                                                    }
                                            )
                                        } else {
                                            actions.forEachIndexed { index, action ->
                                                val row = action.copy(checked = if (action.toggle) {
                                                    toggleStates[action.id] == true
                                                } else {
                                                    action.checked
                                                })
                                                AppActionSheet.ActionSheetRow(row, { selected ->
                                                    if (selected.id == ACTION_RESET_CONTROLLER_LAYOUT) {
                                                        gateInputUntilRelease()
                                                        confirmingReset = true
                                                        cancelPlaced = false
                                                        resetPlaced = false
                                                    } else if (selected.toggle) {
                                                        val enabled = !(toggleStates[selected.id] == true)
                                                        toggleStates = toggleStates + (selected.id to enabled)
                                                        onToggleChanged(selected.id, enabled)
                                                    } else {
                                                        dismiss()
                                                        onAction(selected.id)
                                                    }
                                                }, Modifier
                                                    .then(if (index == 0) Modifier
                                                        .focusRequester(focusRequester)
                                                        .onGloballyPositioned { placed = true }
                                                    else Modifier)
                                                    .then(if (action.id == ACTION_RESET_CONTROLLER_LAYOUT) {
                                                        Modifier
                                                            .focusRequester(resetFocusRequester)
                                                            .onGloballyPositioned { resetPlaced = true }
                                                    } else Modifier)
                                                    .focusProperties {
                                                        left = FocusRequester.Cancel
                                                        right = FocusRequester.Cancel
                                                    }
                                                    .onFocusChanged {
                                                        if (it.isFocused) focusedAction = action.id
                                                        else if (focusedAction == action.id) focusedAction = null
                                                    })
                                            }
                                            SettingsSliderRow(
                                                id = ACTION_OPACITY,
                                                title = context.getString(R.string.dialog_title_osc_opacity),
                                                value = opacity,
                                                valueRange = 0f..100f,
                                                step = 1f,
                                                onValueChange = {
                                                    opacity = it
                                                    onOpacityChanged(it.toInt())
                                                }
                                            )
                                            SettingsSliderRow(
                                                id = ACTION_SIZE,
                                                title = context.getString(R.string.osc_settings_button_size),
                                                value = sizeScale,
                                                valueRange = 50f..200f,
                                                step = 5f,
                                                onValueChange = {
                                                    sizeScale = it
                                                    onSizeScaleChanged(it / 100f)
                                                }
                                            )
                                        }
                                    }
                                    GameMenuVerticalScrollbar(
                                        scrollState = scrollState,
                                        viewportHeightPx = viewportHeightPx,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .width(3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        AppActionSheet.prepareDialog(this, view, fullScreen = true)
    }

    @androidx.compose.runtime.Composable
    private fun SettingsSliderRow(
        id: Int,
        title: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        step: Float,
        onValueChange: (Float) -> Unit
    ) {
        var focused by remember { mutableStateOf(false) }
        val modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) focusedAction = id
                else if (focusedAction == id) focusedAction = null
            }
            .then(if (focused) Modifier.background(appAccentSoftColor(), AppShapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.primary, AppShapes.medium) else Modifier)
            .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step
                        onValueChange((value + delta).coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                    true
                } else keyCode in confirmKeyCodes
            }
            .focusable()
        Column(modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Slider(
                    modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1
                )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ownsFocus) return true
        val identity = event.deviceId to event.keyCode
        if (identity in awaitingDigitalRelease) {
            if (event.action == KeyEvent.ACTION_UP) awaitingDigitalRelease.remove(identity)
            return true
        }
        if (event.keyCode in confirmKeyCodes) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (identity !in confirms) confirms[identity] = focusedAction
            } else if (event.action == KeyEvent.ACTION_UP) {
                val target = confirms.remove(identity)
                if (target != null && target == focusedAction && !event.isCanceled) {
                    sendKeyPair(KeyEvent.KEYCODE_DPAD_CENTER)
                }
            }
            return true
        }
        if (event.keyCode in dismissKeyCodes) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) dismissKeys.add(identity)
            if (event.action == KeyEvent.ACTION_UP && dismissKeys.remove(identity) && !event.isCanceled) {
                UiDismissKeyHandler.handle(event.action, event.keyCode) {
                    if (closeConfirmation?.invoke() != true) cancel()
                }
            }
            return true
        }
        val key = when (event.keyCode) {
            268, 269 -> KeyEvent.KEYCODE_DPAD_UP
            270, 271 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> event.keyCode
        }
        if (key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            val source = sources.getOrPut(event.deviceId) { Source() }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                !source.digital.containsKey(event.keyCode)) {
                source.digital[event.keyCode] = key
                navigationSource = event.deviceId
                step(key)
                source.nextStepAt = SystemClock.uptimeMillis() + 350L
                scheduleRepeat()
            } else if (event.action == KeyEvent.ACTION_UP) {
                source.digital.remove(event.keyCode)
            }
            return true
        }
        // Preserve ordinary keyboard handling; all gamepad input remains local.
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD) || super.dispatchKeyEvent(event)
    }

    private fun step(key: Int) {
        // The list is one column. Horizontal input is meaningful only for the two sliders.
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
            (key in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) &&
                focusedAction in setOf(ACTION_OPACITY, ACTION_SIZE))) {
            sendKeyPair(key)
        }
    }

    private fun sendKeyPair(key: Int) {
        val now = SystemClock.uptimeMillis()
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0) {
            val (navigation, scrollY) = readAxes(event)
            dispatchAxes(event.deviceId, navigation, scrollY)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    fun dispatchAxes(sourceId: Int, axes: List<Pair<Float, Float>>, scrollY: Float) {
        if (!isShowing) return
        val source = sources.getOrPut(sourceId) { Source() }
        val scrollAxes = listOf(0f to scrollY)
        if (sourceId in awaitingNeutral) {
            if (source.navigation.isNeutral(axes) && source.scroll.isNeutral(scrollAxes)) awaitingNeutral.remove(sourceId)
            return
        }
        if (!ownsFocus) return
        val nav = source.navigation.update(axes)
        val scroll = source.scroll.update(scrollAxes)
        if (nav.changed && nav.pressedKeyCode != null && source.digital.isEmpty()) {
            navigationSource = sourceId
            step(nav.pressedKeyCode)
            source.nextStepAt = SystemClock.uptimeMillis() + 350L
        }
        if (scroll.changed && scroll.pressedKeyCode != null) scrollSource = sourceId
        scheduleRepeat()
    }

    private fun scheduleRepeat() {
        if (repeatScheduled || !isShowing || !ownsFocus) return
        val nav = sources[navigationSource]
        if (nav?.digital?.isNotEmpty() != true && nav?.navigation?.activeKeyCode == null &&
            sources[scrollSource]?.scroll?.activeKeyCode == null) return
        repeatScheduled = true
        if (lastTickAt == 0L) lastTickAt = SystemClock.uptimeMillis()
        handler.postDelayed(repeat, 16L)
    }

    fun releaseSource(sourceId: Int) {
        sources.remove(sourceId)
        awaitingNeutral.remove(sourceId)
        confirms.keys.removeAll { it.first == sourceId }
        dismissKeys.removeAll { it.first == sourceId }
        if (navigationSource == sourceId) navigationSource = null
        if (scrollSource == sourceId) scrollSource = null
    }

    private fun clearInput() {
        handler.removeCallbacks(repeat)
        repeatScheduled = false
        lastTickAt = 0L
        sources.clear()
        confirms.clear()
        dismissKeys.clear()
        navigationSource = null
        scrollSource = null
    }

    private fun gateInputUntilRelease() {
        sources.forEach { (id, source) ->
            if (source.navigation.activeKeyCode != null || source.scroll.activeKeyCode != null) {
                awaitingNeutral.add(id)
            }
            source.digital.keys.forEach { awaitingDigitalRelease.add(id to it) }
        }
        clearInput()
        focusedAction = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ownsFocus = hasFocus
        if (!hasFocus) {
            gateInputUntilRelease()
        }
    }

    override fun onStop() {
        ownsFocus = false
        clearInput()
        awaitingNeutral.clear()
        awaitingDigitalRelease.clear()
        scrollBy = null
        closeConfirmation = null
        super.onStop()
    }

    companion object {
        const val ACTION_DRAG = 103
        const val ACTION_RESET = 104
        const val ACTION_ONLY_L3_R3 = 105
        const val ACTION_SHOW_GUIDE = 106
        const val ACTION_HALF_HEIGHT = 107
        const val ACTION_OPACITY = 108
        const val ACTION_SIZE = 109
        const val ACTION_RESET_CONTROLLER_LAYOUT = 110
        private const val ACTION_CANCEL_RESET = 111
        private const val ACTION_CONFIRM_RESET = 112
        private val confirmKeyCodes = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
        private val dismissKeyCodes = setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
    }
}
