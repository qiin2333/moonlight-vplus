package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder

import com.limelight.R

/**
 * 搜索结果里的分类标题。系统每次重绑都会清掉外部后加的点击，
 * 所以点击放在绑定回调里，标题命中导致整组重绑后也不会丢。
 */
class SearchablePreferenceCategory : PreferenceCategory {
    var searchOpenHandler: ((String) -> Unit)? = null

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val hint = holder.findViewById(R.id.settings_category_open)
        val categoryKey = key
        val canOpen = !categoryKey.isNullOrEmpty() && searchOpenHandler != null
        hint?.visibility = if (canOpen) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener(
            if (canOpen) View.OnClickListener { searchOpenHandler?.invoke(categoryKey) } else null
        )
        holder.itemView.isClickable = canOpen
        holder.itemView.isFocusable = canOpen
    }

    fun refreshSearchAction() = notifyChanged()
}
