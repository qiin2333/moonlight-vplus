package com.limelight.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import com.limelight.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 首页卡片装饰层的程序化工厂（替代 168 个桶位 XML 变体）。
 *
 * [accentBucket] 有效（0..11，跟随壁纸模式）时，按当前主题解析品牌粉调色板，
 * 在 CIELAB 空间做色相旋转（锚点 345°：锁 L* 与感知亮度、色度 C×0.8、色相转到
 * 桶位，alpha 原样保留），在代码里构建表面选择器/堆叠卡/光晕/图标底。
 * [accentBucket] 无效（品牌粉模式/无背景）时，直接返回原版 XML drawable——
 * 其日夜外观由 values / values-night 调色板决定，与原版行为完全一致。
 *
 * 缓存按 (装饰类型, 桶位, 日夜) 键控；深浅色调色板由资源系统解析，缓存键自动区分。
 */
object PcCardDecor {

    private const val MAX_BUCKET = 11
    private const val BRAND_H = 345.0
    private const val CHROMA_SCALE = 0.8

    private val cache = HashMap<String, Drawable>()

    // ---------- 对外 API ----------

    /** 卡片表面状态选择器（默认/按下/聚焦/选中 渐变）。 */
    fun selector(context: Context, bucket: Int): Drawable =
        cached(context, "sel", bucket) { ctx, b -> buildSelector(ctx, b) }

    /** 多地址堆叠卡状态选择器（三层堆叠的按下/聚焦/选中 与 默认）。 */
    fun multiSelector(context: Context, bucket: Int): Drawable =
        cached(context, "multi", bucket) { ctx, b -> buildMultiSelector(ctx, b) }

    /** 图标光晕（径向渐变椭圆）。 */
    fun glow(context: Context, bucket: Int): Drawable =
        cached(context, "glow", bucket) { ctx, b ->
            buildRadialOval(
                ctx,
                intArrayOf(
                    themed(ctx, b, R.color.pc_item_icon_glow_start),
                    themed(ctx, b, R.color.pc_item_icon_glow_center),
                    themed(ctx, b, R.color.pc_item_icon_glow_end),
                ),
                95f,
                null,
            )
        }

    /** 图标背景（径向渐变椭圆 + 1dp 描边）。 */
    fun iconBg(context: Context, bucket: Int): Drawable =
        cached(context, "iconbg", bucket) { ctx, b ->
            buildRadialOval(
                ctx,
                intArrayOf(
                    themed(ctx, b, R.color.pc_item_icon_bg_start),
                    themed(ctx, b, R.color.pc_item_icon_bg_center),
                    themed(ctx, b, R.color.pc_item_icon_bg_end),
                ),
                80f,
                themed(ctx, b, R.color.pc_item_icon_bg_stroke) to dp(ctx, 1f),
            )
        }

    /** 跟随壁纸模式下卡片表面文字的 on-color（表面锁定浅色，文字恒为深色）。 */
    const val TEXT_ON_SURFACE = 0xFF1C1C1E.toInt()
    const val TEXT_DISABLED_ON_SURFACE = 0xFF8E8E93.toInt()

    // ---------- 缓存与回退 ----------

    private fun cached(context: Context, kind: String, bucket: Int, build: (Context, Int) -> Drawable): Drawable {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val key = "$kind:${if (bucket in 0..MAX_BUCKET) bucket else -1}:$night"
        return cache.getOrPut(key) {
            if (bucket in 0..MAX_BUCKET) build(context, bucket)
            else fallback(context, kind)
        }
    }

    private fun fallback(context: Context, kind: String): Drawable = when (kind) {
        "sel" -> ContextCompat.getDrawable(context, R.drawable.pc_item_selector)!!
        "multi" -> ContextCompat.getDrawable(context, R.drawable.pc_item_multiple_addresses_selector)!!
        "glow" -> ContextCompat.getDrawable(context, R.drawable.pc_icon_glow)!!
        else -> ContextCompat.getDrawable(context, R.drawable.pc_item_icon_bg)!!
    }

    // ---------- 颜色 ----------

    private fun themed(context: Context, bucket: Int, res: Int): Int {
        val color = ContextCompat.getColor(context, res)
        if (bucket !in 0..MAX_BUCKET || res in KEEP_IDS) return color
        return rotateHue(color, bucket * 30.0 + 15.0)
    }

