package com.limelight.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.utils.AppTheme
import kotlin.math.roundToInt

/** HarmonyOS V/+ terrain, rasterized once at pixel-art resolution and tiled by the GPU. */
@Suppress("OVERRIDE_DEPRECATION")
internal class PixelMapBackgroundDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val shader = BitmapShader(
        cachedTile(ContextCompat.getColor(AppTheme.paletteContext(context), R.color.pixel_map_background)),
        Shader.TileMode.REPEAT,
        Shader.TileMode.REPEAT
    )
    private val matrix = Matrix()
    private val paint = Paint().apply { shader = this@PixelMapBackgroundDrawable.shader }

    override fun onBoundsChange(bounds: Rect) {
        matrix.setScale(density, density)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = if (paint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    companion object {
        private const val COLS = 28
        private const val ROWS = 42
        private const val CELL = 14
        private const val GLYPH_SATURATION = 0.6f
        private val tiles = HashMap<Int, Bitmap>(2)
        // Original terrain opacity multiplied by the HarmonyOS decoration layer's 0.55 opacity.
        private val colors = intArrayOf(0x778FCB6B, 0x77E5C76B, 0x7EA8744A, 0x775BA8D8, 0x70DC6E96, 0x779C6BC6)
        private val glyphs = charArrayOf('+', '+', 'V', 'V', '+', 'V')
        private val shadeLevels = floatArrayOf(0.35f, 0.55f, 0.8f, 1.1f, 1.4f)

        private fun noise(col: Int, row: Int): Int {
            var value = col * 374761393 + row * 668265263 + 0x51F15E
            value = (value xor (value ushr 13)) * 1274126177
            return (value xor (value ushr 16)) and Int.MAX_VALUE
        }

        /** Broad 4x4 light/shade patches with finer grain; stable across redraws and themes. */
        private fun shadedColor(color: Int, col: Int, row: Int): Int {
            val patch = shadeLevels[noise(col / 4, row / 4) % shadeLevels.size]
            val grain = 0.8f + (noise(col, row) % 41) / 100f
            val alpha = ((color ushr 24) * patch * grain).roundToInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

        @Synchronized
        private fun cachedTile(background: Int): Bitmap = tiles.getOrPut(background) {
            Bitmap.createBitmap(COLS * CELL, ROWS * CELL, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 12f
                    textAlign = Paint.Align.CENTER
                    // Bake the softer palette into the cached tile once.
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(GLYPH_SATURATION) })
                }
                val baseline = CELL / 2f - (text.ascent() + text.descent()) / 2f
                val chars = CharArray(1)
                for (row in 0 until ROWS) for (col in 0 until COLS) {
                    val terrain = (row * 7 + col * 13 + row * col * 3 + (row * row + col * col) % 11) % colors.size
                    val variation = (row * 31 + col * 17 + (row xor col) * 5 + row * col * col) % 25
                    if (variation == 13) continue
                    val color = when (variation) {
                        1 -> 0x7E281E19
                        7 -> 0x46F5EBD2
                        else -> colors[terrain]
                    }
                    text.color = shadedColor(color, col, row)
                    chars[0] = glyphs[terrain]
                    canvas.drawText(chars, 0, 1, col * CELL + CELL / 2f, row * CELL + baseline, text)
                }
                bitmap.setHasAlpha(false)
            }
        }
    }
}
