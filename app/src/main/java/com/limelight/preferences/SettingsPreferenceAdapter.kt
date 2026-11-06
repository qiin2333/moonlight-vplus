package com.limelight.preferences

import android.annotation.SuppressLint
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.utils.AppTheme
import com.limelight.utils.AppSwitchStyle

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
                refreshEdgesAt(fromPosition - 1, fromPosition, toPosition - 1, toPosition, toPosition + itemCount)
        })
    }

    /** Labels and styles switches, then decorates standard main-page rows as connected cards. */
    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position) ?: return
        // Both preference types keep their own binding and persistence behavior.
        for (id in listOf(android.R.id.checkbox, androidx.preference.R.id.switchWidget)) {
            (holder.findViewById(id) as? SwitchCompat)?.let { switch ->
                AppSwitchStyle.apply(switch)
                switch.contentDescription = preference.title
            }
        }
        if (!mainCards) return
        if (!isStandardLayout(preference)) return
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val inset = holder.itemView.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
        (holder.itemView.layoutParams as? RecyclerView.LayoutParams)?.apply {
            marginStart = inset
            marginEnd = inset
        }
        if (preference is PreferenceCategory) return
        holder.itemView.background = SettingsCardStyle.background(AppTheme.paletteContext(holder.itemView.context),
            !sharesCard(preference, if (position > 0) getItem(position - 1) else null),
            !sharesCard(preference, if (position + 1 < itemCount) getItem(position + 1) else null))
    }

    protected open fun isStandardLayout(preference: Preference): Boolean = isStandardLayoutResource(preference.layoutResource)

    protected fun sharesCard(preference: Preference, neighbor: Preference?): Boolean =
        neighbor != null && neighbor !is PreferenceCategory && isStandardLayout(neighbor) &&
            neighbor.parent === preference.parent

    /** Leaves custom preference layouts outside automatic card decoration. */
    protected fun isStandardLayoutResource(layout: Int): Boolean = when (layout) {
        R.layout.preference_material_compact, R.layout.preference_category_material,
        androidx.preference.R.layout.preference, androidx.preference.R.layout.preference_material,
        androidx.preference.R.layout.preference_category, androidx.preference.R.layout.preference_category_material -> true
        else -> false
    }

    /** Coalesces neighbor rebinds after hierarchy updates so changed group edges get fresh corners. */
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

    /** Cancels deferred decoration and stops notifications before the adapter leaves its page. */
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
