package com.limelight.ui

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
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
 *  - 展开的详情只读
 */
class LastSettingsPanel(
    private val root: View,
    private val onReuseChanged: (Boolean) -> Unit,
    private val onReturnToApp: () -> Unit
) {
    private val context = root.context
    private val density = root.resources.displayMetrics.density

    private val pill: View = root.findViewById(R.id.lastSettingsPillAction)
    private val icon: ImageView = root.findViewById(R.id.lastSettingsPillIcon)
    private val title: TextView = root.findViewById(R.id.lastSettingsPillTitle)
    private val metrics: TextView = root.findViewById(R.id.lastSettingsPillMetrics)
    private val chevron: ImageView = root.findViewById(R.id.lastSettingsPillChevron)
    private val reuseLabel: TextView = root.findViewById(R.id.lastSettingsPillReuse)
    private val reuseSwitch: SwitchCompat = root.findViewById(R.id.lastSettingsPillSwitch)

    private val detail: View = root.findViewById(R.id.lastSettingsDetail)
    private val detailTime: TextView = root.findViewById(R.id.lastSettingsDetailTime)
    private val detailRows: LinearLayout = root.findViewById(R.id.lastSettingsDetailRows)

    private var expanded = false
    private var reuseEnabled = false
    private var suppressCallback = false

    init {
        applyTheme()
        pill.setOnClickListener { setExpanded(!expanded, animate = true) }
        val controllerKeys = View.OnKeyListener { view, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    view.isPressed = event.action == KeyEvent.ACTION_DOWN && !event.isCanceled
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) view.performClick()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) onReturnToApp()
                    true
                }
                else -> false
            }
        }
        pill.setOnKeyListener(controllerKeys)
        reuseSwitch.setOnKeyListener(controllerKeys)
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

    /** 主题或屏幕方向变化后更新胶囊样式。 */
    fun applyTheme() {
        AppSwitchStyle.apply(reuseSwitch)
        val configuration = root.resources.configuration
        val margin = root.resources.getDimension(R.dimen.appview_content_margin)
        val available = (configuration.screenWidthDp * density - 2 * margin).toInt()
        root.layoutParams = root.layoutParams.apply {
            width = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && available > 0) {
                minOf(root.resources.getDimensionPixelSize(R.dimen.appview_preset_landscape_width), available)
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
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
        icon.alpha = dim
        reuseLabel.alpha = if (reuseEnabled) 1f else 0.72f
    }

    fun collapseDetails(): Boolean {
        if (!expanded) return false
        setExpanded(false, animate = true)
        return true
    }

    private fun applyExpandedState(animate: Boolean) {
        chevron.isActivated = expanded
        chevron.setColorFilter(ContextCompat.getColor(
            context,
            if (expanded) R.color.appview_preset_active else R.color.appview_preset_text_secondary
        ))
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
