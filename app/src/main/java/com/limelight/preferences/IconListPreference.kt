package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.res.TypedArray
import androidx.preference.ListPreference
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView

import com.limelight.Game
import com.limelight.R

class IconListPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    var entryIcons: IntArray? = null
        private set
    private var mOriginalSummary: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.IconListPreference)
        val iconsResId = a.getResourceId(R.styleable.IconListPreference_entryIcons, 0)
        if (iconsResId != 0) {
            val icons = context.resources.obtainTypedArray(iconsResId)
            entryIcons = IntArray(icons.length()) { icons.getResourceId(it, 0) }
            icons.recycle()
        }
        a.recycle()

        mOriginalSummary = summary?.toString()

        onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            updateSummary(newValue.toString())

            if (context is Game) {
                (context as Game).refreshDisplayPosition()
            }
            true
        }

        updateSummary(value)
    }

    override fun setSummary(summary: CharSequence?) {
        if (summary != null && (mOriginalSummary == null || !summary.toString().contains(mOriginalSummary!!))) {
            mOriginalSummary = summary.toString()
        }
        super.setSummary(summary)
    }

    private fun updateSummary(value: String?) {
        val entries = entries
        val entryValues = entryValues

        if (entries == null || entryValues == null) {
            return
        }

        val index = findIndexOfValue(value)
        if (index >= 0) {
            val currentEntry = entries[index].toString()
            val summary = "$mOriginalSummary (当前：$currentEntry)"
            super.setSummary(summary)
        } else {
            super.setSummary(mOriginalSummary)
        }
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        updateSummary(value)
    }
}
