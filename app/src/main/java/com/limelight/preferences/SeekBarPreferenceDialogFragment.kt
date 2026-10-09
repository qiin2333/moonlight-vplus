package com.limelight.preferences

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged

import com.limelight.R
import com.limelight.binding.input.isZeroControllerDeadzone
import com.limelight.utils.AppDialogStyler
import kotlin.math.roundToInt
import kotlin.math.abs
import java.util.Locale
import com.limelight.utils.UiHelper

class SeekBarPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var seekBar: SeekBar? = null
    private lateinit var valueText: TextView
    private var numberInput: EditText? = null
    private var syncingNumber = false
    private var enteredValue: Int? = null
    private var useRecommendedBitrate = false

    private val pref: SeekBarPreference
        get() = preference as SeekBarPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppDialogStyle)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        if (pref.isLogarithmic) {
            builder.setNeutralButton(R.string.title_restore_recommended_bitrate, null)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute)
        tintDialogButtons()
        val alert = dialog as? AlertDialog ?: return
        if (pref.isLogarithmic) {
            alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val recommended = PreferenceConfiguration.getDefaultBitrate(requireContext())
                syncingNumber = true
                seekBar?.progress = pref.logToLinear(recommended)
                syncingNumber = false
                updateValueText(recommended)
                numberInput?.error = null
                useRecommendedBitrate = true
            }
        }
        numberInput?.let { input ->
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = readNumber()
                if (value == null) {
                    input.error = getString(R.string.numeric_parameter_range,
                        pref.formatDisplayValue(pref.minValue), pref.formatDisplayValue(pref.maxValue),
                        pref.suffix.orEmpty())
                    input.requestFocus()
                } else {
                    enteredValue = value
                    onClick(alert, AlertDialog.BUTTON_POSITIVE)
                    dismiss()
                }
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onBindDialogView(layout: View) {
        super.onBindDialogView(layout)

        val pref = pref
        // 确保从持久化存储加载最新值
        pref.refreshCurrentValue()
        if (pref.isLogarithmic) {
            useRecommendedBitrate = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
        }

        // Message text
        val messageView = layout.findViewById<TextView>(R.id.pref_seekbar_message)
        if (pref.dialogMessageText != null) {
            messageView.text = pref.dialogMessageText
            if (pref.isLogarithmic) {
                messageView.append("\n" + getString(R.string.summary_recommended_bitrate,
                    pref.formatDisplayValue(PreferenceConfiguration.getDefaultBitrate(requireContext()))))
            }
            messageView.visibility = View.VISIBLE
        }

        // Value display
        valueText = layout.findViewById(R.id.pref_seekbar_value)
        val numeric = pref.isLogarithmic || pref.key == "seekbar_fec_percentage"
        numberInput = layout.findViewById<EditText>(R.id.pref_seekbar_input).takeIf { numeric }
        if (numeric) {
            valueText.visibility = View.GONE
            numberInput?.apply {
                visibility = View.VISIBLE
                contentDescription = pref.title
                inputType = InputType.TYPE_CLASS_NUMBER or
                    (if (pref.divisor == 1) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL)
            }
            layout.findViewById<TextView>(R.id.pref_seekbar_unit).apply {
                visibility = View.VISIBLE
                text = pref.suffix
            }
        }

        // +/- buttons (logarithmic mode only)
        val btnMinus = layout.findViewById<ImageView>(R.id.pref_seekbar_btn_minus)
        val btnPlus = layout.findViewById<ImageView>(R.id.pref_seekbar_btn_plus)
        if (numeric) {
            btnMinus.setImageResource(R.drawable.ic_pref_minus)
            btnPlus.setImageResource(R.drawable.ic_pref_plus)
            btnMinus.visibility = View.VISIBLE
            btnPlus.visibility = View.VISIBLE
            setupLongPressView(btnMinus, -1)
            setupLongPressView(btnPlus, 1)
        }

        // SeekBar
        // 当最小值为负数（如 -20dB）时使用偏移映射：progress 范围 0..(max-min)，
        // 显示值 = min + progress，保证 0 值位于滑块正中且负值可调。
        val usesOffsetRange = !pref.isLogarithmic && pref.minValue < 0
        seekBar = layout.findViewById(R.id.pref_seekbar)
        seekBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) useRecommendedBitrate = false
                // 将 progress 换算为显示值
                val displayValue = if (usesOffsetRange) value + pref.minValue else value

                if (displayValue < pref.minValue) {
                    seekBar.progress = if (usesOffsetRange) 0 else pref.minValue
                    return
                }
                if (displayValue > pref.maxValue) {
                    seekBar.progress = if (usesOffsetRange) pref.maxValue - pref.minValue else pref.maxValue
                    return
                }

                if (!pref.isLogarithmic) {
                    val roundedValue = maxOf(pref.minValue, (displayValue.toFloat() / pref.stepSize).roundToInt() * pref.stepSize)
                    val snappedProgress = if (usesOffsetRange) roundedValue - pref.minValue else roundedValue
                    if (snappedProgress != value) {
                        seekBar.progress = snappedProgress
                        return
                    }
                }

                if (!syncingNumber) {
                    updateValueText(if (pref.isLogarithmic) pref.linearToLog(displayValue) else displayValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Tick marks
        if (pref.showTickMarks && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val tickDrawable: Drawable? = requireContext().getDrawable(R.drawable.pref_seekbar_tick)
            seekBar!!.tickMark = tickDrawable
        }

        // Range labels
        val minLabel = layout.findViewById<TextView>(R.id.pref_seekbar_min_label)
        val maxLabel = layout.findViewById<TextView>(R.id.pref_seekbar_max_label)
        minLabel.text = pref.formatDisplayValue(pref.minValue)
        maxLabel.text = pref.formatDisplayValue(pref.maxValue)

        // Initialize seekbar
        // 负值域时 seekbar 最大进度为 (max-min)，进度 = 当前值 - min
        seekBar!!.max = if (!usesOffsetRange) {
            pref.maxValue
        } else {
            pref.maxValue - pref.minValue
        }
        if (pref.keyStepSize != 0) {
            seekBar!!.keyProgressIncrement = pref.keyStepSize
        }
        seekBar!!.progress = if (pref.isLogarithmic && pref.currentValue > 0) {
            pref.logToLinear(pref.currentValue)
        } else if (usesOffsetRange) {
            (pref.currentValue - pref.minValue).coerceIn(0, pref.maxValue - pref.minValue)
        } else {
            pref.currentValue
        }

        seekBar!!.post { seekBar!!.requestFocus() }
        updateValueText(pref.currentValue)
        numberInput?.doAfterTextChanged {
            if (!syncingNumber) {
                useRecommendedBitrate = false
                readNumber()?.let { value ->
                    syncingNumber = true
                    seekBar?.progress = if (pref.isLogarithmic) pref.logToLinear(value) else value
                    syncingNumber = false
                }
            }
        }
    }

    private fun updateValueText(displayValue: Int) {
        val pref = pref
        var text = pref.formatDisplayValue(displayValue)
        if (pref.suffix != null) {
            text += if (pref.suffix.length > 1) " ${pref.suffix}" else pref.suffix
        }
        valueText.text = text
        numberInput?.let { input ->
            syncingNumber = true
            val raw = if (pref.divisor == 1) displayValue.toString()
                      else String.format(Locale.ROOT, "%.3f", displayValue / pref.divisor.toDouble())
                          .trimEnd('0').trimEnd('.')
            input.setText(raw)
            syncingNumber = false
        }
    }

    private fun readNumber(): Int? {
        val raw = numberInput?.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val scaled = raw * pref.divisor
        if (!scaled.isFinite() || scaled < pref.minValue || scaled > pref.maxValue) return null
        val value = scaled.roundToInt()
        return value.takeIf { abs(scaled - value) < 0.001 }
    }

    private fun adjustValue(direction: Int) {
        val seekBar = seekBar ?: return
        val pref = pref
        useRecommendedBitrate = false

        val currentProgress = seekBar.progress
        val newProgress: Int

        if (pref.isLogarithmic) {
            val currentBitrate = pref.linearToLog(currentProgress)
            val adjustStep = if (currentBitrate > 50000) pref.stepSize * 2 else pref.stepSize
            val newBitrate = maxOf(pref.minValue, minOf(pref.maxValue, currentBitrate + direction * adjustStep))
            newProgress = pref.logToLinear(newBitrate)
        } else {
            newProgress = maxOf(pref.minValue, minOf(pref.maxValue, currentProgress + direction * pref.stepSize))
        }

        seekBar.progress = newProgress
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressView(view: View, direction: Int) {
        val handler = Handler(Looper.getMainLooper())
        val isLongPressing = booleanArrayOf(false)

        val repeatRunnable = object : Runnable {
            override fun run() {
                if (isLongPressing[0]) {
                    adjustValue(direction)
                    handler.postDelayed(this, LONG_PRESS_INTERVAL.toLong())
                }
            }
        }

        view.setOnClickListener {
            if (!isLongPressing[0]) {
                adjustValue(direction)
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressing[0] = false
                    handler.postDelayed({
                        isLongPressing[0] = true
                        adjustValue(direction)
                        handler.postDelayed(repeatRunnable, LONG_PRESS_INTERVAL.toLong())
                    }, LONG_PRESS_DELAY.toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    isLongPressing[0] = false
                }
            }
            false
        }
    }

    private fun tintDialogButtons() {
        val alert = dialog as? AlertDialog ?: return
        AppDialogStyler.tintTitle(alert, requireContext())
        AppDialogStyler.installDismissKeys(alert)
        val accentColor = UiHelper.accentColor(requireContext())
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { buttonId ->
                alert.getButton(buttonId)?.setTextColor(accentColor)
            }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult && seekBar != null) {
            val pref = pref
            val valueToSave = when {
                enteredValue != null -> enteredValue!!
                pref.isLogarithmic -> pref.linearToLog(seekBar!!.progress)
                !pref.isLogarithmic && pref.minValue < 0 -> seekBar!!.progress + pref.minValue
                else -> seekBar!!.progress
            }
            if (persistValue(pref, valueToSave) &&
                isZeroControllerDeadzone(pref.key, valueToSave)
            ) {
                Toast.makeText(
                    requireContext(),
                    R.string.toast_zero_deadzone_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun persistValue(pref: SeekBarPreference, value: Int): Boolean {
        if (!pref.callChangeListener(value)) return false
        if (pref.isLogarithmic) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                putBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, useRecommendedBitrate)
            }
        }
        pref.setProgress(value)
        return true
    }

    companion object {
        private const val LONG_PRESS_DELAY = 400
        private const val LONG_PRESS_INTERVAL = 80

        fun newInstance(key: String): SeekBarPreferenceDialogFragment {
            return SeekBarPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply {
                    putString(ARG_KEY, key)
                }
            }
        }
    }
}
