package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.os.Build;

import androidx.preference.DialogPreference;

import com.limelight.R;

import java.util.Locale;

public class SeekBarPreference extends DialogPreference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    final String dialogMessageText;
    final String suffix;
    final int defaultValue;
    final int maxValue;
    final int minValue;
    final int stepSize;
    final int keyStepSize;
    final int divisor;
    final boolean isLogarithmic;
    final boolean showTickMarks;
    int currentValue;

    // 缓存对数计算值
    double minLog;
    double maxLog;
    double logRange;
    double linearRange;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        dialogMessageText = getStringAttribute(context, attrs, ANDROID_SCHEMA_URL, "dialogMessage");
        suffix = getStringAttribute(context, attrs, ANDROID_SCHEMA_URL, "text");

        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", 
                PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);

        showTickMarks = attrs.getAttributeBooleanValue(SEEKBAR_SCHEMA_URL, "tickMarks", false);

        String key = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "key");
        isLogarithmic = PreferenceConfiguration.BITRATE_PREF_STRING.equals(key);

        if (isLogarithmic) {
            minLog = Math.log(minValue);
            maxLog = Math.log(maxValue);
            logRange = maxLog - minLog;
            linearRange = maxValue - minValue;
        }

        // 为 AndroidX 框架提供默认值
        setDefaultValue(defaultValue);
        setDialogLayoutResource(R.layout.pref_seekbar_dialog);
    }

    private static String getStringAttribute(Context context, AttributeSet attrs, String schema, String name) {
        int resId = attrs.getAttributeResourceValue(schema, name, 0);
        if (resId != 0) {
            return context.getString(resId);
        }
        return attrs.getAttributeValue(schema, name);
    }

    int linearToLog(int linearValue) {
        if (linearValue <= minValue) return minValue;
        double normalizedValue = (linearValue - minValue) / linearRange;
        int result = (int) Math.round(Math.exp(minLog + normalizedValue * logRange));
        result = Math.max(minValue, Math.min(maxValue, result));
        return Math.round((float) result / stepSize) * stepSize;
    }

    int logToLinear(int logValue) {
        if (logValue <= minValue) return minValue;
        double normalizedValue = (Math.log(logValue) - minLog) / logRange;
        return (int) Math.round(minValue + normalizedValue * linearRange);
    }

    String formatDisplayValue(int value) {
        if (divisor != 1) {
            return String.format((Locale) null, "%.1f", value / (double) divisor);
        }
        return String.valueOf(value);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, defaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        int def = defaultValue instanceof Integer ? (Integer) defaultValue : this.defaultValue;
        currentValue = getPersistedInt(def);
    }

    /**
     * 从持久化存储重新加载当前值（对话框打开前调用）
     */
    void refreshCurrentValue() {
        currentValue = getPersistedInt(defaultValue);
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        persistInt(progress);
    }

    public int getProgress() {
        return currentValue;
    }
}
