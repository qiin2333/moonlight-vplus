package com.limelight.binding.input.advance_setting.superpage

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import com.limelight.R

class NumberSeekbar : LinearLayout {

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
    private var onNumberSeekbarChangeListener: OnNumberSeekbarChangeListener? = null
    private var minimumValue = 0
    private var correctingProgress = false

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
        minimumValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) numberSeekbarSeekbar.min else 0
        numberSeekbarNumber.text = numberSeekbarSeekbar.progress.toString()

        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.NumberSeekbar, 0, 0)
            try {
                val maxValue = a.getInt(R.styleable.NumberSeekbar_max, 100)
                val minValue = a.getInt(R.styleable.NumberSeekbar_min, 0)
                val progressValue = a.getInt(R.styleable.NumberSeekbar_progress, 0)
                numberSeekbarSeekbar.max = maxValue
                setProgressMin(minValue)
                numberSeekbarSeekbar.progress = progressValue.coerceAtLeast(minimumValue)
                numberSeekbarNumber.text = numberSeekbarSeekbar.progress.toString()

                val title = a.getString(R.styleable.NumberSeekbar_text)
                numberSeekbarTitle.text = title
            } finally {
                a.recycle()
            }
        }

        numberSeekbarSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (correctingProgress) return
                val value = progress.coerceAtLeast(minimumValue)
                if (value != progress) {
                    // Pre-O SeekBar has no minimum. Keep its real progress in range so callers
                    // reading SeekBar.getProgress() in stop callbacks still receive the value.
                    correctingProgress = true
                    seekBar.progress = value
                    correctingProgress = false
                }
                numberSeekbarNumber.text = value.toString()
                onNumberSeekbarChangeListener?.onProgressChanged(seekBar, value, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                onNumberSeekbarChangeListener?.onStartTrackingTouch(seekBar)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onNumberSeekbarChangeListener?.onStopTrackingTouch(seekBar)
            }
        })

        numberSeekbarMinus.setOnClickListener {
            val progress = numberSeekbarSeekbar.progress
            if (progress > minimumValue) {
                onNumberSeekbarChangeListener?.let { listener ->
                    listener.onStartTrackingTouch(numberSeekbarSeekbar)
                    numberSeekbarSeekbar.progress = progress - 1
                    listener.onStopTrackingTouch(numberSeekbarSeekbar)
                }
            }
        }

        numberSeekbarAdd.setOnClickListener {
            val progress = numberSeekbarSeekbar.progress
            if (progress < numberSeekbarSeekbar.max) {
                numberSeekbarSeekbar.progress = progress + 1
                onNumberSeekbarChangeListener?.onStopTrackingTouch(numberSeekbarSeekbar)
            }
        }
    }

    val value: Int
        get() = numberSeekbarSeekbar.progress

    fun setValueWithNoCallBack(value: Int) {
        val temp = onNumberSeekbarChangeListener
        onNumberSeekbarChangeListener = null
        numberSeekbarSeekbar.progress = value.coerceAtLeast(minimumValue)
        numberSeekbarNumber.text = numberSeekbarSeekbar.progress.toString()
        onNumberSeekbarChangeListener = temp
    }

    fun setTitle(title: String) {
        numberSeekbarTitle.text = title
    }

    fun setProgressMax(max: Int) {
        numberSeekbarSeekbar.max = max.coerceAtLeast(minimumValue)
    }

    fun setProgressMin(min: Int) {
        minimumValue = min.coerceAtMost(numberSeekbarSeekbar.max)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            numberSeekbarSeekbar.min = minimumValue
        } else if (numberSeekbarSeekbar.progress < minimumValue) {
            numberSeekbarSeekbar.progress = minimumValue
        }
        numberSeekbarNumber.text = numberSeekbarSeekbar.progress.toString()
    }

    fun setOnNumberSeekbarChangeListener(listener: OnNumberSeekbarChangeListener?) {
        this.onNumberSeekbarChangeListener = listener
    }
}
