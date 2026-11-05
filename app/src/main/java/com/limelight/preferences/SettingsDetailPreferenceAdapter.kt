package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.utils.AppTheme
import com.limelight.utils.UiHelper
import java.util.IdentityHashMap

/** Card rows for a visible subtree, without replacing any preference or its behavior. */
@SuppressLint("RestrictedApi")
internal class SettingsDetailPreferenceAdapter(group: PreferenceGroup) : SettingsPreferenceAdapter(group, mainCards = false) {
    private data class OriginalLayout(val layout: Int, val widget: Int)
    private val originalLayouts = IdentityHashMap<Preference, OriginalLayout>()

    /** Converts standard layouts for details while saving their originals for the main page. */
    override fun getItemViewType(position: Int): Int {
        getItem(position)?.let { preference ->
            if (isStandardLayout(preference)) {
                originalLayouts.getOrPut(preference) {
                    OriginalLayout(preference.layoutResource, preference.widgetLayoutResource)
                }
                preference.layoutResource = if (preference is PreferenceCategory) {
                    R.layout.preference_detail_category
                } else R.layout.preference_detail_row
                if (preference.widgetLayoutResource == R.layout.preference_widget_navigation) {
                    // The row already has one navigation indicator.
                    preference.widgetLayoutResource = 0
                }
            }
        }
        return super.getItemViewType(position)
    }

    /** Adds detail separators and navigation without replacing preference-owned click behavior. */
    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position) ?: return
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        if (!isStandardLayout(preference)) return
        val palette = AppTheme.paletteContext(holder.itemView.context)
        if (preference is PreferenceCategory) {
            holder.itemView.background = null
            holder.itemView.isFocusable = false
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
            (holder.findViewById(android.R.id.title) as? TextView)?.apply {
                setTextColor(ContextCompat.getColor(palette, R.color.ui_shell_text_secondary))
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.itemView.defaultFocusHighlightEnabled = false
        if (preference.icon == null) holder.findViewById(androidx.preference.R.id.icon_frame)?.visibility = View.GONE
        val previous = if (position > 0) getItem(position - 1) else null
        val next = if (position + 1 < itemCount) getItem(position + 1) else null
        val top = !sharesCard(preference, previous)
        val bottom = !sharesCard(preference, next)
        holder.itemView.background = SettingsCardStyle.background(palette, top, bottom)
        holder.findViewById(R.id.settings_detail_row_divider)?.apply {
            visibility = if (bottom) View.GONE else View.VISIBLE
            setBackgroundColor(ContextCompat.getColor(palette, R.color.ui_shell_outline))
            alpha = 0.4f
        }
        (holder.findViewById(R.id.settings_detail_row_chevron) as? ImageView)?.apply {
            visibility = if (preference.isSelectable && preference !is TwoStatePreference &&
                preference.widgetLayoutResource == 0) View.VISIBLE else View.GONE
            imageTintList = ColorStateList.valueOf(UiHelper.accentColor(palette))
        }
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            setTextColor(ContextCompat.getColorStateList(palette, R.color.settings_preference_title_text))
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            setTextColor(ContextCompat.getColorStateList(palette, R.color.settings_preference_summary_text))
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        restoreOriginalLayouts()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** Call before building the main adapter if it is created before this adapter is detached. */
    fun restoreOriginalLayouts() {
        originalLayouts.forEach { (preference, original) ->
            preference.layoutResource = original.layout
            preference.widgetLayoutResource = original.widget
        }
        originalLayouts.clear()
    }

    /** Checks the saved layout so converted rows stay eligible when recycled or rebound. */
    private fun isStandardLayout(preference: Preference): Boolean {
        val layout = originalLayouts[preference]?.layout ?: preference.layoutResource
        return isStandardLayoutResource(layout)
    }

    private fun sharesCard(preference: Preference, neighbor: Preference?): Boolean =
        neighbor != null && neighbor !is PreferenceCategory && isStandardLayout(neighbor) &&
            neighbor.parent === preference.parent
}
