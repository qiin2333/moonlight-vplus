package com.limelight.binding.input.advance_setting.superpage

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.limelight.R

/** Section label with a consistently sized, text-colored decorative icon. */
class CrownSectionTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {
    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.CrownSectionTextView, defStyleAttr, 0)
        val iconRes = attributes.getResourceId(R.styleable.CrownSectionTextView_crownSectionIcon, 0)
        attributes.recycle()
        if (iconRes != 0) {
            ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { icon ->
                val drawable = DrawableCompat.wrap(icon)
                val size = (14f * resources.displayMetrics.density + 0.5f).toInt()
                drawable.setBounds(0, 0, size, size)
                DrawableCompat.setTintList(drawable, textColors)
                setCompoundDrawablesRelative(drawable, null, null, null)
                compoundDrawablePadding = (6f * resources.displayMetrics.density + 0.5f).toInt()
            }
        }
    }
}
