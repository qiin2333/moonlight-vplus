package com.limelight.binding.input.advance_setting.superpage

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import com.limelight.R

class NumberSeekbar : LinearLayout {

    // Progress callbacks and value expose logical values; the child SeekBar is zero-based.
    interface OnNumberSeekbarChangeListener {
        fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: SeekBar)
        fun onStopTrackingTouch(seekBar: SeekBar)
    }

    private lateinit var numberSeekbarTitle: TextView
    private lateinit var numberSeekbarMinus: android.view.View
    private lateinit var numberSeekbarNumber: TextView
    private lateinit var numberSeekbarAdd: android.view.View
    private lateinit var numberSeekbarSeekbar: SeekBar
    private var minimumValue = 0
    private var onNumberSeekbarChangeListener: OnNumberSeekbarChangeListener? = null

    constructor(context: Context) : super(context) {
        init(context, null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        LayoutInflater.from(context).inflate(R.layout.number_seekbar, this, true)

        numberSeekbarTitle = findViewById(R.id.number_seekbar_title)
        numberSeekbarMinus = findViewById(R.id.number_seekbar_minus)
        numberSeekbarNumber = findViewById(R.id.number_seekbar_number)
        numberSeekbarAdd = findViewById(R.id.number_seekbar_add)
        numberSeekbarSeekbar = findViewById(R.id.number_seekbar_seekbar)
        numberSeekbarNumber.text = numberSeekbarSeekbar.progress.toString()

        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.NumberSeekbar, 0, 0)
            try {
                val maxValue = a.getInt(R.styleable.NumberSeekbar_max, 100)
                val minValue = a.getInt(R.styleable.NumberSeekbar_min, 0)
                val progressValue = a.getInt(R.styleable.NumberSeekbar_progress, 0)
                minimumValue = minValue.coerceIn(0, maxValue.coerceAtLeast(0))
                numberSeekbarSeekbar.max = maxValue.coerceAtLeast(0) - minimumValue
                setValueWithNoCallBack(progressValue)

                val title = a.getString(R.styleable.NumberSeekbar_text)
                numberSeekbarTitle.text = title
            } finally {
                a.recycle()
            }
        }

        numberSeekbarSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val logicalValue = minimumValue + progress
                numberSeekbarNumber.text = logicalValue.toString()
                onNumberSeekbarChangeListener?.onProgressChanged(seekBar, logicalValue, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                onNumberSeekbarChangeListener?.onStartTrackingTouch(seekBar)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onNumberSeekbarChangeListener?.onStopTrackingTouch(seekBar)
            }
        })

        // Keyboard edits share the touch and +/- controls' save lifecycle.
        var changingWithKeys = false
        fun finishKeyChange() {
            if (changingWithKeys) {
                changingWithKeys = false
                onNumberSeekbarChangeListener?.onStopTrackingTouch(numberSeekbarSeekbar)
            }
        }
        numberSeekbarSeekbar.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN && numberSeekbarSeekbar.isEnabled && !changingWithKeys) {
                    changingWithKeys = true
                    onNumberSeekbarChangeListener?.onStartTrackingTouch(numberSeekbarSeekbar)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    finishKeyChange()
                }
            }
            false // Keep native stepping, bounds and key-repeat handling.
        }
        numberSeekbarSeekbar.setOnFocusChangeListener { _, focused -> if (!focused) finishKeyChange() }

        numberSeekbarMinus.setOnClickListener { changeBy(-1) }
        numberSeekbarAdd.setOnClickListener { changeBy(1) }
    }

    private fun changeBy(delta: Int) {
        val progress = numberSeekbarSeekbar.progress
        val next = (progress + delta).coerceIn(0, numberSeekbarSeekbar.max)
        if (next == progress) return
        onNumberSeekbarChangeListener?.onStartTrackingTouch(numberSeekbarSeekbar)
        numberSeekbarSeekbar.progress = next
        onNumberSeekbarChangeListener?.onStopTrackingTouch(numberSeekbarSeekbar)
    }

    val value: Int
        get() = minimumValue + numberSeekbarSeekbar.progress

    fun setValueWithNoCallBack(value: Int) {
        val temp = onNumberSeekbarChangeListener
        onNumberSeekbarChangeListener = null
        numberSeekbarSeekbar.progress = (value - minimumValue).coerceIn(0, numberSeekbarSeekbar.max)
        numberSeekbarNumber.text = this.value.toString()
        onNumberSeekbarChangeListener = temp
    }

    fun setTitle(title: String) {
        numberSeekbarTitle.text = title
    }

    fun setControlsEnabled(enabled: Boolean) {
        listOf(numberSeekbarSeekbar, numberSeekbarMinus, numberSeekbarAdd).forEach {
            it.isEnabled = enabled
            it.isFocusable = enabled
        }
        alpha = if (enabled) 1f else 0.5f
    }

    fun setProgressMax(max: Int) {
        updateRange(minimumValue, max.coerceAtLeast(minimumValue))
    }

    fun setProgressMin(min: Int) {
        updateRange(min.coerceIn(0, minimumValue + numberSeekbarSeekbar.max),
            minimumValue + numberSeekbarSeekbar.max)
    }

    /** Use the same offset on every API level; native min was only added in API 26. */
    private fun updateRange(min: Int, max: Int) {
        val previousValue = value
        val listener = onNumberSeekbarChangeListener
        onNumberSeekbarChangeListener = null
        minimumValue = min
        numberSeekbarSeekbar.max = max - min
        setValueWithNoCallBack(previousValue)
        onNumberSeekbarChangeListener = listener
        if (value != previousValue) listener?.onProgressChanged(numberSeekbarSeekbar, value, false)
    }

    fun setOnNumberSeekbarChangeListener(listener: OnNumberSeekbarChangeListener?) {
        this.onNumberSeekbarChangeListener = listener
    }
}
