package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.CheckBoxPreference
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
internal class SettingsDetailPreferenceAdapter(group: PreferenceGroup) : SettingsPreferenceAdapter(group) {
    private data class OriginalLayout(val layout: Int, val widget: Int)
    private val originalLayouts = IdentityHashMap<Preference, OriginalLayout>()
    private var recycler: RecyclerView? = null
    private val pendingEdgePositions = mutableSetOf<Int>()
    private val refreshEdges = Runnable {
        val positions = pendingEdgePositions.toList()
        pendingEdgePositions.clear()
        if (recycler?.adapter === this) positions.filter { it in 0 until itemCount }.forEach(::notifyItemChanged)
    }
    private val edgeObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
            refreshEdgesAt(positionStart - 1, positionStart + itemCount)

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
            refreshEdgesAt(positionStart - 1, positionStart)

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            refreshEdgesAt(fromPosition - 1, fromPosition, toPosition - 1, toPosition + itemCount)
    }

    init { registerAdapterDataObserver(edgeObserver) }

    override fun getItemViewType(position: Int): Int {
        getItem(position)?.let { preference ->
            if (isStandardLayout(preference)) {
                originalLayouts.getOrPut(preference) {
                    OriginalLayout(preference.layoutResource, preference.widgetLayoutResource)
                }
                preference.layoutResource = if (preference is PreferenceCategory) {
                    R.layout.preference_detail_category
                } else R.layout.preference_detail_row
                if (preference is CheckBoxPreference) {
                    preference.widgetLayoutResource = R.layout.preference_detail_switch
                } else if (preference.widgetLayoutResource == R.layout.preference_widget_navigation) {
                    // The row already has one navigation indicator.
                    preference.widgetLayoutResource = 0
                }
            }
        }
        return super.getItemViewType(position)
    }

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
        holder.itemView.background = cardBackground(palette, top, bottom)
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
        (holder.findViewById(android.R.id.checkbox) as? SwitchCompat)?.let { switch ->
            tintSwitch(switch, palette)
        }
        (holder.findViewById(androidx.preference.R.id.switchWidget) as? SwitchCompat)?.let { switch ->
            tintSwitch(switch, palette)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeCallbacks(refreshEdges)
        pendingEdgePositions.clear()
        recycler = null
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

    private fun isStandardLayout(preference: Preference): Boolean {
        val layout = originalLayouts[preference]?.layout ?: preference.layoutResource
        return layout == R.layout.preference_material_compact ||
            layout == R.layout.preference_category_material ||
            layout == androidx.preference.R.layout.preference ||
            layout == androidx.preference.R.layout.preference_material ||
            layout == androidx.preference.R.layout.preference_category ||
            layout == androidx.preference.R.layout.preference_category_material
    }

    private fun sharesCard(preference: Preference, neighbor: Preference?): Boolean =
        neighbor != null && neighbor !is PreferenceCategory && isStandardLayout(neighbor) &&
            neighbor.parent === preference.parent

    private fun refreshEdgesAt(vararg positions: Int) {
        val view = recycler ?: return
        pendingEdgePositions.addAll(positions.toList())
        view.removeCallbacks(refreshEdges)
        view.post(refreshEdges)
    }

    private fun tintSwitch(switch: SwitchCompat, context: Context) {
        val active = UiHelper.accentColor(context)
        val inactive = ContextCompat.getColor(context, R.color.ui_shell_text_secondary)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
        switch.thumbTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 97), active, inactive))
        switch.trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 38),
            ColorUtils.setAlphaComponent(active, 89), ColorUtils.setAlphaComponent(inactive, 51)))
    }

    private fun cardBackground(context: Context, top: Boolean, bottom: Boolean): StateListDrawable {
        val radius = context.resources.getDimension(R.dimen.corner_radius_extra_large)
        val radii = floatArrayOf(if (top) radius else 0f, if (top) radius else 0f,
            if (top) radius else 0f, if (top) radius else 0f,
            if (bottom) radius else 0f, if (bottom) radius else 0f,
            if (bottom) radius else 0f, if (bottom) radius else 0f)
        fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
            cornerRadii = radii
            setColor(color)
            if (focused) setStroke((2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                UiHelper.accentFocusColor(context))
        }
        val surface = ContextCompat.getColor(context, R.color.ui_shell_surface_elevated)
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(surface))
            addState(intArrayOf(android.R.attr.state_focused), shape(UiHelper.accentSoftColor(context), true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(ContextCompat.getColor(context, R.color.ui_shell_surface_pressed)))
            addState(intArrayOf(), shape(surface))
        }
    }
}
