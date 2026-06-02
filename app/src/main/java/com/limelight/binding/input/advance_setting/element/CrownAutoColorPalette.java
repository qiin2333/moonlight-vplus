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
        float[] accentHsv = new float[3];
        Color.colorToHSV(accent, accentHsv);

        int background = withAlpha(tuneColor(accentHsv, 0.56f, 1.18f, 0.16f, 0.96f), 0x24);
        int normal = withAlpha(tuneColor(accentHsv, 0.68f, 1.24f, 0.22f, 0.98f), 0x90);
        int pressed = withAlpha(tuneColor(accentHsv, 0.82f, 1.32f, 0.30f, 1.00f), 0xB8);

        int normalText = freshTextColor(accent, 0xEC);
        int pressedText = freshTextColor(pressed, 0xF6);
        return new CrownAutoColorPalette(
                normal,
                pressed,
                background,
                normalText,
                pressedText
        );
    }

    private static int tuneColor(float[] sourceHsv, float saturationScale, float valueScale,
                                 float minSaturation, float maxValue) {
        float[] hsv = new float[]{
                sourceHsv[0],
                clamp(sourceHsv[1] * saturationScale, minSaturation, 0.64f),
                clamp(sourceHsv[2] * valueScale + 0.08f, 0.72f, maxValue)
        };
        return Color.HSVToColor(hsv);
    }

    private static int freshTextColor(int accent, int alpha) {
        double luminance = luminance(accent);
        if (luminance > 0.72) {
            return withAlpha(mixRgb(accent, Color.rgb(28, 34, 40), 0.62f), alpha);
        }
        if (luminance < 0.34) {
            return withAlpha(mixRgb(accent, Color.WHITE, 0.76f), alpha);
        }
        return withAlpha(mixRgb(accent, Color.WHITE, 0.62f), alpha);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int mixRgb(int color, int target, float targetAmount) {
        float colorAmount = 1 - targetAmount;
        return Color.rgb(
                clampToByte(Color.red(color) * colorAmount + Color.red(target) * targetAmount),
                clampToByte(Color.green(color) * colorAmount + Color.green(target) * targetAmount),
                clampToByte(Color.blue(color) * colorAmount + Color.blue(target) * targetAmount)
        );
    }

    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
    }

    private static int clampToByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
