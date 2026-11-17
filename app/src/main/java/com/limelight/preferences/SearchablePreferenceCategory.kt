package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder

import com.limelight.R

/**
 * 分类标题的说明入口与搜索跳转。系统每次重绑都会清掉外部后加的点击，
 * 所以点击放在绑定回调里，标题命中导致整组重绑后也不会丢。
 */
class SearchablePreferenceCategory : PreferenceCategory {
    var searchOpenHandler: ((String) -> Unit)? = null
    var infoClickHandler: (() -> Unit)? = null

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
        val info = holder.findViewById(R.id.settings_category_info)
        val hasInfo = infoClickHandler != null && info != null
        hint?.visibility = if (canOpen) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener(
            if (canOpen) View.OnClickListener { searchOpenHandler?.invoke(categoryKey) } else null
        )
        holder.itemView.isClickable = canOpen
        holder.itemView.isFocusable = canOpen
        // PreferenceCategory disables its bound children; the independent help action remains usable.
        holder.itemView.isEnabled = canOpen || hasInfo
        info?.apply {
            visibility = if (hasInfo) View.VISIBLE else View.GONE
            isEnabled = hasInfo
            isFocusable = hasInfo
            setOnClickListener(if (hasInfo) View.OnClickListener { infoClickHandler?.invoke() } else null)
            setOnKeyListener(if (canOpen && hasInfo) View.OnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.action == KeyEvent.ACTION_DOWN) holder.itemView.requestFocus()
                    true
                } else false
            } else null)
        }
        holder.itemView.setOnKeyListener(if (canOpen && hasInfo) View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) info?.requestFocus()
                true
            } else false
        } else null)
    }

    fun refreshSearchAction() = notifyChanged()
}
