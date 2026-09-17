package com.limelight.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.limelight.utils.AppTheme
import com.limelight.R

/** Original XML geometry with a palette overlay; no duplicate shapes or runtime color conversion. */
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
    fun glow(context: Context, bucket: Int): Drawable = drawable(context, bucket, R.drawable.pc_icon_glow)
    fun iconBg(context: Context, bucket: Int): Drawable = drawable(context, bucket, R.drawable.pc_item_icon_bg)

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
