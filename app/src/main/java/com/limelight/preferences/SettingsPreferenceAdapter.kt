package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.utils.AppTheme
import com.limelight.utils.UiHelper

/** Shared switch styling and card-edge updates for both settings navigation levels. */
@SuppressLint("RestrictedApi")
internal open class SettingsPreferenceAdapter(group: PreferenceGroup, private val mainCards: Boolean = true) : PreferenceGroupAdapter(group) {
    private var stopped = false
    private var recycler: RecyclerView? = null
    private val edgePositions = mutableSetOf<Int>()
    private val refreshEdges = Runnable {
        val positions = edgePositions.toList()
        edgePositions.clear()
        if (!stopped && recycler?.adapter === this) {
            positions.filter { it in 0 until itemCount }.forEach(::notifyItemChanged)
        }
    }

    init {
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                refreshEdgesAt(positionStart - 1, positionStart + itemCount)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                refreshEdgesAt(positionStart - 1, positionStart)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                refreshEdgesAt(fromPosition - 1, fromPosition, toPosition - 1, toPosition + itemCount)
        })
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val palette = AppTheme.paletteContext(holder.itemView.context)
        // Both preference types keep their own binding and persistence behavior.
        (holder.findViewById(android.R.id.checkbox) as? SwitchCompat)?.let { tintSwitch(it, palette) }
        (holder.findViewById(androidx.preference.R.id.switchWidget) as? SwitchCompat)?.let { tintSwitch(it, palette) }
        if (!mainCards) return
        val preference = getItem(position) ?: return
        if (!isStandardLayoutResource(preference.layoutResource)) return
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val inset = holder.itemView.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
        (holder.itemView.layoutParams as? RecyclerView.LayoutParams)?.apply {
            marginStart = inset
            marginEnd = inset
        }
        if (preference is PreferenceCategory) return
        fun sharesCard(neighbor: Preference?) = neighbor != null && neighbor !is PreferenceCategory &&
            isStandardLayoutResource(neighbor.layoutResource) && neighbor.parent === preference.parent
        holder.itemView.background = SettingsCardStyle.background(palette,
            !sharesCard(if (position > 0) getItem(position - 1) else null),
            !sharesCard(if (position + 1 < itemCount) getItem(position + 1) else null))
    }

    protected fun isStandardLayoutResource(layout: Int): Boolean = when (layout) {
        R.layout.preference_material_compact, R.layout.preference_category_material,
        androidx.preference.R.layout.preference, androidx.preference.R.layout.preference_material,
        androidx.preference.R.layout.preference_category, androidx.preference.R.layout.preference_category_material -> true
        else -> false
    }

    private fun tintSwitch(switch: SwitchCompat, context: Context) {
        val active = UiHelper.accentColor(context)
        val inactive = ContextCompat.getColor(context, R.color.ui_shell_text_secondary)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
        switch.thumbTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 97), active, inactive))
        switch.trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 38),
            ColorUtils.setAlphaComponent(active, 89), ColorUtils.setAlphaComponent(inactive, 51)))
    }

    private fun refreshEdgesAt(vararg positions: Int) {
        if (stopped) return
        val view = recycler ?: return
        edgePositions.addAll(positions.toList())
        view.removeCallbacks(refreshEdges)
        view.post(refreshEdges)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeCallbacks(refreshEdges)
        edgePositions.clear()
        recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun stopObservingChanges() {
        stopped = true
        recycler?.removeCallbacks(refreshEdges)
        edgePositions.clear()
    }

    override fun onPreferenceChange(preference: Preference) {
        if (!stopped) super.onPreferenceChange(preference)
    }

    override fun onPreferenceHierarchyChange(preference: Preference) {
        if (!stopped) super.onPreferenceHierarchyChange(preference)
    }

    override fun onPreferenceVisibilityChange(preference: Preference) {
        if (!stopped) super.onPreferenceVisibilityChange(preference)
    }
}
