package com.limelight.utils

import android.graphics.Bitmap
import android.graphics.Color

import com.limelight.nvstream.http.NvApp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object SoftBackgroundColorExtractor {
    private const val SAMPLE_GRID_SIZE = 36
    private const val MIN_ALPHA = 128
    private const val MIN_COLORFUL_SATURATION = 0.08f
    private const val MIN_COLORFUL_VALUE = 0.12f
    private const val MAX_COLORFUL_VALUE = 0.96f
    private const val MIN_COLORFUL_WEIGHT = 0.01
    private const val OUTPUT_SATURATION_SCALE = 0.55f
    private const val OUTPUT_VALUE_SCALE = 0.72f
    private const val MIN_OUTPUT_SATURATION = 0.16f
    private const val MAX_OUTPUT_SATURATION = 0.32f
    private const val MIN_OUTPUT_VALUE = 0.28f
    private const val MAX_OUTPUT_VALUE = 0.42f

    fun fallbackFor(app: NvApp): Int {
        val hash = 31 * app.appId + app.appName.hashCode()
        val hue = ((hash % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.24f, 0.34f))
    }

    fun fromBitmap(bitmap: Bitmap, fallbackColor: Int): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return fallbackColor
        }

        return try {
            val sample = sampleBitmap(bitmap) ?: return fallbackColor
            val hsv = sample.toHsv()
            val outputSaturation = (hsv[1] * OUTPUT_SATURATION_SCALE)
                    .coerceIn(MIN_OUTPUT_SATURATION, MAX_OUTPUT_SATURATION)
            val outputValue = (hsv[2] * OUTPUT_VALUE_SCALE)
                    .coerceIn(MIN_OUTPUT_VALUE, MAX_OUTPUT_VALUE)
            Color.HSVToColor(floatArrayOf(hsv[0], outputSaturation, outputValue))
        } catch (e: Exception) {
            fallbackColor
        }
    }

    private fun sampleBitmap(bitmap: Bitmap): ColorSample? {
        val hsv = FloatArray(3)
        val sample = ColorSample()
        val stepX = (bitmap.width / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_GRID_SIZE).coerceAtLeast(1)

        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                sample.addPixel(bitmap.getPixel(x, y), hsv)
                x += stepX
            }
            y += stepY
        }

        return sample.takeIf { it.totalPixels > 0 }
    }

    private class ColorSample {
        var hueX = 0.0
        var hueY = 0.0
        var weightedSaturation = 0.0
        var weightedValue = 0.0
        var colorfulWeight = 0.0
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var totalPixels = 0

        fun addPixel(pixel: Int, hsv: FloatArray) {
            if (Color.alpha(pixel) <= MIN_ALPHA) {
                return
            }

            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            redTotal += red.toLong()
            greenTotal += green.toLong()
            blueTotal += blue.toLong()
            totalPixels++

            Color.RGBToHSV(red, green, blue, hsv)
            if (hsv[1] >= MIN_COLORFUL_SATURATION && hsv[2] in MIN_COLORFUL_VALUE..MAX_COLORFUL_VALUE) {
                val weight = hsv[1] * (0.35f + hsv[2])
                val radians = Math.toRadians(hsv[0].toDouble())
                hueX += cos(radians) * weight
                hueY += sin(radians) * weight
                weightedSaturation += hsv[1] * weight
                weightedValue += hsv[2] * weight
                colorfulWeight += weight
            }
        }

        fun toHsv(): FloatArray {
            val hsv = FloatArray(3)
            if (colorfulWeight < MIN_COLORFUL_WEIGHT) {
                Color.RGBToHSV(
                        (redTotal / totalPixels).toInt(),
                        (greenTotal / totalPixels).toInt(),
                        (blueTotal / totalPixels).toInt(),
                        hsv
                )
                return hsv
            }

            var hue = Math.toDegrees(atan2(hueY, hueX))
            if (hue < 0) {
                hue += 360.0
            }
            hsv[0] = hue.toFloat()
            hsv[1] = (weightedSaturation / colorfulWeight).toFloat()
            hsv[2] = (weightedValue / colorfulWeight).toFloat()
            return hsv
        }
    }
}
