package com.limelight.binding.input.advance_setting.element;

import android.graphics.Color;

final class CrownAutoColorPalette {
    final int normalColor;
    final int pressedColor;
    final int backgroundColor;
    final int normalTextColor;
    final int pressedTextColor;

    private CrownAutoColorPalette(int normalColor, int pressedColor, int backgroundColor,
                                  int normalTextColor, int pressedTextColor) {
        this.normalColor = normalColor;
        this.pressedColor = pressedColor;
        this.backgroundColor = backgroundColor;
        this.normalTextColor = normalTextColor;
        this.pressedTextColor = pressedTextColor;
    }

    static CrownAutoColorPalette fromAccent(int accent) {
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);

        hsv[1] = clamp(hsv[1] * 1.15f, 0.45f, 0.88f);
        hsv[2] = clamp(hsv[2] * 1.10f, 0.56f, 0.92f);
        int normal = withAlpha(Color.HSVToColor(hsv), 0xE6);

        hsv[1] = clamp(hsv[1] * 1.10f, 0.55f, 0.96f);
        hsv[2] = clamp(hsv[2] * 1.18f, 0.66f, 1.00f);
        int pressed = withAlpha(Color.HSVToColor(hsv), 0xF2);

        int background = withAlpha(accent, 0x34);
        return new CrownAutoColorPalette(
                normal,
                pressed,
                background,
                contrastColor(normal),
                contrastColor(pressed)
        );
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int contrastColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.58 ? Color.BLACK : Color.WHITE;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
