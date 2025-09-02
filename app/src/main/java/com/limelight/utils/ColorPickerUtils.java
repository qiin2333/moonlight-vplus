package com.limelight.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 一个用于显示 ARGB 颜色选择器对话框的公共工具类。
 */
public class ColorPickerUtils {

    /**
     * 当颜色被选择并确认后的回调接口。
     */
    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    // 私有构造函数，防止外部实例化这个工具类
    private ColorPickerUtils() {}

    /**
     * 创建并显示一个带有 ARGB 滑块的颜色选择器对话框。
     *
     * @param context      当前上下文。
     * @param initialColor 用于初始化滑块的初始颜色。
     * @param listener     当点击“确定”按钮时调用的监听器。
     */
    public static void show(Context context, int initialColor, final OnColorSelectedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("选择颜色");

        // 创建对话框的主布局
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 25, 40, 25);

        // 创建一个视图用于实时预览所选颜色
        final View colorPreview = new View(context);
        colorPreview.setBackgroundColor(initialColor);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 120);
        previewParams.bottomMargin = 40;
        colorPreview.setLayoutParams(previewParams);
        mainLayout.addView(colorPreview);

        // 存储当前颜色的 ARGB 分量
        final int[] currentArgb = {
                Color.alpha(initialColor),
                Color.red(initialColor),
                Color.green(initialColor),
                Color.blue(initialColor)
        };

        // 为 Alpha, Red, Green, Blue 创建滑块
        String[] labels = {"A", "R", "G", "B"};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(context);
            label.setText(labels[i]);
            row.addView(label);

            SeekBar seekBar = new SeekBar(context);
            seekBar.setMax(255);
            seekBar.setProgress(currentArgb[i]);

            TextView value = new TextView(context);
            value.setText(String.valueOf(currentArgb[i]));
            value.setMinWidth(80);
            value.setGravity(Gravity.END);

            final int index = i;
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    currentArgb[index] = progress;
                    value.setText(String.valueOf(progress));
                    colorPreview.setBackgroundColor(Color.argb(currentArgb[0], currentArgb[1], currentArgb[2], currentArgb[3]));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

            LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            seekParams.leftMargin = 20;
            seekParams.rightMargin = 20;
            row.addView(seekBar, seekParams);
            row.addView(value);

            mainLayout.addView(row);
        }

        builder.setView(mainLayout);
        builder.setPositiveButton("确定", (dialogInterface, which) -> {
            if (listener != null) {
                int finalColor = Color.argb(currentArgb[0], currentArgb[1], currentArgb[2], currentArgb[3]);
                listener.onColorSelected(finalColor);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.create().show();
    }
}