package com.limelight.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.limelight.utils.AppTheme
import com.limelight.R
import kotlin.math.roundToInt

/** PC-card decorations using the current palette, with runtime radial gradients for compatibility. */
object PcCardDecor {
    private data class Key(val drawable: Int, val bucket: Int)
    private val cache = HashMap<Key, Drawable.ConstantState>()
    private var cachedConfiguration: Configuration? = null

    fun textColor(context: Context, disabled: Boolean = false): Int = ContextCompat.getColor(
        AppTheme.configurationContext(context),
        if (disabled) R.color.pc_item_text_disabled else R.color.pc_item_text_primary,
    )

    fun selector(context: Context, bucket: Int): Drawable = drawable(context, bucket, R.drawable.pc_item_selector)
    fun multiSelector(context: Context, bucket: Int): Drawable = drawable(context, bucket, R.drawable.pc_item_multiple_addresses_selector)
    fun glow(context: Context, bucket: Int): Drawable = radialDrawable(
        context,
        bucket,
        R.drawable.pc_icon_glow,
        R.attr.pcDecorIconGlowStart,
        R.attr.pcDecorIconGlowCenter,
        R.attr.pcDecorIconGlowEnd,
        radiusDp = 95f,
    )

    fun iconBg(context: Context, bucket: Int): Drawable = radialDrawable(
        context,
        bucket,
        R.drawable.pc_item_icon_bg,
        R.attr.pcDecorIconBgStart,
        R.attr.pcDecorIconBgCenter,
        R.attr.pcDecorIconBgEnd,
        radiusDp = 80f,
        strokeAttr = R.attr.pcDecorIconBgStroke,
    )

    /**
     * Builds the two radial icon decorations directly instead of inflating their XML.
     * Android 9 OEM frameworks can lose the radial radius while reapplying a theme to
     * a drawable whose colors are theme attributes, even though the XML declares it.
     */
    private fun radialDrawable(
        context: Context,
        bucket: Int,
        resource: Int,
        startAttr: Int,
        centerAttr: Int,
        endAttr: Int,
        radiusDp: Float,
        strokeAttr: Int? = null,
    ): Drawable {
        val themed = AppTheme.paletteContext(context, bucket)
        val configuration = themed.resources.configuration
        if (cachedConfiguration != configuration) {
            cache.clear()
            cachedConfiguration = Configuration(configuration)
        }
        val key = Key(resource, if (bucket in 0..11) bucket else -1)
        cache[key]?.let { return it.newDrawable(themed.resources, themed.theme).mutate() }

        val attrs = if (strokeAttr == null) {
            intArrayOf(startAttr, centerAttr, endAttr)
        } else {
            intArrayOf(startAttr, centerAttr, endAttr, strokeAttr)
        }
        val typedArray = themed.obtainStyledAttributes(attrs)
        val colors = try {
            val strokeIndex = attrs.lastIndex
            IntArray(3) { index -> typedArray.getColor(index, 0) } to
                strokeAttr?.let { typedArray.getColor(strokeIndex, 0) }
        } finally {
            typedArray.recycle()
        }
        val drawable = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setGradientType(GradientDrawable.RADIAL_GRADIENT)
            setColors(colors.first)
            setGradientCenter(0.5f, 0.5f)
            setGradientRadius(radiusDp * themed.resources.displayMetrics.density)
            colors.second?.let { setStroke(themed.resources.displayMetrics.density.roundToInt(), it) }
        }
        drawable.constantState?.let {
            cache[key] = it
            return it.newDrawable(themed.resources, themed.theme).mutate()
        }
        return drawable
    }

    private fun drawable(context: Context, bucket: Int, resource: Int): Drawable {
        val themed = AppTheme.paletteContext(context, bucket)
        val configuration = themed.resources.configuration
        if (cachedConfiguration != configuration) {
            cache.clear()
            cachedConfiguration = Configuration(configuration)
        }
        val key = Key(resource, if (bucket in 0..11) bucket else -1)
        cache[key]?.let { return it.newDrawable(themed.resources, themed.theme).mutate() }
        val drawable = ContextCompat.getDrawable(themed, resource)!!
        drawable.constantState?.let {
            cache[key] = it
            return it.newDrawable(themed.resources, themed.theme).mutate()
        }
        return drawable.mutate()
    }
}
