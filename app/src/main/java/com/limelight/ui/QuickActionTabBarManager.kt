package com.limelight.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration

/**
 * In-stream quick-action tab bar (drawer-style).
 *
 * Landscape: anchors to the left long edge, vertical orientation, handle on the right side of the panel.
 * Portrait: anchors to the bottom long edge, horizontal orientation, handle on the top side of the panel.
 *
 * Collapsed state shows only a ~12dp edge handle. Tapping the handle slides the panel in.
 * Button clicks are stubs (logged only) in this revision — actions will be wired up in a follow-up
 * by delegating to existing `GameMenu` paths.
 */
class QuickActionTabBarManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
) {

    private var root: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var handle: View? = null

    private var isExpanded: Boolean = false
    private var isHiddenForPip: Boolean = false
    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    fun initialize() {
        val rootView = activity.findViewById<FrameLayout>(R.id.quickActionTabBarRoot) ?: return
        val panelView = rootView.findViewById<LinearLayout>(R.id.quickActionPanel) ?: return
        val handleView = rootView.findViewById<View>(R.id.quickActionHandle) ?: return

        root = rootView
        panel = panelView
        handle = handleView

        handleView.setOnClickListener { toggleExpanded() }
        rootView.findViewById<TextView>(R.id.quickActionBtnClose)?.setOnClickListener { collapse() }

        rootView.findViewById<TextView>(R.id.quickActionBtnOps)?.setOnClickListener {
            LimeLog.info("QuickAction: ops clicked — stub")
        }
        rootView.findViewById<TextView>(R.id.quickActionBtnKeyboard)?.setOnClickListener {
            LimeLog.info("QuickAction: keyboard clicked — stub")
        }
        rootView.findViewById<TextView>(R.id.quickActionBtnShowDesktop)?.setOnClickListener {
            LimeLog.info("QuickAction: showDesktop clicked — stub")
        }
        rootView.findViewById<TextView>(R.id.quickActionBtnShowWindows)?.setOnClickListener {
            LimeLog.info("QuickAction: showWindows clicked — stub")
        }

        currentOrientation = activity.resources.configuration.orientation
        applyGravityForOrientation(currentOrientation)
        applyRequestedVisibility()
        resetToCollapsed()
    }

    fun onConfigurationChanged() {
        val newOrientation = activity.resources.configuration.orientation
        if (newOrientation == currentOrientation) return
        currentOrientation = newOrientation
        applyGravityForOrientation(newOrientation)
        resetToCollapsed()
    }

    fun applyRequestedVisibility() {
        val rootView = root ?: return
        if (isHiddenForPip) {
            rootView.visibility = View.GONE
            return
        }
        rootView.visibility = if (prefConfig.enableQuickActionTabBar) View.VISIBLE else View.GONE
    }

    fun hideForPip() {
        isHiddenForPip = true
        root?.visibility = View.GONE
    }

    fun onPipExited() {
        isHiddenForPip = false
        applyRequestedVisibility()
    }

    private fun applyGravityForOrientation(orientation: Int) {
        val rootView = root ?: return
        val panelView = panel ?: return
        val handleView = handle ?: return

        val rootParams = rootView.layoutParams as? FrameLayout.LayoutParams ?: return
        val handleParams = handleView.layoutParams as? FrameLayout.LayoutParams ?: return

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            rootParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            panelView.orientation = LinearLayout.HORIZONTAL
            // In portrait the drawer slides up from bottom; put handle at the top of the panel
            // so the visible nub sits on the edge facing the stream.
            handleParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            handleParams.width = dp(80)
            handleParams.height = dp(12)
        } else {
            rootParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            panelView.orientation = LinearLayout.VERTICAL
            // In landscape the drawer slides in from the left; handle sits on the right of the panel.
            handleParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            handleParams.width = dp(12)
            handleParams.height = dp(80)
        }

        rootView.layoutParams = rootParams
        handleView.layoutParams = handleParams
    }

    private fun toggleExpanded() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        val panelView = panel ?: return
        isExpanded = true
        panelView.post {
            panelView.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(ANIM_DURATION_MS)
                .start()
        }
    }

    private fun collapse() {
        val panelView = panel ?: return
        isExpanded = false
        panelView.post {
            if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                panelView.animate()
                    .translationX(0f)
                    .translationY(panelView.height.toFloat())
                    .setDuration(ANIM_DURATION_MS)
                    .start()
            } else {
                panelView.animate()
                    .translationX(-panelView.width.toFloat())
                    .translationY(0f)
                    .setDuration(ANIM_DURATION_MS)
                    .start()
            }
        }
    }

    private fun resetToCollapsed() {
        val panelView = panel ?: return
        isExpanded = false
        panelView.post {
            panelView.animate().cancel()
            if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                panelView.translationX = 0f
                panelView.translationY = panelView.height.toFloat()
            } else {
                panelView.translationX = -panelView.width.toFloat()
                panelView.translationY = 0f
            }
        }
    }

    private fun dp(value: Int): Int {
        val density = activity.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    companion object {
        private const val ANIM_DURATION_MS = 180L
    }
}
