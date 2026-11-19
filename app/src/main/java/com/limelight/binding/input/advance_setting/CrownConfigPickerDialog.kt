package com.limelight.binding.input.advance_setting

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.view.WindowManager
import com.limelight.R
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.ui.UiDialogKeyHandler
import com.limelight.utils.AppDialogStyler

/** Only the configuration editor opens this picker; executing a bound action never does. */
class CrownConfigPickerDialog(
    context: Context,
    configChoices: List<ConfigChoice>,
    private val readAxes: (MotionEvent) -> Pair<List<Pair<Float, Float>>, Float>,
    private val onSelected: (Int) -> Unit
) : AlertDialog(context, R.style.AppDialogStyle) {
    data class ConfigChoice(
        val name: String,
        val elementCount: Int,
        val preview: CrownProfileShareManager.ProfilePreview?,
        val configId: Long = -1L,
        val isCurrent: Boolean = false,
        val isSelectable: Boolean = true
    )

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
        divider = null
        dividerHeight = 0
        clipToPadding = false
        setPadding(dp(8), dp(4), dp(8), dp(4))
        adapter = ConfigChoiceAdapter(context, configChoices)
        setOnItemClickListener { _, _, position, _ ->
            val choice = (adapter as? ConfigChoiceAdapter)?.getItem(position)
            if (choice?.isSelectable != true) return@setOnItemClickListener
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
        private val choices: List<ConfigChoice>
    ) : BaseAdapter() {
        override fun getCount(): Int = choices.size

        override fun getItem(position: Int): ConfigChoice = choices[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root = (convertView as? LinearLayout) ?: createRow()
            val choice = getItem(position)
            (root.getChildAt(0) as? CrownChoiceThumbnailView)?.bind(choice.preview)
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
                text = if (choice.isCurrent) "✓" else ""
                setTextColor(context.resources.getColor(R.color.crown_text_primary))
                alpha = if (choice.isCurrent) 1f else 0f
            }
            root.alpha = if (choice.isSelectable) 1f else 0.72f
            return root
        }

        private fun createRow(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = true
            isClickable = true
            background = context.resources.getDrawable(R.drawable.crown_config_action_button_bg)
            addView(CrownChoiceThumbnailView(context), LinearLayout.LayoutParams(dp(102), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(context).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(context.resources.getColor(R.color.crown_text_primary))
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    maxLines = 1
                    setTextColor(context.resources.getColor(R.color.crown_text_secondary))
                    textSize = 11f
                    setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setTextColor(context.resources.getColor(R.color.crown_text_primary))
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        private fun dp(value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    private class CrownChoiceThumbnailView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var preview: CrownProfileShareManager.ProfilePreview? = null

        fun bind(value: CrownProfileShareManager.ProfilePreview?) {
            preview = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val radius = 8f * density
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = Color.rgb(28, 31, 39)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)

            val value = preview ?: return
            if (value.isEmpty) return
            val scale = minOf(
                width / value.widthPx.toFloat().coerceAtLeast(1f),
                height / value.heightPx.toFloat().coerceAtLeast(1f)
            )
            val left = (width - value.widthPx * scale) / 2f
            val top = (height - value.heightPx * scale) / 2f
            value.elements.forEach { element ->
                val x = left + (element.centerX - element.width / 2f) * scale
                val y = top + (element.centerY - element.height / 2f) * scale
                val elementWidth = (element.width * scale).coerceAtLeast(2f)
                val elementHeight = (element.height * scale).coerceAtLeast(2f)
                paint.alpha = (element.alpha * 220).toInt().coerceIn(20, 220)
                paint.color = element.fillColor
                when (element.kind) {
                    CrownProfileShareManager.PreviewElementKind.STICK -> {
                        canvas.drawCircle(
                            x + elementWidth / 2f,
                            y + elementHeight / 2f,
                            minOf(elementWidth, elementHeight) / 2f,
                            paint
                        )
                    }
                    CrownProfileShareManager.PreviewElementKind.WHEEL -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (minOf(elementWidth, elementHeight) / 5f).coerceAtLeast(1.5f)
                        canvas.drawCircle(
                            x + elementWidth / 2f,
                            y + elementHeight / 2f,
                            minOf(elementWidth, elementHeight) / 2f,
                            paint
                        )
                        paint.style = Paint.Style.FILL
                    }
                    CrownProfileShareManager.PreviewElementKind.GROUP -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (1.5f * density).coerceAtLeast(1f)
                        canvas.drawRoundRect(
                            x, y, x + elementWidth, y + elementHeight,
                            5f * density, 5f * density, paint
                        )
                        paint.style = Paint.Style.FILL
                    }
                    CrownProfileShareManager.PreviewElementKind.PAD,
                    CrownProfileShareManager.PreviewElementKind.BUTTON -> {
                        canvas.drawRoundRect(
                            x, y, x + elementWidth, y + elementHeight,
                            minOf(elementWidth, elementHeight) / 3f,
                            minOf(elementWidth, elementHeight) / 3f,
                            paint
                        )
                    }
                }
            }
            paint.alpha = 255
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
                    val choice = (choices.adapter as? ConfigChoiceAdapter)?.getItem(position)
                    if (position in 0 until choices.count && choice?.isSelectable == true) {
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
