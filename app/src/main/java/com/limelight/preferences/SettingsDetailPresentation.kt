package com.limelight.preferences

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.utils.AppTheme

/** Moves the existing preference container into an isolated, full-width detail page. */
internal class SettingsDetailPresentation(private val activity: Activity) {
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun restore(view: View) = view.setPadding(left, top, right, bottom)
    }

    private var shell: ViewGroup? = null
    private var preferenceContainer: ViewGroup? = null
    private var originalParent: ViewGroup? = null
    private var originalIndex = 0
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var originalPadding: Padding? = null
    private var originalChildrenPadding: List<Pair<View, Padding>> = emptyList()
    private var mainRoot: View? = null
    private var originalRootVisibility = View.VISIBLE
    private var originalSystemUiVisibility = 0
    private var originalStatusBarColor = Color.TRANSPARENT
    private var originalNavigationBarColor = Color.TRANSPARENT
    private var onBack: (() -> Unit)? = null
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused && isShowing) updateSystemBars()
    }

    val isShowing: Boolean get() = shell != null

    @Suppress("DEPRECATION")
    fun show(title: CharSequence, onBack: () -> Unit) {
        this.onBack = onBack
        shell?.let {
            it.findViewById<TextView>(R.id.settings_detail_title).text = title
            refreshTheme()
            return
        }

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val container = activity.findViewById<ViewGroup>(R.id.preference_container)
        val parent = container.parent as ViewGroup
        var root: View = container
        while (root.parent !== content && root.parent is View) root = root.parent as View

        preferenceContainer = container
        originalParent = parent
        originalIndex = parent.indexOfChild(container)
        originalLayoutParams = container.layoutParams
        originalPadding = container.paddingSnapshot()
        originalChildrenPadding = (0 until container.childCount).map { index ->
            container.getChildAt(index).let { it to it.paddingSnapshot() }
        }
        mainRoot = root
        originalRootVisibility = root.visibility
        originalSystemUiVisibility = activity.window.decorView.systemUiVisibility
        originalStatusBarColor = activity.window.statusBarColor
        originalNavigationBarColor = activity.window.navigationBarColor

        val detail = LayoutInflater.from(activity).inflate(R.layout.settings_detail_presentation, content, false) as ViewGroup
        shell = detail
        detail.findViewById<TextView>(R.id.settings_detail_title).text = title
        detail.findViewById<View>(R.id.settings_detail_back).setOnClickListener { this.onBack?.invoke() }
        ViewCompat.setOnApplyWindowInsetsListener(detail) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // The original preference fragment also listens for bottom insets.
            // Consume them here so the moved list has exactly one safe-area owner.
            WindowInsetsCompat.CONSUMED
        }

        parent.removeView(container)
        container.setPadding(0, 0, 0, 0)
        originalChildrenPadding.forEach { (child, _) -> child.setPadding(0, 0, 0, 0) }
        detail.findViewById<FrameLayout>(R.id.settings_detail_content).addView(container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.visibility = View.GONE
        content.addView(detail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        detail.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        ViewCompat.requestApplyInsets(detail)
        refreshTheme()
    }

    @Suppress("DEPRECATION")
    fun hide() {
        val detail = shell ?: return
        val container = preferenceContainer ?: return
        val parent = originalParent ?: return
        detail.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(windowFocusListener)
        (container.parent as? ViewGroup)?.removeView(container)
        parent.addView(container, originalIndex.coerceIn(0, parent.childCount), originalLayoutParams)
        originalPadding?.restore(container)
        originalChildrenPadding.forEach { (child, padding) -> padding.restore(child) }
        mainRoot?.visibility = originalRootVisibility
        (detail.parent as? ViewGroup)?.removeView(detail)
        activity.window.decorView.systemUiVisibility = originalSystemUiVisibility
        activity.window.statusBarColor = originalStatusBarColor
        activity.window.navigationBarColor = originalNavigationBarColor
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !AppTheme.wantedNight(activity)
            isAppearanceLightNavigationBars = !AppTheme.wantedNight(activity)
        }
        mainRoot?.let(ViewCompat::requestApplyInsets)
        shell = null
        preferenceContainer = null
        originalParent = null
        originalLayoutParams = null
        originalPadding = null
        originalChildrenPadding = emptyList()
        mainRoot = null
        onBack = null
    }

    fun refreshTheme() {
        val detail = shell ?: return
        val palette = AppTheme.paletteContext(activity)
        detail.setBackgroundColor(ContextCompat.getColor(palette, R.color.game_menu_dialog_background))
        detail.findViewById<TextView>(R.id.settings_detail_title)
            .setTextColor(ContextCompat.getColor(palette, R.color.ui_shell_text_primary))
        detail.findViewById<ImageView>(R.id.settings_detail_back).apply {
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(palette, R.color.ui_shell_text_primary))
            background = ResourcesCompat.getDrawable(palette.resources, R.drawable.settings_detail_back_background, palette.theme)
        }
        preferenceContainer?.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            ?.adapter?.notifyDataSetChanged()
        updateSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        val window = activity.window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = !AppTheme.wantedNight(activity)
            isAppearanceLightNavigationBars = !AppTheme.wantedNight(activity)
        }
        window.statusBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) Color.BLACK else Color.TRANSPARENT
        window.navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) Color.BLACK else Color.TRANSPARENT
    }

    private fun View.paddingSnapshot() = Padding(paddingLeft, paddingTop, paddingRight, paddingBottom)
}
