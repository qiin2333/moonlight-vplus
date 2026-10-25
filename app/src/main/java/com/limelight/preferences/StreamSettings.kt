@file:Suppress("DEPRECATION")
package com.limelight.preferences

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.animation.AnimationUtils

import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import com.limelight.services.KeyboardAccessibilityEnabler
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.ui.UiDismissKeyHandler
import rikka.shizuku.Shizuku
import com.limelight.utils.AppTheme
import com.limelight.LimeLog
import com.limelight.PcView
import com.limelight.R
import com.limelight.nvstream.http.AdaptiveBitrateService
import com.limelight.ExternalDisplayManager
import com.limelight.TargetDisplayResolver
import com.limelight.binding.input.InputDeviceSensorPolicy
import com.limelight.binding.audio.MicrophoneButtonPreferences
import com.limelight.binding.audio.MicrophoneButtonPositionStore
import com.limelight.ui.FloatBallPositionStore
import com.limelight.ui.FloatBallPreferences
import com.limelight.binding.input.haptics.GameRumbleMode
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.handbook.HandbookLauncher
import com.limelight.utils.AboutDialogLauncher
import com.limelight.utils.ConfigurationSyncManager
import com.limelight.utils.ConfigurationSyncScheduler
import com.limelight.utils.Dialog
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.AppActionSheet
import com.limelight.utils.HdrCapabilityHelper
import com.limelight.utils.RemoteImeAutoShowPolicy
import com.limelight.ui.screenCombinationModeIconRes
import com.limelight.utils.UiHelper
import com.limelight.utils.UpdateManager
import com.limelight.utils.background.PipwImages
import com.limelight.utils.background.PipwImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import jp.wasabeef.glide.transformations.BlurTransformation
import jp.wasabeef.glide.transformations.ColorFilterTransformation

import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.*

