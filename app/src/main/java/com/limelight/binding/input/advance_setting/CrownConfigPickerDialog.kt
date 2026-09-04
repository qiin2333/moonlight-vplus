package com.limelight.binding.input.advance_setting

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.ui.UiDialogKeyHandler
import com.limelight.utils.AppDialogStyler

/** Only the configuration editor opens this picker; executing a bound action never does. */
class CrownConfigPickerDialog(
    context: Context,
    names: List<String>,
    private val readAxes: (MotionEvent) -> Pair<List<Pair<Float, Float>>, Float>,
    private val onSelected: (Int) -> Unit
) : AlertDialog(context, R.style.AppDialogStyle) {
    private class Source {
        val navigation = MenuAxisNavigationState()
        val scroll = MenuAxisNavigationState()
        val digital = linkedSetOf<Int>()
        var lastStepAt = 0L
    }

    private val sources = mutableMapOf<Int, Source>()
    private val sourcesAwaitingNeutral = mutableSetOf<Int>()
    private var activeSource: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var repeatScheduled = false
    private var ownsWindowFocus = false
    private data class ConfirmTarget(val view: View?, val position: Int)
    private val confirmKeys = mutableMapOf<Pair<Int, Int>, ConfirmTarget>()
    private val choices = ListView(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
        setOnItemClickListener { _, _, position, _ ->
            dismiss()
            onSelected(position)
        }
    }
    private val repeat = object : Runnable {
        override fun run() {
            repeatScheduled = false
            if (!isShowing) return
            val source = sources[activeSource] ?: return
            val direction = source.digital.lastOrNull() ?: source.navigation.activeKeyCode
            val scroll = source.scroll.activeKeyCode
            if (direction == null && scroll == null) return
            val now = SystemClock.uptimeMillis()
            if (now - source.lastStepAt >= 100L) {
                direction?.let(::step)
                if (scroll != null) {
                    val distance = (context.resources.displayMetrics.density * 32).toInt()
                    choices.scrollListBy(if (scroll == KeyEvent.KEYCODE_DPAD_UP) -distance else distance)
                }
                source.lastStepAt = now
            }
            scheduleRepeat()
        }
    }

    init {
        setTitle(R.string.crown_direct_config_action)
        setView(choices)
        setButton(BUTTON_NEGATIVE, context.getString(R.string.game_menu_cancel)) { _, _ -> cancel() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDialogStyler.applySystemChoiceList(this, context)
        choices.post {
            if (isShowing) {
                choices.requestFocus()
                choices.setSelection(0)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ownsWindowFocus) return true
        val identity = event.deviceId to event.keyCode
        val confirms = event.keyCode in setOf(KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
        if (confirms) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 && identity !in confirmKeys) {
                    confirmKeys[identity] = currentConfirmTarget()
                }
                return true
            }
            if (event.action != KeyEvent.ACTION_UP) return true
            val target = confirmKeys.remove(identity)
            if (target == null || target != currentConfirmTarget() || event.isCanceled) return true
        }
        if (UiDialogKeyHandler.handle(event.action, event.keyCode, { cancel() }, {
                val focus = currentFocus
                if (focus === choices || focus == null) {
                    val position = choices.selectedItemPosition
                    if (position in 0 until choices.count) {
                        dismiss()
                        onSelected(position)
                    }
                } else {
                    focus.performClick()
                }
            })) return true
        val key = when (event.keyCode) {
            // Numeric diagonal codes keep API 22 support.
            268, 269 -> KeyEvent.KEYCODE_DPAD_UP
            270, 271 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> event.keyCode
        }
        if (key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            val source = sources.getOrPut(event.deviceId) { Source() }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
            if (event.action == KeyEvent.ACTION_DOWN && source.digital.add(key)) {
                activeSource = event.deviceId
                step(key)
                source.lastStepAt = SystemClock.uptimeMillis() + 250L
                scheduleRepeat()
            } else if (event.action == KeyEvent.ACTION_UP) {
                source.digital.remove(key)
            }
            return true
        }
        // No gamepad buttons can reach the stream while this dialog owns input.
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD) || super.dispatchKeyEvent(event)
    }

    private fun step(key: Int) {
        val now = SystemClock.uptimeMillis()
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private fun currentConfirmTarget() = ConfirmTarget(
        currentFocus,
        if (currentFocus === choices) choices.selectedItemPosition else ListView.INVALID_POSITION
    )

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0) {
            val (navigation, scrollY) = readAxes(event)
            dispatchAxes(event.deviceId, navigation, scrollY)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    fun dispatchAxes(sourceId: Int, navigation: List<Pair<Float, Float>>, scrollY: Float) {
        if (!isShowing || !ownsWindowFocus) return
        val source = sources.getOrPut(sourceId) { Source() }
        val scrollAxes = listOf(0f to scrollY)
        if (sourceId in sourcesAwaitingNeutral) {
            if (source.navigation.isNeutral(navigation) && source.scroll.isNeutral(scrollAxes)) {
                sourcesAwaitingNeutral.remove(sourceId)
            }
            return
        }
        val nav = source.navigation.update(navigation)
        val scroll = source.scroll.update(scrollAxes)
        if (nav.pressedKeyCode != null || scroll.pressedKeyCode != null) {
            // A steady snapshot from another held controller is not a new navigation request.
            if (activeSource != sourceId &&
                !(nav.changed && nav.pressedKeyCode != null) &&
                !(scroll.changed && scroll.pressedKeyCode != null)) return
            activeSource = sourceId
            if (nav.changed && source.digital.isEmpty()) nav.pressedKeyCode?.let(::step)
            if (nav.changed) source.lastStepAt = SystemClock.uptimeMillis() + 250L
            scheduleRepeat()
        } else if (activeSource == sourceId && source.digital.isEmpty()) {
            activeSource = null
        }
    }

    private fun scheduleRepeat() {
        if (repeatScheduled) return
        repeatScheduled = true
        handler.postDelayed(repeat, 50L)
    }

    fun releaseSource(sourceId: Int) {
        sources.remove(sourceId)
        sourcesAwaitingNeutral.remove(sourceId)
        confirmKeys.keys.removeAll { it.first == sourceId }
        if (activeSource == sourceId) {
            activeSource = null
            handler.removeCallbacks(repeat)
            repeatScheduled = false
        }
    }

    private fun resetInput() {
        handler.removeCallbacks(repeat)
        repeatScheduled = false
        confirmKeys.clear()
        sources.clear()
        activeSource = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ownsWindowFocus = hasFocus
        if (!hasFocus) {
            sources.filterValues { it.navigation.activeKeyCode != null || it.scroll.activeKeyCode != null }
                .keys.let(sourcesAwaitingNeutral::addAll)
            resetInput()
        }
    }

    override fun onStop() {
        ownsWindowFocus = false
        sourcesAwaitingNeutral.clear()
        resetInput()
        super.onStop()
    }
}
