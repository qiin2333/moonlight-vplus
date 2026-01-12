package com.limelight.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

public class SeekBarPreference extends DialogPreference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private static final int LONG_PRESS_DELAY = 400;
    private static final int LONG_PRESS_INTERVAL = 80;

    private SeekBar seekBar;
    private TextView valueText;
    private final Context context;

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private final boolean isLogarithmic;
    private int currentValue;

    // 缓存对数计算值
    private double minLog;
    private double maxLog;
    private double logRange;
    private double linearRange;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        dialogMessage = getStringAttribute(attrs, ANDROID_SCHEMA_URL, "dialogMessage");
        suffix = getStringAttribute(attrs, ANDROID_SCHEMA_URL, "text");

        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", 
                PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);

        String key = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "key");
        isLogarithmic = PreferenceConfiguration.BITRATE_PREF_STRING.equals(key);

        // 预计算对数值
        if (isLogarithmic) {
            minLog = Math.log(minValue);
            maxLog = Math.log(maxValue);
            logRange = maxLog - minLog;
            linearRange = maxValue - minValue;
        }
    }

    private String getStringAttribute(AttributeSet attrs, String schema, String name) {
        int resId = attrs.getAttributeResourceValue(schema, name, 0);
        if (resId != 0) {
            return context.getString(resId);
        }
        return attrs.getAttributeValue(schema, name);
    }

    private int linearToLog(int linearValue) {
        if (linearValue <= minValue) return minValue;

        double normalizedValue = (linearValue - minValue) / linearRange;
        int result = (int) Math.round(Math.exp(minLog + normalizedValue * logRange));
        result = Math.max(minValue, Math.min(maxValue, result));
        return Math.round((float) result / stepSize) * stepSize;
    }

    private int logToLinear(int logValue) {
        if (logValue <= minValue) return minValue;

        double normalizedValue = (Math.log(logValue) - minLog) / logRange;
        return (int) Math.round(minValue + normalizedValue * linearRange);
    }

    private String formatDisplayValue(int value) {
        if (divisor != 1) {
            return String.format((Locale) null, "%.1f", value / (double) divisor);
        }
        return String.valueOf(value);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected View onCreateDialogView() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        if (dialogMessage != null) {
            TextView splashText = new TextView(context);
            splashText.setPadding(30, 10, 30, 10);
            splashText.setText(dialogMessage);
            layout.addView(splashText);
        }

        layout.addView(createValueContainer());
        layout.addView(createSeekBar());

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        initializeSeekBar();
        return layout;
    }

    private LinearLayout createValueContainer() {
        LinearLayout valueContainer = new LinearLayout(context);
        valueContainer.setOrientation(LinearLayout.HORIZONTAL);
        valueContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        valueContainer.setPadding(0, 10, 0, 10);

        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER);
        valueText.setTextSize(32);
        valueText.setText("0%");
        if (isLogarithmic) {
            valueText.setMinWidth(dpToPx(120));
        }

        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        valueText.setLayoutParams(valueParams);
        valueContainer.addView(valueText);

        if (isLogarithmic) {
            valueContainer.addView(createAdjustButton("−", -1, true));
            valueContainer.addView(createAdjustButton("+", 1, false));
        }

        return valueContainer;
    }

    private Button createAdjustButton(String text, int direction, boolean hasRightMargin) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(24);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        params.gravity = Gravity.CENTER_VERTICAL;
        if (hasRightMargin) {
            params.rightMargin = dpToPx(8);
        }
        button.setLayoutParams(params);

        setupLongPressButton(button, direction);
        return button;
    }

    private SeekBar createSeekBar() {
        seekBar = new SeekBar(context);
        seekBar.setFocusable(true);
        seekBar.setFocusableInTouchMode(true);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (value < minValue) {
                    seekBar.setProgress(minValue);
                    return;
                }

                if (!isLogarithmic) {
                    int roundedValue = Math.max(minValue, Math.round((float) value / stepSize) * stepSize);
                    if (roundedValue != value) {
                        seekBar.setProgress(roundedValue);
                        return;
                    }
                }

                updateValueText(isLogarithmic ? linearToLog(value) : value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        return seekBar;
    }

    private void updateValueText(int displayValue) {
        String text = formatDisplayValue(displayValue);
        if (suffix != null) {
            text = text.concat(suffix.length() > 1 ? " " + suffix : suffix);
        }
        valueText.setText(text);
    }

    private void initializeSeekBar() {
        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(isLogarithmic && currentValue > 0 ? logToLinear(currentValue) : currentValue);
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        initializeSeekBar();
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue) {
        super.onSetInitialValue(restore, defaultValue);
        currentValue = restore ? (shouldPersist() ? getPersistedInt(this.defaultValue) : 0) : (Integer) defaultValue;
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            seekBar.setProgress(isLogarithmic && progress > 0 ? logToLinear(progress) : progress);
        }
    }

    public int getProgress() {
        return currentValue;
    }

    private void setupLongPressButton(Button button, int direction) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] isLongPressing = {false};

        final Runnable repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isLongPressing[0]) {
                    adjustValue(direction);
                    handler.postDelayed(this, LONG_PRESS_INTERVAL);
                }
            }
        };

        button.setOnClickListener(v -> {
            if (!isLongPressing[0]) {
                adjustValue(direction);
            }
        });

        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressing[0] = false;
                    handler.postDelayed(() -> {
                        isLongPressing[0] = true;
                        adjustValue(direction);
                        handler.postDelayed(repeatRunnable, LONG_PRESS_INTERVAL);
                    }, LONG_PRESS_DELAY);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacksAndMessages(null);
                    isLongPressing[0] = false;
                    break;
            }
            return false;
        });
    }

    private void adjustValue(int direction) {
        if (seekBar == null) return;

        int currentProgress = seekBar.getProgress();
        int newProgress;

        if (isLogarithmic) {
            int currentBitrate = linearToLog(currentProgress);
            int adjustStep = currentBitrate > 50000 ? stepSize * 2 : stepSize;
            int newBitrate = Math.max(minValue, Math.min(maxValue, currentBitrate + direction * adjustStep));
            newProgress = logToLinear(newBitrate);
        } else {
            newProgress = Math.max(minValue, Math.min(maxValue, currentProgress + direction * stepSize));
        }

        seekBar.setProgress(newProgress);
    }

    @Override
    public void showDialog(Bundle state) {
        super.showDialog(state);

        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (shouldPersist()) {
                    int valueToSave = isLogarithmic ? linearToLog(seekBar.getProgress()) : seekBar.getProgress();
                    currentValue = valueToSave;
                    persistInt(valueToSave);
                    callChangeListener(valueToSave);
                }
                dialog.dismiss();
            });

            if (seekBar != null) {
                seekBar.post(seekBar::requestFocus);
            }
        }
    }
}