    /** 刻意不跟随的固定色：蓝色聚焦描边、白色闪烁高光。 */
    private val KEEP_IDS = setOf(
            R.color.pc_item_outline_focused,
            R.color.pc_item_icon_shimmer_start,
            R.color.pc_item_icon_shimmer_center,
            R.color.pc_item_icon_shimmer_end,
    )

    /**
     * CIELAB 色相旋转：锁 L*（感知亮度）与色度 C（×CHROMA_SCALE），色相转到
     * 桶位（桶内保留与品牌粉的相对偏移）；alpha 原样保留。超出 sRGB 色域时
     * 收缩色度、不改变明度。
     */
    private fun srgbToLab(color: Int): Triple<Double, Double, Double> {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(Color.red(color)); val g = lin(Color.green(color)); val b = lin(Color.blue(color))
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        val l = 116 * f(y) - 16
        val aLab = 500 * (f(x) - f(y))
        val bLab = 200 * (f(y) - f(z))
        return Triple(l, aLab, bLab)
    }

    private fun rotateHue(color: Int, bucketHue: Double): Int =
        rotateHueTowards(color, bucketHue, BRAND_H, 2.0)

    /**
     * LAB 色相旋转 + 色相混合兜底：目标色相 = 桶位色相（桶内保留与品牌粉的
     * 相对偏移）。近白明度下 sRGB 对青/蓝色相的色度上限很低——目标色相装不下
     * 时按 25% 步进向品牌粉色相回退，保证输出永远有可感知的色调。
     */
    private fun rotateHueTowards(color: Int, bucketHue: Double, brandHue: Double, minChroma: Double): Int {
        val alpha = Color.alpha(color) shl 24
        val (l, aLab, bLab) = srgbToLab(color)
        var chroma = hypot(aLab, bLab)
        if (chroma < 2.0) return color   // 中性色不动
        chroma *= CHROMA_SCALE
        val srcH = Math.toDegrees(atan2(bLab, aLab))
        var k = 1.0   // 1.0 = 完全跟随桶位色相，0.0 = 完全品牌粉色相
        while (true) {
            val h = srcH + shortestArc(srcH, bucketHue) * k
            val rad = Math.toRadians(h)
            val (out, kept) = labToSrgbClampedScaled(l, chroma * cos(rad), chroma * sin(rad))
            if (kept >= minChroma || k <= 0.0) {
                return alpha or (out and 0x00FFFFFF)
            }
            k -= 0.25
        }
    }

    /** 色相环最短弧差（±180°）。 */
    private fun shortestArc(from: Double, to: Double): Double =
            ((to - from) % 360.0).let { if (it > 180.0) it - 360.0 else if (it < -180.0) it + 360.0 else it }

    /**
     * LAB → sRGB；若色度超出色域则收缩色度（保 L* 与色相）。
     * 返回 (颜色, 实际保留的色度)。
     */
    private fun labToSrgbClampedScaled(l: Double, aIn: Double, bIn: Double): Pair<Int, Double> {
        val chromaIn = hypot(aIn, bIn)
        var scale = 1.0
        while (scale > 0.0) {
            val fx = (l + 16) / 116 + aIn * scale / 500
            val fz = (l + 16) / 116 - bIn * scale / 200
            fun fi(t: Double) = if (t.pow(3) > 0.008856) t.pow(1.0 / 3) else (t - 16.0 / 116) / 7.787
            val x = fi(fx) * 0.95047
            val y = fi((l + 16) / 116)
            val z = fi(fz) * 1.08883
            val rl = 3.2406 * x - 1.5372 * y - 0.4986 * z
            val gl = -0.9689 * x + 1.8758 * y + 0.0415 * z
            val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
            if (rl in -0.001..1.001 && gl in -0.001..1.001 && bl in -0.001..1.001) {
                fun to8(v: Double) = "%02X".format((255 * v.coerceIn(0.0, 1.0)).roundToInt())
                val out = 0xFF000000.toInt() or
                        (to8(rl).toInt(16) shl 16) or
                        (to8(gl).toInt(16) shl 8) or
                        to8(bl).toInt(16)
                return Pair(out, chromaIn * scale)
            }
            scale -= 0.1
        }
        return Pair(Color.BLACK, 0.0)
    }

