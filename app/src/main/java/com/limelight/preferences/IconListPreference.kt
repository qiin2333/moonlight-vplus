package com.limelight.preferences

import android.content.Context
import androidx.preference.ListPreference
import android.util.AttributeSet

import com.limelight.Game
import com.limelight.R
import androidx.core.content.withStyledAttributes

class IconListPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    var entryIcons: IntArray? = null
        private set
    private var mOriginalSummary: String? = null
    private var writingOwnSummary = false

    /**
     * 搜索只命中下拉文案时，由设置页提供「包含：…」。
     * 不能从外部 setSummary 追加：那样会被当成新的原始说明保存下来。
     */
    var searchMatchNoteProvider: (() -> CharSequence?)? = null
        set(value) {
            field = value
            refreshSearchMatchNote()
        }

    init {
        context.withStyledAttributes(attrs, R.styleable.IconListPreference) {
            val iconsResId = getResourceId(R.styleable.IconListPreference_entryIcons, 0)
            if (iconsResId != 0) {
                val icons = context.resources.obtainTypedArray(iconsResId)
                entryIcons = IntArray(icons.length()) { icons.getResourceId(it, 0) }
                icons.recycle()
            }
        }

        mOriginalSummary = summary?.toString()

        onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            updateSummary(newValue.toString())

            if (context is Game) {
                context.refreshDisplayPosition()
            }
            true
        }

        updateSummary(value)
    }

    override fun setSummary(summary: CharSequence?) {
        if (!writingOwnSummary &&
            summary != null &&
            (mOriginalSummary == null || !summary.toString().contains(mOriginalSummary!!))
        ) {
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
        val base = if (index >= 0) {
            val currentEntry = entries[index].toString()
            context.getString(R.string.preference_summary_current, mOriginalSummary.orEmpty(), currentEntry)
        } else {
            mOriginalSummary
        }
        val note = searchMatchNoteProvider?.invoke()?.takeIf { it.isNotBlank() }
        writingOwnSummary = true
        try {
            setSummary(if (note == null) base else buildSearchSummary(base, note))
        } finally {
            writingOwnSummary = false
        }
    }

    /** Rebuilds the summary after the settings search query changes. */
    fun refreshSearchMatchNote() {
        updateSummary(value)
    }

    private fun buildSearchSummary(base: CharSequence?, note: CharSequence): CharSequence {
        val builder = android.text.SpannableStringBuilder()
        if (!base.isNullOrBlank()) builder.append(base).append('\n')
        val noteStart = builder.length
        builder.append(note)
        val accent = com.limelight.utils.UiHelper.accentColor(context)
        builder.setSpan(
            android.text.style.ForegroundColorSpan(accent),
            noteStart, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            noteStart, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        updateSummary(value)
    }
}
