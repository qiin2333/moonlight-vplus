package com.limelight.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.utils.UiHelper

/**
 * 首页卡片装饰层：按背景色相生成渐变，保留原版日夜配色与几何形状。
 *
 * bucket 有效（0..11，跟随壁纸模式）时，按当前主题解析品牌粉调色板，
 * 在 CIELAB 空间做色相旋转（锚点 345°：锁 L* 与感知亮度、色度 C×0.8、色相转到
 * 桶位，alpha 原样保留），在代码里构建表面选择器/堆叠卡/光晕/图标底。
 * bucket 无效（品牌粉模式/无背景）时，直接返回原版 XML drawable——
 * 其日夜外观由 values / values-night 调色板决定，与原版行为完全一致。
 *
 * 配置变化时清理模板缓存；每个 View 获得独立 Drawable，避免状态和尺寸串扰。
 */
object PcCardDecor {

    private const val MAX_BUCKET = 11
    private val cache = HashMap<String, Drawable.ConstantState>()
    private var cachedConfiguration: Configuration? = null

    // Do not retain an Activity context. The platform night-mode change may arrive
    // after the preference write, so resolve every card resource against the same mode.
    private fun decorContextOf(context: Context): Context {
        val night = UiHelper.wantedNight(context)
        val configuration = context.resources.configuration
        val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == mode) return context
        val override = Configuration(configuration)
        override.uiMode = (override.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return context.createConfigurationContext(override)
    }

    fun textColor(context: Context, disabled: Boolean = false): Int = ContextCompat.getColor(
        decorContextOf(context),
        if (disabled) R.color.pc_item_text_disabled else R.color.pc_item_text_primary,
    )

    // ---------- 对外 API ----------

    /** 卡片表面状态选择器（默认/按下/聚焦/选中 渐变）。 */
    fun selector(context: Context, bucket: Int): Drawable =
        cached(context, "sel", bucket) { ctx, b -> buildSelector(ctx, b) }

    /** 多地址堆叠卡状态选择器（三层堆叠的按下/聚焦/选中 与 默认）。 */
    fun multiSelector(context: Context, bucket: Int): Drawable =
        cached(context, "multi", bucket) { ctx, b -> buildMultiSelector(ctx, b) }

    /** 图标光晕（径向渐变椭圆，保留原版透明度）。 */
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
            val stops = intArrayOf(
                themed(ctx, b, R.color.pc_item_icon_bg_start),
                themed(ctx, b, R.color.pc_item_icon_bg_center),
                themed(ctx, b, R.color.pc_item_icon_bg_end),
            )
            buildRadialOval(ctx, stops, 80f, strokeColor = themed(ctx, b, R.color.pc_item_icon_bg_stroke))
        }

    // Cache immutable templates, never a Drawable owned by another View.
    private fun cached(context: Context, kind: String, bucket: Int, build: (Context, Int) -> Drawable): Drawable {
        val ctx = decorContextOf(context)
        if (bucket !in 0..MAX_BUCKET) return fallback(ctx, kind).mutate()
        val configuration = ctx.resources.configuration
        if (cachedConfiguration != configuration) {
            cache.clear()
            cachedConfiguration = Configuration(configuration)
        }
        val key = "$kind:$bucket"
        // These templates already contain pixel dimensions for this configuration.
        // Passing Resources here would scale their default (160 dpi) state a second time.
        cache[key]?.let { return it.newDrawable().mutate() }
        val drawable = build(ctx, bucket)
        drawable.constantState?.let {
            cache[key] = it
            return it.newDrawable().mutate()
        }
        return drawable
    }

    private fun fallback(context: Context, kind: String): Drawable = ContextCompat.getDrawable(
        context,
        when (kind) {
            "sel" -> R.drawable.pc_item_selector
            "multi" -> R.drawable.pc_item_multiple_addresses_selector
            "glow" -> R.drawable.pc_icon_glow
            else -> R.drawable.pc_item_icon_bg
        },
    )!!

    // ---------- 颜色 ----------

    private fun themed(context: Context, bucket: Int, res: Int): Int {
        val color = ContextCompat.getColor(context, res)
        // 聚焦描边保持固定蓝色。
        if (bucket !in 0..MAX_BUCKET || res == R.color.pc_item_outline_focused) return color
        return CardAccentColors.forBucket(color, bucket)
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
        fun shape(stops: IntArray, strokeWidthDp: Float?, strokeColor: Int?): GradientDrawable {
            val gd = GradientDrawable(Orientation.BR_TL, stops)
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dim(context, R.dimen.corner_radius_large)
            strokeColor?.let { gd.setStroke(dp(context, strokeWidthDp!!), it) }
            return gd
        }
        val defaultStops = surfaceStops(context, bucket, "default")
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
            val gd = GradientDrawable(Orientation.TL_BR, intArrayOf(
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
        // XML layer insets use dimension pixel offsets (truncate rather than round).
        val density = context.resources.displayMetrics.density
        ld.setLayerInset(0, 0, 0, 0, (20f * density).toInt())
        ld.setLayerInset(1, 0, 0, 0, (10f * density).toInt())
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
            strokeColor: Int? = null,
    ): Drawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.gradientType = GradientDrawable.RADIAL_GRADIENT
        gd.setColors(stops)
        gd.setGradientCenter(0.5f, 0.5f)
        gd.setGradientRadius(radiusDp * context.resources.displayMetrics.density)
        strokeColor?.let { gd.setStroke(dp(context, 1f), it) }
        return gd
    }
}
