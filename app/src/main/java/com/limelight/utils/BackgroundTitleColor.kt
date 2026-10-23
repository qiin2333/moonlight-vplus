package com.limelight.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.annotation.UiThread
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Samples only background layers, so title text and focus chrome cannot affect its own color. */
internal object BackgroundTitleColor {
    @UiThread
    fun sample(title: View, layers: List<ImageView>, baseColor: Int): Int {
        val opaqueBase = ColorUtils.setAlphaComponent(baseColor, 255)
        if (title.width <= 0 || title.height <= 0) return foreground(opaqueBase)

        var sample: Bitmap? = null
        return try {
            val bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888)
            sample = bitmap
            val canvas = Canvas(bitmap)
            canvas.drawColor(opaqueBase)
            canvas.scale(bitmap.width.toFloat() / title.width, bitmap.height.toFloat() / title.height)
            val titleLocation = IntArray(2)
            val layerLocation = IntArray(2)
            title.getLocationOnScreen(titleLocation)
            for (layer in layers) {
                if (!layer.isShown || layer.width <= 0 || layer.height <= 0 || layer.alpha <= 0f) continue
                layer.getLocationOnScreen(layerLocation)
                val saved = canvas.save()
                canvas.translate(
                    (layerLocation[0] - titleLocation[0]).toFloat(),
                    (layerLocation[1] - titleLocation[1]).toFloat()
                )
                // ImageView.draw applies its image matrix, padding, clipping and imageAlpha.
                if (layer.alpha < 1f) {
                    canvas.saveLayerAlpha(
                        0f, 0f, layer.width.toFloat(), layer.height.toFloat(),
                        (layer.alpha * 255).roundToInt()
                    )
                }
                layer.draw(canvas)
                canvas.restoreToCount(saved)
            }
            var red = 0
            var green = 0
            var blue = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                }
            }
            val count = bitmap.width * bitmap.height
            foreground(Color.rgb(red / count, green / count, blue / count))
        } catch (_: RuntimeException) {
            // Recycled or hardware-backed drawables may not support software rendering.
            foreground(opaqueBase)
        } finally {
            sample?.recycle()
        }
    }

    private fun foreground(background: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(background, hsl)
        val light = ColorUtils.calculateLuminance(background) < 0.179
        hsl[1] = hsl[1].coerceAtMost(0.18f)
        hsl[2] = if (light) 0.88f else 0.15f
        val tinted = ColorUtils.HSLToColor(hsl)
        val limit = if (light) Color.WHITE else Color.BLACK
        for (step in 0..16) {
            val candidate = ColorUtils.blendARGB(tinted, limit, step / 16f)
            if (ColorUtils.calculateContrast(candidate, background) >= 4.5) return candidate
        }
        return limit
    }
}
