package com.limelight.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends DialogPreference
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";
    private static final String TAG = "SeekBarPreference";

    private SeekBar seekBar;
    private TextView valueText;
    private final Context context;
    private static final int LONG_PRESS_DELAY = 400; // 长按延迟 400ms
    private static final int LONG_PRESS_INTERVAL = 80; // 长按后每 80ms 触发一次

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;
    private boolean isLogarithmic = false;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        // Read the message from XML
        int dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        if (dialogMessageId == 0) {
            dialogMessage = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage");
        }
        else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        
        // 检查是否为码率设置
        String key = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "key");
        if (key != null && key.equals(PreferenceConfiguration.BITRATE_PREF_STRING)) {
            isLogarithmic = true;
        }
    }
    
    // 将线性滑块值转换为对数刻度值
    private int linearToLog(int linearValue) {
        if (linearValue <= minValue) return minValue;
        
        double minLog = Math.log(minValue);
        double maxLog = Math.log(maxValue);
        double normalizedValue = (linearValue - minValue) / (double)(maxValue - minValue);
        double logValue = Math.exp(minLog + normalizedValue * (maxLog - minLog));
        int result = (int) Math.round(logValue);
        result = Math.max(minValue, Math.min(maxValue, result));
        
        // 使用四舍五入取整到步长倍数（避免向上取整导致累积增加）
        return Math.round((float)result / stepSize) * stepSize;
    }
    
    // 将对数刻度值转换回线性滑块值
    private int logToLinear(int logValue) {
        if (logValue <= minValue) return minValue;
        double minLog = Math.log(minValue);
        double maxLog = Math.log(maxValue);
        double normalizedValue = (Math.log(logValue) - minLog) / (maxLog - minLog);
        double linearValue = minValue + normalizedValue * (maxValue - minValue);
        return (int) Math.round(linearValue);
    }
    
    // 格式化显示值
    private String formatDisplayValue(int value) {
        if (divisor != 1) {
            double displayValue = value / (double)divisor;
            return String.format((Locale)null, "%.1f", displayValue);
        } else {
            return String.valueOf(value);
        }
    }

    @Override
    protected View onCreateDialogView() {

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        // 创建水平布局容器，包含数值文本和加减号按钮
        LinearLayout valueContainer = new LinearLayout(context);
        valueContainer.setOrientation(LinearLayout.HORIZONTAL);
        valueContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        valueContainer.setPadding(0, 10, 0, 10);

        // 数值文本
        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER);
        valueText.setTextSize(32);
        if (isLogarithmic) {
            valueText.setMinWidth(dpToPx(120));
        }
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0%");
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f); // 占据剩余空间
        valueText.setLayoutParams(valueParams);
        valueContainer.addView(valueText);

        Button minusButton = null;
        Button plusButton = null;

        // 如果是码率设置，添加加号减号按钮
        if (isLogarithmic) {
            // 减号按钮
            minusButton = new Button(context);
            minusButton.setText("−");
            minusButton.setTextSize(24);
            minusButton.setGravity(Gravity.CENTER);
            minusButton.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams minusParams = new LinearLayout.LayoutParams(
                    dpToPx(48),
                    dpToPx(48));
            minusParams.rightMargin = dpToPx(8);
            minusParams.gravity = Gravity.CENTER_VERTICAL;
            minusButton.setLayoutParams(minusParams);
            setupLongPressButton(minusButton, -1);

            // 加号按钮
            plusButton = new Button(context);
            plusButton.setText("+");
            plusButton.setTextSize(24);
            plusButton.setGravity(Gravity.CENTER);
            plusButton.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(
                    dpToPx(48),
                    dpToPx(48));
            plusParams.gravity = Gravity.CENTER_VERTICAL;
            plusButton.setLayoutParams(plusParams);
            setupLongPressButton(plusButton, 1);
            
            valueContainer.addView(minusButton);
            valueContainer.addView(plusButton);
        }
        
        // 将容器添加到主布局
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueContainer, params);

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                if (value < minValue) {
                    seekBar.setProgress(minValue);
                    return;
                }

                // 对于码率设置，不对线性值取整（线性值只是中间值，取整会影响码率精度）
                // 对于非码率设置，需要取整到步长倍数
                if (!isLogarithmic) {
                    int roundedValue = Math.round((float)value / stepSize) * stepSize;
                    roundedValue = Math.max(minValue, roundedValue);
                    if (roundedValue != value) {
                        seekBar.setProgress(roundedValue);
                        return;
                    }
                }
                
                // 如果是码率设置，应用对数变换
                int displayValue = value;
                if (isLogarithmic) {
                    displayValue = linearToLog(value);
                }

                // 使用优化的格式化方法
                String t = formatDisplayValue(displayValue);
                valueText.setText(suffix == null ? t : t.concat(suffix.length() > 1 ? " "+suffix : suffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        
        // 如果是码率设置，将对数值转换为线性值显示
        if (isLogarithmic && currentValue > 0) {
            seekBar.setProgress(logToLinear(currentValue));
        } else {
            seekBar.setProgress(currentValue);
        }

        return layout;
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        
        // 如果是码率设置，将对数值转换为线性值显示
        if (isLogarithmic && currentValue > 0) {
            seekBar.setProgress(logToLinear(currentValue));
        } else {
            seekBar.setProgress(currentValue);
        }
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue)
    {
        super.onSetInitialValue(restore, defaultValue);
        if (restore) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = (Integer) defaultValue;
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            if (isLogarithmic && progress > 0) {
                seekBar.setProgress(logToLinear(progress));
            } else {
                seekBar.setProgress(progress);
            }
        }
    }
    
    public int getProgress() {
        return currentValue;
    }
    
    // dp 转 px 辅助方法
    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    // 设置长按按钮（每个按钮独立的 Handler 和状态）
    private void setupLongPressButton(Button button, int direction) {
        // 使用数组包装，使其在 lambda 中可修改
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] isLongPressing = {false};
        final Runnable[] repeatRunnable = {null};
        
        // 重复执行的 Runnable
        repeatRunnable[0] = new Runnable() {
            @Override
            public void run() {
                if (isLongPressing[0]) {
                    adjustValue(direction);
                    handler.postDelayed(this, LONG_PRESS_INTERVAL);
                }
            }
        };

        button.setOnClickListener(v -> {
            // 单击：仅在非长按时触发
            if (!isLongPressing[0]) {
                adjustValue(direction);
            }
        });

        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressing[0] = false;
                    // 延迟后开始长按，并立即执行第一次
                    handler.postDelayed(() -> {
                        isLongPressing[0] = true;
                        adjustValue(direction); // 长按触发后立即执行第一次
                        handler.postDelayed(repeatRunnable[0], LONG_PRESS_INTERVAL);
                    }, LONG_PRESS_DELAY);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 停止长按
                    handler.removeCallbacksAndMessages(null);
                    isLongPressing[0] = false;
                    break;
            }
            return false; // 返回 false 以允许 onClick 事件
        });
    }

    // 调整数值（+1 或 -1）
    private void adjustValue(int direction) {
        if (seekBar == null) return;

        int currentProgress = seekBar.getProgress();
        int newProgress;

        if (isLogarithmic) {
            // 对于码率设置，需要先转换为实际码率值，调整后再转回线性值
            int currentBitrate = linearToLog(currentProgress);

            // 计算调整步长（根据当前码率值动态调整）
            int adjustStep = stepSize;
            if (currentBitrate > 50000) {
                // 高码率时使用更大的步长
                adjustStep = stepSize * 2;
            }

            int newBitrate = currentBitrate + (direction * adjustStep);
            newBitrate = Math.max(minValue, Math.min(maxValue, newBitrate));

            // 转回线性值
            newProgress = logToLinear(newBitrate);
        } else {
            // 非码率设置，直接调整线性值
            newProgress = currentProgress + (direction * stepSize);
            newProgress = Math.max(minValue, Math.min(maxValue, newProgress));
        }

        // 更新滑块位置（这会触发 onProgressChanged，自动更新显示）
        // 注意：对于码率设置，onProgressChanged 中不会对线性值取整，
        // 所以不会触发额外的调整，只是更新显示
        seekBar.setProgress(newProgress);
    }

    @Override
    public void showDialog(Bundle state) {
        super.showDialog(state);

        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (shouldPersist()) {
                        int valueToSave = seekBar.getProgress();
                        
                        // 如果是码率设置，保存对数变换后的值
                        if (isLogarithmic) {
                            valueToSave = linearToLog(valueToSave);
                        }
                        
                        currentValue = valueToSave;
                        persistInt(valueToSave);
                        callChangeListener(valueToSave);
                    }

                    getDialog().dismiss();
                }
            });
        }
    }
}
