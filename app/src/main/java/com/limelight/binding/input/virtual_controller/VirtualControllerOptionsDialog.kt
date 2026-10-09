package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.ui.UiDismissKeyHandler
import com.limelight.utils.AppActionSheet

/** Stream-local input owner. Neither USB snapshots nor framework axes may reach the host. */
internal class VirtualControllerOptionsDialog(
    context: Context,
    private val actions: List<AppActionSheet.Action>,
    private val store: OscSettingsButtonStore,
    private val readAxes: (MotionEvent) -> Pair<List<Pair<Float, Float>>, Float>,
    private val onDragEnabled: (Boolean) -> Unit,
    private val onAction: (Int) -> Unit
) : ComponentDialog(context, R.style.AppActionSheetStyle) {
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
                    var dragEnabled by remember { mutableStateOf(store.dragEnabled) }
                    val focusRequester = remember { FocusRequester() }
                    var placed by remember { mutableStateOf(false) }
                    val inputMode = LocalInputModeManager.current
                    val listState = rememberLazyListState()
                    scrollBy = { listState.dispatchRawDelta(it) }
                    LaunchedEffect(placed) {
                        if (placed) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            focusRequester.requestFocus()
                        }
                    }
                    AppActionSheet.ActionSheetContainer {
                        AppActionSheet.ActionSheetHeader(context.getString(R.string.osc_quick_menu), null, false)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                                val row = if (action.id == ACTION_DRAG) action.copy(checked = dragEnabled) else action
                                AppActionSheet.ActionSheetRow(row, { selected ->
                                    if (selected.id == ACTION_DRAG) {
                                        dragEnabled = !dragEnabled
                                        onDragEnabled(dragEnabled)
                                    } else {
                                        dismiss()
                                        onAction(selected.id)
                                    }
                                }, Modifier
                                    .then(if (index == 0) Modifier.focusRequester(focusRequester)
                                        .onGloballyPositioned { placed = true } else Modifier)
                                    .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                                    .onFocusChanged {
                                        if (it.isFocused) focusedAction = action.id
                                        else if (focusedAction == action.id) focusedAction = null
                                    })
                            }
                        }
                    }
                }
            }
        }
        AppActionSheet.prepareDialog(this, view)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ownsFocus) return true
        val identity = event.deviceId to event.keyCode
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
                UiDismissKeyHandler.handle(event.action, event.keyCode, ::cancel)
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
        // This is a single-column menu, so horizontal input cannot escape to another surface.
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) sendKeyPair(key)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ownsFocus = hasFocus
        if (!hasFocus) {
            sources.filterValues { it.navigation.activeKeyCode != null || it.scroll.activeKeyCode != null }
                .keys.let(awaitingNeutral::addAll)
            clearInput()
        }
    }

    override fun onStop() {
        ownsFocus = false
        clearInput()
        awaitingNeutral.clear()
        scrollBy = null
        super.onStop()
    }

    companion object {
        const val ACTION_DRAG = 103
        const val ACTION_RESET = 104
        private val confirmKeyCodes = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
        private val dismissKeyCodes = setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
    }
}
