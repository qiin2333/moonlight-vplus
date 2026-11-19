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
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.view.WindowManager
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.ui.UiDialogKeyHandler
import com.limelight.utils.AppDialogStyler

/** Only the configuration editor opens this picker; executing a bound action never does. */
class CrownConfigPickerDialog(
    context: Context,
    configChoices: List<CrownConfigChoice>,
    private val readAxes: (MotionEvent) -> Pair<List<Pair<Float, Float>>, Float>,
    private val onSelected: (CrownConfigChoice) -> Unit
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
        tag = "crown_picker_list"
        divider = null
        dividerHeight = 0
        clipToPadding = false
        setPadding(dp(12), dp(4), dp(12), dp(4))
        // Rows draw their own selection state; the default focus highlight would double-draw.
        setSelector(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Rows stay focusable but not clickable: ListView keeps the focus, moves
        // its selection on dpad/stick input (mirrored into the row highlight by
        // the selection listener below), and still owns taps for the item click
        // listener because the rows themselves don't consume them.
        adapter = ConfigChoiceAdapter(context, configChoices) { position -> select(position) }
        // Rows are not clickable, so ListView routes taps here.
        setOnItemClickListener { _, _, position, _ -> select(position) }
        // Gamepad/axis navigation moves the ListView selection; mirror it into the row state.
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != AdapterView.INVALID_POSITION) syncSelection(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Row highlighted as the pending choice; confirmed by the positive button. */
    private var selectedPosition = configChoices.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
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
        // Design L7: state the consequence up front, before the user picks a row.
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setPadding(dp(24), dp(2), dp(24), dp(6))
                setTextColor(context.resources.getColor(R.color.crown_text_secondary))
                textSize = 11f
                text = context.getString(R.string.crown_direct_config_subtitle)
            })
            addView(choices)
        }
        setView(content)
        // L7 flow: a tap only highlights the row; the positive button applies it.
        setButton(BUTTON_POSITIVE, context.getString(R.string.crown_menu_change_profile)) { _, _ ->
            confirmSelection()
        }
        setButton(BUTTON_NEGATIVE, context.getString(R.string.game_menu_cancel)) { _, _ -> cancel() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the styled title/buttons; the list is our own ListView, not the system one.
        AppDialogStyler.applyChrome(this, context)
        choices.post {
            if (isShowing) {
                choices.requestFocus()
                choices.setSelection(selectedPosition)
                (choices.adapter as? ConfigChoiceAdapter)?.let { adapter ->
                    adapter.highlightedPosition = selectedPosition
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Match the L7 design width while retaining usable margins on phones
        // and in split-screen windows.
        val width = minOf(dp(366),
            (context.resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)))
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private class ConfigChoiceAdapter(
        private val context: Context,
        private val choices: List<CrownConfigChoice>,
        private val onSelect: (Int) -> Unit
    ) : BaseAdapter() {
        /** Mirrors the dialog's pending selection so rows can highlight themselves. */
        var highlightedPosition = -1

        override fun getCount(): Int = choices.size

        override fun getItem(position: Int): CrownConfigChoice = choices[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root = (convertView as? LinearLayout) ?: createRow()
            val choice = getItem(position)
            val selected = position == highlightedPosition
            root.background = context.resources.getDrawable(
                if (selected) R.drawable.crown_picker_row_selected_bg
                else R.drawable.crown_picker_row_normal_bg
            )
            (root.getChildAt(0) as? CrownThumbnailView)?.bind(choice.preview)
            (root.getChildAt(1) as? LinearLayout)?.apply {
                (getChildAt(0) as? TextView)?.text = choice.name
                (getChildAt(1) as? TextView)?.text = context.getString(
                    R.string.crown_store_detail_element_count,
                    choice.elementCount
                ).let { base ->
                    if (choice.isCurrent) {
                        "$base · ${context.getString(R.string.crown_direct_config_current)}"
                    } else {
                        base
                    }
                }
            }
            (root.getChildAt(2) as? TextView)?.apply {
                text = if (choice.isCurrent || selected) "✓" else ""
                setTextColor(context.resources.getColor(R.color.crown_on_accent))
                background = context.resources.getDrawable(
                    if (choice.isCurrent || selected) R.drawable.crown_picker_check_on_bg
                    else R.drawable.crown_picker_check_off_bg
                )
                visibility = if (choice.isCurrent || selected) View.VISIBLE else View.INVISIBLE
            }
            root.alpha = if (choice.isSelectable) 1f else 0.72f
            return root
        }

        private fun createRow(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(8), dp(8), dp(10), dp(8))
            // Focusable so gamepad/key navigation moves between rows (ListView
            // reports the focused row as the selected position, also in touch
            // mode); NOT clickable, so ListView still owns taps and forwards
            // them to its item click listener.
            isFocusable = true
            isClickable = false
            addView(CrownThumbnailView(context), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(11), 0, dp(8), 0)
                addView(TextView(context).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(context.resources.getColor(R.color.crown_text_primary))
                    textSize = 13f
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(TextView(context).apply {
                    maxLines = 1
                    setTextColor(context.resources.getColor(R.color.crown_text_secondary))
                    textSize = 11f
                    setPadding(0, dp(2), 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                gravity = android.view.Gravity.CENTER
                textSize = 10f
                setTextColor(context.resources.getColor(R.color.crown_on_accent))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
        }

        private fun dp(value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    private fun select(position: Int) {
        val adapter = choices.adapter as? ConfigChoiceAdapter ?: return
        if (position !in 0 until adapter.count) return
        if (!adapter.getItem(position).isSelectable) return
        adapter.highlightedPosition = position
        adapter.notifyDataSetChanged()
        choices.setSelection(position)
    }

    private fun syncSelection(position: Int) {
        val adapter = choices.adapter as? ConfigChoiceAdapter ?: return
        if (position !in 0 until adapter.count) return
        if (adapter.highlightedPosition == position) return
        adapter.highlightedPosition = position
        adapter.notifyDataSetChanged()
    }

    private fun confirmSelection() {
        val adapter = choices.adapter as? ConfigChoiceAdapter ?: return
        // The ListView's live selection is the source of truth: it tracks dpad
        // and stick moves synchronously, while highlightedPosition only mirrors
        // it for rendering and can lag behind within the same frame.
        val position = if (choices.selectedItemPosition in 0 until adapter.count) {
            choices.selectedItemPosition
        } else if (adapter.highlightedPosition in 0 until adapter.count) {
            adapter.highlightedPosition
        } else {
            return
        }
        if (position !in 0 until adapter.count) return
        val choice = adapter.getItem(position)
        if (!choice.isSelectable) return
        // Confirming the active profile is a no-op; keep the dialog's state unchanged.
        if (choice.isCurrent) {
            dismiss()
            return
        }
        dismiss()
        onSelected(choice)
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
                    confirmSelection()
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

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

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