import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class StreamSettings : ThemedAppCompatActivity() {

    private lateinit var previousPrefs: PreferenceConfiguration
    private var previousDisplayPixelCount = 0
    private val detailPresentation by lazy { SettingsDetailPresentation(this) }
    private var detailOwner: SettingsFragment? = null

    private fun showDetailSettings(owner: SettingsFragment, title: CharSequence) {
        if (detailOwner !== owner) detailPresentation.hide()
        detailOwner = owner
        detailPresentation.show(title) { owner.closeSettingsScreen() }
    }

    private fun hideDetailSettings(owner: SettingsFragment) {
        if (detailOwner !== owner) return
        detailPresentation.hide()
        detailOwner = null
    }

    private val backgroundFutureLock = Any()
    @Volatile private var backgroundGeneration = 0
    private var backgroundJob: Job? = null
    private var backgroundTarget: CustomTarget<Drawable>? = null
    private var backgroundFuture: FutureTarget<Drawable>? = null
    private var pipwBackgroundLease: PipwImageStore.Lease? = null
    private var backgroundReceiverRegistered = false
    private val backgroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isDestroyed && !isFinishing) loadBackgroundImage()
        }
    }
    private val backgroundPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BackgroundSource.KEY_SOURCE || key == BackgroundSource.KEY_API_URL || key == BackgroundSource.KEY_LOCAL_PATH) {
            if (!isDestroyed && !isFinishing) loadBackgroundImage()
        }
    }
    private var externalDisplayManager: ExternalDisplayManager? = null

    // 抽屉菜单相关
    private var drawerLayout: DrawerLayout? = null // 竖屏时使用，横屏时为 null
    private var categoryList: RecyclerView? = null
    private var categoryAdapter: CategoryAdapter? = null
    private val categories: MutableList<CategoryItem> = ArrayList()
    private var selectedCategoryIndex = 0

    // 搜索栏：竖屏是可展开按钮，横屏常驻在分类栏 Logo 下方
    private var searchBar: View? = null
    private var searchInput: EditText? = null
    private var searchToggle: ImageView? = null
    private var menuToggleView: ImageView? = null
    // 进入搜索前记住的选中分类；退出搜索（含空结果）后按它恢复
    private var preSearchCategoryKey: String? = null
    private var pendingRestoreCategoryKey: String? = null
    private var lastQueryBlank = true
    private var lastNightMode = false

    // 状态保存键
    companion object {
        private const val KEY_SELECTED_CATEGORY = "selected_category_index"
        private const val KEY_SEARCH_QUERY = "settings.search.query"
        private const val KEY_SEARCH_VISIBLE = "settings.search.visible"
        private const val REQUEST_CODE_PICK_FRAMEGEN_DLL = 4
        private const val REQUEST_CODE_CREATE_CONFIG_SYNC = 5
        private const val REQUEST_CODE_OPEN_CONFIG_SYNC = 6
        private const val REQUEST_CODE_OPEN_CONFIG_SYNC_DIRECTORY = 7

        /**
         * 获取分类对应的 Phosphor 矢量图标资源 ID（与鸿蒙项目一致）。
         */
        private fun getIconForCategory(key: String): Int {
            return when (key) {
                "category_basic_settings" -> R.drawable.phc_settings
                "category_screen_position" -> R.drawable.phc_video_camera
                "category_display_behavior" -> R.drawable.phc_perf_resolution
                "category_audio_settings" -> R.drawable.phc_audio
                "category_microphone_settings" -> R.drawable.ic_mic_gm
                "category_gamepad_settings" -> R.drawable.phc_gamepad
                "category_input_settings" -> R.drawable.phc_keyboard
                "category_onscreen_controls" -> R.drawable.phc_game_controller
                "category_crown_features" -> R.drawable.phc_crown
                "category_host_settings" -> R.drawable.phc_host
                "category_ui_settings" -> R.drawable.phc_lightbulb
                "category_advanced_features" -> R.drawable.phc_lightning   // 性能与流畅度
                "category_framegen_settings" -> R.drawable.phc_video_camera
                "category_help" -> R.drawable.phc_info
                else -> R.drawable.phc_list
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    fun reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
        val previousFragment = supportFragmentManager.findFragmentById(R.id.preference_container) as? SettingsFragment
        val navigation = previousFragment?.saveNavigationState()
        previousFragment?.let(::hideDetailSettings)
        supportFragmentManager.beginTransaction().replace(
                R.id.preference_container, SettingsFragment().apply { arguments = navigation }
        ).commitAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用带阴影的主题
        AppTheme.applyStyle(this, R.style.PreferenceThemeWithShadow)

        super.onCreate(savedInstanceState)
        // Android 13+ system Back reaches this dispatcher directly when predictive Back
        // is enabled, without calling the deprecated Activity.onBackPressed override.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSettingsBack()
            }
        })
        AppTheme.observeAccent(this, this) { refreshAccentColors() }
        ConfigurationSyncScheduler.runNow(this)

        previousPrefs = PreferenceConfiguration.readPreferences(this)

        // 初始化外接显示器管理器
        if (previousPrefs.useExternalDisplay) {
            externalDisplayManager = ExternalDisplayManager(
                this,
                previousPrefs,
                TargetDisplayResolver(this)
            )
            externalDisplayManager?.initialize()
        }

        UiHelper.setLocale(this)

        // 设置自定义布局
        setContentView(R.layout.activity_stream_settings)
        lastNightMode = isNightMode()

        // 启用沉浸式顶栏：内容延伸到状态栏/导航栏下方，系统栏完全透明
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        applySettingsThemeSurfaces()

        UiHelper.notifyNewRootView(this)
        applyNavigationBarInsets()

        // 恢复保存的状态（屏幕旋转时）
        if (savedInstanceState != null) {
            selectedCategoryIndex = savedInstanceState.getInt(KEY_SELECTED_CATEGORY, 0)
        }

        // 初始化抽屉菜单
        initDrawerMenu()
        savedInstanceState?.let { state ->
            searchInput?.setText(state.getString(KEY_SEARCH_QUERY).orEmpty())
            if (state.getBoolean(KEY_SEARCH_VISIBLE)) {
                searchBar?.visibility = View.VISIBLE
                searchToggle?.visibility = View.GONE
                menuToggleView?.visibility = View.GONE
            }
        }

        // 加载背景图片
        loadBackgroundImage()
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(backgroundPrefsListener)
        ContextCompat.registerReceiver(this, backgroundReceiver,
            IntentFilter(BackgroundSource.ACTION_REFRESH), ContextCompat.RECEIVER_NOT_EXPORTED)
        backgroundReceiverRegistered = true

        // 设置版本号
        setupVersionInfo()
    }

    private fun applyNavigationBarInsets() {
        val insetSource = findViewById<View>(R.id.drawer_layout)
            ?: findViewById(R.id.settings_layout_root)
        val drawerMenu = findViewById<View>(R.id.drawer_menu)
        val preferenceContainer = findViewById<View>(R.id.preference_container)
        val initialDrawerPaddingLeft = drawerMenu.paddingLeft
        val initialDrawerPaddingTop = drawerMenu.paddingTop
        val initialDrawerPaddingRight = drawerMenu.paddingRight
        val initialDrawerPaddingBottom = drawerMenu.paddingBottom
        val initialPaddingLeft = preferenceContainer.paddingLeft
        val initialPaddingTop = preferenceContainer.paddingTop
        val initialPaddingRight = preferenceContainer.paddingRight
        val initialPaddingBottom = preferenceContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(insetSource) { _, windowInsets ->
            if (detailPresentation.isShowing) return@setOnApplyWindowInsetsListener windowInsets
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val safeLeft = maxOf(systemBars.left, displayCutout.left)
            val safeRight = maxOf(systemBars.right, displayCutout.right)
            val safeBottom = maxOf(systemBars.bottom, displayCutout.bottom)

            if (isLandscape) {
                drawerMenu.setPadding(
                    initialDrawerPaddingLeft + safeLeft,
                    initialDrawerPaddingTop,
                    initialDrawerPaddingRight,
                    initialDrawerPaddingBottom + safeBottom
                )
            }

            preferenceContainer.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight + if (isLandscape) safeRight else 0,
                initialPaddingBottom + if (isLandscape) safeBottom else 0
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(insetSource)
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun refreshAccentColors() {
        categoryAdapter?.notifyDataSetChanged()
        val fragment = supportFragmentManager.findFragmentById(R.id.preference_container) as? SettingsFragment
        fragment?.view?.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            ?.adapter?.notifyDataSetChanged()
        applySearchBarTheme()
        detailPresentation.refreshTheme()
        val accent = ColorStateList.valueOf(UiHelper.accentColor(this))
        findViewById<ImageView>(R.id.settings_search_toggle)?.imageTintList = accent
        findViewById<TextView>(R.id.drawer_version)?.setTextColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(UiHelper.accentColor(this), 69))
    }

    private fun applySettingsThemeSurfaces() {
        findViewById<View>(R.id.settingsBackgroundOverlay)?.setBackgroundColor(
                ContextCompat.getColor(this, R.color.settings_background_overlay)
        )
        // 横屏侧栏背景在布局创建时解析。深浅切换不重建 Activity，需要按当前主题重设。
        findViewById<View>(R.id.drawer_menu)?.setBackgroundColor(
                ContextCompat.getColor(this, R.color.settings_drawer_background_landscape)
        )
        findViewById<ImageView>(R.id.settings_back)?.setColorFilter(
                ContextCompat.getColor(this, R.color.ui_shell_text_primary)
        )
        applySearchBarTheme()
        val useDarkSystemIcons = !isNightMode()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkSystemIcons
            isAppearanceLightNavigationBars = useDarkSystemIcons
        }
    }

    /** 搜索框的底色、描边、图标和文字都在创建时解析，深浅切换时按当前主题重设。 */
    private fun applySearchBarTheme() {
        val searchBar = findViewById<View>(R.id.settings_search_bar) ?: return
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.corner_radius_extra_large)
            setColor(ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_surface_elevated))
            setStroke(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                UiHelper.accentFocusColor(this@StreamSettings)
            )
        }
        searchBar.background = background
        val accent = ColorStateList.valueOf(UiHelper.accentColor(this))
        searchBar.findViewById<ImageView>(R.id.settings_search_icon)?.imageTintList = accent
        searchBar.findViewById<ImageView>(R.id.settings_search_close)?.imageTintList = accent
        searchBar.findViewById<TextView>(R.id.settings_search_input)?.apply {
            setTextColor(ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_text_primary))
            setHintTextColor(ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_text_secondary))
        }
    }

    /**
     * 设置版本号显示
     */
    @SuppressLint("SetTextI18n")
    private fun setupVersionInfo() {
        val versionText = findViewById<TextView>(R.id.drawer_version) ?: return
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            versionText.text = "v$versionName"
        } catch (e: PackageManager.NameNotFoundException) {
            versionText.visibility = View.GONE
        }
    }

    /**
     * 初始化抽屉菜单
     * 竖屏使用 DrawerLayout，横屏使用并排布局
     */
    private fun initDrawerMenu() {
        // 横屏布局使用独立根容器 ID，因此这里只会取得竖屏 DrawerLayout。
        drawerLayout = findViewById(R.id.drawer_layout)

        categoryList = findViewById(R.id.category_list)

        setupMenuToggle()
        setupBackButton()
        setupCategoryList()
        setupDrawerListener()
        setupSearchBar()
    }

    /**
     * 横屏分类栏顶部的返回。竖屏用系统返回和抽屉，没有这个按钮。
     */
    private fun setupBackButton() {
        val backButton = findViewById<ImageView>(R.id.settings_back) ?: return
        backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        backButton.isFocusable = true
        backButton.isFocusableInTouchMode = false
    }

    /**
     * 设置菜单按钮（仅竖屏有效）
     */
    private fun setupMenuToggle() {
        val menuToggle = findViewById<ImageView>(R.id.settings_menu_toggle) ?: return
        menuToggle.setOnClickListener { openDrawer() }
        menuToggle.isFocusable = true
        menuToggle.isFocusableInTouchMode = false
    }

    /**
     * 竖屏：浮动搜索按钮展开顶部搜索栏。
     * 横屏：搜索框常驻在分类栏 Logo 下方，没有展开按钮，清空按钮只清内容。
     */
    private fun setupSearchBar() {
        searchBar = findViewById(R.id.settings_search_bar)
        searchInput = findViewById(R.id.settings_search_input)
        searchToggle = findViewById(R.id.settings_search_toggle)
        menuToggleView = findViewById(R.id.settings_menu_toggle)
        val closeBtn = findViewById<ImageView?>(R.id.settings_search_close)

        if (searchBar == null || searchInput == null) return

        searchToggle?.setOnClickListener { showSearchBar() }
        closeBtn?.setOnClickListener {
            if (searchToggle == null) {
                clearSearchQuery()
            } else {
                hideSearchBar()
            }
        }

        searchInput?.doAfterTextChanged { applyFilterToFragment(it?.toString().orEmpty()) }

        // IME 回车：仅收起键盘，保留过滤结果
        searchInput?.setOnEditorActionListener { v, _, _ ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        }
    }

    private val isSearchBarVisible: Boolean
        get() = searchToggle != null && searchBar?.visibility == View.VISIBLE

    private fun fadeIn(v: View?) {
        v ?: return
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(180L).start()
    }

    private fun fadeOut(v: View?) {
        v ?: return
        v.animate().alpha(0f).setDuration(140L).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun showSearchBar() {
        fadeOut(searchToggle)
        fadeOut(menuToggleView)
        fadeIn(searchBar)
        searchInput?.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        clearSearchQuery()
        fadeOut(searchBar)
        fadeIn(searchToggle)
        fadeIn(menuToggleView)
    }

    private fun clearSearchQuery() {
        searchInput?.setText("")
        applyFilterToFragment("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput?.windowToken, 0)
    }

    private fun applyFilterToFragment(query: String) {
        val searching = query.isNotBlank()
        if (searching && lastQueryBlank) {
            // 空结果会清掉分类列表，进入搜索前记住当前分类
            preSearchCategoryKey = categories.getOrNull(selectedCategoryIndex)?.key
        }
        if (!searching && !lastQueryBlank && preSearchCategoryKey != null) {
            pendingRestoreCategoryKey = preSearchCategoryKey
            preSearchCategoryKey = null
        }
        lastQueryBlank = !searching
        val fragment = supportFragmentManager
                .findFragmentById(R.id.preference_container) as? SettingsFragment
        fragment?.applySearchFilter(query)
    }

    /**
     * 设置分类列表
     */
    private fun setupCategoryList() {
        val list = categoryList ?: return
        list.layoutManager = LinearLayoutManager(this)
        categoryAdapter = CategoryAdapter()
        list.adapter = categoryAdapter
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        list.isFocusable = true
    }

    /**
     * 设置抽屉监听器（仅竖屏有效）
     */
    private fun setupDrawerListener() {
        if (drawerLayout == null) return

        drawerLayout?.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                focusSelectedCategory()
            }

            override fun onDrawerClosed(drawerView: View) {
                focusPreferenceList()
            }
        })
    }

    /**
     * 打开抽屉（仅竖屏有效）
     */
    private fun openDrawer() {
        drawerLayout?.openDrawer(findViewById(R.id.drawer_menu))
    }

    /**
     * 聚焦到选中的分类项
     */
    private fun focusSelectedCategory() {
        if (categoryList != null && categoryAdapter != null && (categoryAdapter?.itemCount ?: 0) > 0) {
            categoryList?.post {
                val vh = categoryList?.findViewHolderForAdapterPosition(selectedCategoryIndex)
                vh?.itemView?.requestFocus()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UpdateManager.INSTALL_PERMISSION_REQUEST_CODE) {
            UpdateManager.onInstallPermissionResult(this)
        }
    }

    /**
     * 聚焦到设置列表
     */
    private fun focusPreferenceList() {
        val preferenceContainer = findViewById<View>(R.id.preference_container)
        preferenceContainer?.requestFocus()
    }

    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * 分类数据项
     */
    class CategoryItem(var key: String, var title: String, var iconRes: Int)

    /**
     * 分类菜单适配器
     */
    inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.category_title)
            val icon: ImageView = itemView.findViewById(R.id.category_icon)
            val indicator: View = itemView.findViewById(R.id.category_indicator)
            val root: View = itemView.findViewById(R.id.category_item_root)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_category_menu, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = categories[position]
            holder.root.background = ContextCompat.getDrawable(this@StreamSettings, R.drawable.category_item_background)
            holder.indicator.background = ContextCompat.getDrawable(this@StreamSettings, R.drawable.category_indicator_shape)
            // Phosphor 矢量图标 + 文本（图标颜色随选中态在 updateItemAppearance 中切换）
            holder.icon.setImageResource(item.iconRes)
            holder.title.text = item.title

            // 高亮选中项
            val isSelected = position == selectedCategoryIndex
            updateItemAppearance(holder, isSelected, false)

            // 点击事件
            holder.root.setOnClickListener { selectCategory(holder.bindingAdapterPosition, item) }

            // 焦点变化事件（控制器支持）
            holder.root.setOnFocusChangeListener { _, hasFocus ->
                val selected = holder.bindingAdapterPosition == selectedCategoryIndex
                updateItemAppearance(holder, selected, hasFocus)
            }
        }

        /**
         * 更新菜单项的外观（选中/焦点状态）- 精致风格
         */
        private fun updateItemAppearance(holder: ViewHolder, isSelected: Boolean, hasFocus: Boolean) {
            // 使用项目公共粉色主题
            val accentColor = UiHelper.accentColor(this@StreamSettings)
            val primaryText = ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_text_primary)
            val secondaryText = ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_text_secondary)
            val subtleText = ContextCompat.getColor(this@StreamSettings, R.color.ui_shell_outline_strong)

            val isLandscapeSidebar = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // 横屏常驻侧栏通过图标和文字颜色表示状态，释放装饰元素占用的标题空间。
            holder.indicator.visibility = when {
                isLandscapeSidebar -> View.GONE
                isSelected -> View.VISIBLE
                else -> View.INVISIBLE
            }

            // 文字 + 图标颜色三态切换
            val textColor: Int; val textAlpha: Float; val iconColor: Int; val iconAlpha: Float
            when {
                isSelected -> { textColor = primaryText; textAlpha = 1.0f; iconColor = accentColor; iconAlpha = 1.0f }
                hasFocus   -> { textColor = accentColor; textAlpha = 1.0f; iconColor = accentColor; iconAlpha = 0.95f }
                else       -> { textColor = secondaryText; textAlpha = 0.9f; iconColor = secondaryText; iconAlpha = 0.7f }
            }
            holder.title.setTextColor(textColor)
            holder.title.alpha = textAlpha
            holder.icon.setColorFilter(iconColor)
            holder.icon.alpha = iconAlpha

            // 箭头透明度和颜色
            val arrow = holder.root.findViewById<ImageView>(R.id.category_arrow)
            if (arrow != null) {
                arrow.visibility = if (isLandscapeSidebar) View.GONE else View.VISIBLE
                if (!isLandscapeSidebar && isSelected) {
                    arrow.alpha = 1.0f
                    arrow.setColorFilter(accentColor)
                } else if (!isLandscapeSidebar && hasFocus) {
                    arrow.alpha = 0.9f
                    arrow.setColorFilter(accentColor)
                } else if (!isLandscapeSidebar) {
                    arrow.alpha = 0.4f
                    arrow.setColorFilter(subtleText)
                }
            }
        }

        /**
         * 选择分类
         */
        private fun selectCategory(position: Int, item: CategoryItem) {
            if (position < 0 || position >= categories.size) return

            val oldIndex = selectedCategoryIndex
            selectedCategoryIndex = position

            // 确保 oldIndex 有效再通知更新
            if (oldIndex in 0 until categories.size) {
                notifyItemChanged(oldIndex)
            }
            notifyItemChanged(selectedCategoryIndex)

            // 滚动到对应分类
            scrollToCategory(item.key)

            // 竖屏时关闭抽屉（横屏时 drawerLayout 为 null，无需处理）
            if (drawerLayout != null) {
                drawerLayout?.closeDrawers()
            }
        }

        override fun getItemCount(): Int = categories.size
    }

    /**
     * 滚动到指定分类
     */
    fun scrollToCategory(categoryKey: String) {
        val fragment = supportFragmentManager
                .findFragmentById(R.id.preference_container) as? SettingsFragment
        fragment?.scrollToCategoryByKey(categoryKey)
    }

    /**
     * 通知 Activity 分类已加载
     */
    fun onCategoriesLoaded(loadedCategories: List<CategoryItem>) {
        val hadCategories = categories.isNotEmpty()
        val restoredIndex = selectedCategoryIndex
        val selectedKey = categories.getOrNull(selectedCategoryIndex)?.key
        categories.clear()
        categories.addAll(loadedCategories)

        // 退出搜索时优先回到进入前的分类；空结果时上面的 selectedKey 已丢，
        // 没有这个快照会强制跳回第 0 项
        val restoreIndex = pendingRestoreCategoryKey
            ?.let { key -> categories.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
        pendingRestoreCategoryKey = null

        val keptIndex = selectedKey
            ?.let { key -> categories.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
        selectedCategoryIndex = when {
            restoreIndex != null -> restoreIndex
            keptIndex != null -> keptIndex
            !hadCategories && categories.isNotEmpty() ->
                restoredIndex.coerceIn(categories.indices)
            hadCategories && categories.isNotEmpty() -> 0
            else -> restoredIndex
        }

        categoryAdapter?.notifyDataSetChanged()
        if (categories.isNotEmpty() &&
            (restoreIndex != null || (hadCategories && keptIndex == null))
        ) {
            // 侧栏高亮回到恢复/兜底分类的同时右窗格也滚过去，
            // 否则同一 adapter 位置在全量列表里已经是别的内容
            categoryList?.scrollToPosition(selectedCategoryIndex)
            categories.getOrNull(selectedCategoryIndex)?.let { scrollToCategory(it.key) }
        }
    }

    /**
     * 更新选中的分类
     */
    fun updateSelectedCategory(index: Int) {
        if (index == selectedCategoryIndex || index < 0 || index >= categories.size) return

        val oldIndex = selectedCategoryIndex
        selectedCategoryIndex = index
        // 确保 oldIndex 有效再通知更新
        if (oldIndex in 0 until categories.size) {
            categoryAdapter?.notifyItemChanged(oldIndex)
        }
        categoryAdapter?.notifyItemChanged(selectedCategoryIndex)
    }

    /**
     * 更新抽屉布局模式（仅竖屏有效）
     * 横屏使用并排的 LinearLayout，不需要 DrawerLayout 操作
     * 竖屏：默认关闭，可通过菜单按钮打开
     */
    private fun updateDrawerMode() {
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return

        // 以下代码仅在竖屏时执行
        val drawerMenu = findViewById<View>(R.id.drawer_menu)
        val menuToggle = findViewById<ImageView>(R.id.settings_menu_toggle)

        // 竖屏：可收起抽屉
        drawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, drawerMenu)
        drawerLayout?.setScrimColor(0x99000000.toInt())

        // 关闭抽屉
        if (drawerLayout?.isDrawerOpen(drawerMenu) == true) {
            drawerLayout?.closeDrawer(drawerMenu, false)
        }

        menuToggle?.visibility = View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // 设置抽屉模式
        updateDrawerMode()

        // FragmentManager restores the preference view and an unconfirmed multi-choice
        // draft before this callback. Replacing it here would discard both on rotation.
        if (supportFragmentManager.findFragmentById(R.id.preference_container) !is SettingsFragment) {
            reloadSettings()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val isUsingPortraitDrawerLayout = findViewById<View>(R.id.drawer_layout) is DrawerLayout
        val shouldUsePortraitDrawerLayout = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (isUsingPortraitDrawerLayout != shouldUsePortraitDrawerLayout) {
            recreate()
            return
        }

        // 更新抽屉模式
        updateDrawerMode()
        val nightModeChanged = lastNightMode != isNightMode()
        lastNightMode = isNightMode()
        applySettingsThemeSurfaces()
        loadBackgroundImage()
        var shouldReloadSettings = nightModeChanged
        if (nightModeChanged) {
            AppTheme.applyStyle(this, R.style.PreferenceThemeWithShadow)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.physicalWidth * mode.physicalHeight != previousDisplayPixelCount) {
                shouldReloadSettings = true
            }
        }
        if (shouldReloadSettings) {
            reloadSettings()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleDrawerKeyEvent(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (activeCustomDialogController?.dispatchAxes(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.preference_container) as? SettingsFragment
        if (event.action == KeyEvent.ACTION_DOWN) fragment?.recordSettingsHardwareInput()
        if (fragment?.activeSettingsScreenKey != null &&
            UiDismissKeyHandler.handle(event.action, event.keyCode) { fragment.closeSettingsScreen() }) return true
        // Android 5 Views do not consistently map a controller's A button to activation.
        val navigationEvent = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
        } else event
        return super.dispatchKeyEvent(navigationEvent)
    }

    /**
     * 处理控制器按键事件（抽屉导航）
     *
     * 手柄支持（仅竖屏有效，横屏时菜单固定显示）：
     * - L1/L2：打开抽屉菜单
     * - R1/R2：关闭抽屉菜单
     * - D-pad 左：打开抽屉
     * - D-pad 右：关闭抽屉（从抽屉内）
     * - B 键：关闭抽屉
     */
    private fun handleDrawerKeyEvent(keyCode: Int): Boolean {
        if ((supportFragmentManager.findFragmentById(R.id.preference_container) as? SettingsFragment)
                ?.activeSettingsScreenKey != null) return false
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return false

        // 以下代码仅在竖屏时执行
        val drawerMenu = findViewById<View>(R.id.drawer_menu)
        val isDrawerOpen = drawerLayout?.isDrawerOpen(drawerMenu)

        // L1/L2：打开抽屉
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (isDrawerOpen != true) {
                drawerLayout?.openDrawer(drawerMenu)
                return true
            }
        }

        // R1/R2：关闭抽屉
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (isDrawerOpen == true) {
                drawerLayout?.closeDrawer(drawerMenu)
                return true
            }
        }

        // D-pad 左键：打开抽屉
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (isDrawerOpen != true) {
                drawerLayout?.openDrawer(drawerMenu)
                return true
            }
        }

        // D-pad 右键：关闭抽屉（从抽屉内）
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (isDrawerOpen == true) {
                val focusedView = currentFocus
                if (focusedView != null && isViewInsideDrawer(focusedView)) {
                    drawerLayout?.closeDrawer(drawerMenu)
                    return true
                }
            }
        }

        // B 键（手柄）：关闭抽屉
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (isDrawerOpen == true) {
                drawerLayout?.closeDrawer(drawerMenu)
                return true
            }
        }

        return false
    }

    /**
     * 检查视图是否在抽屉内
     */
    private fun isViewInsideDrawer(view: View): Boolean {
        val drawerMenu = findViewById<View>(R.id.drawer_menu) ?: return false

        var current: View? = view
        while (current != null) {
            if (current === drawerMenu) return true
            current = if (current.parent is View) current.parent as View else null
        }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存选中的分类索引，用于屏幕旋转后恢复
        outState.putInt(KEY_SELECTED_CATEGORY, selectedCategoryIndex)
        outState.putString(KEY_SEARCH_QUERY, searchInput?.text?.toString())
        outState.putBoolean(KEY_SEARCH_VISIBLE, isSearchBarVisible)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(backgroundPrefsListener)
        if (backgroundReceiverRegistered) {
            unregisterReceiver(backgroundReceiver)
            backgroundReceiverRegistered = false
        }
        cancelBackgroundLoad()
        pipwBackgroundLease?.close()
        pipwBackgroundLease = null
        AboutDialogLauncher.release(this)
        UpdateManager.cleanup()
        super.onDestroy()
        externalDisplayManager?.cleanup()
        externalDisplayManager = null
    }

    private fun handleSettingsBack() {
        if ((supportFragmentManager.findFragmentById(R.id.preference_container) as? SettingsFragment)
                ?.closeSettingsScreen() == true) return
        // 搜索栏可见时，优先关闭搜索而不是退出
        if (isSearchBarVisible) {
            hideSearchBar()
            return
        }
        // 横屏常驻搜索框没有展开按钮，isSearchBarVisible 恒 false；
        // 有查询内容时返回先清搜索再退出，与竖屏行为一致
        if (!searchInput?.text?.toString().isNullOrBlank()) {
            clearSearchQuery()
            return
        }

        if (handleBackForDrawer()) {
            return
        }

        finish()
        handleLanguageChange()
    }

    /**
     * 处理返回键时的抽屉关闭逻辑（仅竖屏有效）
     */
    private fun handleBackForDrawer(): Boolean {
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return false

        // 以下代码仅在竖屏时执行
        val drawerMenu = findViewById<View>(R.id.drawer_menu)
        if (drawerLayout?.isDrawerOpen(drawerMenu) != true) return false

        drawerLayout?.closeDrawer(drawerMenu)
        return true
    }

    /**
     * 处理语言变更后的界面刷新（Android 13 以下）
     */
    private fun handleLanguageChange() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val newPrefs = PreferenceConfiguration.readPreferences(this)
            if (newPrefs.language != previousPrefs.language) {
                val intent = Intent(this, PcView::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent, null)
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private companion object {
            private const val SCREEN_COMBINATION_MODE_PREF_KEY = "list_screen_combination_mode"
            private const val BACKGROUND_STREAM_BEHAVIOR_PREF_KEY = "list_background_stream_behavior"
            private const val QUIT_BEHAVIOR_PREF_KEY = "list_quit_behavior"
            private const val DUALSENSE_OUTPUT_MODE_PREF_KEY = "list_dualsense_output_mode"
            private const val BLUETOOTH_CONNECT_PERMISSION_REQUEST = 4721
            private const val MANAGE_DISPLAY_RESOLUTIONS_ACTION = -1
            private const val MULTI_CHOICE_KEY = "settings.multiChoice.key"
            private const val MULTI_CHOICE_VALUES = "settings.multiChoice.values"
            private const val MULTI_CHOICE_CHANGED = "settings.multiChoice.changed"
            private const val FLOAT_BALL_SCREEN_KEY = "settings_float_ball"
            private const val DETAIL_SCREEN = "settings.detail.screen"
            private const val DETAIL_FOCUS = "settings.detail.focus"
            private const val DETAIL_HARDWARE = "settings.detail.hardware"
            private const val MAIN_LIST_STATE = "settings.main.listState"
            private const val DIALOG_RETURN_KEY = "settings.dialog.returnKey"
            private val PREFERENCE_DIALOG_TAGS = setOf(
                "SeekBarPreference", "StyledEditTextPreference", "ConfirmDeleteOscPreference"
            )
            private val FEATURE_MASTER_KEYS = mapOf(
                "settings_audio_passthrough" to "checkbox_enable_audio_passthrough",
                "settings_audio_vibration" to "checkbox_audio_vibration",
                "settings_performance_overlay" to "checkbox_enable_perf_overlay",
            )
        }

        private var pendingSyncExportString: String = ""
        private var pendingSyncImportString: String = ""
        private val configSyncSnapshotHandler = Handler(Looper.getMainLooper())
        private val configSyncSnapshotRunnable = Runnable {
            writeConfigSyncLocalSnapshot(showToast = false, requireAutoEnabled = true)
        }
        private val configSyncPreferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                refreshSettingsPresentation(key)
                if (!suppressConfigSyncPreferenceChanges) {
                    handleConfigSyncPreferenceChanged(key)
                }
            }
        private var configSyncSnapshotDirty = false
        private var configSyncSnapshotInProgress = false
        private var configSyncPreferenceListenerRegistered = false
        private var suppressConfigSyncPreferenceChanges = false
        private var pendingDualSenseOutputMode: DualSenseOutputMode? = null
        var activeSettingsScreenKey: String? = null
            private set
        private var mainListState: Parcelable? = null
        private var mainListPadding: IntArray? = null
        private var detailHardwareInput = false
        private var pendingNavigationFocusKey: String? = null
        private var navigationGeneration = 0
        private val navigationWindowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) restoreNavigationFocus()
        }

        private var dialogReturnPreferenceKey: String? = null
        private var dialogHardwareInput = false
        private val preferenceDialogCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, fragment: Fragment) {
                if (fragment.tag !in PREFERENCE_DIALOG_TAGS) return
                val dialog = (fragment as? DialogFragment)?.dialog ?: return
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        dialogHardwareInput = true
                        recordSettingsHardwareInput()
                    }
                    if (UiDismissKeyHandler.handle(event.action, keyCode, dialog::cancel)) true
                    else if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                        dialog.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action,
                            KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState,
                            event.deviceId, event.scanCode, event.flags, event.source))
                    } else false
                }
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
                if (fragment.tag in PREFERENCE_DIALOG_TAGS &&
                    !settingsChoiceViewDestroyed && (dialogHardwareInput || listView?.isInTouchMode == false)) {
                    dialogReturnPreferenceKey?.let { key ->
                        findPreference<Preference>(key)?.let { restoreSettingsChoiceFocus(it, settingsChoiceGeneration) }
                    }
                }
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            dialogReturnPreferenceKey = savedInstanceState?.getString(DIALOG_RETURN_KEY)
            parentFragmentManager.registerFragmentLifecycleCallbacks(preferenceDialogCallbacks, false)
        }

        override fun onDestroy() {
            parentFragmentManager.unregisterFragmentLifecycleCallbacks(preferenceDialogCallbacks)
            super.onDestroy()
        }

        // 分类列表（用于抽屉菜单同步）
        private val categoryList: MutableList<PreferenceCategory> = ArrayList()
        private var currentCategoryIndex = 0
        // 标记是否正在手动滚动（点击分类触发的滚动）
        private var isManualScrolling = false

        // 缓存 category -> adapter position 映射，避免每帧 O(N*M) 全表扫
        // 仅在数据集变化时失效；滚动期间复用
        private var categoryPositions: IntArray = IntArray(0)
        private var categoryPositionsValid = false
        private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null
        @Volatile
        private var developerUnlockVerificationRunning = false
        @Volatile
        private var developerPendingDeviceCode: GitHubStarVerifier.DeviceCode? = null
        @Volatile
        private var developerLastForegroundPollMs = 0L
        @Volatile
        private var developerForegroundPollRunning = false
        private var developerDeviceCodeDialog: AlertDialog? = null
        private var customResolutionsDialog: android.app.Dialog? = null
        private var settingsChoiceDialog: android.app.Dialog? = null
        private var settingsChoiceGeneration = 0
        private var settingsChoiceViewDestroyed = false
        private data class MultiChoiceDraft(val key: String, val values: Set<String>, val changed: Boolean = false)
        private var multiChoiceDraft: MultiChoiceDraft? = null
        private var multiChoiceSession: Any? = null

        /**
         * 获取目标显示器（优先使用外接显示器）
         */
        private fun getTargetDisplay(): Display {
            val settingsActivity = activity as? StreamSettings
            return settingsActivity?.externalDisplayManager?.getTargetDisplay()
                ?: requireActivity().windowManager.defaultDisplay
        }

        private fun appDialogBuilder(context: Context = requireContext()): AlertDialog.Builder {
            return AlertDialog.Builder(context, R.style.AppDialogStyle)
        }

        private fun AlertDialog.Builder.showStyled(): AlertDialog {
            return showStyledDialog(create())
        }

        private fun showStyledDialog(dialog: AlertDialog): AlertDialog {
            hideKeyboardBeforeDialog()
            dialog.show()
            AppDialogStyler.apply(dialog, requireContext())
            return dialog
        }

        private fun hideKeyboardBeforeDialog() {
            val hostActivity = activity ?: return
            val focusedView = hostActivity.currentFocus ?: view ?: return
            val imm = hostActivity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }

        private fun replaceSavedResolutionEntries() {
            val pref = findPreference<DisplayChoicePreference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!
            val labels = DisplayChoiceLists.labelLookup(
                resources, R.array.resolution_names, R.array.resolution_values
            )
            val saved = savedSelection(pref, PreferenceConfiguration.DEFAULT_RESOLUTION)
            val displayNative = DisplayChoiceLists.nativeSize(requireActivity(), getTargetDisplay())
            val storedValues = DisplayChoiceLists.resolutionStoreValues(requireActivity())
            val choiceValues = DisplayChoiceLists.resolutionValues(
                storedValues,
                saved
            )
            val choiceLabels = choiceValues.map { value ->
                when {
                    value == PreferenceConfiguration.RES_NATIVE -> getString(R.string.resolution_prefix_native)
                    else -> labels[value] ?: value.replace("x", "×")
                }
            }
            val subs = choiceValues.map { value ->
                if (value == PreferenceConfiguration.RES_NATIVE) {
                    val size = displayNative?.let { "${it.first}×${it.second}" } ?: "—"
                    getString(R.string.display_native_resolution_description, size)
                } else if (value == saved && value !in storedValues) {
                    getString(R.string.display_resolution_current_not_saved)
                } else if (value in labels) {
                    value.replace("x", "×")
                } else {
                    null
                }
            }
            pref.replaceChoices(
                choiceLabels,
                choiceValues,
                saved,
                choiceValues.filter { value ->
                    value == PreferenceConfiguration.RES_NATIVE || displayLimits().supportsResolution(value)
                }.toSet(),
                subs
            )
            pref.setAction(getString(R.string.display_custom_resolution_action))
        }

        private fun openCustomResolutionsEditor(pref: DisplayChoicePreference, restoreHardwareFocus: Boolean) {
            customResolutionsDialog = CustomResolutionsDialog.show(
                requireContext(),
                targetDisplay = getTargetDisplay(),
                requestInitialFocus = restoreHardwareFocus,
                onClosed = {
                    customResolutionsDialog = null
                    if (isAdded && !settingsChoiceViewDestroyed) {
                        replaceSavedResolutionEntries()
                        visibilityController.refreshSearch()
                        refreshSearchPresentation()
                        listView?.post {
                            if (isAdded && !settingsChoiceViewDestroyed && findAdapterPositionForPreference(pref) >= 0) {
                                showListChoiceSheet(pref, MANAGE_DISPLAY_RESOLUTIONS_ACTION, restoreHardwareFocus)
                            }
                        }
                    }
                }
            )
        }

        private fun replaceSavedFrameRateEntries() {
            val pref = findPreference<DisplayChoicePreference>(PreferenceConfiguration.FPS_PREF_STRING)!!
            val limits = displayLimits()
            val saved = savedSelection(pref, PreferenceConfiguration.DEFAULT_FPS)
            val choiceValues = DisplayChoiceLists.frameRateValues(
                resources.getStringArray(R.array.fps_values).toList(),
                limits.maxRefreshRate,
                listOf(saved)
            )
            pref.replaceChoices(
                choiceValues.map { value ->
                    val label = "$value ${getString(R.string.fps_suffix_fps)}"
                    if (limits.exceedsRefreshRate(value)) {
                        "$label ${getString(R.string.display_frame_rate_above_refresh_rate_marker)}"
                    } else label
                },
                choiceValues,
                saved
            )
        }

        private fun showListChoiceSheet(
            pref: ListPreference,
            initialActionId: Int? = null,
            openedWithHardwareFocus: Boolean = listView?.isInTouchMode == false
        ) {
            val entries = pref.entries ?: return
            val values = pref.entryValues?.map(CharSequence::toString) ?: return
            if (entries.size != values.size) return
            val displayPref = pref as? DisplayChoicePreference
            val screenDescriptions = if (pref.key == SCREEN_COMBINATION_MODE_PREF_KEY) {
                resources.getStringArray(R.array.screen_combination_mode_descriptions)
            } else null
            val actions = values.mapIndexed { index, value ->
                val enabled = displayPref == null || value in displayPref.enabledValues
                val description = listOfNotNull(
                    displayPref?.descriptionFor(value) ?: screenDescriptions?.getOrNull(index),
                    if (enabled) null else getString(R.string.display_choice_unavailable)
                ).joinToString(" · ").ifBlank { null }
                AppActionSheet.Action(
                    id = index,
                    title = entries[index],
                    description = description,
                    enabled = enabled,
                    checked = value == pref.value,
                    emphasized = pref.key == PreferenceConfiguration.FPS_PREF_STRING &&
                        displayLimits().exceedsRefreshRate(value),
                    iconRes = (pref as? IconListPreference)?.entryIcons?.getOrNull(index)?.takeIf { it != 0 }
                        ?: if (screenDescriptions != null) value.toIntOrNull()?.let(::screenCombinationModeIconRes) else null
                )
            } + listOfNotNull(displayPref?.actionLabel?.let { label ->
                AppActionSheet.Action(
                    id = MANAGE_DISPLAY_RESOLUTIONS_ACTION,
                    title = label,
                    sectionStart = true,
                    opensSubmenu = true
                )
            })
            var selectedAction: AppActionSheet.Action? = null
            showSettingsChoiceDialog(pref, openedWithHardwareFocus,
                restoreOnDismiss = { selectedAction?.id != MANAGE_DISPLAY_RESOLUTIONS_ACTION }
            ) { hasHardwareInput, dismissed ->
                AppActionSheet.show(
                    requireContext(),
                    title = pref.title ?: "",
                    actions = actions,
                    initialActionId = initialActionId ?: values.indexOf(pref.value).takeIf { it >= 0 },
                    singleChoice = true,
                    onAction = { action ->
                        if (!settingsChoiceViewDestroyed) {
                            if (action.id == MANAGE_DISPLAY_RESOLUTIONS_ACTION && displayPref != null) {
                                openCustomResolutionsEditor(displayPref, hasHardwareInput())
                            } else {
                                values.getOrNull(action.id)?.let { value ->
                                    if (displayPref != null) {
                                        if (displayPref.selectValue(value) &&
                                            pref.key == PreferenceConfiguration.RESOLUTION_PREF_STRING) {
                                            // Retire an unsaved current value as soon as another size is selected.
                                            replaceSavedResolutionEntries()
                                            visibilityController.refreshSearch()
                                            refreshSearchPresentation()
                                        }
                                    }
                                    // Some position preferences reset a dragged position even when the value is unchanged.
                                    else if (pref.callChangeListener(value)) pref.value = value
                                }
                            }
                        }
                    },
                    onDismiss = { action -> selectedAction = action; dismissed() }
                )
            }
        }

        private fun showMultiChoiceSheet(pref: MultiSelectListPreference, restored: MultiChoiceDraft? = null) {
            val entries = pref.entries ?: return
            val values = pref.entryValues?.map(CharSequence::toString) ?: return
            if (entries.size != values.size) return
            val draft = restored ?: MultiChoiceDraft(pref.key, pref.values.toSet())
            val session = Any()
            multiChoiceSession = session
            multiChoiceDraft = draft
            val retainedValues = draft.values - values.toSet()
            showSettingsChoiceDialog(pref) { hasHardwareInput, dismissed ->
                AppActionSheet.showMultiSelect(
                    requireContext(),
                    title = pref.title ?: "",
                    actions = values.mapIndexed { index, value ->
                        AppActionSheet.Action(index, entries[index], checked = value in draft.values)
                    },
                    confirmLabel = getString(android.R.string.ok),
                    cancelLabel = getString(android.R.string.cancel),
                    forceInitialFocus = hasHardwareInput(),
                    onSelectionChanged = { ids ->
                        if (multiChoiceSession === session) {
                            multiChoiceDraft = MultiChoiceDraft(pref.key,
                                ids.mapNotNull(values::getOrNull).toSet() + retainedValues, changed = true)
                        }
                    },
                    onConfirm = { ids ->
                        val selected = ids.mapNotNull(values::getOrNull).toSet() + retainedValues
                        if (!settingsChoiceViewDestroyed && multiChoiceSession === session && multiChoiceDraft?.changed == true &&
                            pref.callChangeListener(selected)) pref.values = selected
                        if (multiChoiceSession === session) multiChoiceDraft = null
                    },
                    onDismiss = {
                        if (!settingsChoiceViewDestroyed && multiChoiceSession === session) multiChoiceDraft = null
                        dismissed()
                    }
                )
            }
        }

        /** Keeps one settings sheet alive and restores the originating row for hardware input. */
        private fun showSettingsChoiceDialog(
            preference: Preference,
            openedWithHardwareFocus: Boolean = listView?.isInTouchMode == false,
            restoreOnDismiss: () -> Boolean = { true },
            build: (hasHardwareInput: () -> Boolean, dismissed: () -> Unit) -> android.app.Dialog
        ) {
            if (preference !is MultiSelectListPreference) {
                multiChoiceSession = null
                multiChoiceDraft = null
            }
            var hardwareInput = openedWithHardwareFocus
            val generation = ++settingsChoiceGeneration
            settingsChoiceDialog?.dismiss()
            hideKeyboardBeforeDialog()
            val hasHardwareInput = { hardwareInput || listView?.isInTouchMode == false }
            settingsChoiceDialog = build(hasHardwareInput) {
                if (generation == settingsChoiceGeneration) {
                    settingsChoiceDialog = null
                    if (restoreOnDismiss() && hasHardwareInput()) {
                        restoreSettingsChoiceFocus(preference, generation)
                    }
                }
            }.apply {
                setOnKeyListener { _, keyCode, event ->
                    // B/Back can dismiss a touch-opened sheet without leaving Android touch mode.
                    // Remember that input explicitly so the original settings row can regain focus.
                    if (event.action == KeyEvent.ACTION_DOWN) hardwareInput = true
                    UiDismissKeyHandler.handle(event.action, keyCode, ::cancel)
                }
            }
        }

        private fun restoreSettingsChoiceFocus(pref: Preference, generation: Int) {
            val recycler = listView ?: return
            recycler.post {
                if (!isAdded || settingsChoiceViewDestroyed || generation != settingsChoiceGeneration ||
                    settingsChoiceDialog != null || customResolutionsDialog != null) return@post
                val position = findAdapterPositionForPreference(pref)
                if (position < 0) return@post
                // The row normally remains visible under the sheet; keep its scroll offset.
                val row = recycler.findViewHolderForAdapterPosition(position)?.itemView
                if (row != null) {
                    row.requestFocusFromTouch()
                } else {
                    recycler.scrollToPosition(position)
                    recycler.post {
                        if (!settingsChoiceViewDestroyed && generation == settingsChoiceGeneration) {
                            recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocusFromTouch()
                        }
                    }
                }
            }
        }

        /** 设备能力一次计算整个视图生命周期内复用,避免每次建表都枚举解码器。 */
        private var cachedDisplayLimits: DisplayChoiceLimits? = null

        private fun displayLimits(): DisplayChoiceLimits {
            return cachedDisplayLimits
                ?: DisplayChoiceLimits.from(requireActivity(), getTargetDisplay())
                    .also { cachedDisplayLimits = it }
        }

        private fun savedSelection(preference: DisplayChoicePreference, fallback: String): String {
            return preference.sharedPreferences?.getString(preference.key, null) ?: fallback
        }

        private fun resetBitrateToDefault(prefs: SharedPreferences, res: String?, fps: String?) {
            if (!prefs.getBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)) return
            var resValue = res
            var fpsValue = fps
            if (resValue == null) {
                resValue = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION)
            }
            if (fpsValue == null) {
                fpsValue = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS)
            }

            val defaultBitrate = DisplayChoiceLists.recommendedBitrate(
                resValue!!, fpsValue!!, DisplayChoiceLists.nativeSize(requireActivity(), getTargetDisplay())
            )
            val bitratePreference = findPreference<SeekBarPreference>(PreferenceConfiguration.BITRATE_PREF_STRING)
            if (bitratePreference != null) {
                bitratePreference.setProgress(defaultBitrate)
            } else {
                prefs.edit {
                    putInt(PreferenceConfiguration.BITRATE_PREF_STRING, defaultBitrate)
                }
            }
        }

        private fun updateHostCadencePreciseSyncVisibility(framePacingValue: String? = null) {
            val hostCadencePref = findPreference<CheckBoxPreference>(
                PreferenceConfiguration.ENABLE_HOST_CADENCE_PRECISE_SYNC_STRING
            ) ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
            val selectedFramePacing = framePacingValue
                ?: prefs.getString(
                    PreferenceConfiguration.FRAME_PACING_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_FRAME_PACING
                )

            updateRuntimeVisibility(hostCadencePref, selectedFramePacing == "precise-sync")
            updateRuntimeVisibility(
                findPreference("checkbox_reduce_refresh_rate"),
                selectedFramePacing == "balanced",
            )
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            settingsChoiceViewDestroyed = false
            val view = super.onCreateView(inflater, container, savedInstanceState)
            // Keep visibility changes stable while dependent settings are inserted or removed.
            listView?.itemAnimator = null
            // 确保列表背景透明
            view.setBackgroundColor(Color.TRANSPARENT)

            // 减少顶部间距，让设置内容更贴近导航栏
            val topPadding = view.paddingTop
            val reducedPadding =
                0.coerceAtLeast(topPadding - (16 * resources.displayMetrics.density).toInt())
            view.setPadding(view.paddingLeft, reducedPadding,
                    view.paddingRight, view.paddingBottom)
            UiHelper.applyStatusBarPadding(view)
            return view
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityCreated(savedInstanceState: Bundle?) {
            @Suppress("DEPRECATION")
            super.onActivityCreated(savedInstanceState)

            val activity = activity
            if (activity == null || activity !is StreamSettings) return

            val settingsActivity = activity
            // Ignore finite expansion counts restored from an older version's saved hierarchy.
            removeExpansionLimits(preferenceScreen)
            applySearchFilter(settingsActivity.searchInput?.text?.toString().orEmpty())
            listView.viewTreeObserver.addOnWindowFocusChangeListener(navigationWindowFocusListener)
            activeSettingsScreenKey?.let { key ->
                findPreference<PreferenceScreen>(key)?.let { screen ->
                    settingsActivity.showDetailSettings(this, screen.title ?: "")
                }
            }
            restoreNavigationFocus()

            // 添加滚动监听
            Handler(Looper.getMainLooper()).post {
                val recyclerView = listView
                if (recyclerView != null) {
                    setupScrollListener(recyclerView, settingsActivity)

                }
            }
            val pendingKey = savedInstanceState?.getString(MULTI_CHOICE_KEY)
            if (pendingKey != null) {
                val draft = MultiChoiceDraft(pendingKey,
                    savedInstanceState.getStringArrayList(MULTI_CHOICE_VALUES).orEmpty().toSet(),
                    savedInstanceState.getBoolean(MULTI_CHOICE_CHANGED))
                listView?.post {
                    if (isAdded && !settingsChoiceViewDestroyed) {
                        findPreference<MultiSelectListPreference>(pendingKey)?.let { showMultiChoiceSheet(it, draft) }
                    }
                }
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putAll(saveNavigationState())
            outState.putString(DIALOG_RETURN_KEY, dialogReturnPreferenceKey)
            multiChoiceDraft?.let { draft ->
                outState.putString(MULTI_CHOICE_KEY, draft.key)
                outState.putStringArrayList(MULTI_CHOICE_VALUES, ArrayList(draft.values))
                outState.putBoolean(MULTI_CHOICE_CHANGED, draft.changed)
            }
        }

        /**
         * 根据 key 滚动到指定分类
         */
        fun scrollToCategoryByKey(categoryKey: String) {
            for (i in categoryList.indices) {
                val category = categoryList[i]
                val key = category.key ?: "category_$i"
                if (key == categoryKey) {
                    scrollToCategoryAtIndex(i)
                    return
                }
            }
        }

        private val visibilityController by lazy {
            SettingsVisibilityController(
                screenProvider = { preferenceScreen },
                onCategoryEligibilityChanged = { rebuildCategoryList() },
            )
        }
        private var pendingCategoryRevealKey: String? = null
        private var revealAfterListRebuild = false

        private val modeStore by lazy {
            LegacySettingsModeStore(
                PreferenceManager.getDefaultSharedPreferences(requireContext())
            )
        }

        /**
         * XML 里的普通分类换成会在绑定后自己挂点击的分类。
         * 外部补挂会被 Preference 的重绑清掉，标题命中时整组重绑，点击就丢了。
         */
        private fun replaceSearchableCategories(group: PreferenceGroup) {
            val pending = ArrayList<PreferenceCategory>()
            for (index in 0 until group.preferenceCount) {
                val category = group.getPreference(index) as? PreferenceCategory ?: continue
                if (category !is SearchablePreferenceCategory) pending.add(category)
            }
            for (category in pending) {
                val order = category.order
                val children = ArrayList<androidx.preference.Preference>()
                for (index in 0 until category.preferenceCount) children.add(category.getPreference(index))
                category.removeAll()
                group.removePreference(category)

                val replacement = SearchablePreferenceCategory(requireContext())
                replacement.key = category.key
                replacement.title = category.title
                replacement.summary = category.summary
                replacement.layoutResource = category.layoutResource
                replacement.isVisible = category.isVisible
                replacement.isIconSpaceReserved = category.isIconSpaceReserved
                replacement.order = order
                group.addPreference(replacement)
                children.forEach { child ->
                    replacement.addPreference(child)
                    if (child is PreferenceGroup) replaceSearchableCategories(child)
                }
            }
        }

        private fun updateSearchCategoryActions(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val child = group.getPreference(index)
                if (child is SearchablePreferenceCategory) {
                    val wasOpenable = child.searchOpenHandler != null
                    child.searchOpenHandler = if (activeSettingsScreenKey == null && group === preferenceScreen && visibilityController.isSearching()) {
                        { openCategoryFromSearch(it) }
                    } else {
                        null
                    }
                    if (wasOpenable != (child.searchOpenHandler != null)) child.refreshSearchAction()
                }
                if (child is PreferenceGroup) updateSearchCategoryActions(child)
            }
        }

        private fun rebuildCategoryList() {
            val settingsActivity = activity as? StreamSettings ?: return
            val screen = preferenceScreen ?: return
            categoryList.clear()
            val items = ArrayList<CategoryItem>()
            for (i in 0 until screen.preferenceCount) {
                val category = screen.getPreference(i) as? PreferenceCategory ?: continue
                val eligible = visibilityController.isListed(category)
                val title = category.title?.toString() ?: continue
                if (!eligible) continue
                val key = category.key ?: "category_$i"
                categoryList.add(category)
                items.add(CategoryItem(key, title, getIconForCategory(key)))
            }
            categoryPositionsValid = false
            settingsActivity.onCategoriesLoaded(items)
            if (revealAfterListRebuild) {
                revealAfterListRebuild = false
                listView?.post { revealPendingCategory() }
            }
        }

        private fun updateRuntimeVisibility(preference: Preference?, visible: Boolean) {
            visibilityController.setRuntimeVisible(preference, visible)
        }

        /**
         * 应用搜索过滤。空查询恢复全部运行时可见性；
         * 非空查询匹配标题、说明和下拉选项文案。分类名命中时显示整组。
         */
        fun applySearchFilter(query: String) {
            visibilityController.applySearch(query)
            if (query.isBlank()) refreshSettingsPresentation("list_fec_mode")
            updateFloatBallSettingsSummary()
            preferenceScreen?.let { updateSearchCategoryActions(it) }
            refreshSearchPresentation()
        }

        /**
         * 退出搜索并回到这个分类的完整内容。分类标题上的「在分类中查看」走这里，
         * 设置项本身的点击仍然直接修改。
         */
        fun openCategoryFromSearch(categoryKey: String) {
            pendingCategoryRevealKey = categoryKey
            revealAfterListRebuild = true
            (activity as? StreamSettings)?.clearSearchQuery()
        }

        /** 上一轮各 List/MultiSelect 行的「包含：…」备注，用于跳过没变化的行。 */
        private val lastSearchNotes = IdentityHashMap<Preference, String?>()

        @SuppressLint("RestrictedApi")
        private fun refreshSearchPresentation() {
            val adapter = listView?.adapter as? PreferenceGroupAdapter ?: return
            for (index in 0 until adapter.itemCount) {
                val preference = adapter.getItem(index)
                when {
                    // setSummary 内部会 notifyChanged，只重建即可，避免双重刷新
                    preference is IconListPreference -> preference.refreshSearchMatchNote()
                    preference is ListPreference || preference is MultiSelectListPreference -> {
                        // 备注有变化才重绑；其余行不因每次按键全量刷新
                        val note = searchMatchNote(preference)
                        if (lastSearchNotes[preference] != note) {
                            lastSearchNotes[preference] = note
                            adapter.notifyItemChanged(index)
                        }
                    }
                }
            }
        }

        private fun revealPendingCategory() {
            val categoryKey = pendingCategoryRevealKey ?: return
            if (visibilityController.isSearching()) return
            pendingCategoryRevealKey = null
            val index = categoryList.indexOfFirst { it.key == categoryKey }
            if (index < 0) return
            scrollToCategoryAtIndex(index)
            (activity as? StreamSettings)?.updateSelectedCategory(index)
            listView?.post {
                if (!isAdded) return@post
                val position = findAdapterPositionForPreference(categoryList.getOrNull(index))
                val holder = if (position >= 0) listView?.findViewHolderForAdapterPosition(position) else null
                holder?.itemView?.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.settings_category_reveal))
            }
        }

        /**
         * 递归遍历 PreferenceGroup，给所有 ListPreference 装上一个 SummaryProvider：
         * - 第一行显示「当前: {entry}」
         * - 第二行保留 XML 中原本写好的说明 summary（如果有）
         * SummaryProvider 是按需调用的，所以即便后续动态 setEntries() 也能拿到最新值。
         */
        private fun applyListPreferenceCurrentValueSummary(group: PreferenceGroup) {
            val valueText = ContextCompat.getColor(group.context, R.color.ui_shell_text_primary)
            val disabledAccent = ContextCompat.getColor(group.context, R.color.ui_shell_text_disabled_primary)
            applyHighlightedSummariesRecursively(group, valueText, disabledAccent)
        }

        private fun applyHighlightedSummariesRecursively(
                group: PreferenceGroup,
                valueText: Int,
                disabledAccent: Int
        ) {
            for (i in 0 until group.preferenceCount) {
                val child = group.getPreference(i)
                when {
                    child is PreferenceGroup -> applyHighlightedSummariesRecursively(child, valueText, disabledAccent)
                    // IconListPreference 自己重写 setSummary 维护 "(当前：xxx)"，
                    // 装 SummaryProvider 会与其 super.setSummary 调用互斥。
                    // 命中说明改由它在拼接时询问，避免被当成新的原始说明保存。
                    child is IconListPreference -> child.searchMatchNoteProvider = { searchMatchNote(child) }
                    child is DisplayChoicePreference -> applyHighlightedSummary(child, valueText, disabledAccent) {
                        it.currentLabel
                    }
                    child is ListPreference -> applyHighlightedSummary(child, valueText, disabledAccent) {
                        val entry = it.entry?.toString()
                        if (entry.isNullOrBlank()) "—" else entry
                    }
                    child is SeekBarPreference -> applyHighlightedSummary(child, valueText, disabledAccent) {
                        val display = it.formatDisplayValue(it.currentValue)
                        val suffix = it.suffix?.takeIf { s -> s.isNotBlank() }
                        if (suffix != null) "$display $suffix" else display
                    }
                    child is MultiSelectListPreference -> applyHighlightedSummary(child, valueText, disabledAccent) {
                        selectedMultiSelectLabels(it)
                    }
                }
            }
        }

        /**
         * 给 Preference 装上一个 SummaryProvider：
         * 第一行用主题强调色 + 粗体显示 currentValueProvider 返回的当前值，
         * 第二行恢复默认色显示 XML 中原本的说明 summary。
         */
        private inline fun <reified T : Preference> applyHighlightedSummary(
                pref: T,
                valueText: Int,
                disabledAccent: Int,
                crossinline currentValueProvider: (T) -> String
        ) {
            val originalSummary = pref.summary?.toString()?.takeIf { it.isNotBlank() }
            applyHighlightedSummary(
                pref,
                valueText,
                disabledAccent,
                currentValueProvider,
            ) { originalSummary }
        }

        private inline fun <reified T : Preference> applyHighlightedSummary(
                pref: T,
                valueText: Int,
                disabledAccent: Int,
                crossinline currentValueProvider: (T) -> String,
                crossinline descriptionProvider: (T) -> CharSequence?
        ) {
            pref.summaryProvider = Preference.SummaryProvider<T> { p ->
                val current = currentValueProvider(p)
                val builder = SpannableStringBuilder()
                val markerStart = builder.length
                builder.append('●')
                builder.setSpan(
                        ForegroundColorSpan(if (p.isEnabled) UiHelper.accentColor(requireActivity()) else disabledAccent),
                        markerStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(' ')
                val valueStart = builder.length
                builder.append(current)
                builder.setSpan(
                        ForegroundColorSpan(if (p.isEnabled) valueText else disabledAccent),
                        valueStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        valueStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val description = descriptionProvider(p)?.takeIf { it.isNotBlank() }
                if (description != null) {
                    builder.append('\n').append(description)
                }
                searchMatchNote(p)?.let { note ->
                    val noteStart = builder.length
                    builder.append('\n').append(note)
                    builder.setSpan(
                        ForegroundColorSpan(UiHelper.accentColor(requireActivity())),
                        noteStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        noteStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                builder
            }
        }

        /** 多选没有「当前值」行，搜索时用已选项顶上，避免说明只剩命中备注。 */
        private fun selectedMultiSelectLabels(preference: MultiSelectListPreference): String {
            val selected = preference.values ?: emptySet()
            val labels = preference.entryValues
                ?.mapIndexedNotNull { index, value ->
                    preference.entries?.getOrNull(index)?.takeIf { selected.contains(value.toString()) }
                }
                .orEmpty()
            return if (labels.isEmpty()) "—" else labels.joinToString(
                getString(R.string.settings_search_match_separator)
            )
        }

        private fun searchMatchNote(preference: Preference): String? {
            if (activeSettingsScreenKey != null || !visibilityController.isSearching()) return null
            val matches = visibilityController.matchedDropdownLabels(preference)
            if (matches.isEmpty()) return null
            return getString(
                R.string.settings_search_contains,
                matches.joinToString(getString(R.string.settings_search_match_separator))
            )
        }

        private fun createSyncDocument() {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_TITLE, ConfigurationSyncManager.DEFAULT_FILE_NAME)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_CREATE_CONFIG_SYNC)
        }

        private fun openSyncDocument() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_OPEN_CONFIG_SYNC)
        }

        private fun openSyncDirectory() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_OPEN_CONFIG_SYNC_DIRECTORY)
        }

        private fun readDocumentText(uri: android.net.Uri): String {
            val resolver = requireContext().contentResolver
            return resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IOException("Unable to open input stream")
        }

        private fun writeDocumentText(uri: android.net.Uri, text: String) {
            val resolver = requireContext().contentResolver
            resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: throw IOException("Unable to open output stream")
        }

        private fun setupConfigSyncPreferences() {
            findPreference<Preference>("config_sync_export")?.setOnPreferenceClickListener {
                exportConfigSyncPackage()
                true
            }

            findPreference<Preference>("config_sync_import")?.setOnPreferenceClickListener {
                openSyncDocument()
                true
            }

            findPreference<Preference>(ConfigurationSyncManager.PREF_LOCAL_SNAPSHOT_NOW)
                ?.setOnPreferenceClickListener {
                    writeConfigSyncLocalSnapshot(showToast = true, requireAutoEnabled = false)
                    true
                }

            findPreference<Preference>(ConfigurationSyncManager.PREF_EXTERNAL_SYNC_DIRECTORY)
                ?.setOnPreferenceClickListener {
                    openSyncDirectory()
                    true
                }
            findPreference<Preference>(ConfigurationSyncManager.PREF_BACKUP_PASSWORD)
                ?.setOnPreferenceClickListener {
                    showExternalSyncPasswordDialog()
                    true
                }
            findPreference<Preference>(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_IMPORT)
                ?.setOnPreferenceClickListener {
                    importExternalSyncSnapshot()
                    true
            }
            updateLocalSnapshotPreferenceSummary()
            updateExternalSyncDirectorySummary()
            updateConfigSyncStatusSummary()
        }

        private fun handleConfigSyncPreferenceChanged(key: String?) {
            if (key == ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED) {
                val ctx = context ?: return
                if (ConfigurationSyncManager.isAutoSnapshotEnabled(ctx)) {
                    requestConfigSyncAutoSnapshot(delayMs = 0L)
                } else {
                    configSyncSnapshotDirty = false
                    configSyncSnapshotHandler.removeCallbacks(configSyncSnapshotRunnable)
                }
                updateLocalSnapshotPreferenceSummary()
                updateConfigSyncStatusSummary()
                return
            }

            if (key == ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED) {
                updateExternalSyncDirectorySummary()
                updateConfigSyncStatusSummary()
                val ctx = context ?: return
                if (PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false) &&
                    !ConfigurationSyncManager.hasExternalSyncPassword(ctx)) {
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit {
                        putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
                    }
                    Toast.makeText(context, R.string.toast_config_sync_password_required, Toast.LENGTH_LONG).show()
                    showExternalSyncPasswordDialog()
                    updateExternalSyncDirectorySummary()
                    return
                }
                if (ConfigurationSyncManager.isExternalSnapshotEnabled(ctx)) {
                    requestConfigSyncAutoSnapshot(delayMs = 0L)
                    ConfigurationSyncScheduler.schedule(ctx)
                } else {
                    ConfigurationSyncScheduler.cancel(ctx)
                }
                return
            }

            if (key == ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED) {
                updateExternalSyncDirectorySummary()
                updateConfigSyncStatusSummary()
                val ctx = context ?: return
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val enabled = prefs.getBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
                if (enabled &&
                    !ConfigurationSyncManager.hasExternalSyncPassword(ctx)) {
                    prefs.edit {
                        putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
                    }
                    Toast.makeText(context, R.string.toast_config_sync_password_required, Toast.LENGTH_LONG).show()
                    showExternalSyncPasswordDialog()
                    updateExternalSyncDirectorySummary()
                    return
                }
                prefs.edit {
                    putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, enabled)
                    putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, enabled)
                }
                if (enabled) {
                    requestConfigSyncAutoSnapshot(delayMs = 0L)
                    ConfigurationSyncScheduler.runNow(ctx)
                } else {
                    ConfigurationSyncScheduler.cancel(ctx)
                }
                updateExternalSyncDirectorySummary()
                return
            }

            if (ConfigurationSyncManager.isConfigSyncMetadataPreferenceKey(key)) {
                updateLocalSnapshotPreferenceSummary()
                updateExternalSyncDirectorySummary()
                updateConfigSyncStatusSummary()
                return
            }

            requestConfigSyncAutoSnapshot()
        }

        private fun registerConfigSyncPreferenceListener() {
            if (configSyncPreferenceListenerRegistered) return
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(configSyncPreferenceChangeListener)
            configSyncPreferenceListenerRegistered = true
        }

        private fun unregisterConfigSyncPreferenceListener() {
            if (!configSyncPreferenceListenerRegistered) return
            val ctx = context ?: return
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .unregisterOnSharedPreferenceChangeListener(configSyncPreferenceChangeListener)
            configSyncPreferenceListenerRegistered = false
        }

        private fun requestConfigSyncAutoSnapshot(delayMs: Long = 1500L) {
            val ctx = context ?: return
            if (!ConfigurationSyncManager.isAutoSnapshotEnabled(ctx)) return

            configSyncSnapshotDirty = true
            configSyncSnapshotHandler.removeCallbacks(configSyncSnapshotRunnable)
            configSyncSnapshotHandler.postDelayed(configSyncSnapshotRunnable, delayMs)
        }

        private fun flushConfigSyncAutoSnapshot() {
            val ctx = context ?: return
            if (!ConfigurationSyncManager.isAutoSnapshotEnabled(ctx)) return

            configSyncSnapshotDirty = true
            configSyncSnapshotHandler.removeCallbacks(configSyncSnapshotRunnable)
            writeConfigSyncLocalSnapshot(showToast = false, requireAutoEnabled = true)
        }

        private fun writeConfigSyncLocalSnapshot(showToast: Boolean, requireAutoEnabled: Boolean) {
            val appContext = context?.applicationContext ?: return
            if (requireAutoEnabled && !ConfigurationSyncManager.isAutoSnapshotEnabled(appContext)) return
            if (configSyncSnapshotInProgress) {
                if (requireAutoEnabled) {
                    configSyncSnapshotDirty = true
                }
                return
            }

            configSyncSnapshotInProgress = true
            if (requireAutoEnabled) {
                configSyncSnapshotDirty = false
            }

            thread(name = "ConfigSyncLocalSnapshot") {
                val result = runCatching {
                    val syncManager = ConfigurationSyncManager(appContext)
                    if (ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
                        syncManager.synchronizeWithExternalSnapshot()
                            .also { syncManager.rememberAutoSyncResult(it) }
                    } else {
                        syncManager.writeConfiguredSnapshots()
                    }
                }.onFailure {
                    if (ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
                        ConfigurationSyncManager(appContext)
                            .rememberAutoSyncFailure(it.message ?: it.javaClass.simpleName)
                    }
                }

                configSyncSnapshotHandler.post {
                    configSyncSnapshotInProgress = false
                    if (isAdded) {
                        updateLocalSnapshotPreferenceSummary()
                        updateExternalSyncDirectorySummary()
                        updateConfigSyncStatusSummary()
                        if (showToast) {
                            val syncResult = result.getOrNull()
                            val syncSucceeded = result.isSuccess &&
                                    (syncResult !is ConfigurationSyncManager.AutoSyncResult ||
                                            syncResult.errorMessage == null)
                            if (syncSucceeded) {
                                Toast.makeText(
                                    context,
                                    R.string.toast_config_sync_snapshot_success,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.toast_config_sync_snapshot_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    result.exceptionOrNull()?.let {
                        Log.e("ConfigSync", "Failed to write local configuration sync snapshot", it)
                    }
                    (result.getOrNull() as? ConfigurationSyncManager.AutoSyncResult)
                        ?.errorMessage
                        ?.let { Log.w("ConfigSync", "Local snapshot background merge failed: $it") }
                    (result.getOrNull() as? ConfigurationSyncManager.AutoSyncResult)
                        ?.let { maybeShowPairingRestoreRestartPrompt(it.pairingItemsImported, it.pairingItemsFailed) }

                    if (configSyncSnapshotDirty &&
                        ConfigurationSyncManager.isAutoSnapshotEnabled(appContext)) {
                        requestConfigSyncAutoSnapshot()
                    } else if (ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
                        ConfigurationSyncScheduler.schedule(appContext)
                    }
                }
            }
        }

        private fun updateLocalSnapshotPreferenceSummary() {
            val pref = findPreference<Preference>(ConfigurationSyncManager.PREF_LOCAL_SNAPSHOT_NOW)
                ?: return
            val ctx = context ?: return
            val info = ConfigurationSyncManager(ctx).localSnapshotInfo()
            pref.summary = if (info.exists) {
                val updatedAt = DateFormat
                    .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(info.updatedAt))
                val sizeKb = maxOf(1L, (info.sizeBytes + 1023L) / 1024L)
                getString(R.string.summary_config_sync_snapshot_latest, updatedAt, sizeKb)
            } else {
                getString(R.string.summary_config_sync_snapshot_never)
            }
        }

        private fun updateExternalSyncDirectorySummary() {
            val ctx = context ?: return
            val treeUri = ConfigurationSyncManager.externalSyncTreeUri(ctx)
            val directoryPref = findPreference<Preference>(
                ConfigurationSyncManager.PREF_EXTERNAL_SYNC_DIRECTORY
            )
            val externalSnapshotPref = findPreference<CheckBoxPreference>(
                ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED
            )
            val backgroundSyncPref = findPreference<CheckBoxPreference>(
                ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED
            )
            val backupPasswordPref = findPreference<Preference>(
                ConfigurationSyncManager.PREF_BACKUP_PASSWORD
            )
            val importExternalSnapshotPref = findPreference<Preference>(
                ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_IMPORT
            )
            val hasBackupPassword = ConfigurationSyncManager.hasExternalSyncPassword(ctx)

            directoryPref?.summary = if (treeUri == null) {
                getString(R.string.summary_config_sync_select_directory_none)
            } else {
                val label = treeUri.lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: treeUri.toString()
                getString(R.string.summary_config_sync_select_directory_selected, label)
            }

            backupPasswordPref?.summary = if (hasBackupPassword) {
                getString(R.string.summary_config_sync_backup_password_set)
            } else {
                getString(R.string.summary_config_sync_backup_password_missing)
            }
            externalSnapshotPref?.isEnabled = treeUri != null && hasBackupPassword
            backgroundSyncPref?.isEnabled = treeUri != null && hasBackupPassword
            importExternalSnapshotPref?.isEnabled = treeUri != null
        }

        private fun updateConfigSyncStatusSummary() {
            val pref = findPreference<Preference>(ConfigurationSyncManager.PREF_SYNC_STATUS)
                ?: return
            val ctx = context ?: return
            if (!ConfigurationSyncManager.isBackgroundSyncEnabled(ctx)) {
                pref.summary = getString(R.string.summary_config_sync_status_disabled)
                return
            }

            val status = ConfigurationSyncManager(ctx).syncStatusInfo()
            if (!status.hasCompletedSync) {
                pref.summary = getString(R.string.summary_config_sync_status_never)
                return
            }

            val completedAt = DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(status.completedAt))
            pref.summary = if (status.success) {
                getString(
                    R.string.summary_config_sync_status_success,
                    completedAt,
                    formatSyncStatusFlag(status.readExternal),
                    formatSyncStatusFlag(status.appliedMergedPackage),
                    formatSyncStatusFlag(status.wroteExternal)
                )
            } else {
                getString(
                    R.string.summary_config_sync_status_failed,
                    completedAt,
                    status.errorMessage.ifBlank { getString(R.string.config_sync_unknown_version) }
                )
            }
        }

        private fun formatSyncStatusFlag(value: Boolean): String {
            return getString(
                if (value) R.string.config_sync_status_yes else R.string.config_sync_status_no
            )
        }

        private fun handleSyncExportResult(data: Intent?) {
            val uri = data?.data ?: return
            try {
                writeDocumentText(uri, pendingSyncExportString)
                Toast.makeText(context, R.string.toast_config_sync_export_success, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Log.e("ConfigSync", "Failed to write configuration sync package", e)
                Toast.makeText(context, R.string.toast_config_sync_export_failed, Toast.LENGTH_LONG).show()
            }
        }

        private fun exportConfigSyncPackage() {
            val manager = ConfigurationSyncManager(requireContext())
            try {
                val savedPasswordPackage = manager.exportEncryptedSyncPackageWithSavedPassword()
                if (savedPasswordPackage != null) {
                    pendingSyncExportString = savedPasswordPackage
                    createSyncDocument()
                    return
                }
            } catch (e: Exception) {
                Log.w("ConfigSync", "Failed to export with saved backup password", e)
            }

            showConfigSyncPasswordDialog(
                titleRes = R.string.title_config_sync_export,
                messageRes = R.string.message_config_sync_backup_password_export,
                positiveRes = R.string.config_sync_action_export
            ) { password ->
                try {
                    manager.saveExternalSyncPassword(password)
                    updateExternalSyncDirectorySummary()
                    pendingSyncExportString = manager.exportEncryptedSyncPackage(password)
                    createSyncDocument()
                } catch (e: Exception) {
                    Log.e("ConfigSync", "Failed to export configuration sync package", e)
                    Toast.makeText(context, R.string.toast_config_sync_export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun handleSyncImportResult(data: Intent?) {
            val uri = data?.data ?: return
            try {
                val syncPackage = readDocumentText(uri)
                if (ConfigurationSyncManager.isEncryptedSyncPackage(syncPackage)) {
                    if (!previewSyncPackageForImportWithSavedPassword(syncPackage)) {
                        showConfigSyncPasswordDialog(
                            titleRes = R.string.title_config_sync_import,
                            messageRes = R.string.message_config_sync_backup_password_import,
                            positiveRes = R.string.config_sync_action_import
                        ) { password ->
                            previewSyncPackageForImport(syncPackage, password)
                        }
                    }
                } else {
                    previewSyncPackageForImport(syncPackage, null)
                }
            } catch (e: Exception) {
                Log.e("ConfigSync", "Failed to import configuration sync package", e)
                Toast.makeText(context, R.string.toast_config_sync_import_failed, Toast.LENGTH_LONG).show()
            }
        }

        private fun handleSyncDirectoryResult(data: Intent?) {
            val uri = data?.data ?: return
            try {
                val grantFlags = data.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (grantFlags != 0) {
                    requireContext().contentResolver.takePersistableUriPermission(uri, grantFlags)
                }
                showConfigSyncPasswordDialog(
                    titleRes = R.string.title_config_sync_backup_password,
                    messageRes = R.string.message_config_sync_backup_password_external,
                    positiveRes = R.string.config_sync_action_save_password
                ) { password ->
                    val manager = ConfigurationSyncManager(requireContext())
                    if (!manager.saveExternalSyncPassword(password)) {
                        Toast.makeText(context, R.string.toast_config_sync_password_unavailable, Toast.LENGTH_LONG).show()
                        return@showConfigSyncPasswordDialog
                    }
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                        putString(ConfigurationSyncManager.PREF_EXTERNAL_SYNC_TREE_URI, uri.toString())
                        putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
                        putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
                        putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
                    }
                    Toast.makeText(context, R.string.toast_config_sync_password_saved, Toast.LENGTH_SHORT).show()
                    updateExternalSyncDirectorySummary()
                    showEnableExternalSyncConfirmation()
                }
            } catch (e: Exception) {
                Log.e("ConfigSync", "Failed to select external configuration sync directory", e)
                Toast.makeText(context, R.string.toast_config_sync_directory_failed, Toast.LENGTH_LONG).show()
            }
        }

        private fun importExternalSyncSnapshot() {
            val appContext = context?.applicationContext ?: return
            if (!ConfigurationSyncManager.hasExternalSyncPassword(appContext)) {
                showConfigSyncPasswordDialog(
                    titleRes = R.string.title_config_sync_import_external_snapshot,
                    messageRes = R.string.message_config_sync_backup_password_import,
                    positiveRes = R.string.config_sync_action_import
                ) { password ->
                    if (!ConfigurationSyncManager(requireContext()).saveExternalSyncPassword(password)) {
                        Toast.makeText(context, R.string.toast_config_sync_password_unavailable, Toast.LENGTH_LONG).show()
                        return@showConfigSyncPasswordDialog
                    }
                    updateExternalSyncDirectorySummary()
                    importExternalSyncSnapshot()
                }
                return
            }
            thread(name = "ConfigSyncReadExternalSnapshot") {
                val result = runCatching {
                    val syncManager = ConfigurationSyncManager(appContext)
                    val syncPackage = syncManager.readExternalSnapshot()
                    syncManager.previewSyncPackage(syncPackage) to syncPackage
                }

                configSyncSnapshotHandler.post {
                    if (!isAdded) return@post
                    result
                        .onSuccess { (preview, syncPackage) ->
                            pendingSyncImportString = syncPackage
                            showSyncImportPreview(preview)
                        }
                        .onFailure {
                            Log.e("ConfigSync", "Failed to read external configuration sync snapshot", it)
                            Toast.makeText(
                                context,
                                R.string.toast_config_sync_import_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }

        private fun previewSyncPackageForImport(syncPackage: String, password: String?) {
            try {
                val manager = ConfigurationSyncManager(requireContext())
                val plainPackage = if (ConfigurationSyncManager.isEncryptedSyncPackage(syncPackage)) {
                    manager.decryptEncryptedSyncPackage(syncPackage, password.orEmpty())
                } else {
                    syncPackage
                }
                val preview = manager.previewSyncPackage(plainPackage)
                pendingSyncImportString = plainPackage
                showSyncImportPreview(preview)
            } catch (e: Exception) {
                Log.e("ConfigSync", "Failed to preview configuration sync package", e)
                Toast.makeText(context, R.string.toast_config_sync_import_failed, Toast.LENGTH_LONG).show()
            }
        }

        private fun previewSyncPackageForImportWithSavedPassword(syncPackage: String): Boolean {
            val manager = ConfigurationSyncManager(requireContext())
            val plainPackage = runCatching {
                manager.decryptEncryptedSyncPackageWithSavedPassword(syncPackage)
            }.getOrNull() ?: return false

            previewSyncPackageForImport(plainPackage, null)
            return true
        }

        private fun showExternalSyncPasswordDialog() {
            showConfigSyncPasswordDialog(
                titleRes = R.string.title_config_sync_backup_password,
                messageRes = R.string.message_config_sync_backup_password_external,
                positiveRes = R.string.config_sync_action_save_password
            ) { password ->
                if (ConfigurationSyncManager(requireContext()).saveExternalSyncPassword(password)) {
                    Toast.makeText(context, R.string.toast_config_sync_password_saved, Toast.LENGTH_SHORT).show()
                    updateExternalSyncDirectorySummary()
                    updateConfigSyncStatusSummary()
                } else {
                    Toast.makeText(context, R.string.toast_config_sync_password_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun showConfigSyncPasswordDialog(
            titleRes: Int,
            messageRes: Int,
            positiveRes: Int,
            onPassword: (String) -> Unit
        ) {
            val input = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                hint = getString(R.string.hint_config_sync_backup_password)
            }
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val inset = (24 * resources.displayMetrics.density).toInt()
                setPadding(inset, 0, inset, 0)
                addView(input)
            }

            val dialog = AlertDialog.Builder(requireActivity(), R.style.AppDialogStyle)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setView(container)
                .setPositiveButton(positiveRes, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val password = input.text?.toString().orEmpty()
                    if (password.isBlank()) {
                        input.error = getString(R.string.toast_config_sync_password_required)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    onPassword(password)
                }
                input.requestFocus()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            showStyledDialog(dialog)
        }

        private fun showSyncImportPreview(preview: ConfigurationSyncManager.PackagePreview) {
            val sourceVersion = preview.appVersionName.takeIf { it.isNotBlank() }
                ?: if (preview.appVersionCode > 0) preview.appVersionCode.toString()
                else getString(R.string.config_sync_unknown_version)

            AlertDialog.Builder(requireActivity(), R.style.AppDialogStyle)
                .setTitle(R.string.title_config_sync_import)
                .setMessage(
                    getString(
                        R.string.message_config_sync_import_preview,
                        sourceVersion,
                        preview.defaultPreferenceCount,
                        preview.appLastSettingsCount,
                        preview.customResolutionsCount,
                        preview.sceneConfigsCount,
                        preview.appViewPreferenceCount,
                        preview.hiddenAppsCount,
                        preview.crownProfilesCount,
                        preview.pairedComputersCount,
                        formatSyncStatusFlag(preview.hasPairingIdentity)
                    )
                )
                .setPositiveButton(R.string.config_sync_action_import) { _, _ -> importPendingSyncPackage() }
                .setNegativeButton(android.R.string.cancel, null)
                .showStyled()
        }

        private fun importPendingSyncPackage() {
            val syncPackage = pendingSyncImportString
            if (syncPackage.isBlank()) return

            try {
                val importResult = ConfigurationSyncManager(requireContext()).importSyncPackage(syncPackage)
                val failedItems = importResult.crownProfilesFailed + importResult.pairingItemsFailed
                val toastRes = if (failedItems > 0) {
                    R.string.toast_config_sync_import_success_with_failures
                } else {
                    R.string.toast_config_sync_import_success
                }
                val toastText = if (failedItems > 0) {
                    getString(toastRes, importResult.totalImported, failedItems)
                } else {
                    getString(toastRes, importResult.totalImported)
                }
                pendingSyncImportString = ""
                Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                maybeShowPairingRestoreRestartPrompt(
                    importResult.pairingItemsImported,
                    importResult.pairingItemsFailed
                )
                requestConfigSyncAutoSnapshot(delayMs = 0L)
                Handler(Looper.getMainLooper()).post {
                    (activity as? StreamSettings)?.reloadSettings()
                }
            } catch (e: Exception) {
                Log.e("ConfigSync", "Failed to apply configuration sync package", e)
                Toast.makeText(context, R.string.toast_config_sync_import_failed, Toast.LENGTH_LONG).show()
            }
        }

        private fun maybeShowPairingRestoreRestartPrompt(imported: Int, failed: Int) {
            if (!isAdded || imported <= 0 && failed <= 0) return
            val message = if (failed > 0) {
                getString(R.string.message_config_sync_restart_required_with_failures, failed)
            } else {
                getString(R.string.message_config_sync_restart_required)
            }
            AlertDialog.Builder(requireActivity(), R.style.AppDialogStyle)
                .setTitle(R.string.title_config_sync_restart_required)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .showStyled()
        }

        private fun scrollToCategoryAtIndex(index: Int) {
            if (index < 0 || index >= categoryList.size) return

            val category = categoryList[index]
            val position = findAdapterPositionForPreference(category)
            if (position >= 0) {
                isManualScrolling = true
                currentCategoryIndex = index

                val recyclerView = listView
                if (recyclerView != null) {
                    val lm = recyclerView.layoutManager
                    if (lm is LinearLayoutManager) {
                        lm.scrollToPositionWithOffset(position, dpToPx(2))
                    }
                }
            }
        }

        /**
         * 设置滚动监听
         */
        private fun setupScrollListener(recyclerView: RecyclerView, settingsActivity: StreamSettings) {
            observeCategoryPositions(recyclerView)

            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        isManualScrolling = false
                        updateVisibleCategory(rv, settingsActivity)
                    }
                }

                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!isManualScrolling) {
                        updateVisibleCategory(rv, settingsActivity)
                    }
                }
            })
            updateVisibleCategory(recyclerView, settingsActivity)
        }

        private fun observeCategoryPositions(recyclerView: RecyclerView) {
            // 注册数据集变化监听，仅在数据集变化时失效缓存的 category positions
            val adapter = recyclerView.adapter
            if (adapter != null && adapterDataObserver == null) {
                val observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { categoryPositionsValid = false }
                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { categoryPositionsValid = false }
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { categoryPositionsValid = false }
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { categoryPositionsValid = false }
                    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { categoryPositionsValid = false }
                }
                adapter.registerAdapterDataObserver(observer)
                adapterDataObserver = observer
            }

        }

        override fun onDestroyView() {
            settingsChoiceViewDestroyed = true
            settingsChoiceGeneration++
            settingsChoiceDialog?.dismiss()
            settingsChoiceDialog = null
            navigationGeneration++
            listView.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(navigationWindowFocusListener)
            (activity as? StreamSettings)?.hideDetailSettings(this)
            (listView.adapter as? SettingsPreferenceAdapter)?.stopObservingChanges()
            (listView.adapter as? SettingsDetailPreferenceAdapter)?.restoreOriginalLayouts()
            unregisterConfigSyncPreferenceListener()
            configSyncSnapshotHandler.removeCallbacks(configSyncSnapshotRunnable)
            customResolutionsDialog?.dismiss()
            customResolutionsDialog = null
            cachedDisplayLimits = null
            // 注销 adapter observer，避免泄漏
            val obs = adapterDataObserver
            if (obs != null) {
                try {
                    listView?.adapter?.unregisterAdapterDataObserver(obs)
                } catch (_: Exception) {
                }
                adapterDataObserver = null
            }
            categoryPositionsValid = false
            super.onDestroyView()
        }

        private val shizukuAuthorizationListener =
            Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizukuAuthorization() }

        private fun setupShizukuKeyboardAuthorization() {
            findPreference<Preference>("shizuku_keyboard_authorization")?.setOnPreferenceClickListener {
                KeyboardAccessibilityEnabler.requestAuthorization()
                refreshShizukuAuthorization()
                true
            }
            refreshShizukuAuthorization()
        }

        private fun refreshShizukuAuthorization() {
            val preference = findPreference<Preference>("shizuku_keyboard_authorization") ?: return
            val summary = when (KeyboardAccessibilityEnabler.authorizationState()) {
                KeyboardAccessibilityEnabler.AuthorizationState.NOT_INSTALLED,
                KeyboardAccessibilityEnabler.AuthorizationState.NOT_RUNNING -> R.string.summary_shizuku_not_running
                KeyboardAccessibilityEnabler.AuthorizationState.AUTHORIZATION_REQUIRED -> R.string.summary_shizuku_authorization_required
                KeyboardAccessibilityEnabler.AuthorizationState.AUTHORIZED -> R.string.summary_shizuku_authorized
            }
            preference.setSummary(summary)
        }

        override fun onResume() {
            super.onResume()
            KeyboardAccessibilityEnabler.addAuthorizationListener(shizukuAuthorizationListener)
            refreshShizukuAuthorization()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                val store = modeStore
                val mode = store.dualSenseMode()
                if (mode.systemBluetooth) {
                    store.setDualSenseMode(
                        DualSenseOutputMode.fromLegacy(
                            systemBluetooth = false,
                            wirelessBridge = mode.wirelessBridge,
                        )
                    )
                }
            }
            refreshSettingsPresentation()
            registerConfigSyncPreferenceListener()
            updateLocalSnapshotPreferenceSummary()
            updateExternalSyncDirectorySummary()
            updateConfigSyncStatusSummary()
            resumeDeveloperUnlockVerificationIfPending()
        }

        override fun onPause() {
            KeyboardAccessibilityEnabler.removeAuthorizationListener(shizukuAuthorizationListener)
            flushConfigSyncAutoSnapshot()
            unregisterConfigSyncPreferenceListener()
            super.onPause()
        }

        /**
         * 一次性扫描 adapter，建立 category preference -> position 的映射缓存。
         * 避免每个滚动帧都做 O(categories * itemCount) 全表扫描。
         */
        @SuppressLint("RestrictedApi")
        private fun ensureCategoryPositions(recyclerView: RecyclerView): Boolean {
            if (categoryPositionsValid && categoryPositions.size == categoryList.size) {
                return true
            }
            val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return false
            val n = categoryList.size
            if (categoryPositions.size != n) {
                categoryPositions = IntArray(n) { -1 }
            } else {
                for (i in 0 until n) categoryPositions[i] = -1
            }
            // 用引用比较的 IdentityHashMap 反查表，单次 O(itemCount)
            val targets = java.util.IdentityHashMap<Preference, Int>(n * 2)
            for (i in 0 until n) targets[categoryList[i]] = i
            val total = adapter.itemCount
            var found = 0
            for (i in 0 until total) {
                val pref = adapter.getItem(i) ?: continue
                val idx = targets[pref] ?: continue
                if (categoryPositions[idx] == -1) {
                    categoryPositions[idx] = i
                    found++
                    if (found >= n) break
                }
            }
            categoryPositionsValid = true
            return true
        }

        /**
         * 更新当前可见分类
         * 使用缓存的 categoryPositions 配合 firstVisiblePosition 做 O(N) 单次扫描，
         * 替代原先每帧 O(categories * adapter.itemCount) 的全表扫。
         */
        private fun updateVisibleCategory(recyclerView: RecyclerView?, settingsActivity: StreamSettings) {
            if (activeSettingsScreenKey != null || recyclerView == null || categoryList.isEmpty()) return

            val lm = recyclerView.layoutManager
            if (lm !is LinearLayoutManager) return

            if (!ensureCategoryPositions(recyclerView)) return

            val firstVisiblePosition = lm.findFirstVisibleItemPosition()
            if (firstVisiblePosition < 0) return

            // 找到最大的 position <= firstVisiblePosition 的 category
            // categoryPositions 通常按 adapter 顺序递增，但保险起见线性扫描（N ~ 15）
            var newCategoryIndex = -1
            var bestPosition = -1
            for (i in 0 until categoryPositions.size) {
                val p = categoryPositions[i]
                if (p in 0..firstVisiblePosition && p > bestPosition) {
                    bestPosition = p
                    newCategoryIndex = i
                }
            }
            // 若所有 category 都在 first 之后，则取第一个可见的
            if (newCategoryIndex < 0) {
                val lastVisible = lm.findLastVisibleItemPosition()
                for (i in 0 until categoryPositions.size) {
                    val p = categoryPositions[i]
                    if (p in 0..lastVisible) {
                        newCategoryIndex = i
                        break
                    }
                }
            }

            if (newCategoryIndex >= 0 && newCategoryIndex != currentCategoryIndex) {
                currentCategoryIndex = newCategoryIndex
                settingsActivity.updateSelectedCategory(currentCategoryIndex)
            }
        }

        private fun dpToPx(dp: Int): Int {
            val density = resources.displayMetrics.density
            return (dp * density).roundToInt()
        }

        /** Navigate only the displayed subtree; the full preference manager stays attached. */
        override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
            openSettingsScreen(preferenceScreen.key,
                visibilityController.matchingDescendant(preferenceScreen)?.key)
        }

        fun recordSettingsHardwareInput() {
            if (activeSettingsScreenKey != null) detailHardwareInput = true
        }

        fun openSettingsScreen(key: String, targetKey: String? = null) {
            val screen = findPreference<PreferenceScreen>(key) ?: return
            if (screen === preferenceScreen || !screen.isVisible) return
            val recycler = listView ?: return
            if (activeSettingsScreenKey == null) {
                mainListState = recycler.layoutManager?.onSaveInstanceState()
                mainListPadding = intArrayOf(recycler.paddingLeft, recycler.paddingTop,
                    recycler.paddingRight, recycler.paddingBottom)
                detailHardwareInput = !recycler.isInTouchMode
            }
            navigationGeneration++
            activeSettingsScreenKey = key
            pendingNavigationFocusKey = targetKey
            replaceVisibleAdapter()
            (activity as? StreamSettings)?.showDetailSettings(this, screen.title ?: "")
            if (pendingNavigationFocusKey == null && detailHardwareInput) {
                pendingNavigationFocusKey = firstSelectablePreferenceKey()
            }
            preferenceScreen?.let(::updateSearchCategoryActions)
            refreshSearchPresentation()
            restoreNavigationFocus()
        }

        fun closeSettingsScreen(): Boolean {
            val entryKey = activeSettingsScreenKey ?: return false
            navigationGeneration++
            val restoreFocus = detailHardwareInput || listView?.isInTouchMode == false
            activeSettingsScreenKey = null
            pendingNavigationFocusKey = if (restoreFocus) entryKey else null
            replaceVisibleAdapter(mainListState)
            (activity as? StreamSettings)?.hideDetailSettings(this)
            mainListPadding?.let { listView.setPadding(it[0], it[1], it[2], it[3]) }
            mainListState = null
            mainListPadding = null
            updateFloatBallSettingsSummary()
            updateFeaturePageSummaries()
            preferenceScreen?.let(::updateSearchCategoryActions)
            refreshSearchPresentation()
            restoreNavigationFocus()
            return true
        }

        private fun replaceVisibleAdapter(layoutState: Parcelable? = null) {
            val recycler = listView ?: return
            val generation = navigationGeneration
            adapterDataObserver?.let { recycler.adapter?.unregisterAdapterDataObserver(it) }
            adapterDataObserver = null
            (recycler.adapter as? SettingsPreferenceAdapter)?.stopObservingChanges()
            recycler.adapter = null
            recycler.recycledViewPool.clear()
            categoryPositionsValid = false
            // AndroidX's outgoing adapter may have a hierarchy sync already queued. Let it
            // finish before a new adapter takes ownership of the preferences' internal listeners.
            recycler.post {
                if (!isAdded || settingsChoiceViewDestroyed || generation != navigationGeneration) return@post
                // A new manager cannot carry an unconsumed scroll request from the other page.
                recycler.layoutManager = onCreateLayoutManager()
                recycler.adapter = onCreateAdapter(preferenceScreen)
                if (layoutState != null) recycler.layoutManager?.onRestoreInstanceState(layoutState)
                else recycler.layoutManager?.scrollToPosition(0)
                if (activeSettingsScreenKey != null && pendingNavigationFocusKey == null && detailHardwareInput) {
                    pendingNavigationFocusKey = firstSelectablePreferenceKey()
                }
                observeCategoryPositions(recycler)
                refreshSearchPresentation()
                restoreNavigationFocus()
            }
        }

        fun saveNavigationState(): Bundle = Bundle().apply {
            putString(DETAIL_SCREEN, activeSettingsScreenKey)
            putParcelable(MAIN_LIST_STATE, mainListState)
            putBoolean(DETAIL_HARDWARE, detailHardwareInput)
            putString(DETAIL_FOCUS, pendingNavigationFocusKey ?: focusedPreferenceKey())
        }

        @SuppressLint("RestrictedApi")
        private fun focusedPreferenceKey(): String? {
            val recycler = listView ?: return null
            val row = recycler.findFocus()?.let(recycler::findContainingItemView) ?: return null
            val position = recycler.getChildAdapterPosition(row)
            val adapter = recycler.adapter as? PreferenceGroupAdapter ?: return null
            return if (position >= 0) adapter.getItem(position)?.key else null
        }

        @SuppressLint("RestrictedApi")
        private fun firstSelectablePreferenceKey(): String? {
            val adapter = listView?.adapter as? PreferenceGroupAdapter ?: return null
            return (0 until adapter.itemCount).firstNotNullOfOrNull { position ->
                adapter.getItem(position)?.takeIf { it.isEnabled && it.isSelectable }?.key
            }
        }

        /** Wait beyond window-focus dispatch: older releases can otherwise overwrite the row. */
        @SuppressLint("RestrictedApi")
        private fun restoreNavigationFocus(attempts: Int = 5) {
            val key = pendingNavigationFocusKey ?: return
            val recycler = listView ?: return
            if (settingsChoiceViewDestroyed || !recycler.hasWindowFocus()) return
            val generation = navigationGeneration
            recycler.post {
                if (!isAdded || settingsChoiceViewDestroyed || generation != navigationGeneration ||
                    !recycler.hasWindowFocus() || settingsChoiceDialog != null) return@post
                if (!recycler.isLaidOut || recycler.isLayoutRequested || recycler.isComputingLayout ||
                    recycler.hasPendingAdapterUpdates()) {
                    recycler.postOnAnimation {
                        if (generation == navigationGeneration) restoreNavigationFocus(attempts)
                    }
                    return@post
                }
                val adapter = recycler.adapter as? PreferenceGroupAdapter ?: return@post
                val requestedPosition = adapter.getPreferenceAdapterPosition(key).takeIf { it >= 0 }
                val position = requestedPosition?.takeIf {
                    adapter.getItem(it)?.let { item -> item.isEnabled && item.isSelectable } == true
                } ?: (0 until adapter.itemCount).firstOrNull {
                    adapter.getItem(it)?.let { item -> item.isEnabled && item.isSelectable } == true
                }
                if (position == null) {
                    pendingNavigationFocusKey = null
                    return@post
                }
                val row = recycler.findViewHolderForAdapterPosition(position)?.itemView
                if (row != null && row.isShown && row.requestFocusFromTouch()) {
                    pendingNavigationFocusKey = null
                } else if (attempts > 0) {
                    recycler.scrollToPosition(position)
                    recycler.postOnAnimation {
                        if (generation == navigationGeneration) restoreNavigationFocus(attempts - 1)
                    }
                }
            }
        }

        private fun removeExpansionLimits(group: PreferenceGroup) {
            group.initialExpandedChildrenCount = Int.MAX_VALUE
            for (index in 0 until group.preferenceCount) {
                (group.getPreference(index) as? PreferenceGroup)?.let(::removeExpansionLimits)
            }
        }

        @SuppressLint("RestrictedApi")
        private fun findAdapterPositionForPreference(target: Preference?): Int {
            val recyclerView = listView ?: return -1
            if (target == null) return -1
            // 优先走缓存
            if (activeSettingsScreenKey == null && categoryPositionsValid && categoryPositions.size == categoryList.size) {
                val idx = categoryList.indexOfFirst { it === target }
                if (idx >= 0) return categoryPositions[idx]
            }
            return (recyclerView.adapter as? PreferenceGroupAdapter)
                ?.getPreferenceAdapterPosition(target) ?: -1
        }

        @SuppressLint("RestrictedApi")
        override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
            val detail = activeSettingsScreenKey?.let { preferenceScreen.findPreference<PreferenceScreen>(it) }
                ?.takeIf { visibilityController.isRuntimeVisible(it) }
            if (detail == null) activeSettingsScreenKey = null
            return if (detail != null) SettingsDetailPreferenceAdapter(detail)
                else SettingsPreferenceAdapter(preferenceScreen)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val navigation = savedInstanceState ?: arguments
            activeSettingsScreenKey = navigation?.getString(DETAIL_SCREEN)
            mainListState = navigation?.getParcelable(MAIN_LIST_STATE)
            detailHardwareInput = navigation?.getBoolean(DETAIL_HARDWARE) ?: false
            pendingNavigationFocusKey = navigation?.getString(DETAIL_FOCUS)
            // Keep search updates local instead of rebinding the entire list.
            preferenceManager.preferenceComparisonCallback = PreferenceManager.SimplePreferenceComparisonCallback()
            // 添加阴影主题
            AppTheme.applyStyle(requireActivity(), R.style.PreferenceThemeWithShadow)

            MicrophoneButtonPreferences(requireContext()).migrateLegacyVisibilityIfNeeded()
            initializeTouchModeDefaultsIfNeeded()
            val bitratePrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            if (!bitratePrefs.contains(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING)) {
                val recommended = PreferenceConfiguration.getDefaultBitrate(requireContext())
                bitratePrefs.edit {
                    putBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING,
                        bitratePrefs.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, recommended) == recommended)
                }
            }
            setPreferencesFromResource(R.xml.preferences, null)
            val screen = preferenceScreen
            replaceSearchableCategories(screen)

            setupFramegenPreferences()
            setupConfigSyncPreferences()
            setupMicVolumeProcessingPreferences()
            setupInputModePresetPreference()
            setupPerfOverlayPreferences()
            setupMicrophoneButtonPositionPreference()
            setupFloatBallSettings()
            setupLegacyBackedModeSelectors()
            setupShizukuKeyboardAuthorization()

            // 让所有 ListPreference 在 summary 顶部显示当前选中值，
            // 避免用户必须点开才知道现值。原 summary 作为说明保留在第二行。
            applyListPreferenceCurrentValueSummary(screen)
            findPreference<SeekBarPreference>("seekbar_float_ball_auto_hide_delay")?.summaryProvider =
                Preference.SummaryProvider<SeekBarPreference> { item ->
                    val delay = item.currentValue.coerceIn(0, 5000)
                    if (delay == 0) getString(R.string.float_ball_summary_no_hide)
                    else getString(R.string.float_ball_summary_delay,
                        java.text.NumberFormat.getNumberInstance().format(delay / 1000.0))
                }
            setupAdaptiveBitratePresentation()

            // 为 LocalImagePickerPreference 设置 Fragment 实例，确保 onActivityResult 回调正确
            val localImagePicker = findPreference<LocalImagePickerPreference>("local_image_picker")
            localImagePicker?.setFragment(this)

            // Route API URL changes through BackgroundSource so source state,
            // cache invalidation, and the live refresh stay atomic.
            val backgroundImageUrlPref = findPreference<EditTextPreference>("background_image_url")
            backgroundImageUrlPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val context = requireActivity()
                val url = (newValue as? String).orEmpty().trim()

                if (url.isNotEmpty()) {
                    PreferenceManager.getDefaultSharedPreferences(context).edit {
                        putString(BackgroundSource.KEY_API_URL, url)
                    }
                    BackgroundSource.setActivePreservingExtras(context, BackgroundSource.Api)
                } else {
                    BackgroundSource.setActive(context, BackgroundSource.Auto)
                }

                updateBackgroundPreferenceVisibility(
                    if (url.isNotEmpty()) BackgroundSource.Api.prefValue else BackgroundSource.Auto.prefValue
                )

                true
            }
            findPreference<ListPreference>(BackgroundSource.KEY_SOURCE)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    updateBackgroundPreferenceVisibility(newValue as? String)
                    true
                }
            findPreference<Preference>("checkbox_force_mtk_max_operating_rate")?.isVisible =
                MediaCodecHelper.hasMediaTekDecoder()

            // hide on-screen controls category on non touch screen devices
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                val category = findPreference<PreferenceCategory>("category_onscreen_controls")!!
                screen.removePreference(category)
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    requireActivity().packageManager.hasSystemFeature("com.nvidia.feature.shield")) {
                findPreference<Preference>("checkbox_absolute_mouse_mode")?.let { it.parent?.removePreference(it) }
            }

            // InputDeviceSensorManager is unsafe on Android 12 and 12L. Keep the device-sensor
            // fallback visible because it uses the regular SensorManager instead.
            if (!InputDeviceSensorPolicy.isSupported(Build.VERSION.SDK_INT)) {
                findPreference<Preference>("checkbox_gamepad_motion_sensors")?.let { it.parent?.removePreference(it) }
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                findPreference<Preference>("checkbox_gamepad_motion_fallback")?.let { it.parent?.removePreference(it) }
            }

            // Remote text field auto-show uses the temporary input connection on API 30+.
            // API 33+ additionally enables StreamView's auto-handwriting shield.
            if (!RemoteImeAutoShowPolicy.isSupported(Build.VERSION.SDK_INT)) {
                findPreference<Preference>("checkbox_remote_ime_auto_show")?.isVisible = false
            }

            // Hide USB driver options on devices without USB host support
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                findPreference<Preference>("checkbox_experimental_haptic_protocols")?.isVisible = false
            }
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                findPreference<Preference>("checkbox_usb_bind_all")?.let { it.parent?.removePreference(it) }
                findPreference<Preference>("checkbox_usb_driver")?.let { it.parent?.removePreference(it) }
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !requireActivity().packageManager.hasSystemFeature("android.software.picture_in_picture") ||
                    requireActivity().packageManager.hasSystemFeature("com.amazon.software.fireos")) {
                findPreference<Preference>("checkbox_enable_pip")?.let { it.parent?.removePreference(it) }
            }

            val deviceVibrator = requireActivity().getSystemService(VIBRATOR_SERVICE) as Vibrator
            val deviceRumbleStrength = findPreference<Preference>("seekbar_vibrate_fallback_strength")!!
            // Routing remains useful without a phone motor because controller-only and Smart
            // still work. Only the device-specific strength control should disappear.
            if (!deviceVibrator.hasVibrator()) {
                deviceRumbleStrength.parent?.removePreference(deviceRumbleStrength)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !deviceVibrator.hasAmplitudeControl()) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                deviceRumbleStrength.parent?.removePreference(deviceRumbleStrength)
            }

            // 获取目标显示器（优先使用外接显示器）
            replaceSavedResolutionEntries()
            replaceSavedFrameRateEntries()

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS")
                // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                val hdrModePref = findPreference<Preference>("list_hdr_mode")
                if (hdrModePref != null) {
                    hdrModePref.parent?.removePreference(hdrModePref)
                }
                val hdrPeakBrightnessPref = findPreference<Preference>("seekbar_hdr_peak_brightness_nits")
                if (hdrPeakBrightnessPref != null) {
                    hdrPeakBrightnessPref.parent?.removePreference(hdrPeakBrightnessPref)
                }
                val hdrBrightnessOverridePref = findPreference<Preference>("checkbox_hdr_brightness_override")
                if (hdrBrightnessOverridePref != null) {
                    hdrBrightnessOverridePref.parent?.removePreference(hdrBrightnessOverridePref)
                }
                val hdrHighBrightnessPref = findPreference<Preference>("checkbox_enable_hdr_high_brightness")
                if (hdrHighBrightnessPref != null) {
                    hdrHighBrightnessPref.parent?.removePreference(hdrHighBrightnessPref)
                }
                val hdrPref = findPreference<Preference>("checkbox_enable_hdr")
                if (hdrPref != null) {
                    hdrPref.parent?.removePreference(hdrPref)
                }
            } else {
                // 获取目标显示器的 HDR 能力（优先使用外接显示器）
                val targetDisplay = getTargetDisplay()

                // Settings are shown before a concrete display mode is selected, so use the
                // display-wide capabilities rather than restricting the menu to the current mode.
                val hdrTypeSupport = HdrCapabilityHelper.getDisplayWideHdrTypeSupport(targetDisplay)
                val foundHdr10 = hdrTypeSupport.hasHdr10
                val foundHdr10Plus = hdrTypeSupport.hasHdr10Plus
                val foundHlg = hdrTypeSupport.hasHlg
                val foundDolbyVision = hdrTypeSupport.hasDolbyVision

                val hdrPref = findPreference<CheckBoxPreference>("checkbox_enable_hdr")
                val hdrHighBrightnessPref = findPreference<CheckBoxPreference>("checkbox_enable_hdr_high_brightness")
                val hdrBrightnessOverridePref = findPreference<CheckBoxPreference>("checkbox_hdr_brightness_override")
                val hdrPeakBrightnessPref = findPreference<Preference>("seekbar_hdr_peak_brightness_nits")
                val hdrModePref = findPreference<ListPreference>("list_hdr_mode")

                if (!foundHdr10 && !foundHdr10Plus && !foundHlg) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities")
                    // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                    if (hdrModePref != null) {
                        hdrModePref.parent?.removePreference(hdrModePref)
                    }
                    if (hdrPeakBrightnessPref != null) {
                        hdrPeakBrightnessPref.parent?.removePreference(hdrPeakBrightnessPref)
                    }
                    if (hdrBrightnessOverridePref != null) {
                        hdrBrightnessOverridePref.parent?.removePreference(hdrBrightnessOverridePref)
                    }
                    if (hdrHighBrightnessPref != null) {
                        hdrHighBrightnessPref.parent?.removePreference(hdrHighBrightnessPref)
                    }
                    if (hdrPref != null) {
                        hdrPref.parent?.removePreference(hdrPref)
                    }
                } else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                    if (hdrPref != null) {
                        hdrPref.isEnabled = false
                        hdrPref.isChecked = false
                        hdrPref.summary = requireContext().getString(R.string.hdr_shield_update)
                    }
                    // 同时禁用 HDR 高亮度选项
                    if (hdrHighBrightnessPref != null) {
                        hdrHighBrightnessPref.isEnabled = false
                        hdrHighBrightnessPref.isChecked = false
                    }
                    if (hdrBrightnessOverridePref != null) {
                        hdrBrightnessOverridePref.isEnabled = false
                        hdrBrightnessOverridePref.isChecked = false
                    }
                    if (hdrPeakBrightnessPref != null) {
                        hdrPeakBrightnessPref.isEnabled = false
                    }
                    // 同时禁用 HDR 模式选项
                    if (hdrModePref != null) {
                        hdrModePref.isEnabled = false
                    }
                } else {
                    // HDR is supported, configure the HDR mode preference
                    if (hdrModePref != null) {
                        var pyrowaveNativeVivid = false
                        var pyrowaveVividProbed = false
                        val updateHdrEntries: (Boolean) -> Unit = { pyrowaveSelected ->
                            val entries = mutableListOf<CharSequence>()
                            val entryValues = mutableListOf<CharSequence>()

                            if (foundHdr10 || foundHdr10Plus) {
                                entries += getString(R.string.hdr_mode_hdr10)
                                entryValues += "1"
                            }
                            if (foundHdr10Plus || (pyrowaveSelected && foundHdr10)) {
                                entries += getString(R.string.hdr_mode_hdr10_plus)
                                entryValues += "3"
                            }
                            if (foundHlg) {
                                entries += getString(R.string.hdr_mode_hlg)
                                entryValues += "2"
                            }
                            if (pyrowaveSelected) {
                                if (pyrowaveNativeVivid && (foundHdr10 || foundHdr10Plus)) {
                                    entries += getString(R.string.hdr_mode_pyrowave_vivid_pq)
                                    entryValues += "6"
                                }
                                if (pyrowaveNativeVivid && foundHlg) {
                                    entries += getString(R.string.hdr_mode_pyrowave_vivid_hlg)
                                    entryValues += "7"
                                }
                                // HDR10+ and Dolby Vision use PyroWave's bounded
                                // application mapping path. The base transfer still
                                // follows the display's HDR10/HLG capability.
                                if (foundHdr10 || foundHdr10Plus) {
                                    entries += getString(R.string.hdr_mode_dolby_vision)
                                    entryValues += "4"
                                }
                                if (foundHlg) {
                                    entries += getString(R.string.hdr_mode_dolby_vision_84)
                                    entryValues += "5"
                                }
                            } else if (foundDolbyVision) {
                                entries += getString(R.string.hdr_mode_dolby_vision)
                                entryValues += "4"
                                // 8.4 rides the HLG base layer into the same DV
                                // display pipeline; availability is decoder- and
                                // display-gated identically to 8.1.
                                entries += getString(R.string.hdr_mode_dolby_vision_84)
                                entryValues += "5"
                            }

                            hdrModePref.entries = entries.toTypedArray()
                            hdrModePref.entryValues = entryValues.toTypedArray()

                            // An HDR10+ selection may have been restored from another display/profile.
                            // Prefer static HDR10 when this display cannot present HDR10+.
                            if (!pyrowaveSelected && hdrModePref.value == "3" && !foundHdr10Plus && foundHdr10) {
                                hdrModePref.value = "1"
                            }
                            val pendingVividSelection = pyrowaveSelected && !pyrowaveVividProbed &&
                                    (hdrModePref.value == "6" || hdrModePref.value == "7")
                            if (!pendingVividSelection && hdrModePref.value !in entryValues) {
                                hdrModePref.value = entryValues.first().toString()
                            }

                            // 当前选中值由通用的 SummaryProvider 自动显示（applyListPreferenceCurrentValueSummary），
                            // 这里不再单独设置 summary，避免与 SummaryProvider 互斥而抛 IllegalStateException
                        }
                        val formatPref = findPreference<ListPreference>("video_format")
                        updateHdrEntries(formatPref?.value == "pyrowave")
                        formatPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                            // Keep the current preference/dialog focus; only update HDR choices.
                            updateHdrEntries(newValue == "pyrowave")
                            true
                        }
                        lifecycleScope.launch {
                            pyrowaveNativeVivid = PyrowaveCapabilityProbe.collect().hasNativeVivid
                            pyrowaveVividProbed = true
                            if (isAdded) {
                                updateHdrEntries(formatPref?.value == "pyrowave")
                            }
                        }
                    }
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference<Preference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        val valueStr = newValue as String

                        // 只有显式选择 Native(跟随面板)时才提示;普通预设置即使与面板同尺寸也不再警告
                        if (valueStr == PreferenceConfiguration.RES_NATIVE) {
                            Dialog.displayDialog(requireActivity(),
                                    resources.getString(R.string.title_native_res_dialog),
                                    resources.getString(R.string.text_native_res_dialog),
                                    false)
                        }

                        // Write the new bitrate value
                        resetBitrateToDefault(prefs, valueStr, null)

                        // Allow the original preference change to take place
                        true
                    }
            findPreference<Preference>(PreferenceConfiguration.FPS_PREF_STRING)!!.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        val valueStr = newValue as String

                        // Write the new bitrate value
                        resetBitrateToDefault(prefs, null, valueStr)

                        if (displayLimits().exceedsRefreshRate(valueStr)) {
                            Toast.makeText(requireContext(), R.string.display_frame_rate_above_refresh_rate,
                                Toast.LENGTH_LONG).show()
                        }

                        // Allow the original preference change to take place
                        true
                    }
            findPreference<Preference>(PreferenceConfiguration.CROWN_CONFIG_MANAGEMENT_STRING)!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        startActivity(Intent(requireActivity(), CrownStoreActivity::class.java))
                        true
                    }

            findPreference<Preference>("documentation_handbook")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        HandbookLauncher.openIndex(requireActivity())
                        true
                    }

            findPreference<Preference>("about_us")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        AboutDialogLauncher.show(requireActivity())
                        true
                    }

            findPreference<Preference>("open_source_notices")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        OpenSourceNoticesActivity.open(requireActivity())
                        true
                    }

            // 添加检查更新选项的点击事件
            findPreference<Preference>("check_for_updates")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        UpdateManager.checkForUpdates(requireActivity(), true)
                        true
                    }

            // 编解码与屏幕能力检测
            findPreference<Preference>("capability_diagnostic")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        val capIntent = Intent(requireActivity(), CapabilityDiagnosticActivity::class.java)
                        startActivity(capIntent)
                        true
                    }

            findPreference<Preference>("pyrowave_capability_diagnostic")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        startActivity(Intent(requireActivity(), CapabilityDiagnosticActivity::class.java)
                            .putExtra(CapabilityDiagnosticActivity.EXTRA_PYROWAVE_REPORT, true))
                        true
                    }

            findPreference<Preference>("controller_diagnostic")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        startActivity(Intent(requireActivity(), ControllerDiagnosticActivity::class.java))
                        true
                    }

            findPreference<Preference>("controller_mouse_settings")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        startActivity(Intent(requireActivity(), ControllerMouseSettingsActivity::class.java))
                        true
                    }

            findPreference<Preference>("stick_calibration")!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        startActivity(Intent(requireActivity(), StickCalibrationActivity::class.java))
                        true
                    }

            refreshSettingsPresentation()
        }

        private fun showEnableExternalSyncConfirmation() {
            appDialogBuilder()
                .setTitle(R.string.title_enable_external_config_sync)
                .setMessage(R.string.message_enable_external_config_sync)
                .setPositiveButton(R.string.config_sync_action_enable) { _, _ ->
                    suppressConfigSyncPreferenceChanges = true
                    try {
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                            putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, true)
                            putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, true)
                            putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, true)
                        }
                    } finally {
                        suppressConfigSyncPreferenceChanges = false
                    }
                    writeConfigSyncLocalSnapshot(showToast = true, requireAutoEnabled = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showStyled()
        }

        private fun initializeTouchModeDefaultsIfNeeded() {
            val context = requireContext()
            modeStore.ensureTouchDefaults(
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            )
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode != BLUETOOTH_CONNECT_PERMISSION_REQUEST) return
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val pendingMode = pendingDualSenseOutputMode
            pendingDualSenseOutputMode = null
            if (granted && pendingMode != null) {
                persistDualSenseOutputMode(pendingMode)
            } else if (!granted) {
                val currentMode = modeStore.dualSenseMode()
                modeStore.setDualSenseMode(
                    DualSenseOutputMode.fromLegacy(
                        systemBluetooth = false,
                        wirelessBridge = currentMode.wirelessBridge,
                    )
                )
                refreshModeSelectors()
                Toast.makeText(
                    requireContext(),
                    R.string.toast_dualsense_bluetooth_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            dialogReturnPreferenceKey = preference.key
            dialogHardwareInput = listView?.isInTouchMode == false
            when (preference) {
                is SeekBarPreference -> {
                    val f = SeekBarPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "SeekBarPreference")
                }
                is ConfirmDeleteOscPreference -> {
                    val f = ConfirmDeleteOscDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "ConfirmDeleteOscPreference")
                }
                is EditTextPreference -> {
                    val f = StyledEditTextPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "StyledEditTextPreference")
                }
                is MultiSelectListPreference -> showMultiChoiceSheet(preference)
                is ListPreference -> showListChoiceSheet(preference)
                else -> super.onDisplayPreferenceDialog(preference)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_CODE_OPEN_CONFIG_SYNC_DIRECTORY && resultCode == RESULT_OK) {
                handleSyncDirectoryResult(data)
                return
            }
            if (requestCode == REQUEST_CODE_CREATE_CONFIG_SYNC && resultCode == RESULT_OK) {
                handleSyncExportResult(data)
                return
            }
            if (requestCode == REQUEST_CODE_OPEN_CONFIG_SYNC && resultCode == RESULT_OK) {
                handleSyncImportResult(data)
                return
            }
            if (requestCode == REQUEST_CODE_PICK_FRAMEGEN_DLL && resultCode == RESULT_OK) {
                val dllUri = data?.data
                if (dllUri != null) {
                    val ctx = requireContext()
                    Log.i("Framegen", "picked Lossless.dll uri=$dllUri flags=${data.flags}")
                    Toast.makeText(ctx, R.string.toast_framegen_lossless_dll_copying, Toast.LENGTH_SHORT).show()

                    thread(name = "FramegenLosslessDllProbe") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                        val resultText = try {
                            // takePersistableUriPermission 只接受 READ/WRITE 两个权限位（0x1 / 0x2），
                            // 不能把 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 本身传进去——否则抛 IllegalArgumentException。
                            try {
                                val readWriteFlags = data.flags and
                                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                val grantFlags = if (readWriteFlags != 0) readWriteFlags
                                                 else Intent.FLAG_GRANT_READ_URI_PERMISSION
                                ctx.contentResolver.takePersistableUriPermission(dllUri, grantFlags)
                            } catch (e: Throwable) {
                                // 某些文档提供器不支持持久授权 / Intent 里没带 read flag；
                                // 这一步只是为了下次冷启动还能读，失败不该阻塞本次复制。
                                Log.w("Framegen", "takePersistableUriPermission skipped: ${e.message}")
                            }

                            val stagedFile = stageFramegenLosslessDll(dllUri)
                            Log.i(
                                "Framegen",
                                "staged Lossless.dll path=${stagedFile.absolutePath} size=${stagedFile.length()}"
                            )
                            prefs.edit {
                                putString(FramegenSettings.PREF_LOSSLESS_DLL_STAGED_PATH, stagedFile.absolutePath)
                            }

                            val probeResult = com.limelight.framegen.FramegenInterceptor()
                                .probeLosslessDll(stagedFile.absolutePath)
                            ctx.getString(R.string.toast_framegen_lossless_dll_probe_result, probeResult)
                        } catch (e: Exception) {
                            Log.e("Framegen", "pick/probe Lossless.dll failed", e)
                            ctx.getString(
                                R.string.toast_framegen_lossless_dll_pick_failed,
                                e.message ?: e.javaClass.simpleName
                            )
                        }

                        activity?.runOnUiThread {
                            if (isAdded) {
                                updateFramegenDllPreferenceSummary()
                                refreshDeveloperFeatureGateState()
                            }
                            Toast.makeText(
                                ctx,
                                resultText,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            // 处理本地图片选择
            if (requestCode == LocalImagePickerPreference.PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
                val pickerPreference = LocalImagePickerPreference.instance
                pickerPreference?.handleImagePickerResult(data)
            }
        }

        private fun setupFramegenPreferences() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            DeveloperUnlockSettings.migrateLegacyPrefs(prefs)
            FramegenSettings.migrateLegacyCustomScale(prefs, getSelectedStreamWidth(prefs))
            setupDeveloperUnlockPreference()
            setupFramegenSelfTestPreference()
            setupFramegenLosslessDllPreference()
            setupFramegenEnabledPreference()
            setupFramegenAdaptivePreference()
            setupFramegenQualityPreference()
            refreshDeveloperFeatureGateState()
            updateFramegenDllPreferenceSummary()
        }

        private fun getSelectedStreamWidth(prefs: SharedPreferences): Int {
            val resolution = prefs.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.DEFAULT_RESOLUTION
            ) ?: PreferenceConfiguration.DEFAULT_RESOLUTION
            return resolution.substringBefore('x').toIntOrNull()
                ?: PreferenceConfiguration.DEFAULT_RESOLUTION.substringBefore('x').toInt()
        }

        private fun setupDeveloperUnlockPreference() {
            findPreference<Preference>(DeveloperUnlockSettings.PREF_ENTRY)?.setOnPreferenceClickListener {
                showDeveloperUnlockDialog()
                true
            }
        }

        private fun setupFramegenSelfTestPreference() {
            findPreference<Preference>("pref_framegen_selftest")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val result = if (com.limelight.framegen.FramegenInterceptor.isAvailable()) {
                    com.limelight.framegen.FramegenInterceptor().selfTest()
                } else {
                    ctx.getString(R.string.toast_framegen_unavailable)
                }
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.toast_framegen_selftest_result, result),
                    Toast.LENGTH_LONG
                ).show()
                true
            }
        }

        private fun setupFramegenLosslessDllPreference() {
            findPreference<Preference>("pref_framegen_pick_lossless_dll")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                if (!com.limelight.framegen.FramegenInterceptor.isAvailable()) {
                    Toast.makeText(ctx, R.string.toast_framegen_unavailable, Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                appDialogBuilder(ctx)
                    .setTitle(R.string.title_framegen_pick_lossless_dll)
                    .setMessage(R.string.message_framegen_lossless_dll_source)
                    .setPositiveButton(R.string.action_framegen_select_lossless_dll) { _, _ ->
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_CODE_PICK_FRAMEGEN_DLL)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showStyled()
                true
            }
        }

        private fun setupFramegenEnabledPreference() {
            findPreference<CheckBoxPreference>(FramegenSettings.PREF_ENABLED)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        val ctx = requireContext()
                        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                        if (!DeveloperUnlockSettings.isUnlocked(prefs)) {
                            showDeveloperUnlockDialog()
                            return@OnPreferenceChangeListener false
                        }
                        if (!FramegenSettings.isLosslessDllReady(prefs)) {
                            Toast.makeText(ctx, R.string.toast_framegen_need_lossless_dll, Toast.LENGTH_LONG).show()
                            updateFramegenDllPreferenceSummary()
                            return@OnPreferenceChangeListener false
                        }
                        if (!com.limelight.framegen.FramegenInterceptor.isAvailable()) {
                            Toast.makeText(ctx, R.string.toast_framegen_unavailable, Toast.LENGTH_LONG).show()
                            return@OnPreferenceChangeListener false
                        }
                        Toast.makeText(ctx, R.string.toast_framegen_enabled_warning, Toast.LENGTH_LONG).show()
                    }
                    true
                }
        }

        private fun setupFramegenAdaptivePreference() {
            findPreference<CheckBoxPreference>(FramegenSettings.PREF_ADAPTIVE_ENABLED)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        val ctx = requireContext()
                        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                        if (!DeveloperUnlockSettings.isUnlocked(prefs)) {
                            showDeveloperUnlockDialog()
                            return@OnPreferenceChangeListener false
                        }
                        if (!FramegenSettings.isLosslessDllReady(prefs)) {
                            Toast.makeText(ctx, R.string.toast_framegen_need_lossless_dll, Toast.LENGTH_LONG).show()
                            updateFramegenDllPreferenceSummary()
                            return@OnPreferenceChangeListener false
                        }
                        if (!com.limelight.framegen.FramegenInterceptor.isAvailable()) {
                            Toast.makeText(ctx, R.string.toast_framegen_unavailable, Toast.LENGTH_LONG).show()
                            return@OnPreferenceChangeListener false
                        }
                    }
                    true
                }
        }

        private fun setupFramegenQualityPreference() {
            findPreference<ListPreference>(FramegenSettings.PREF_QUALITY_PRESET)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    updateFramegenQualityVisibility(newValue as? String)
                    true
                }
            updateFramegenQualityVisibility(
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(FramegenSettings.PREF_QUALITY_PRESET, null)
            )
        }

        private fun updateBackgroundPreferenceVisibility(sourceValue: String?) {
            val source = BackgroundSource.fromPrefValue(sourceValue)
            updateRuntimeVisibility(
                findPreference(BackgroundSource.KEY_API_URL),
                source is BackgroundSource.Api,
            )
            updateRuntimeVisibility(
                findPreference("local_image_picker"),
                source is BackgroundSource.Local,
            )
            updateRuntimeVisibility(
                findPreference("reset_background_image"),
                source !is BackgroundSource.Auto,
            )
        }

        private fun setupLegacyBackedModeSelectors() {
            val store = modeStore

            listOf(
                LegacySettingsModeStore.KEY_RESUME_STREAM,
                LegacySettingsModeStore.KEY_KEEP_STREAM_CONNECTED,
                LegacySettingsModeStore.KEY_BACKGROUND_AUDIO,
                LegacySettingsModeStore.KEY_DISCONNECT_ONLY_ON_QUIT,
                PreferenceConfiguration.DUALSENSE_DIRECT_BLUETOOTH_PREF_STRING,
                LegacySettingsModeStore.KEY_DUALSENSE_WIRELESS_BRIDGE,
            ).forEach { key -> findPreference<Preference>(key)?.isVisible = false }

            findPreference<ListPreference>(BACKGROUND_STREAM_BEHAVIOR_PREF_KEY)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val mode = BackgroundStreamBehaviorPolicy
                        .fromPreferenceValue(newValue as? String)
                    store.setBackgroundMode(mode)
                    true
                }

            findPreference<ListPreference>(QUIT_BEHAVIOR_PREF_KEY)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val mode = QuitBehavior.fromPreferenceValue(newValue as? String)
                    store.setQuitMode(mode)
                    true
                }

            val dualSenseSelector = findPreference<ListPreference>(DUALSENSE_OUTPUT_MODE_PREF_KEY)
            if (dualSenseSelector != null) {
                val packageManager = requireContext().packageManager
                val supportsSystemBluetooth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                val supportsWirelessBridge =
                    packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                val supportedModes = buildList {
                    add(DualSenseOutputMode.OFF)
                    if (supportsSystemBluetooth) add(DualSenseOutputMode.SYSTEM_BLUETOOTH)
                    if (supportsWirelessBridge) add(DualSenseOutputMode.WIRELESS_BRIDGE)
                    if (supportsSystemBluetooth && supportsWirelessBridge) {
                        add(DualSenseOutputMode.BOTH)
                    }
                }
                val allModeLabels = resources.getTextArray(R.array.dualsense_output_mode_names)
                dualSenseSelector.entries = supportedModes
                    .map { mode -> allModeLabels[mode.ordinal] }
                    .toTypedArray()
                dualSenseSelector.entryValues = supportedModes
                    .map { mode -> mode.preferenceValue }
                    .toTypedArray()
                updateRuntimeVisibility(
                    dualSenseSelector,
                    supportsSystemBluetooth || supportsWirelessBridge,
                )
                dualSenseSelector.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        val mode = DualSenseOutputMode.fromPreferenceValue(newValue as? String)
                        val needsBluetoothPermission = mode.systemBluetooth &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            ContextCompat.checkSelfPermission(
                                requireContext(),
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsBluetoothPermission) {
                            pendingDualSenseOutputMode = mode
                            requestPermissions(
                                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                                BLUETOOTH_CONNECT_PERMISSION_REQUEST,
                            )
                            false
                        } else {
                            persistDualSenseOutputMode(mode)
                            true
                        }
                    }
            }

            refreshModeSelectors()
        }

        private fun persistDualSenseOutputMode(mode: DualSenseOutputMode) {
            modeStore.setDualSenseMode(mode)
            refreshModeSelectors()
        }

        private fun refreshModeSelectors() {
            val store = modeStore
            findPreference<ListPreference>(BACKGROUND_STREAM_BEHAVIOR_PREF_KEY)?.value =
                store.backgroundMode().preferenceValue
            findPreference<ListPreference>(QUIT_BEHAVIOR_PREF_KEY)?.value =
                store.quitMode().preferenceValue

            val selector = findPreference<ListPreference>(DUALSENSE_OUTPUT_MODE_PREF_KEY)
            val legacyMode = store.dualSenseMode()
            val availableValues = selector?.entryValues?.map(CharSequence::toString).orEmpty()
            selector?.value = when {
                legacyMode.preferenceValue in availableValues -> legacyMode.preferenceValue
                DualSenseOutputMode.SYSTEM_BLUETOOTH.preferenceValue in availableValues &&
                    legacyMode == DualSenseOutputMode.BOTH ->
                    DualSenseOutputMode.SYSTEM_BLUETOOTH.preferenceValue
                DualSenseOutputMode.WIRELESS_BRIDGE.preferenceValue in availableValues &&
                    legacyMode == DualSenseOutputMode.BOTH ->
                    DualSenseOutputMode.WIRELESS_BRIDGE.preferenceValue
                else -> DualSenseOutputMode.OFF.preferenceValue
            }

            refreshTouchModeSelector(store)
            refreshMicrophoneModeSelector(store)
        }

        /** Reapplies preference presentation derived from the changed stored value. */
        private fun refreshSettingsPresentation(changedKey: String? = null) {
            if (changedKey == null || changedKey.contains("float_ball")) {
                updateFloatBallSettingsSummary()
            }
            if (changedKey == null || changedKey in LegacySettingsModeStore.modeKeys) {
                refreshModeSelectors()
            }
            if (changedKey == null || changedKey in setOf(
                    "checkbox_adaptive_bitrate", "list_abr_mode",
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING,
                    PreferenceConfiguration.RESOLUTION_PREF_STRING,
                    PreferenceConfiguration.FPS_PREF_STRING,
                )) {
                setupAdaptiveBitratePresentation()
                updateAdaptiveBitratePresentation()
            }
            if (changedKey == null || changedKey == "list_fec_mode") {
                val fixed = findPreference<ListPreference>("list_fec_mode")?.value == "fixed"
                updateRuntimeVisibility(findPreference("seekbar_fec_percentage"), fixed)
            }
            if (changedKey == null || changedKey == "checkbox_enable_audio_passthrough") {
                updateAudioPipelineVisibility()
            }
            if (changedKey == null || changedKey == "checkbox_stretch_video") {
                updateDisplayLayoutVisibility()
            }
            if (changedKey == null || changedKey == "checkbox_enable_hdr" ||
                changedKey == "checkbox_hdr_brightness_override"
            ) {
                updateHdrPreferenceVisibility()
            }
            if (changedKey == null ||
                changedKey == PreferenceConfiguration.FRAME_PACING_PREF_STRING
            ) {
                updateHostCadencePreciseSyncVisibility()
            }
            if (changedKey == null ||
                changedKey == PreferenceConfiguration.GAME_RUMBLE_MODE_PREF_STRING
            ) {
                updateDeviceRumbleVisibility()
            }
            if (changedKey == null || changedKey == BackgroundSource.KEY_SOURCE) {
                updateBackgroundPreferenceVisibility(
                    BackgroundSource.current(requireContext()).prefValue
                )
            }
            refreshFeaturePageVisibility(preferenceScreen)
            updateFeaturePageSummaries()
        }

        /** Capability filtering can empty a feature or an internal group on older devices. */
        private fun refreshFeaturePageVisibility(group: PreferenceGroup): Boolean {
            var hasVisibleSetting = false
            for (index in 0 until group.preferenceCount) {
                val child = group.getPreference(index)
                val visible = if (child is PreferenceGroup) {
                    val hasChildren = refreshFeaturePageVisibility(child)
                    if (visibilityController.isRuntimeVisible(child) != hasChildren) {
                        updateRuntimeVisibility(child, hasChildren)
                    }
                    hasChildren
                } else visibilityController.isRuntimeVisible(child)
                hasVisibleSetting = hasVisibleSetting || visible
            }
            return hasVisibleSetting
        }

        private val featurePageDescriptions = mutableMapOf<String, CharSequence?>()

        private fun updateFeaturePageSummaries() {
            for ((screenKey, masterKey) in FEATURE_MASTER_KEYS) {
                val screen = findPreference<PreferenceScreen>(screenKey) ?: continue
                val master = findPreference<CheckBoxPreference>(masterKey) ?: continue
                if (!featurePageDescriptions.containsKey(screenKey)) featurePageDescriptions[screenKey] = screen.summary
                val status = getString(if (master.isChecked) R.string.game_menu_option_enabled
                    else R.string.game_menu_option_disabled)
                screen.summary = listOfNotNull(status, featurePageDescriptions[screenKey]).joinToString(" · ")
            }
        }

        private fun refreshTouchModeSelector(store: LegacySettingsModeStore) {
            val selector = findPreference<ListPreference>(
                PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING
            ) ?: return
            val hasTouchscreen = requireContext().packageManager.hasSystemFeature(
                PackageManager.FEATURE_TOUCHSCREEN
            )
            val state = store.touchState(hasTouchscreen)
            val preset = TouchModePreferencePolicy.exactPresetFor(state)
            store.reconcileTouchModeMirror(state)

            selector.entries = resources.getTextArray(R.array.native_mouse_mode_preset_names)
            selector.entryValues = resources.getTextArray(R.array.native_mouse_mode_preset_values)
            if (preset == null) {
                selector.entries = selector.entries +
                    getString(R.string.native_mouse_mode_preset_custom)
                selector.entryValues = selector.entryValues + LegacySettingsModeStore.TOUCH_MODE_CUSTOM
            }
            selector.value = preset?.preferenceValue ?: LegacySettingsModeStore.TOUCH_MODE_CUSTOM

            listOf(
                "seekbar_flat_region_pixels",
                "checkbox_enhanced_touch_on_which_side",
                "enhanced_touch_zone_divider",
                "pointer_velocity_factor",
            ).forEach { key ->
                updateRuntimeVisibility(
                    findPreference(key),
                    hasTouchscreen && state.enhancedTouch,
                )
            }
        }

        private fun refreshMicrophoneModeSelector(store: LegacySettingsModeStore) {
            val selector = findPreference<ListPreference>(
                PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING
            ) ?: return
            val mode = store.microphoneMode()
            val flags = MicVolumeProcessingPolicy.flagsFor(mode)
            store.reconcileMicrophoneModeMirror(mode)

            selector.entries = resources.getTextArray(R.array.mic_volume_processing_mode_names)
            selector.entryValues = resources.getTextArray(R.array.mic_volume_processing_mode_values)
            if (mode == MicVolumeProcessingPolicy.LEGACY_PROCESSING_ONLY) {
                selector.entries = selector.entries +
                    getString(R.string.mic_volume_processing_mode_legacy)
                selector.entryValues = selector.entryValues + mode
            }
            selector.value = mode

            updateRuntimeVisibility(findPreference("seekbar_mic_gain_db"), flags.gain)
            updateRuntimeVisibility(findPreference("seekbar_mic_balance_target"), flags.balance)
            updateRuntimeVisibility(
                findPreference("checkbox_mic_voice_enhancement"),
                flags.processing,
            )
        }

        private fun setupAdaptiveBitratePresentation() {
            val adaptiveBitrate = findPreference<CheckBoxPreference>("checkbox_adaptive_bitrate") ?: return
            val bitrate = findPreference<SeekBarPreference>(PreferenceConfiguration.BITRATE_PREF_STRING) ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val valueText = ContextCompat.getColor(bitrate.context, R.color.ui_shell_text_primary)
            val disabledAccent = ContextCompat.getColor(bitrate.context, R.color.ui_shell_text_disabled_primary)
            val recommended = prefs.getBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
            val description = buildString {
                append(getString(if (adaptiveBitrate.isChecked) R.string.summary_seekbar_bitrate_baseline
                                 else R.string.summary_seekbar_bitrate))
                if (adaptiveBitrate.isChecked) {
                    val range = AdaptiveBitrateService.bitrateRange(bitrate.currentValue,
                        prefs.getString("list_abr_mode", "balanced") ?: "balanced")
                    append("\n").append(getString(R.string.summary_abr_bitrate_range,
                        bitrate.formatDisplayValue(range.first), bitrate.formatDisplayValue(range.last)))
                }
                append("\n").append(getString(if (recommended) R.string.summary_bitrate_recommended
                                              else R.string.summary_bitrate_manual))
            }
            bitrate.dialogMessageText = getString(if (adaptiveBitrate.isChecked)
                R.string.summary_seekbar_bitrate_baseline else R.string.summary_seekbar_bitrate)
            applyHighlightedSummary(bitrate, valueText, disabledAccent,
                currentValueProvider = { "${it.formatDisplayValue(it.currentValue)} ${it.suffix.orEmpty()}" },
                descriptionProvider = { description })

            findPreference<ListPreference>("list_fec_mode")?.let { fec ->
                applyHighlightedSummary(fec, valueText, disabledAccent,
                    currentValueProvider = { it.entry?.toString().orEmpty() },
                    descriptionProvider = { getString(when (it.value) {
                        "automatic" -> R.string.summary_fec_automatic
                        "fixed" -> R.string.summary_fec_fixed
                        else -> R.string.summary_fec_host
                    }) })
            }
        }

        private fun updateAdaptiveBitratePresentation() {
            val adaptiveBitrate = findPreference<CheckBoxPreference>("checkbox_adaptive_bitrate") ?: return
            findPreference<SeekBarPreference>(PreferenceConfiguration.BITRATE_PREF_STRING)?.let { bitrate ->
                bitrate.setTitle(if (adaptiveBitrate.isChecked) R.string.title_seekbar_bitrate_baseline
                                 else R.string.title_seekbar_bitrate)
                bitrate.dialogTitle = bitrate.title
            }
            updateRuntimeVisibility(findPreference("list_abr_mode"), adaptiveBitrate.isChecked)
        }

        private fun updateAudioPipelineVisibility() {
            val passthrough = findPreference<CheckBoxPreference>("checkbox_enable_audio_passthrough")
                ?: return
            val audioEffects = findPreference<Preference>("checkbox_enable_audiofx")
            val spatializer = findPreference<Preference>("checkbox_enable_spatializer")

            audioEffects?.isEnabled = !passthrough.isChecked
            audioEffects?.setSummary(
                if (passthrough.isChecked) R.string.summary_checkbox_enable_audiofx_passthrough
                else R.string.summary_checkbox_enable_audiofx
            )
            spatializer?.isEnabled = !passthrough.isChecked
            spatializer?.setSummary(
                if (passthrough.isChecked) R.string.summary_checkbox_enable_spatializer_passthrough
                else R.string.summary_checkbox_enable_spatializer
            )
        }

        private fun updateDisplayLayoutVisibility() {
            val stretchVideo = findPreference<CheckBoxPreference>("checkbox_stretch_video") ?: return
            val layoutPreferences = listOf(
                "list_screen_position",
                "seekbar_screen_offset_x",
                "seekbar_screen_offset_y",
            ).mapNotNull { key -> findPreference<Preference>(key) }

            layoutPreferences.forEach { preference ->
                updateRuntimeVisibility(preference, !stretchVideo.isChecked)
            }
        }

        private fun updateHdrPreferenceVisibility() {
            val hdr = findPreference<CheckBoxPreference>("checkbox_enable_hdr") ?: return
            val brightnessOverride =
                findPreference<CheckBoxPreference>("checkbox_hdr_brightness_override")
            val hdrChildren = listOf(
                "checkbox_enable_hdr_high_brightness",
                "checkbox_hdr_brightness_override",
                "list_hdr_mode",
            ).mapNotNull { key -> findPreference<Preference>(key) }
            val peakBrightness = findPreference<Preference>("seekbar_hdr_peak_brightness_nits")

            hdrChildren.forEach { preference ->
                updateRuntimeVisibility(preference, hdr.isChecked)
            }
            updateRuntimeVisibility(
                peakBrightness,
                hdr.isChecked && brightnessOverride?.isChecked == true,
            )
        }

        private fun updateDeviceRumbleVisibility() {
            val strength = findPreference<Preference>("seekbar_vibrate_fallback_strength")
                ?: return
            val mode = findPreference<ListPreference>(
                PreferenceConfiguration.GAME_RUMBLE_MODE_PREF_STRING
            )?.value
            updateRuntimeVisibility(
                strength,
                GameRumbleMode.fromPreferenceValue(mode) != GameRumbleMode.CONTROLLER,
            )
        }

        private fun setupInputModePresetPreference() {
            val presetPref = findPreference<ListPreference>(
                PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING
            ) ?: return

            findPreference<Preference>(
                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING
            )?.isVisible = false
            findPreference<Preference>(
                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING
            )?.isVisible = false

            presetPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val preset = TouchModePreset.fromPreferenceValue(newValue as? String)
                    ?: return@OnPreferenceChangeListener false
                modeStore.setTouchPreset(preset)
                refreshModeSelectors()
                val presetName = presetPref.entries[
                    presetPref.entryValues.indexOfFirst { it.toString() == preset.preferenceValue }
                ]
                Toast.makeText(
                    activity,
                    getString(R.string.toast_preset_applied, presetName),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }

        private fun setupMicVolumeProcessingPreferences() {
            val modePref = findPreference<ListPreference>(
                PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING
            ) ?: return
            findPreference<CheckBoxPreference>(
                PreferenceConfiguration.MIC_VOLUME_PROCESSING_PREF_STRING
            )?.isVisible = false
            findPreference<CheckBoxPreference>(
                PreferenceConfiguration.MIC_GAIN_ENABLED_PREF_STRING
            )?.isVisible = false
            findPreference<CheckBoxPreference>(
                PreferenceConfiguration.MIC_BALANCE_ENABLED_PREF_STRING
            )?.isVisible = false

            modePref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                modeStore.setMicrophoneMode(
                    newValue as? String ?: MicVolumeProcessingPolicy.OFF
                )
                refreshModeSelectors()
                true
            }
        }

        private fun setupMicrophoneButtonPositionPreference() {
            val positionPreference = findPreference<ListPreference>(
                MicrophoneButtonPreferences.KEY_PRESET_POSITION
            ) ?: return
            val positionStore = MicrophoneButtonPositionStore(requireContext())
            positionPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    positionStore.clearCustomPosition()
                    true
                }
        }

        private fun setupFloatBallSettings() {
            findPreference<ListPreference>(FloatBallPreferences.KEY_PRESET_POSITION)
                ?.setOnPreferenceChangeListener { _, _ ->
                    FloatBallPositionStore(requireContext()).clearCustomPosition()
                    updateFloatBallSettingsSummary()
                    true
                }
            findPreference<CheckBoxPreference>("checkbox_enable_float_ball")?.summaryOff =
                getString(R.string.float_ball_settings_disabled_summary)
            updateFloatBallSettingsSummary()
        }

        private fun updateFloatBallSettingsSummary() {
            val screen = findPreference<PreferenceScreen>(FLOAT_BALL_SCREEN_KEY) ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val status = if (!prefs.getBoolean("checkbox_enable_float_ball", true)) {
                getString(R.string.game_menu_option_disabled)
            } else {
                val positionStore = FloatBallPositionStore(requireContext())
                val position = if (positionStore.customPosition() != null || positionStore.legacyPosition() != null) {
                    getString(R.string.float_ball_summary_custom_position)
                } else {
                    val value = FloatBallPreferences(requireContext()).presetPosition()
                    val values = resources.getStringArray(R.array.float_ball_position_values)
                    val labels = resources.getStringArray(R.array.float_ball_position_entries)
                    labels.getOrNull(values.indexOf(value)).orEmpty()
                }
                val delayMs = prefs.getInt("seekbar_float_ball_auto_hide_delay", 2000).coerceIn(0, 5000)
                val delay = if (delayMs == 0) getString(R.string.float_ball_summary_no_hide) else {
                    val seconds = java.text.NumberFormat.getNumberInstance().format(delayMs / 1000.0)
                    getString(R.string.float_ball_summary_delay, seconds)
                }
                getString(R.string.float_ball_summary_enabled, position, delay)
            }
            val matched = visibilityController.matchingDescendant(screen)?.title
            screen.summary = if (matched != null) {
                getString(R.string.float_ball_summary_search, status, matched)
            } else status
        }

        private fun setupPerfOverlayPreferences() {
            val metricsPreference = findPreference<PerfOverlayDisplayItemsPreference>(
                "perf_overlay_display_items"
            ) ?: return
            val clockFormatPreference = findPreference<Preference>(
                "list_perf_overlay_clock_format"
            ) ?: return

            fun updateClockFormatEnabled(selectedItems: Set<*>?) {
                clockFormatPreference.isEnabled = selectedItems?.contains("clock") == true
            }

            updateClockFormatEnabled(metricsPreference.values)
            metricsPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    updateClockFormatEnabled(newValue as? Set<*>)
                    true
                }
        }

        private fun refreshDeveloperFeatureGateState() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val unlocked = DeveloperUnlockSettings.isUnlocked(prefs)
            val dllReady = FramegenSettings.isLosslessDllReady(prefs)

            findPreference<Preference>(DeveloperUnlockSettings.PREF_ENTRY)?.summary =
                getString(
                    if (unlocked) {
                        R.string.summary_developer_unlock_unlocked
                    } else {
                        R.string.summary_developer_unlock_locked
                    }
                )

            findPreference<CheckBoxPreference>(FramegenSettings.PREF_ENABLED)?.let { pref ->
                pref.isEnabled = unlocked && dllReady
                if ((!unlocked || !dllReady) && pref.isChecked) {
                    pref.isChecked = false
                    prefs.edit { putBoolean(FramegenSettings.PREF_ENABLED, false) }
                }
                pref.summary = getString(
                    when {
                        !unlocked -> R.string.summary_framegen_enabled_locked
                        !dllReady -> R.string.summary_framegen_enabled_needs_dll
                        else -> R.string.summary_framegen_enabled
                    }
                )
            }

            updateFramegenAdaptivePreferenceState(prefs, unlocked, dllReady)
        }

        private fun updateFramegenAdaptivePreferenceState(
            prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext()),
            unlocked: Boolean = DeveloperUnlockSettings.isUnlocked(prefs),
            dllReady: Boolean = FramegenSettings.isLosslessDllReady(prefs)
        ) {
            val showAdaptive = unlocked && dllReady
            findPreference<CheckBoxPreference>(FramegenSettings.PREF_ADAPTIVE_ENABLED)?.let { pref ->
                updateRuntimeVisibility(pref, showAdaptive)
                pref.isEnabled = showAdaptive
            }
        }

        private fun updateFramegenQualityVisibility(selectedPreset: String?) {
            val showCustomWidth = selectedPreset == FramegenSettings.QUALITY_CUSTOM
            updateRuntimeVisibility(findPreference(FramegenSettings.PREF_INTERNAL_WIDTH), showCustomWidth)
            updateRuntimeVisibility(findPreference(FramegenSettings.PREF_SLOW_THRESHOLD_MS), showCustomWidth)
            updateRuntimeVisibility(findPreference(FramegenSettings.PREF_PRESENT_REAL_FIRST), showCustomWidth)
        }

        private fun showDeveloperUnlockDialog() {
            val ctx = requireContext()
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val alreadyUnlocked = DeveloperUnlockSettings.isUnlocked(prefs)
            if (!GitHubStarVerifier.isConfigured()) {
                appDialogBuilder(ctx)
                    .setTitle(R.string.title_developer_unlock)
                    .setMessage(R.string.message_developer_oauth_unconfigured)
                    .setPositiveButton(R.string.action_developer_open_project) { _, _ ->
                        openDeveloperProjectPage()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showStyled()
                return
            }

            if (!alreadyUnlocked) {
                val pendingDeviceCode = GitHubDeviceAuthorization.loadPendingDeviceCode(ctx.applicationContext)
                if (pendingDeviceCode?.scope == GitHubStarVerifier.OAuthScope.STAR_VERIFICATION) {
                    developerPendingDeviceCode = pendingDeviceCode
                    showDeveloperDeviceCodeDialog(pendingDeviceCode)
                    pollDeveloperPendingDeviceCode(showPendingToast = false)
                    return
                }
            }

            appDialogBuilder(ctx)
                .setTitle(R.string.title_developer_unlock)
                .setMessage(
                    if (alreadyUnlocked) {
                        R.string.message_developer_unlock_done
                    } else {
                        R.string.message_developer_unlock_required
                    }
                )
                .setPositiveButton(R.string.action_developer_verify_star) { _, _ ->
                    startDeveloperUnlockVerification()
                }
                .setNeutralButton(R.string.action_developer_open_project) { _, _ ->
                    openDeveloperProjectPage()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showStyled()
        }

        private fun startDeveloperUnlockVerification(
            scope: GitHubStarVerifier.OAuthScope = GitHubStarVerifier.OAuthScope.STAR_VERIFICATION
        ) {
            val ctx = requireContext().applicationContext
            if (developerUnlockVerificationRunning) {
                Toast.makeText(ctx, R.string.toast_developer_verification_running, Toast.LENGTH_LONG).show()
                return
            }
            if (!GitHubStarVerifier.isConfigured()) {
                Toast.makeText(ctx, R.string.toast_developer_oauth_unconfigured, Toast.LENGTH_LONG).show()
                return
            }

            GitHubDeviceAuthorization.pendingDeviceCode(ctx, scope)?.let { pendingDeviceCode ->
                if (GitHubDeviceAuthorization.accessToken(ctx, scope) == null) {
                    developerPendingDeviceCode = pendingDeviceCode
                    showDeveloperDeviceCodeDialog(pendingDeviceCode)
                    pollDeveloperPendingDeviceCode(showPendingToast = false)
                    return
                }
            }

            developerUnlockVerificationRunning = true
            Toast.makeText(ctx, R.string.toast_developer_verification_started, Toast.LENGTH_LONG).show()
            thread(name = "DeveloperGitHubStarVerify") {
                try {
                    val savedToken = GitHubDeviceAuthorization.accessToken(ctx, scope)
                    val starCheck = try {
                        savedToken?.let(GitHubStarVerifier::checkStar)
                    } catch (_: GitHubStarVerifier.AuthorizationExpiredException) {
                        GitHubDeviceAuthorization.clearCredentials(ctx, savedToken)
                        null
                    }
                    if (savedToken != null && starCheck != null) {
                        completeDeveloperUnlockVerification(
                            ctx, savedToken, starCheck, scope
                        )
                    } else {
                        val deviceCode = GitHubDeviceAuthorization.pendingDeviceCode(ctx, scope)
                            ?: GitHubStarVerifier.requestDeviceCode(scope).also {
                                GitHubDeviceAuthorization.savePendingDeviceCode(ctx, it)
                            }
                        developerPendingDeviceCode = deviceCode
                        activity?.runOnUiThread {
                            if (isAdded) showDeveloperDeviceCodeDialog(deviceCode)
                        }
                        developerUnlockVerificationRunning = false
                    }
                } catch (e: Exception) {
                    Log.e("DeveloperUnlock", "GitHub star verification failed", e)
                    failDeveloperUnlockVerification(ctx, e.message ?: e.javaClass.simpleName)
                }
            }
        }

        private fun resumeDeveloperUnlockVerificationIfPending() {
            pollDeveloperPendingDeviceCode(showPendingToast = false)
        }

        private fun pollDeveloperPendingDeviceCode(showPendingToast: Boolean) {
            val ctx = requireContext().applicationContext
            val deviceCode = developerPendingDeviceCode
                ?: GitHubDeviceAuthorization.loadPendingDeviceCode(ctx)
            if (deviceCode == null) {
                if (showPendingToast) {
                    Toast.makeText(ctx, R.string.toast_developer_verification_expired, Toast.LENGTH_LONG).show()
                }
                return
            }
            developerPendingDeviceCode = deviceCode
            if (developerForegroundPollRunning) {
                if (showPendingToast) {
                    Toast.makeText(ctx, R.string.toast_developer_verification_running, Toast.LENGTH_LONG).show()
                }
                return
            }

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - developerLastForegroundPollMs < deviceCode.intervalSeconds * 1000L) {
                if (showPendingToast) {
                    Toast.makeText(ctx, R.string.toast_developer_authorization_pending, Toast.LENGTH_LONG).show()
                }
                return
            }
            developerLastForegroundPollMs = nowMs
            developerForegroundPollRunning = true
            developerUnlockVerificationRunning = true

            thread(name = "DeveloperGitHubStarVerifyResume") {
                try {
                    Log.i("DeveloperUnlock", "GitHub star verification foreground poll, manual=$showPendingToast")
                    when (val poll = GitHubStarVerifier.pollAccessToken(deviceCode)) {
                        is GitHubStarVerifier.TokenPollResult.Authorized -> {
                            Log.i("DeveloperUnlock", "GitHub star device flow authorized from foreground poll")
                            val starCheck = GitHubStarVerifier.checkStar(poll.accessToken)
                            runIfDeveloperAttemptActive(deviceCode) {
                                completeDeveloperUnlockVerification(
                                    ctx = ctx,
                                    accessToken = poll.accessToken,
                                    starCheck = starCheck,
                                    scope = deviceCode.scope
                                )
                            }
                        }
                        GitHubStarVerifier.TokenPollResult.Pending -> {
                            Log.i("DeveloperUnlock", "GitHub star verification still pending")
                            if (showPendingToast) {
                                showDeveloperUnlockToastIfActive(deviceCode)
                            }
                        }
                        is GitHubStarVerifier.TokenPollResult.SlowDown -> {
                            GitHubDeviceAuthorization.updatePendingPollInterval(ctx, deviceCode, poll.intervalSeconds)
                            runIfDeveloperAttemptActive(deviceCode) {
                                developerPendingDeviceCode = deviceCode.copy(intervalSeconds = poll.intervalSeconds)
                            }
                            if (showPendingToast) showDeveloperUnlockToastIfActive(deviceCode)
                        }
                        is GitHubStarVerifier.TokenPollResult.Failed -> {
                            runIfDeveloperAttemptActive(deviceCode) {
                                failDeveloperUnlockVerification(ctx, poll.message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeveloperUnlock", "GitHub star foreground verification failed", e)
                    runIfDeveloperAttemptActive(deviceCode) {
                        failDeveloperUnlockVerification(ctx, e.message ?: e.javaClass.simpleName)
                    }
                } finally {
                    activity?.runOnUiThread {
                        developerForegroundPollRunning = false
                        if (developerPendingDeviceCode?.deviceCode == deviceCode.deviceCode) {
                            developerUnlockVerificationRunning = false
                        }
                    }
                }
            }
        }

        private fun runIfDeveloperAttemptActive(
            deviceCode: GitHubStarVerifier.DeviceCode,
            action: () -> Unit
        ) {
            activity?.runOnUiThread {
                if (isAdded && developerPendingDeviceCode?.deviceCode == deviceCode.deviceCode) {
                    action()
                }
            }
        }

        private fun showDeveloperUnlockToastIfActive(deviceCode: GitHubStarVerifier.DeviceCode) {
            runIfDeveloperAttemptActive(deviceCode) {
                Toast.makeText(
                    requireContext(),
                    R.string.toast_developer_authorization_pending,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun clearDeveloperPendingDeviceCode(ctx: Context) {
            developerPendingDeviceCode = null
            GitHubDeviceAuthorization.clearPendingDeviceCode(ctx)
        }

        private fun showDeveloperDeviceCodeDialog(deviceCode: GitHubStarVerifier.DeviceCode) {
            developerDeviceCodeDialog?.dismiss()
            GitHubDeviceAuthorization.copyDeviceCodeToClipboard(requireContext(), deviceCode)
            val dialog = appDialogBuilder()
                .setTitle(
                    if (deviceCode.scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                        R.string.title_crown_store_github_authorization
                    } else {
                        R.string.title_developer_unlock
                    }
                )
                .setMessage(
                    getString(
                        if (deviceCode.scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                            R.string.message_crown_store_device_code
                        } else {
                            R.string.message_developer_device_code
                        },
                        deviceCode.userCode,
                        deviceCode.verificationUri
                    )
                )
                .setPositiveButton(R.string.action_developer_open_authorization, null)
                .setNeutralButton(R.string.action_developer_check_authorization, null)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    developerUnlockVerificationRunning = false
                    clearDeveloperPendingDeviceCode(requireContext().applicationContext)
                }
                .setOnCancelListener {
                    developerUnlockVerificationRunning = false
                    clearDeveloperPendingDeviceCode(requireContext().applicationContext)
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    GitHubDeviceAuthorization.copyDeviceCodeToClipboard(
                        requireContext(),
                        deviceCode,
                        showToast = false
                    )
                    openDeveloperUrl(GitHubDeviceAuthorization.authorizationUrl(deviceCode))
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    pollDeveloperPendingDeviceCode(showPendingToast = true)
                }
            }
            dialog.setOnDismissListener {
                if (developerDeviceCodeDialog === dialog) {
                    developerDeviceCodeDialog = null
                }
            }
            developerDeviceCodeDialog = dialog
            showStyledDialog(dialog)
        }

        private fun completeDeveloperUnlockVerification(
            ctx: Context,
            accessToken: String,
            starCheck: GitHubStarVerifier.StarCheck,
            scope: GitHubStarVerifier.OAuthScope
        ) {
            val dialogToDismiss = developerDeviceCodeDialog
            developerUnlockVerificationRunning = false
            clearDeveloperPendingDeviceCode(ctx)
            GitHubDeviceAuthorization.saveAuthorizedAccount(ctx, accessToken, starCheck, scope)
            Log.i(
                "DeveloperUnlock",
                "GitHub star verification completed: starred=${starCheck.starred}, login=${starCheck.login ?: "unknown"}"
            )
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }
                if (developerDeviceCodeDialog === dialogToDismiss) {
                    dialogToDismiss?.dismiss()
                }
                refreshDeveloperFeatureGateState()

                if (scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                    Toast.makeText(requireContext(), R.string.toast_crown_store_github_connected, Toast.LENGTH_LONG).show()
                } else if (starCheck.starred) {
                    Toast.makeText(requireContext(), R.string.toast_developer_unlocked, Toast.LENGTH_LONG).show()
                } else {
                    appDialogBuilder()
                        .setTitle(R.string.title_developer_unlock)
                        .setMessage(R.string.message_developer_star_not_found)
                        .setPositiveButton(R.string.action_developer_open_project) { _, _ ->
                            openDeveloperProjectPage()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showStyled()
                }
            }
        }

        private fun failDeveloperUnlockVerification(ctx: Context, message: String) {
            developerUnlockVerificationRunning = false
            clearDeveloperPendingDeviceCode(ctx)
            Log.w("DeveloperUnlock", "GitHub star verification failed: $message")
            activity?.runOnUiThread {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.toast_developer_verification_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        private fun openDeveloperProjectPage() {
            openDeveloperUrl(DeveloperUnlockSettings.GITHUB_REPO_URL)
        }

        private fun openDeveloperUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.toast_developer_open_project_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun updateFramegenDllPreferenceSummary() {
            val pref = findPreference<Preference>("pref_framegen_pick_lossless_dll") ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val stagedPath = prefs.getString(FramegenSettings.PREF_LOSSLESS_DLL_STAGED_PATH, null)
            pref.summary = if (!stagedPath.isNullOrBlank() && File(stagedPath).exists()) {
                getString(R.string.summary_framegen_pick_lossless_dll_selected, stagedPath)
            } else {
                getString(R.string.summary_framegen_pick_lossless_dll)
            }
        }

        @Throws(IOException::class)
        private fun stageFramegenLosslessDll(sourceUri: android.net.Uri): File {
            val ctx = requireContext()
            val targetDir = File(ctx.filesDir, "framegen").apply { mkdirs() }
            if (!targetDir.exists()) {
                throw IOException(ctx.getString(R.string.error_framegen_directory_create_failed))
            }

            val targetFile = File(targetDir, "Lossless.dll")
            var copied = false
            var lastError: Exception? = null
            repeat(5) { attempt ->
                try {
                    ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException(ctx.getString(R.string.error_framegen_dll_open_failed))

                    if (targetFile.exists() && targetFile.length() > 0L) {
                        copied = true
                        return@repeat
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w("Framegen", "stage Lossless.dll attempt ${attempt + 1}/5 failed", e)
                }

                if (!copied && attempt < 4) {
                    Thread.sleep(120)
                }
            }

            if (!copied) {
                throw IOException(lastError?.message ?: ctx.getString(R.string.error_framegen_dll_open_failed))
            }

            if (!targetFile.exists() || targetFile.length() <= 0L) {
                throw IOException(ctx.getString(R.string.error_framegen_dll_empty))
            }

            return targetFile
        }
    }

    /**
     * 计算设置页背景图允许的解码尺寸（RGB_565，每像素 2 字节）：
     * - 预算取 min(maxMemory/12, freeMemory*0.4)，更贴近运行期实际可用堆
     * - 仍超出预算则按平方根缩小到目标像素数
     * - 返回 null 表示当前堆压力过大、放弃加载背景图（避免 OOM）
     */
    private fun computeBackgroundDecodeSize(): Pair<Int, Int>? {
        val rt = Runtime.getRuntime()
        val freeBytes = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        // 极端低内存：直接放弃，避免 Glide native decode 第一步就 OOM
        if (freeBytes < 8L * 1024 * 1024) return null
        val dispW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val dispH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val needPixels = dispW.toLong() * dispH
        val budgetBytes = minOf(rt.maxMemory() / 12L, (freeBytes * 4L) / 10L)
        val maxPixels = budgetBytes / 2L  // RGB_565 每像素 2 字节
        if (needPixels <= maxPixels) {
            return dispW to dispH
        }
        val scale = sqrt(maxPixels.toDouble() / needPixels.toDouble())
        val w = (dispW * scale).toInt().coerceAtLeast(1)
        val h = (dispH * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    private fun cancelBackgroundLoad(): Int {
        val previous = synchronized(backgroundFutureLock) {
            backgroundGeneration++
            backgroundFuture.also { backgroundFuture = null }
        }
        backgroundJob?.cancel()
        // Cache hits use a CustomTarget, so clearing the ImageView's Glide request alone does not detach them.
        findViewById<ImageView>(R.id.settingsBackgroundImage)?.setImageDrawable(null)
        backgroundTarget?.let { Glide.with(applicationContext).clear(it) }
        backgroundTarget = null
        previous?.let {
            it.cancel(true)
            Glide.with(applicationContext).clear(it)
        }
        findViewById<ImageView>(R.id.settingsBackgroundImage)?.let { Glide.with(applicationContext).clear(it) }
        return backgroundGeneration
    }

    private fun backgroundIsCurrent(generation: Int): Boolean =
        generation == backgroundGeneration && !isDestroyed && !isFinishing

    private fun loadBackgroundImage() {
        val generation = cancelBackgroundLoad()
        val imageView = findViewById<ImageView>(R.id.settingsBackgroundImage)
        val orientation = resources.configuration.orientation
        val resolved = BackgroundSource.resolveCurrentTarget(
                this,
                orientation
        )
        if (!backgroundIsCurrent(generation)) return
        val target = resolved.target
        val guarded = PipwImages.handles(resolved)
        if (!guarded || pipwBackgroundLease?.key != resolved.cacheKey) {
            pipwBackgroundLease?.close()
            pipwBackgroundLease = null
        }
        if (guarded) imageView.setImageDrawable(null)

        if (target == null) {
            Glide.with(this).clear(imageView)
            imageView.setImageDrawable(null)
            return
        }

        // 解码尺寸根据当前可用堆按比例约束（详见 computeBackgroundDecodeSize）：
        // - 4K 电视 + 大堆设备保持原分辨率
        // - 低堆设备（典型场景：xiaomi MiTV4 SDK 23 maxMemory ≈ 100 MB）
        //   自动缩到安全尺寸
        // - 极端低剩余堆直接放弃背景图（保守不渲染，避免 OOM）
        val size = computeBackgroundDecodeSize() ?: run {
            imageView.setImageDrawable(null)
            pipwBackgroundLease?.close()
            pipwBackgroundLease = null
            return
        }
        val (width, height) = size

        // 模糊 + theme-aware 蒙版，单次解码完成（合并到一个 Glide pipeline）
        // 强制 RGB_565：每像素 2 字节，整张图内存减半，模糊背景视觉无损
        val filterColor = if (isNightMode()) {
            Color.argb(120, 0, 0, 0)
        } else {
            Color.argb(96, 255, 255, 255)
        }
        val transformations = MultiTransformation<Bitmap>(
                BlurTransformation(2, 3),
                ColorFilterTransformation(filterColor)
        )
        val options = RequestOptions()
                .override(width, height)
                .format(DecodeFormat.PREFER_RGB_565)
                .transform(transformations)
                .signature(ObjectKey(resolved.cacheKey))
                .diskCacheStrategy(DiskCacheStrategy.ALL)

        if (guarded) {
            backgroundJob = lifecycleScope.launch {
                try {
                    val lease = pipwBackgroundLease ?: PipwImages.get(this@StreamSettings).acquire(resolved, orientation)
                        .also { pipwBackgroundLease = it }
                    val file = lease.await()
                    if (!backgroundIsCurrent(generation)) return@launch
                    Glide.with(this@StreamSettings).load(file)
                        .apply(options.clone().diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true))
                        .transition(DrawableTransitionOptions.withCrossFade(400))
                        .into(imageView)
                } catch (_: CancellationException) {
                    // A new source or Activity now owns the background.
                } catch (_: Exception) {
                    if (backgroundIsCurrent(generation)) imageView.setImageDrawable(null)
                    LimeLog.warning("Pipw settings background unavailable")
                }
            }
            return
        }

        // 候选 URL（含原始与所有代理变体）。Glide 缓存键以 URL 为基础，
        // 因此可能上次走代理 A 命中、原始 URL 在缓存中并不存在；这里逐个尝试，
        // 任一变体在缓存中就立即贴图，体验等同本地资源。
        if (!target.startsWith("http")) {
            val localFile = File(target)
            if (!localFile.exists()) {
                imageView.setImageDrawable(null)
                return
            }
            Glide.with(this)
                    .load(localFile)
                    .apply(options)
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .into(imageView)
            return
        }

        // Reuse the active home-screen source. Proxy variants remain fallbacks
        // for remote sources so this page keeps its existing network resilience.
        val candidates = mutableListOf<String>().apply {
            add(target)
            try { addAll(UpdateManager.buildProxiedUrls(target)) } catch (_: Exception) {}
        }.distinct()

        tryCachedThenNetwork(imageView, options, candidates, 0, generation)
    }

    /**
     * 依次对候选 URL 做 onlyRetrieveFromCache 同步缓存查询：
     *   - 命中：直接贴图，无线程切换、无渐入，体验秒开
     *   - 全部未命中：转入网络下载路径，下载完成后带 crossFade 渐入
     */
    private fun tryCachedThenNetwork(
            imageView: ImageView,
            options: RequestOptions,
            candidates: List<String>,
            index: Int,
            generation: Int
    ) {
        if (!backgroundIsCurrent(generation)) return
        if (index >= candidates.size) {
            loadBackgroundImageFromNetwork(imageView, options, candidates, generation)
            return
        }
        val requestTarget = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (backgroundIsCurrent(generation)) imageView.setImageDrawable(resource)
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
            override fun onLoadFailed(errorDrawable: Drawable?) {
                tryCachedThenNetwork(imageView, options, candidates, index + 1, generation)
            }
        }
        backgroundTarget = requestTarget
        Glide.with(this).load(candidates[index])
            .apply(options.clone().onlyRetrieveFromCache(true)).into(requestTarget)
    }

    private fun loadBackgroundImageFromNetwork(
            imageView: ImageView,
            options: RequestOptions,
            preBuiltCandidates: List<String>?,
            generation: Int
    ) {
        Thread {
            if (!backgroundIsCurrent(generation)) return@Thread
            // 代理列表可能在调用前还未就绪，需要刷新一次
            UpdateManager.ensureProxyListUpdated(this)
            val candidates = preBuiltCandidates.orEmpty()
            for (url in candidates) {
                try {
                    if (!backgroundIsCurrent(generation)) return@Thread
                    // 后台同步预热：把图解码到 Glide 缓存中（包含 blur+mask 变换）；
                    // 失败则尝试下一条代理，不会污染 UI
                    val future = Glide.with(applicationContext)
                            .asDrawable()
                            .load(url)
                            .apply(options)
                            .submit()
                    val retained = synchronized(backgroundFutureLock) {
                        if (generation == backgroundGeneration) {
                            backgroundFuture = future
                            true
                        } else false
                    }
                    if (!retained) {
                        future.cancel(true)
                        Glide.with(applicationContext).clear(future)
                        return@Thread
                    }
                    try {
                        future.get()
                        runOnUiThread {
                            if (!backgroundIsCurrent(generation)) return@runOnUiThread
                            // 用同一份缓存渲染，加 400ms 渐入避免突兀 pop-in
                            Glide.with(this@StreamSettings)
                                    .load(url)
                                    .apply(options)
                                    .transition(DrawableTransitionOptions.withCrossFade(400))
                                    .into(imageView)
                        }
                        return@Thread
                    } finally {
                        synchronized(backgroundFutureLock) {
                            if (backgroundFuture === future) backgroundFuture = null
                        }
                        Glide.with(applicationContext).clear(future)
                    }
                } catch (e: Exception) {
                    // Try next proxy
                }
            }
        }.start()
    }
}
