package com.limelight.grid

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** D65 sRGB/LAB conversion, shared by all card decoration stops. No Android state. */
internal object CardAccentColors {
    private val gamutScales = doubleArrayOf(1.0, .9, .8, .7, .6, .5, .4, .3, .2, .15, .1, .05, 0.0)

    fun toLab(color: Int): DoubleArray {
        fun linear(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        }
        val r = linear(color ushr 16 and 255)
        val g = linear(color ushr 8 and 255)
        val b = linear(color and 255)
        fun f(t: Double) = if (t > .008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        val x = f((.4124 * r + .3576 * g + .1805 * b) / .95047)
        val y = f(.2126 * r + .7152 * g + .0722 * b)
        val z = f((.0193 * r + .1192 * g + .9505 * b) / 1.08883)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    /** Reduce chroma when needed, retaining lightness and hue even at the gamut boundary. */
    fun fromLab(l: Double, a: Double, b: Double): Int {
        fun inverse(t: Double): Double {
            val cube = t * t * t
            return if (cube > .008856) cube else (t - 16.0 / 116) / 7.787
        }
        fun encoded(v: Double): Int {
            val clamped = v.coerceIn(0.0, 1.0)
            val srgb = if (clamped <= .0031308) 12.92 * clamped
                else 1.055 * clamped.pow(1.0 / 2.4) - .055
            return (srgb * 255).roundToInt()
        }
        val fy = (l.coerceIn(0.0, 100.0) + 16) / 116
        for (scale in gamutScales) {
            val x = inverse(fy + a * scale / 500) * .95047
            val y = inverse(fy)
            val z = inverse(fy - b * scale / 200) * 1.08883
            val r = 3.2406 * x - 1.5372 * y - .4986 * z
            val g = -.9689 * x + 1.8758 * y + .0415 * z
            val blue = .0557 * x - .2040 * y + 1.0570 * z
            if (scale == 0.0 || (r in -.001..1.001 && g in -.001..1.001 && blue in -.001..1.001)) {
                return 0xFF000000.toInt() or (encoded(r) shl 16) or (encoded(g) shl 8) or encoded(blue)
            }
        }
        error("The neutral gamut fallback is always available")
    }

    /** Keep the same rotation and chroma policy as scripts/generate_accent_variants.py. */
    fun forBucket(color: Int, bucket: Int): Int {
        if (bucket !in 0..11) return color
        val (l, a, b) = toLab(color)
        val chroma = hypot(a, b)
        if (chroma < 2.0) return color
        val hue = atan2(b, a) + Math.toRadians(bucket * 30.0 + 15.0 - 345.0)
        val rgb = fromLab(l, chroma * .8 * cos(hue), chroma * .8 * sin(hue))
        return (color and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }
}
