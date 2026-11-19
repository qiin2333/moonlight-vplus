package com.limelight.binding.input.advance_setting.element

import android.graphics.Color

class CrownAutoColorPalette private constructor(
    @JvmField val normalColor: Int,
    @JvmField val pressedColor: Int,
    @JvmField val backgroundColor: Int,
    @JvmField val normalTextColor: Int,
    @JvmField val pressedTextColor: Int
) {
    companion object {
        @JvmStatic
        fun fromAccent(accent: Int): CrownAutoColorPalette {
            val accentHsv = FloatArray(3)
            Color.colorToHSV(accent, accentHsv)

            val background = withAlpha(tuneColor(accentHsv, 0.56f, 1.18f, 0.16f, 0.96f), 0x24)
            val normal = withAlpha(tuneColor(accentHsv, 0.68f, 1.24f, 0.22f, 0.98f), 0x90)
            val pressed = withAlpha(tuneColor(accentHsv, 0.82f, 1.32f, 0.30f, 1.00f), 0xB8)

            val normalText = freshTextColor(accent, 0xEC)
            val pressedText = freshTextColor(pressed, 0xF6)
            return CrownAutoColorPalette(
                normal,
                pressed,
                background,
                normalText,
                pressedText
            )
        }

        private fun tuneColor(sourceHsv: FloatArray, saturationScale: Float, valueScale: Float,
                              minSaturation: Float, maxValue: Float): Int {
            val hsv = floatArrayOf(
                sourceHsv[0],
                clamp(sourceHsv[1] * saturationScale, minSaturation, 0.64f),
                clamp(sourceHsv[2] * valueScale + 0.08f, 0.72f, maxValue)
            )
            return Color.HSVToColor(hsv)
        }

        private fun freshTextColor(accent: Int, alpha: Int): Int {
            val luminance = luminance(accent)
            if (luminance > 0.72) {
                return withAlpha(mixRgb(accent, Color.rgb(28, 34, 40), 0.62f), alpha)
            }
            if (luminance < 0.34) {
                return withAlpha(mixRgb(accent, Color.WHITE, 0.76f), alpha)
            }
            return withAlpha(mixRgb(accent, Color.WHITE, 0.62f), alpha)
        }

        private fun withAlpha(color: Int, alpha: Int): Int {
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

        private fun mixRgb(color: Int, target: Int, targetAmount: Float): Int {
            val colorAmount = 1 - targetAmount
            return Color.rgb(
                clampToByte(Color.red(color) * colorAmount + Color.red(target) * targetAmount),
                clampToByte(Color.green(color) * colorAmount + Color.green(target) * targetAmount),
                clampToByte(Color.blue(color) * colorAmount + Color.blue(target) * targetAmount)
            )
        }

        private fun luminance(color: Int): Double {
            return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        }

        private fun clampToByte(value: Float): Int {
            return Math.max(0, Math.min(255, Math.round(value)))
        }

        private fun clamp(value: Float, min: Float, max: Float): Float {
            return Math.max(min, Math.min(max, value))
        }
    }
}
