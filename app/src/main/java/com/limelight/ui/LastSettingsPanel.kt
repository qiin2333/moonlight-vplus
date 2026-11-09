package com.limelight.ui

import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import com.limelight.R
import com.limelight.utils.AppSwitchStyle
import com.limelight.utils.LastSettingsSummary

/**
 * AppView 底部的「上一次设置」胶囊：收起时只有一行，点按后在上方展开分组详情。
 *
 * 交互约定：
 *  - 胶囊本身是一个焦点（OK 展开/收起）
 *  - 开关是第二个焦点（OK 切换是否沿用）
 *  - 详情卡只读，不参与焦点
 */
class LastSettingsPanel(
    private val root: View,
    private val onReuseChanged: (Boolean) -> Unit
) {
    private val context = root.context
    private val density = root.resources.displayMetrics.density

    private val pill: View = root.findViewById(R.id.lastSettingsPill)
    private val icon: ImageView = root.findViewById(R.id.lastSettingsPillIcon)
    private val title: TextView = root.findViewById(R.id.lastSettingsPillTitle)
    private val metrics: TextView = root.findViewById(R.id.lastSettingsPillMetrics)
    private val more: TextView = root.findViewById(R.id.lastSettingsPillMore)
    private val chevron: ImageView = root.findViewById(R.id.lastSettingsPillChevron)
    private val reuseLabel: TextView = root.findViewById(R.id.lastSettingsPillReuse)
    private val reuseSwitch: SwitchCompat = root.findViewById(R.id.lastSettingsPillSwitch)

    private val detail: View = root.findViewById(R.id.lastSettingsDetail)
    private val detailTime: TextView = root.findViewById(R.id.lastSettingsDetailTime)
    private val detailRows: LinearLayout = root.findViewById(R.id.lastSettingsDetailRows)
    private val detailHint: TextView = root.findViewById(R.id.lastSettingsDetailHint)

    private var expanded = false
    private var reuseEnabled = false
    private var suppressCallback = false

    init {
        AppSwitchStyle.apply(reuseSwitch)
        pill.setOnClickListener { setExpanded(!expanded, animate = true) }
        reuseSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressCallback) {
                return@setOnCheckedChangeListener
            }
            reuseEnabled = isChecked
            applyReuseState()
            onReuseChanged(isChecked)
        }
        applyExpandedState(animate = false)
    }

    /** 主题/横竖屏变化后重新给开关上色。 */
    fun applyTheme() {
        AppSwitchStyle.apply(reuseSwitch)
    }

    /**
     * 绑定当前选中应用的上一次设置。[summary] 为空时不展示整个模块。
     */
    fun bind(summary: LastSettingsSummary?, reuse: Boolean) {
        if (summary == null) {
            setExpanded(false, animate = false)
            root.visibility = View.GONE
            return
        }

        root.visibility = View.VISIBLE

        metrics.text = summary.metricsLine
        detailTime.text = summary.timeLabel

        if (summary.moreCount > 0) {
            more.visibility = View.VISIBLE
            more.text = context.getString(R.string.app_last_settings_more, summary.moreCount)
            more.contentDescription = context.getString(
                R.string.app_last_settings_more_description, summary.moreCount
            )
        } else {
            more.visibility = View.GONE
        }

        rebuildRows(summary)

        suppressCallback = true
        reuseSwitch.isChecked = reuse
        suppressCallback = false
        reuseEnabled = reuse

        pill.contentDescription = context.getString(
            R.string.app_last_settings_pill_description,
            summary.metricsLine,
            summary.timeLabel
        )

        applyReuseState()
        setExpanded(false, animate = false)
    }

    private fun rebuildRows(summary: LastSettingsSummary) {
        detailRows.removeAllViews()
        val inflater = LayoutInflater.from(context)
        summary.rows.forEach { row ->
            val view = inflater.inflate(R.layout.appview_last_settings_row, detailRows, false)
            view.findViewById<TextView>(R.id.lastSettingsRowLabel).text = row.first
            view.findViewById<TextView>(R.id.lastSettingsRowValue).text = row.second
            detailRows.addView(view)
        }
    }

    private fun applyReuseState() {
        val dim = if (reuseEnabled) 1f else 0.55f
        title.alpha = dim
        metrics.alpha = dim
        more.alpha = dim
        icon.alpha = dim
        reuseLabel.alpha = if (reuseEnabled) 1f else 0.72f
        detailHint.setText(
            if (reuseEnabled) R.string.app_last_settings_hint_on
            else R.string.app_last_settings_hint_off
        )
    }

    private fun applyExpandedState(animate: Boolean) {
        val duration = if (animate) 160L else 0L
        chevron.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(duration)
            .start()

        if (expanded) {
            detail.animate().cancel()
            detail.visibility = View.VISIBLE
            if (animate) {
                detail.alpha = 0f
                detail.translationY = 8f * density
                detail.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                detail.alpha = 1f
                detail.translationY = 0f
            }
        } else {
            detail.animate().cancel()
            if (animate && detail.visibility == View.VISIBLE) {
                detail.animate()
                    .alpha(0f)
                    .translationY(8f * density)
                    .setDuration(130L)
                    .withEndAction {
                        detail.visibility = View.GONE
                        detail.alpha = 1f
                        detail.translationY = 0f
                    }
                    .start()
            } else {
                detail.visibility = View.GONE
                detail.alpha = 1f
                detail.translationY = 0f
            }
        }

        ViewCompat.setStateDescription(
            pill,
            context.getString(
                if (expanded) R.string.app_last_settings_collapse else R.string.app_last_settings_expand
            )
        )
    }

    private fun setExpanded(expand: Boolean, animate: Boolean) {
        if (expanded == expand) return
        expanded = expand
        applyExpandedState(animate)
    }
}
