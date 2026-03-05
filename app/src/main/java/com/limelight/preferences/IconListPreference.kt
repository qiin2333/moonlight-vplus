package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.res.TypedArray
import android.preference.ListPreference
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView

import com.limelight.Game
import com.limelight.R

class IconListPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    private var mEntryIcons: IntArray? = null
    private var mOriginalSummary: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.IconListPreference)
        val iconsResId = a.getResourceId(R.styleable.IconListPreference_entryIcons, 0)
        if (iconsResId != 0) {
            val icons = context.resources.obtainTypedArray(iconsResId)
            mEntryIcons = IntArray(icons.length()) { icons.getResourceId(it, 0) }
            icons.recycle()
        }
        a.recycle()

        // 保存原始summary用于以后显示
        mOriginalSummary = summary?.toString()

        // 设置值变化监听器
        onPreferenceChangeListener = OnPreferenceChangeListener { _, newValue ->
            updateSummary(newValue.toString())
            true
        }

        // 初始化summary显示当前值
        updateSummary(value)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        if (entries == null || mEntryIcons == null) {
            super.onPrepareDialogBuilder(builder)
            return
        }

        val adapter = object : ArrayAdapter<CharSequence>(
            context, R.layout.icon_list_item, R.id.text, entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val iconView = view.findViewById<ImageView>(R.id.icon)
                val icons = mEntryIcons
                if (icons != null && position < icons.size && icons[position] != 0) {
                    iconView.setImageResource(icons[position])
                    iconView.visibility = View.VISIBLE
                } else {
                    iconView.visibility = View.GONE
                }
                return view
            }
        }

        builder.setAdapter(adapter, this)
        super.onPrepareDialogBuilder(builder)
    }

    override fun setSummary(summary: CharSequence?) {
        // 如果不是我们程序化设置的summary，保存它作为原始summary
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

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult) {
            updateSummary(value)

            // 如果当前正在游戏中，通知Activity刷新显示位置
            if (context is Game) {
                (context as Game).refreshDisplayPosition()
            }
        }
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restoreValue, defaultValue)
        updateSummary(value)
    }
}
