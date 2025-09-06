package com.limelight.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.ComposeShader;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.graphics.Canvas;

import androidx.annotation.NonNull;

/**
 * 一个功能强大的颜色选择器对话框，模仿了桌面应用中的常见样式。
 */
public class ColorPickerDialog extends Dialog {

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    private final int initialColor;
    private final boolean showAlphaSlider;
    private final OnColorSelectedListener listener;

    private SaturationValueView svView;
    private HueSlider hueSlider;
    private View colorPreview;
    private EditText hexInput;
    private SeekBar alphaSeekBar, redSeekBar, greenSeekBar, blueSeekBar;
    private EditText alphaValue, redValue, greenValue, blueValue;

    private float[] currentHsv = new float[3];
    private int currentAlpha;
    private boolean isUpdatingFromInput = false;

    public ColorPickerDialog(@NonNull Context context, int initialColor, boolean showAlphaSlider, OnColorSelectedListener listener) {
        super(context);
        this.initialColor = initialColor;
        this.showAlphaSlider = showAlphaSlider;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Color.colorToHSV(initialColor, currentHsv);
        currentAlpha = Color.alpha(initialColor);

        // --- 主布局：水平方向 ---
        LinearLayout masterLayout = new LinearLayout(getContext());
        masterLayout.setOrientation(LinearLayout.HORIZONTAL);
        masterLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        masterLayout.setGravity(Gravity.CENTER_VERTICAL);

        // -- 左侧：颜色选择器核心 --
        LinearLayout pickerLayout = new LinearLayout(getContext());
        pickerLayout.setOrientation(LinearLayout.HORIZONTAL);
        svView = new SaturationValueView(getContext());
        hueSlider = new HueSlider(getContext());

        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        svParams.rightMargin = dpToPx(16);
        pickerLayout.addView(svView, svParams);

        LinearLayout.LayoutParams hueParams = new LinearLayout.LayoutParams(dpToPx(24), ViewGroup.LayoutParams.MATCH_PARENT);
        pickerLayout.addView(hueSlider, hueParams);

        LinearLayout.LayoutParams pickerLayoutParams = new LinearLayout.LayoutParams(0, dpToPx(220), 2f);
        pickerLayoutParams.rightMargin = dpToPx(16);
        masterLayout.addView(pickerLayout, pickerLayoutParams);

        // -- 右侧：控件和预览 (使用 ScrollView 包裹) --
        ScrollView controlsScrollView = new ScrollView(getContext());
        LinearLayout controlsLayout = new LinearLayout(getContext());
        controlsLayout.setOrientation(LinearLayout.VERTICAL);

        // -- 预览和 Hex 输入 --
        LinearLayout previewHexLayout = new LinearLayout(getContext());
        previewHexLayout.setOrientation(LinearLayout.HORIZONTAL);
        previewHexLayout.setGravity(Gravity.CENTER_VERTICAL);
        previewHexLayout.setPadding(0, 0, 0, dpToPx(16));

        colorPreview = new View(getContext());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40));
        previewHexLayout.addView(colorPreview, previewParams);

        LinearLayout hexContainer = new LinearLayout(getContext());
        hexContainer.setOrientation(LinearLayout.VERTICAL);
        hexContainer.setPadding(dpToPx(8), 0, 0, 0);

        TextView hexLabel = new TextView(getContext());
        hexLabel.setText("Hex:");
        hexLabel.setTextSize(14);
        hexContainer.addView(hexLabel);

        hexInput = new EditText(getContext());
        hexInput.setSingleLine(true);
        hexInput.setMinEms(9);
        hexContainer.addView(hexInput);

        previewHexLayout.addView(hexContainer);
        controlsLayout.addView(previewHexLayout);

        // -- RGBA 滑块 --
        if (showAlphaSlider) {
            controlsLayout.addView(createSliderRow("A:", 0, 255));
        }
        controlsLayout.addView(createSliderRow("R:", 1, 255));
        controlsLayout.addView(createSliderRow("G:", 2, 255));
        controlsLayout.addView(createSliderRow("B:", 3, 255));

        // -- 确定/取消按钮 --
        LinearLayout buttonLayout = new LinearLayout(getContext());
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.END);
        buttonLayout.setPadding(0, dpToPx(16), 0, 0);

        Button cancelButton = new Button(getContext());
        cancelButton.setText("取消");
        cancelButton.setOnClickListener(v -> dismiss());

        Button okButton = new Button(getContext());
        okButton.setText("确定");
        okButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onColorSelected(getColor());
            }
            dismiss();
        });

        buttonLayout.addView(cancelButton);
        buttonLayout.addView(okButton);
        controlsLayout.addView(buttonLayout);

        controlsScrollView.addView(controlsLayout);

        LinearLayout.LayoutParams controlsLayoutParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        masterLayout.addView(controlsScrollView, controlsLayoutParams);

        setContentView(masterLayout);

        setupListeners();
        updateAllComponents(initialColor);
    }

    private View createSliderRow(String label, int componentIndex, int max) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(4), 0, dpToPx(4));

        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setMinWidth(dpToPx(20));
        row.addView(tvLabel);

        SeekBar seekBar = new SeekBar(getContext());
        seekBar.setMax(max);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seekParams.leftMargin = dpToPx(8);
        seekParams.rightMargin = dpToPx(8);
        row.addView(seekBar, seekParams);

        EditText valueInput = new EditText(getContext());
        valueInput.setText("0");
        valueInput.setMaxLines(1);
        valueInput.setMinWidth(dpToPx(40));
        valueInput.setGravity(Gravity.CENTER);
        row.addView(valueInput);

        switch (componentIndex) {
            case 0: alphaSeekBar = seekBar; alphaValue = valueInput; break;
            case 1: redSeekBar = seekBar; redValue = valueInput; break;
            case 2: greenSeekBar = seekBar; greenValue = valueInput; break;
            case 3: blueSeekBar = seekBar; blueValue = valueInput; break;
        }
        return row;
    }


    private void updateFromRgb() {
        // 1. 从滑块获取最新的 A, R, G, B 值
        int a = showAlphaSlider ? alphaSeekBar.getProgress() : 255;
        int r = redSeekBar.getProgress();
        int g = greenSeekBar.getProgress();
        int b = blueSeekBar.getProgress();
        int color = Color.argb(a, r, g, b);

        // 2. 更新内部状态变量
        currentAlpha = a;
        Color.colorToHSV(color, currentHsv);

        updatePreview(color);

        // 更新饱和度/亮度面板
        svView.setHue(currentHsv[0]);
        svView.setSatVal(currentHsv[1], currentHsv[2]);

        // 更新色相滑块
        hueSlider.setHue(currentHsv[0]);

        // 更新Hex输入框
        updateHexInput(color);
    }

    private void setupListeners() {
        svView.setListener(this::updateFromSv);

        hueSlider.setListener(hue -> {
            currentHsv[0] = hue;
            svView.setHue(hue);
            updateFromHsv();
        });

        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isUpdatingFromInput) return;
                try {
                    String hexString = s.toString();
                    if (!hexString.startsWith("#")) {
                        hexString = "#" + hexString;
                    }
                    int color = Color.parseColor(hexString);
                    if (s.length() <= 7) { // 6位hex
                        color = (currentAlpha << 24) | (color & 0x00FFFFFF);
                    }
                    updateAllComponents(color);
                } catch (IllegalArgumentException e) {
                    // 无效的 hex
                }
            }
        });

        setupComponentListeners(redSeekBar, redValue, 1);
        setupComponentListeners(greenSeekBar, greenValue, 2);
        setupComponentListeners(blueSeekBar, blueValue, 3);
        if (showAlphaSlider) {
            setupComponentListeners(alphaSeekBar, alphaValue, 0);
        }
    }

    private void setupComponentListeners(SeekBar seekBar, EditText editText, int componentIndex) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    isUpdatingFromInput = true;
                    editText.setText(String.valueOf(progress));
                    editText.setSelection(editText.getText().length());
                    updateFromRgb();
                    isUpdatingFromInput = false;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isUpdatingFromInput) return;
                try {
                    int value = Integer.parseInt(s.toString());
                    if (value >= 0 && value <= 255) {
                        isUpdatingFromInput = true;
                        seekBar.setProgress(value);
                        updateFromRgb();
                        isUpdatingFromInput = false;
                    }
                } catch (NumberFormatException e) {
                    // 无效数字
                }
            }
        });
    }

    private void updateFromSv(float sat, float val) {
        currentHsv[1] = sat;
        currentHsv[2] = val;
        updateFromHsv();
    }

    private void updateFromHsv() {
        int color = Color.HSVToColor(currentAlpha, currentHsv);
        if (isUpdatingFromInput) return;
        isUpdatingFromInput = true;
        updateRgbControls(color);
        updateHexInput(color);
        updatePreview(color);
        isUpdatingFromInput = false;
    }

    private void updateAllComponents(int color) {
        Color.colorToHSV(color, currentHsv);
        currentAlpha = Color.alpha(color);

        isUpdatingFromInput = true;
        updateRgbControls(color);
        updateHexInput(color);
        updatePreview(color);
        svView.setHue(currentHsv[0]);
        svView.setSatVal(currentHsv[1], currentHsv[2]);
        hueSlider.setHue(currentHsv[0]);
        isUpdatingFromInput = false;
    }

    private void updateRgbControls(int color) {
        if (showAlphaSlider) {
            alphaSeekBar.setProgress(Color.alpha(color));
            alphaValue.setText(String.valueOf(Color.alpha(color)));
        }
        redSeekBar.setProgress(Color.red(color));
        redValue.setText(String.valueOf(Color.red(color)));
        greenSeekBar.setProgress(Color.green(color));
        greenValue.setText(String.valueOf(Color.green(color)));
        blueSeekBar.setProgress(Color.blue(color));
        blueValue.setText(String.valueOf(Color.blue(color)));
    }

    private void updateHexInput(int color) {
        String hex = showAlphaSlider ?
                String.format("#%08x", color) :
                String.format("#%06x", (0xFFFFFF & color));
        hexInput.setText(hex);
    }

    private void updatePreview(int color) {
        colorPreview.setBackgroundColor(color);
    }

    private int getColor() {
        return Color.HSVToColor(currentAlpha, currentHsv);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class SaturationValueView extends View {
        private Paint satValPaint, strokePaint, selectorPaint;
        private float hue, saturation, value = 1f;
        private float selectorX, selectorY;
        private OnSVChangedListener listener;

        interface OnSVChangedListener {
            void onColorChanged(float sat, float val);
        }

        public SaturationValueView(Context context) {
            super(context);
            satValPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStrokeWidth(dpToPx(2));
            selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        public void setListener(OnSVChangedListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateShaders();
            updateSelectorPosition();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), satValPaint);

            selectorPaint.setColor(Color.HSVToColor(new float[]{hue, saturation, value}));
            canvas.drawCircle(selectorX, selectorY, dpToPx(8), selectorPaint);
            canvas.drawCircle(selectorX, selectorY, dpToPx(8), strokePaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    selectorX = Math.max(0, Math.min(getWidth(), event.getX()));
                    selectorY = Math.max(0, Math.min(getHeight(), event.getY()));
                    saturation = selectorX / getWidth();
                    value = 1 - (selectorY / getHeight());
                    if (listener != null) {
                        listener.onColorChanged(saturation, value);
                    }
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        public void setHue(float hue) {
            this.hue = hue;
            updateShaders();
            invalidate();
        }

        public void setSatVal(float sat, float val) {
            this.saturation = sat;
            this.value = val;
            updateSelectorPosition();
            invalidate();
        }

        private void updateShaders() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            int pureColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
            Shader saturationShader = new LinearGradient(0, 0, getWidth(), 0, Color.WHITE, pureColor, Shader.TileMode.CLAMP);
            Shader valueShader = new LinearGradient(0, 0, 0, getHeight(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
            satValPaint.setShader(new ComposeShader(valueShader, saturationShader, PorterDuff.Mode.SRC_OVER));
        }

        private void updateSelectorPosition() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            selectorX = saturation * getWidth();
            selectorY = (1 - value) * getHeight();
        }

        private int dpToPx(int dp) {
            return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    private static class HueSlider extends View {
        private Paint paint;
        private Shader shader;
        private float hue;
        private float selectorY;
        private OnHueChangedListener listener;

        interface OnHueChangedListener {
            void onHueChanged(float hue);
        }

        public HueSlider(Context context) {
            super(context);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        public void setListener(OnHueChangedListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int[] hueColors = new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED};
            shader = new LinearGradient(0, 0, 0, h, hueColors, null, Shader.TileMode.CLAMP);
            updateSelectorPosition();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setShader(shader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpToPx(2));
            paint.setColor(Color.WHITE);

            float selectorRadius = getWidth() / 2f * 0.8f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            canvas.drawCircle(getWidth() / 2f, selectorY, selectorRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(getWidth() / 2f, selectorY, selectorRadius, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    selectorY = Math.max(0, Math.min(getHeight(), event.getY()));
                    hue = (selectorY / getHeight()) * 360f;
                    if (hue >= 360f) hue = 359.9f;
                    if (listener != null) {
                        listener.onHueChanged(hue);
                    }
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        public void setHue(float hue) {
            this.hue = hue;
            updateSelectorPosition();
            invalidate();
        }

        private void updateSelectorPosition() {
            if (getHeight() > 0) {
                selectorY = (hue / 360f) * getHeight();
            }
        }

        private int dpToPx(int dp) {
            return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}