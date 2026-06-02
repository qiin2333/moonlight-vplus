package com.limelight.binding.input.advance_setting.element;

import android.graphics.Color;
import android.view.Gravity;

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
        colorDisplay.setTextWithNoTextChangedCallBack(String.format("%08X", color));
        colorDisplay.setBackgroundColor(color);
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        colorDisplay.setTextColor(luminance > 0.5 ? Color.BLACK : Color.WHITE);
        colorDisplay.setGravity(Gravity.CENTER);
    }
}
