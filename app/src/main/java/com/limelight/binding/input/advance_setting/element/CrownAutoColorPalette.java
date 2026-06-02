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

        int background = withAlpha(tuneColor(accentHsv, 0.78f, 0.74f, 0.40f, 0.84f), 0x38);
        int normal = withAlpha(tuneColor(accentHsv, 1.05f, 1.12f, 0.42f, 0.88f), 0xB8);
        int pressed = withAlpha(tuneColor(accentHsv, 1.14f, 1.22f, 0.52f, 1.00f), 0xD8);

        int normalText = readableTint(accent, 0xE8);
        int pressedText = readableTint(pressed, 0xF2);
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
                clamp(sourceHsv[1] * saturationScale, minSaturation, 0.92f),
                clamp(sourceHsv[2] * valueScale, 0.50f, maxValue)
        };
        return Color.HSVToColor(hsv);
    }

    private static int readableTint(int accent, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        double luminance = luminance(accent);
        if (luminance < 0.38) {
            hsv[1] = clamp(hsv[1] * 0.72f, 0.28f, 0.72f);
            hsv[2] = clamp(hsv[2] * 1.55f + 0.18f, 0.72f, 1.00f);
            return withAlpha(Color.HSVToColor(hsv), alpha);
        }
        if (luminance > 0.78) {
            hsv[1] = clamp(hsv[1] * 0.82f, 0.20f, 0.68f);
            hsv[2] = clamp(hsv[2] * 0.42f, 0.28f, 0.52f);
            return withAlpha(Color.HSVToColor(hsv), alpha);
        }
        hsv[1] = clamp(hsv[1] * 0.88f, 0.30f, 0.76f);
        hsv[2] = clamp(hsv[2] * 1.18f, 0.70f, 0.96f);
        return withAlpha(Color.HSVToColor(hsv), alpha);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