    /** LAB → sRGB；若色度超出色域则收缩色度（保 L* 与色相）。 */
    private fun labToSrgbClamped(l: Double, aIn: Double, bIn: Double): Int {
        var scale = 1.0
        while (true) {
            val fy = (l + 16) / 116
            val fx = fy + aIn * scale / 500
            val fz = fy - bIn * scale / 200
            fun fi(t: Double) = if (t.pow(3) > 0.008856) t.pow(1.0 / 3) else (t - 16.0 / 116) / 7.787
            val x = fi(fx) * 0.95047
            val y = fi(fy)
            val z = fi(fz) * 1.08883
            val rl = 3.2406 * x - 1.5372 * y - 0.4986 * z
            val gl = -0.9689 * x + 1.8758 * y + 0.0415 * z
            val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
            if (rl in -0.001..1.001 && gl in -0.001..1.001 && bl in -0.001..1.001) {
                fun to8(v: Double) = "%02X".format((255 * v.coerceIn(0.0, 1.0)).roundToInt())
                return 0xFF000000.toInt() or
                        (to8(rl).toInt(16) shl 16) or
                        (to8(gl).toInt(16) shl 8) or
                        to8(bl).toInt(16)
            }
            scale -= 0.1
            if (scale <= 0.0) return Color.BLACK
        }
    }

    /** 取强调色的同族色调 stop：锁色相与 L*，按 chromaMult 缩放色度，alpha 另定。 */
    private fun toneOf(accent: Int, chromaMult: Double, alphaHex: String): Int {
        val (l, aLab, bLab) = srgbToLab(accent)
        val c = hypot(aLab, bLab) * chromaMult
        val h = atan2(bLab, aLab)
        val out = labToSrgbClamped(l, c * cos(h), c * sin(h))
        return (alphaHex.toInt(16) shl 24) or (out and 0x00FFFFFF)
    }

    // ---------- 结构构建 ----------

