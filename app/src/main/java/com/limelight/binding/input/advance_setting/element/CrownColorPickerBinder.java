package com.limelight.binding.input.advance_setting.element;

import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.utils.ColorPickerDialog;

final class CrownColorPickerBinder {
    interface ColorSupplier {
        int get();
    }

    interface ColorUpdater {
        void accept(int color);
    }

    private CrownColorPickerBinder() {
    }

    static void bind(Element owner, ElementEditText colorDisplay, ColorSupplier colorSupplier, ColorUpdater colorUpdater) {
        colorDisplay.setFocusable(false);
        colorDisplay.setCursorVisible(false);
        colorDisplay.setKeyListener(null);
        colorDisplay.setSingleLine(true);
        colorDisplay.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        colorDisplay.setPadding(dp(owner, 10), 0, dp(owner, 8), 0);
        colorDisplay.setCompoundDrawablePadding(dp(owner, 8));
        updateColorDisplay(colorDisplay, colorSupplier.get());
        colorDisplay.setOnClickListener(v -> new ColorPickerDialog(
                owner.getContext(),
                colorSupplier.get(),
                true,
                newColor -> {
                    colorUpdater.accept(newColor);
                    owner.save();
                    updateColorDisplay(colorDisplay, newColor);
                }
        ).show());
    }

    static void updateColorDisplay(ElementEditText colorDisplay, int color) {
        String hex = Color.alpha(color) == 0xFF
                ? String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
                : String.format("#%08X", color);
        colorDisplay.setTextWithNoTextChangedCallBack(hex);
        colorDisplay.setBackgroundResource(R.drawable.crown_color_input_bg);
        Drawable swatch = new ColorSwatchDrawable(colorDisplay.getContext(), color);
        Drawable arrow = ContextCompat.getDrawable(colorDisplay.getContext(), R.drawable.ic_chevron_right);
        if (arrow != null) {
            DrawableCompat.setTint(arrow, ContextCompat.getColor(colorDisplay.getContext(), R.color.crown_text_secondary));
        }
        colorDisplay.setCompoundDrawablesRelative(swatch, null, arrow, null);
        colorDisplay.setTextColor(ContextCompat.getColor(colorDisplay.getContext(), R.color.crown_text_primary));
        colorDisplay.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }

    private static int dp(Element owner, int value) {
        return Math.round(value * owner.getContext().getResources().getDisplayMetrics().density);
    }

    private static final class ColorSwatchDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int color;
        private final float density;

        ColorSwatchDrawable(android.content.Context context, int color) {
            this.color = color;
            density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(22 * density);
            setBounds(0, 0, size, size);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, density));
            border.setColor(ContextCompat.getColor(context, R.color.crown_input_border));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
            float radius = 5 * density;
            if (Color.alpha(color) < 0xFF) {
                paint.setColor(0xFFE1E4EA);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setColor(0xFFB6BBC4);
                float cell = 5 * density;
                canvas.save();
                canvas.clipRect(rect);
                for (float y = rect.top; y < rect.bottom; y += cell) {
                    for (float x = rect.left; x < rect.right; x += cell) {
                        if (((((int) ((x - rect.left) / cell)) + ((int) ((y - rect.top) / cell))) & 1) != 0) {
                            canvas.drawRect(x, y, Math.min(x + cell, rect.right), Math.min(y + cell, rect.bottom), paint);
                        }
                    }
                }
                canvas.restore();
            }
            paint.setColor(color);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.drawRoundRect(rect, radius, radius, border);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
