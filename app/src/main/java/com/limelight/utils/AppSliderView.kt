package com.limelight.utils

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatSeekBar
import com.limelight.R

/** Shared artwork on the native SeekBar, including API 22 and rotated controls. */
class AppSliderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatSeekBar(context, attrs) {
    init {
        val offset = thumbOffset
        val palette = AppTheme.paletteContext(context)
        thumbTintList = null
        progressTintList = null
        secondaryProgressTintList = null
        if (attrs?.getAttributeValue("http://schemas.android.com/apk/res/android", "progressBackgroundTint") == null) {
            progressBackgroundTintList = null
        }
        progressDrawable = AppCompatResources.getDrawable(palette, R.drawable.pref_seekbar_track)?.mutate()
        thumb = AppCompatResources.getDrawable(palette, R.drawable.pref_seekbar_thumb)?.mutate()
        if (attrs?.getAttributeValue("http://schemas.android.com/apk/res/android", "thumbOffset") != null) thumbOffset = offset
        splitTrack = false
    }

    override fun onDraw(canvas: Canvas) {
        val trackHeight = (6 * resources.displayMetrics.density + 0.5f).toInt()
        val top = paddingTop + (height - paddingTop - paddingBottom - trackHeight) / 2
        val left = paddingLeft
        val right = (width - paddingRight).coerceAtLeast(left + 1)
        val opacity = if (isEnabled) 255 else 97
        progressDrawable?.apply {
            // Keep the custom track in the same content bounds that SeekBar uses
            // for its thumb. Starting at zero made Crown sliders look clipped
            // and shifted whenever they had horizontal padding.
            setBounds(left, top, right, top + trackHeight)
            alpha = opacity
        }
        thumb?.alpha = opacity
        super.onDraw(canvas)
    }
}