    private fun dp(context: Context, v: Float): Int =
            (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun dim(context: Context, res: Int): Float = context.resources.getDimension(res)

    private fun surfaceStops(context: Context, bucket: Int, prefix: String): IntArray = intArrayOf(
            themed(context, bucket, surfaceColorIds.getValue("${prefix}_start")),
            themed(context, bucket, surfaceColorIds.getValue("${prefix}_center")),
            themed(context, bucket, surfaceColorIds.getValue("${prefix}_end")),
    )

    private val surfaceColorIds = mapOf(
            "default_start" to R.color.pc_item_surface_default_start,
            "default_center" to R.color.pc_item_surface_default_center,
            "default_end" to R.color.pc_item_surface_default_end,
            "pressed_start" to R.color.pc_item_surface_pressed_start,
            "pressed_center" to R.color.pc_item_surface_pressed_center,
            "pressed_end" to R.color.pc_item_surface_pressed_end,
            "focused_start" to R.color.pc_item_surface_focused_start,
            "focused_center" to R.color.pc_item_surface_focused_center,
            "focused_end" to R.color.pc_item_surface_focused_end,
    )

    private fun buildSelector(context: Context, bucket: Int): Drawable {
        // 静息表面 = 桶位强调色的 tonal ramp（三 stop 同色相、不同明度/透明度），
        // 让整卡带清晰可读的背景色相；按下/聚焦沿用按压/聚焦调色板的鲜明色阶。
        val accent = themed(context, bucket, R.color.pc_item_surface_default_center)
        fun tone(chromaMult: Double, alphaHex: String): Int = toneOf(accent, chromaMult, alphaHex)
        fun shape(stops: IntArray, strokeWidthDp: Float?, strokeColor: Int?): GradientDrawable {
            val gd = GradientDrawable(Orientation.TL_BR, stops)
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dim(context, R.dimen.corner_radius_large)
            strokeColor?.let { gd.setStroke(dp(context, strokeWidthDp!!), it) }
            return gd
        }
        val defaultStops = intArrayOf(
                tone(1.2, "F5"), tone(2.5, "E6"), tone(0.9, "CC"),
        )
        val pressedStops = surfaceStops(context, bucket, "pressed")
        val focusedStops = surfaceStops(context, bucket, "focused")
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                    shape(pressedStops, 2f, themed(context, bucket, R.color.pc_item_outline_focused)))
            addState(intArrayOf(android.R.attr.state_focused), shape(focusedStops, null, null))
            addState(intArrayOf(android.R.attr.state_selected), shape(focusedStops, null, null))
            addState(intArrayOf(), shape(defaultStops, null, null))
        }
    }

    /** LAB 色度增益：保 L* 与色相，只放大色度（用于静息表面中心的可见桶色）。 */
    private fun boostChroma(color: Int, mult: Double): Int {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(Color.red(color)); val g = lin(Color.green(color)); val b = lin(Color.blue(color))
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        val l = 116 * f(y) - 16
        val aLab = 500 * (f(x) - f(y))
        val bLab = 200 * (f(y) - f(z))
        val alpha = color and 0xFF000000.toInt()
        return alpha or (labToSrgbClamped(l, aLab * mult, bLab * mult) and 0x00FFFFFF)
    }

    /** 堆叠卡三层（back/mid/front），kind = "state"|"default"。 */
    private fun buildStack(context: Context, bucket: Int, kind: String): LayerDrawable {
        fun color(layer: String, edge: String): Int = when (kind) {
            "default" -> when (layer) {
                "back" -> when (edge) {
                    "start" -> R.color.pc_item_stack_default_back_start
                    "center" -> R.color.pc_item_stack_default_back_center
                    else -> R.color.pc_item_stack_default_back_end
                }
                "mid" -> when (edge) {
                    "start" -> R.color.pc_item_stack_default_mid_start
                    "center" -> R.color.pc_item_stack_default_mid_center
                    else -> R.color.pc_item_stack_default_mid_end
                }
                else -> when (edge) {
                    "start" -> R.color.pc_item_stack_default_front_start
                    "center" -> R.color.pc_item_stack_default_front_center
                    else -> R.color.pc_item_stack_default_front_end
                }
            }
            else -> when (layer) {
                "back" -> when (edge) {
                    "start" -> R.color.pc_item_stack_state_back_start
                    "center" -> R.color.pc_item_stack_state_back_center
                    else -> R.color.pc_item_stack_state_back_end
                }
                "mid" -> when (edge) {
                    "start" -> R.color.pc_item_stack_state_mid_start
                    "center" -> R.color.pc_item_stack_state_mid_center
                    else -> R.color.pc_item_stack_state_mid_end
                }
                else -> when (edge) {
                    "start" -> R.color.pc_item_stack_state_front_start
                    "center" -> R.color.pc_item_stack_state_front_center
                    else -> R.color.pc_item_stack_state_front_end
                }
            }
        }

        fun gradient(layer: String): GradientDrawable {
            val gd = GradientDrawable(Orientation.BL_TR, intArrayOf(
                    themed(context, bucket, color(layer, "start")),
                    themed(context, bucket, color(layer, "center")),
                    themed(context, bucket, color(layer, "end")),
            ))
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dim(context, R.dimen.corner_radius_medium)
            return gd
        }
        val back = gradient("back")
        val mid = gradient("mid")
        val front = gradient("front").apply {
            if (kind == "state") {
                setStroke(dp(context, 2f), themed(context, bucket, R.color.pc_item_outline_focused))
            }
        }
        val ld = LayerDrawable(arrayOf(back, mid, front))
        ld.setLayerInset(0, 0, 0, 0, dp(context, 20f))
        ld.setLayerInset(1, 0, 0, 0, dp(context, 10f))
        return ld
    }

    private fun buildMultiSelector(context: Context, bucket: Int): Drawable {
        val state = buildStack(context, bucket, "state")
        val default = buildStack(context, bucket, "default")
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), state)
            addState(intArrayOf(android.R.attr.state_focused), state)
            addState(intArrayOf(android.R.attr.state_selected), state)
            addState(intArrayOf(), default)
        }
    }

    private fun buildRadialOval(
            context: Context,
            stops: IntArray,
            radiusDp: Float,
            stroke: Pair<Int, Int>?,
    ): Drawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.gradientType = GradientDrawable.RADIAL_GRADIENT
        gd.setColors(stops)
        gd.setGradientCenter(0.5f, 0.5f)
        gd.setGradientRadius(dp(context, radiusDp).toFloat())
        stroke?.let { gd.setStroke(it.first, it.second) }
        return gd
    }
}
