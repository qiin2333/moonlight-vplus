package com.limelight.preferences

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet

import androidx.preference.DialogPreference

import com.limelight.R

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

class SeekBarPreference(context: Context, attrs: AttributeSet) : DialogPreference(context, attrs) {

    val dialogMessageText: String?
    val suffix: String?
    val defaultValue: Int
    val maxValue: Int
    val minValue: Int
    val stepSize: Int
    val keyStepSize: Int
    val divisor: Int
    val isLogarithmic: Boolean
    val showTickMarks: Boolean
    var currentValue: Int = 0

    // 缓存对数计算值
    var minLog: Double = 0.0
    var maxLog: Double = 0.0
    var logRange: Double = 0.0
    var linearRange: Double = 0.0

    init {
        dialogMessageText = getStringAttribute(context, attrs, ANDROID_SCHEMA_URL, "dialogMessage")
        suffix = getStringAttribute(context, attrs, ANDROID_SCHEMA_URL, "text")

        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue",
            PreferenceConfiguration.getDefaultBitrate(context))
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100)
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1)
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1)
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1)
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0)

        showTickMarks = attrs.getAttributeBooleanValue(SEEKBAR_SCHEMA_URL, "tickMarks", false)

        val key = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "key")
        isLogarithmic = PreferenceConfiguration.BITRATE_PREF_STRING == key

        if (isLogarithmic) {
            minLog = ln(minValue.toDouble())
            maxLog = ln(maxValue.toDouble())
            logRange = maxLog - minLog
            linearRange = (maxValue - minValue).toDouble()
        }

        setDefaultValue(defaultValue)
        setDialogLayoutResource(R.layout.pref_seekbar_dialog)
    }

    fun linearToLog(linearValue: Int): Int {
        if (linearValue <= minValue) return minValue
        val normalizedValue = (linearValue - minValue) / linearRange
        var result = round(exp(minLog + normalizedValue * logRange)).toInt()
        result = max(minValue, min(maxValue, result))
        return round(result.toFloat() / stepSize).toInt() * stepSize
    }

    fun logToLinear(logValue: Int): Int {
        if (logValue <= minValue) return minValue
        val normalizedValue = (ln(logValue.toDouble()) - minLog) / logRange
        return round(minValue + normalizedValue * linearRange).toInt()
    }

    fun formatDisplayValue(value: Int): String {
        return if (divisor != 1) {
            String.format(null as Locale?, "%.1f", value / divisor.toDouble())
        } else {
            value.toString()
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, defaultValue)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val def = if (defaultValue is Int) defaultValue else this.defaultValue
        currentValue = getPersistedInt(def)
    }

    fun refreshCurrentValue() {
        currentValue = getPersistedInt(defaultValue)
    }

    fun setProgress(progress: Int) {
        this.currentValue = progress
        persistInt(progress)
    }

    fun getProgress(): Int = currentValue

    companion object {
        private const val ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar"

        private fun getStringAttribute(context: Context, attrs: AttributeSet, schema: String, name: String): String? {
            val resId = attrs.getAttributeResourceValue(schema, name, 0)
            return if (resId != 0) {
                context.getString(resId)
            } else {
                attrs.getAttributeValue(schema, name)
            }
        }
    }
}
