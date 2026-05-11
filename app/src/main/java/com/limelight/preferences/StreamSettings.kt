@file:Suppress("DEPRECATION")
package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayCutout
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.limelight.LimeLog
import com.limelight.PcView
import com.limelight.R
import com.limelight.ExternalDisplayManager
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.utils.AspectRatioConverter
import com.limelight.utils.Dialog
import com.limelight.utils.UiHelper
import com.limelight.utils.UpdateManager

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import jp.wasabeef.glide.transformations.BlurTransformation
import jp.wasabeef.glide.transformations.ColorFilterTransformation
import androidx.core.graphics.toColorInt
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

class StreamSettings : AppCompatActivity() {

    private lateinit var previousPrefs: PreferenceConfiguration
    private var previousDisplayPixelCount = 0
    private var externalDisplayManager: ExternalDisplayManager? = null

    // 抽屉菜单相关
    private var categoryList: RecyclerView? = null
    private var categoryAdapter: CategoryAdapter? = null
    private var homeContainer: View? = null
    private var navigationButton: ImageView? = null
    private var headerTitle: TextView? = null
    private val categories: MutableList<CategoryItem> = ArrayList()
    private var selectedCategoryIndex = 0
    private var selectedCategoryKey: String? = null
    private var lastKnownOrientation = Configuration.ORIENTATION_UNDEFINED
    private var systemBackCallback: OnBackPressedCallback? = null

    // 状态保存键
    companion object {
        private const val KEY_SELECTED_CATEGORY = "selected_category_index"
        private const val KEY_SELECTED_CATEGORY_KEY = "selected_category_key"

        // HACK for Android 9
        var displayCutoutP: DisplayCutout? = null

        private const val SETTINGS_BG_URL = "https://raw.githubusercontent.com/qiin2333/qiin.github.io/assets/img/moonlight-bg2.webp"

        /**
         * 获取分类对应的 emoji（每个分类唯一）
         */
        private fun getEmojiForCategory(key: String): String {
            return when (key) {
                "category_basic_settings" -> "⚙️"      // 基本设置
                "category_screen_position" -> "📐"     // 屏幕位置
                "category_audio_settings" -> "🔊"      // 音频
                "category_mic_settings" -> "🎤"        // 麦克风
                "category_audio_vibration" -> "📳"     // 音频振动
                "category_gamepad_settings" -> "🎮"    // 手柄
                "category_input_settings" -> "⌨️"      // 输入
                "category_enhanced_touch" -> "👆"      // 触摸增强
                "category_onscreen_controls" -> "🎛️"   // 屏幕控制
                "category_float_ball" -> "⚽"           // 悬浮球
                "category_crown_features" -> "👑"      // 皇冠功能
                "category_host_settings" -> "🖥️"       // 主机
                "category_connection_settings" -> "🔗"  // 连接
                "category_ui_settings" -> "🎨"         // 界面
                "category_advanced_settings" -> "🔧"   // 高级(legacy)
                "category_help" -> "❓"                // 帮助
                else -> "📋"
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    fun reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
        supportFragmentManager.beginTransaction().replace(
                R.id.preference_container, SettingsFragment.newInstance(selectedCategoryKey)
        ).commitAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用带阴影的主题
        theme.applyStyle(R.style.PreferenceThemeWithShadow, true)

        super.onCreate(savedInstanceState)

        previousPrefs = PreferenceConfiguration.readPreferences(this)

        // 初始化外接显示器管理器
        if (previousPrefs.useExternalDisplay) {
            externalDisplayManager = ExternalDisplayManager(this, previousPrefs, null, null, null, null)
            externalDisplayManager?.initialize()
        }

        UiHelper.setLocale(this)

        // 设置自定义布局
        setContentView(R.layout.activity_stream_settings)

        // 确保状态栏透明
        window.statusBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

        UiHelper.notifyNewRootView(this)
        lastKnownOrientation = resources.configuration.orientation

        // 恢复保存的状态（屏幕旋转时）
        if (savedInstanceState != null) {
            selectedCategoryIndex = savedInstanceState.getInt(KEY_SELECTED_CATEGORY, 0)
            selectedCategoryKey = savedInstanceState.getString(KEY_SELECTED_CATEGORY_KEY)
        }

        // 初始化抽屉菜单
        initDrawerMenu()

        applyOrientationSystemBars()

        registerSystemBackHandler()

        // 加载背景图片
        loadBackgroundImage()

        // 设置版本号
        setupVersionInfo()
    }

    /**
     * 设置版本号显示
     */
    @SuppressLint("SetTextI18n")
    private fun setupVersionInfo() {
        val versionText = findViewById<TextView>(R.id.drawer_version)
        if (versionText != null) {
            try {
                val versionName = packageManager
                        .getPackageInfo(packageName, 0).versionName
                versionText.text = "v$versionName"
            } catch (e: PackageManager.NameNotFoundException) {
                versionText.visibility = View.GONE
            }
        }
    }

    /**
     * 初始化首页列表和详情页容器
     */
    private fun initDrawerMenu() {
        categoryList = findViewById(R.id.category_list)
        homeContainer = findViewById(R.id.settings_home_container)
        navigationButton = findViewById(R.id.settings_menu_toggle)
        headerTitle = findViewById(R.id.settings_header_title)

        setupMenuToggle()
        setupCategoryList()
        showHomePage()
    }

    /**
     * 设置返回按钮
     */
    private fun setupMenuToggle() {
        val menuToggle = navigationButton
        if (menuToggle != null) {
            menuToggle.setOnClickListener { handleNavigationButtonClick() }
            menuToggle.isFocusable = true
            menuToggle.isFocusableInTouchMode = false
        }
    }

    /**
     * 设置分类列表
     */
    private fun setupCategoryList() {
        if (categoryList != null) {
            categoryList?.layoutManager = LinearLayoutManager(this)
            categoryAdapter = CategoryAdapter()
            categoryList?.adapter = categoryAdapter
            categoryList?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            categoryList?.isFocusable = true
        }
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

    private fun usesPersistentSidebarLayout(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun showHomePage() {
        if (usesPersistentSidebarLayout()) {
            homeContainer?.visibility = View.VISIBLE
            findViewById<View>(R.id.preference_container)?.visibility = View.VISIBLE
            headerTitle?.visibility = View.INVISIBLE
            updateNavigationButtonForCurrentPage(true)
            updateSystemBackHandlerState()
            focusSelectedCategory()
            return
        }

        homeContainer?.visibility = View.VISIBLE
        findViewById<View>(R.id.preference_container)?.visibility = View.GONE
        headerTitle?.text = getString(R.string.title_settings)
        updateNavigationButtonForCurrentPage(true)
        updateSystemBackHandlerState()
        focusSelectedCategory()
    }

    private fun showCategoryPage(title: String) {
        if (usesPersistentSidebarLayout()) {
            homeContainer?.visibility = View.VISIBLE
            findViewById<View>(R.id.preference_container)?.visibility = View.VISIBLE
            headerTitle?.text = title
            headerTitle?.visibility = View.VISIBLE
            updateNavigationButtonForCurrentPage(false)
            updateSystemBackHandlerState()
            focusPreferenceList()
            return
        }

        homeContainer?.visibility = View.GONE
        findViewById<View>(R.id.preference_container)?.visibility = View.VISIBLE
        headerTitle?.text = title
        updateNavigationButtonForCurrentPage(false)
        updateSystemBackHandlerState()
        focusPreferenceList()
    }

    private fun updateNavigationButtonForCurrentPage(isHomePage: Boolean) {
        val menuToggle = navigationButton ?: return

        if (usesPersistentSidebarLayout()) {
            menuToggle.visibility = View.GONE
            return
        }

        menuToggle.visibility = View.VISIBLE

        if (isHomePage) {
            menuToggle.setImageResource(R.drawable.ic_close)
            menuToggle.contentDescription = getString(R.string.about_dialog_close)
        } else {
            menuToggle.setImageResource(R.drawable.ic_chevron_left)
            menuToggle.contentDescription = getString(R.string.settings_nav_back)
        }
    }

    private fun handleNavigationButtonClick() {
        if (navigateToHomePage()) {
            return
        }

        handleActivityBackPress()
    }

    private fun navigateToHomePage(): Boolean {
        if (usesPersistentSidebarLayout()) {
            return false
        }

        if (selectedCategoryKey == null) {
            return false
        }

        selectedCategoryKey = null
        showHomePage()
        return true
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

    @Suppress("DEPRECATION")
    private fun applyOrientationSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (usesPersistentSidebarLayout()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            window.decorView.post { applyLandscapeTopInsetPadding() }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        controller.show(WindowInsetsCompat.Type.statusBars())
    }

    private fun applyLandscapeTopInsetPadding() {
        if (!usesPersistentSidebarLayout()) {
            return
        }

        val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
        } else {
            0
        }

        homeContainer?.setPadding(
                homeContainer?.paddingLeft ?: 0,
                topInset,
                homeContainer?.paddingRight ?: 0,
                homeContainer?.paddingBottom ?: 0
        )

        val detailContainer = findViewById<View>(R.id.settings_detail_container)
        detailContainer?.setPadding(
            detailContainer.paddingLeft,
                topInset,
            detailContainer.paddingRight,
            detailContainer.paddingBottom
        )
    }

    private fun registerSystemBackHandler() {
        systemBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                handleActivityBackPress()
            }
        }
        onBackPressedDispatcher.addCallback(this, systemBackCallback!!)
        updateSystemBackHandlerState()
    }

    private fun shouldInterceptSystemBack(): Boolean {
        return !usesPersistentSidebarLayout() && homeContainer?.visibility == View.GONE
    }

    private fun updateSystemBackHandlerState() {
        systemBackCallback?.isEnabled = shouldInterceptSystemBack()
    }

    private fun handleActivityBackPress() {
        if (navigateToHomePage()) {
            return
        }

        finish()
        handleLanguageChange()
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
    class CategoryItem(var key: String, var title: String, var emoji: String)

    /**
     * 分类菜单适配器
     */
    inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.category_title)
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
            // 抽屉菜单显示 emoji + 标题
            holder.title.text = "${item.emoji} ${item.title}"

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
            val pinkPrimary = androidx.core.content.ContextCompat.getColor(this@StreamSettings, R.color.theme_pink_primary)    // #FF6B9D
            val white = Color.WHITE
            val lightGray = "#BBBBBB".toColorInt()
            val dimGray = "#888888".toColorInt()
            val usePersistentSidebarLayout = usesPersistentSidebarLayout()

            if (usePersistentSidebarLayout) {
                holder.indicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

                when {
                    isSelected -> {
                        holder.title.setTextColor(white)
                        holder.title.alpha = 1.0f
                    }
                    hasFocus -> {
                        holder.title.setTextColor(pinkPrimary)
                        holder.title.alpha = 1.0f
                    }
                    else -> {
                        holder.title.setTextColor(lightGray)
                        holder.title.alpha = 0.9f
                    }
                }

                val arrow = holder.root.findViewById<ImageView>(R.id.category_arrow)
                if (arrow != null) {
                    when {
                        isSelected -> {
                            arrow.alpha = 1.0f
                            arrow.setColorFilter(pinkPrimary)
                        }
                        hasFocus -> {
                            arrow.alpha = 0.9f
                            arrow.setColorFilter(pinkPrimary)
                        }
                        else -> {
                            arrow.alpha = 0.4f
                            arrow.setColorFilter(dimGray)
                        }
                    }
                }

                return
            }

            holder.indicator.visibility = View.GONE

            if (hasFocus) {
                holder.title.setTextColor(pinkPrimary)
                holder.title.alpha = 1.0f
            } else {
                holder.title.setTextColor(white)
                holder.title.alpha = 0.95f
            }

            val arrow = holder.root.findViewById<ImageView>(R.id.category_arrow)
            if (arrow != null) {
                if (hasFocus) {
                    arrow.alpha = 0.9f
                    arrow.setColorFilter(pinkPrimary)
                } else {
                    arrow.alpha = 0.65f
                    arrow.setColorFilter(dimGray)
                }
            }
        }

        /**
         * 选择分类
         */
        private fun selectCategory(position: Int, item: CategoryItem) {
            if (position < 0 || position >= categories.size) return

            if (usesPersistentSidebarLayout()) {
                val oldIndex = selectedCategoryIndex
                if (position != oldIndex) {
                    selectedCategoryIndex = position
                    notifyItemChanged(oldIndex)
                    notifyItemChanged(position)
                }
            }

            showCategory(item.key, position)
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

    private fun showCategory(categoryKey: String, position: Int) {
        if (position in categories.indices) {
            selectedCategoryIndex = position
        }

        if (selectedCategoryKey != categoryKey) {
            selectedCategoryKey = categoryKey
            reloadSettings()
        } else {
            showCategoryPage(categories[selectedCategoryIndex].title)
        }
    }

    /**
     * 通知 Activity 分类已加载
     */
    fun onCategoriesLoaded(loadedCategories: List<CategoryItem>, currentCategoryKey: String?) {
        categories.clear()
        categories.addAll(loadedCategories)

        if (categories.isEmpty()) {
            selectedCategoryIndex = 0
            selectedCategoryKey = null
            categoryAdapter?.notifyDataSetChanged()
            return
        }

        val resolvedIndex = categories.indexOfFirst { it.key == currentCategoryKey }
                .takeIf { it >= 0 }
                ?: categories.indexOfFirst { it.key == selectedCategoryKey }.takeIf { it >= 0 }
                ?: selectedCategoryIndex.coerceIn(0, categories.lastIndex)

        if (usesPersistentSidebarLayout()) {
            selectedCategoryIndex = resolvedIndex
            categoryAdapter?.notifyDataSetChanged()

            val resolvedKey = currentCategoryKey ?: categories[resolvedIndex].key
            selectedCategoryKey = resolvedKey

            if (currentCategoryKey == null) {
                reloadSettings()
                return
            }

            showCategoryPage(categories[resolvedIndex].title)
            return
        }

        if (currentCategoryKey == null) {
            selectedCategoryIndex = resolvedIndex
            selectedCategoryKey = null
            categoryAdapter?.notifyDataSetChanged()
            showHomePage()
        } else {
            selectedCategoryIndex = resolvedIndex
            selectedCategoryKey = categories[resolvedIndex].key
            categoryAdapter?.notifyDataSetChanged()
            showCategoryPage(categories[resolvedIndex].title)
        }
    }

    /**
     * 更新选中的分类
     */
    fun updateSelectedCategory(index: Int) {
        if (index != selectedCategoryIndex && index >= 0 && index < categories.size) {
            selectedCategoryIndex = index
            selectedCategoryKey = categories[selectedCategoryIndex].key
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            val insets = window.decorView.rootWindowInsets
            if (insets != null) {
                displayCutoutP = insets.displayCutout
            }
        }

        applyOrientationSystemBars()
        reloadSettings()
    }

    override fun onResume() {
        super.onResume()
        applyOrientationSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val previousOrientation = lastKnownOrientation
        lastKnownOrientation = newConfig.orientation

        if (previousOrientation != Configuration.ORIENTATION_UNDEFINED &&
                newConfig.orientation != Configuration.ORIENTATION_UNDEFINED &&
                newConfig.orientation != previousOrientation) {
            recreate()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.physicalWidth * mode.physicalHeight != previousDisplayPixelCount) {
                reloadSettings()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handlePageKeyEvent(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 处理页面返回相关的控制器按键事件
     */
    private fun handlePageKeyEvent(keyCode: Int): Boolean {
        if (selectedCategoryKey != null &&
                (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            return navigateToHomePage()
        }

        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存选中的分类索引，用于屏幕旋转后恢复
        outState.putInt(KEY_SELECTED_CATEGORY, selectedCategoryIndex)
        outState.putString(KEY_SELECTED_CATEGORY_KEY, selectedCategoryKey)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (externalDisplayManager != null) {
            externalDisplayManager?.cleanup()
            externalDisplayManager = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleActivityBackPress()
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

        companion object {
            private const val ARG_CATEGORY_KEY = "selected_category_key"

            fun newInstance(categoryKey: String?): SettingsFragment {
                return SettingsFragment().apply {
                    arguments = Bundle().apply {
                        if (categoryKey != null) {
                            putString(ARG_CATEGORY_KEY, categoryKey)
                        }
                    }
                }
            }
        }

        private var nativeResolutionStartIndex = Int.MAX_VALUE
        private var nativeFramerateShown = false

        private lateinit var exportConfigString: String
        private val availableCategories: MutableList<CategoryItem> = ArrayList()
        private var currentCategoryKey: String? = null

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

        /**
         * 获取目标显示器（优先使用外接显示器）
         */
        private fun getTargetDisplay(): Display {
            val settingsActivity = activity as? StreamSettings
            if (settingsActivity?.externalDisplayManager != null) {
                return settingsActivity.externalDisplayManager?.getTargetDisplay()!!
            }
            return requireActivity().windowManager.defaultDisplay
        }

        private fun setValue(preferenceKey: String, value: String) {
            val pref = findPreference<ListPreference>(preferenceKey)!!
            pref.value = value
        }

        private fun appendPreferenceEntry(pref: ListPreference, newEntryName: String, newEntryValue: String) {
            val newEntries = pref.entries.copyOf(pref.entries.size + 1)
            val newValues = pref.entryValues.copyOf(pref.entryValues.size + 1)

            // Add the new option
            newEntries[newEntries.size - 1] = newEntryName
            newValues[newValues.size - 1] = newEntryValue

            pref.entries = newEntries
            pref.entryValues = newValues
        }

        private fun addNativeResolutionEntry(nativeWidth: Int, nativeHeight: Int, insetsRemoved: Boolean, portrait: Boolean) {
            val pref = findPreference<ListPreference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!

            var newName: String = if (insetsRemoved) {
                resources.getString(R.string.resolution_prefix_native_fullscreen)
            } else {
                resources.getString(R.string.resolution_prefix_native)
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                newName += if (portrait) {
                    " " + resources.getString(R.string.resolution_prefix_native_portrait)
                } else {
                    " " + resources.getString(R.string.resolution_prefix_native_landscape)
                }
            }

            newName += " (${nativeWidth}x${nativeHeight})"

            val newValue = "${nativeWidth}x${nativeHeight}"

            // Check if the native resolution is already present
            for (value in pref.entryValues) {
                if (newValue == value.toString()) {
                    // It is present in the default list, so don't add it again
                    return
                }
            }

            if (pref.entryValues.size < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.entryValues.size
            }
            appendPreferenceEntry(pref, newName, newValue)
        }

        private fun addNativeResolutionEntries(nativeWidth: Int, nativeHeight: Int, insetsRemoved: Boolean) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true)
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false)
        }

        private fun addCustomResolutionsEntries() {
            val storage = requireActivity().getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE,
                MODE_PRIVATE
            )
            val stored = storage.getStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, null)
            val pref = findPreference<ListPreference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!

            val preferencesList = listOf(*pref.entryValues)

            if (stored.isNullOrEmpty()) {
                return
            }

            val lengthComparator = Comparator<String> { s1, s2 ->
                val s1Size = s1.split("x")
                val s2Size = s2.split("x")

                val w1 = s1Size[0].toInt()
                val w2 = s2Size[0].toInt()

                val h1 = s1Size[1].toInt()
                val h2 = s2Size[1].toInt()

                if (w1 == w2) {
                    h1.compareTo(h2)
                } else {
                    w1.compareTo(w2)
                }
            }

            val list = ArrayList(stored)
            Collections.sort(list, lengthComparator)

            for (storedResolution in list) {
                if (preferencesList.contains(storedResolution)) {
                    continue
                }
                val resolution = storedResolution.split("x")
                val width = resolution[0].toInt()
                val height = resolution[1].toInt()
                val aspectRatio = AspectRatioConverter.getAspectRatio(width, height)
                var displayText = "Custom "

                if (aspectRatio != null) {
                    displayText += "$aspectRatio "
                }

                displayText += "($storedResolution)"

                appendPreferenceEntry(pref, displayText, storedResolution)
            }
        }

        private fun addNativeFrameRateEntry(framerate: Float) {
            val frameRateRounded = framerate.roundToInt()
            if (frameRateRounded == 0) {
                return
            }

            val pref = findPreference<ListPreference>(PreferenceConfiguration.FPS_PREF_STRING)!!
            val fpsValue = frameRateRounded.toString()
            val fpsName = resources.getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + resources.getString(R.string.fps_suffix_fps) + ")"

            // Check if the native frame rate is already present
            for (value in pref.entryValues) {
                if (fpsValue == value.toString()) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false
                    return
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue)
            nativeFramerateShown = true
        }

        private fun removeValue(preferenceKey: String, value: String, onMatched: Runnable) {
            var matchingCount = 0

            val pref = findPreference<ListPreference>(preferenceKey)!!

            // Count the number of matching entries we'll be removing
            for (seq in pref.entryValues) {
                if (seq.toString().equals(value, ignoreCase = true)) {
                    matchingCount++
                }
            }

            // Create the new arrays
            val entries = arrayOfNulls<CharSequence>(pref.entries.size - matchingCount)
            val entryValues = arrayOfNulls<CharSequence>(pref.entryValues.size - matchingCount)
            var outIndex = 0
            for (i in pref.entryValues.indices) {
                if (pref.entryValues[i].toString().equals(value, ignoreCase = true)) {
                    // Skip matching values
                    continue
                }

                entries[outIndex] = pref.entries[i]
                entryValues[outIndex] = pref.entryValues[i]
                outIndex++
            }

            if (pref.value.equals(value, ignoreCase = true)) {
                onMatched.run()
            }

            // Update the preference with the new list
            pref.entries = entries
            pref.entryValues = entryValues
        }

        private fun resetBitrateToDefault(prefs: SharedPreferences, res: String?, fps: String?) {
            var resValue = res
            var fpsValue = fps
            if (resValue == null) {
                resValue = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION)
            }
            if (fpsValue == null) {
                fpsValue = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS)
            }

            prefs.edit {
                putInt(
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    PreferenceConfiguration.getDefaultBitrate(resValue!!, fpsValue!!)
                )
            }
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
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
            settingsActivity.onCategoriesLoaded(availableCategories, currentCategoryKey)
        }

        private fun buildAvailableCategories(screen: PreferenceScreen): List<CategoryItem> {
            val items: MutableList<CategoryItem> = ArrayList()

            for (i in 0 until screen.preferenceCount) {
                val pref = screen.getPreference(i)
                if (pref !is PreferenceCategory || pref.title == null) {
                    continue
                }

                val key = pref.key ?: "category_$i"
                items.add(CategoryItem(key, pref.title.toString(), getEmojiForCategory(key)))
            }

            return items
        }

        private fun retainSelectedCategory(screen: PreferenceScreen, selectedKey: String) {
            val toRemove: MutableList<Preference> = ArrayList()

            for (i in 0 until screen.preferenceCount) {
                val pref = screen.getPreference(i)
                if (pref is PreferenceCategory && pref.key != selectedKey) {
                    toRemove.add(pref)
                }
            }

            for (pref in toRemove) {
                screen.removePreference(pref)
            }

            categoryList.clear()
            findPreference<PreferenceCategory>(selectedKey)?.let { categoryList.add(it) }
            categoryPositionsValid = false
        }

        private fun flattenSelectedCategory(screen: PreferenceScreen, selectedKey: String) {
            val selectedCategory = findPreference<PreferenceCategory>(selectedKey) ?: return
            val preferencesToMove: MutableList<Preference> = ArrayList()

            while (selectedCategory.preferenceCount > 0) {
                val childPreference = selectedCategory.getPreference(0)
                selectedCategory.removePreference(childPreference)
                preferencesToMove.add(childPreference)
            }

            screen.removePreference(selectedCategory)

            for (preference in preferencesToMove) {
                screen.addPreference(preference)
            }

            categoryList.clear()
            categoryPositionsValid = false
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

        /**
         * 滚动到指定索引的分类
         */
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

        override fun onDestroyView() {
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
            if (recyclerView == null || categoryList.isEmpty()) return

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

        @SuppressLint("RestrictedApi")
        private fun findAdapterPositionForPreference(target: Preference?): Int {
            val recyclerView = listView ?: return -1
            if (target == null) return -1
            // 优先走缓存
            if (categoryPositionsValid && categoryPositions.size == categoryList.size) {
                val idx = categoryList.indexOfFirst { it === target }
                if (idx >= 0) return categoryPositions[idx]
            }
            val adapter = recyclerView.adapter
            if (adapter is PreferenceGroupAdapter) {
                for (i in 0 until adapter.itemCount) {
                    if (adapter.getItem(i) === target) return i
                }
            }
            return -1
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // 添加阴影主题
            requireActivity().theme.applyStyle(R.style.PreferenceThemeWithShadow, true)

            setPreferencesFromResource(R.xml.preferences, rootKey)
            val screen = preferenceScreen

            // 为 LocalImagePickerPreference 设置 Fragment 实例，确保 onActivityResult 回调正确
            val localImagePicker = findPreference<LocalImagePickerPreference>("local_image_picker")
            localImagePicker?.setFragment(this)

            // 为背景图片API URL设置监听器，保存时设置类型为"api"
            val backgroundImageUrlPref = findPreference<EditTextPreference>("background_image_url")
            backgroundImageUrlPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val url = newValue as String
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())

                if (url.trim().isNotEmpty()) {
                    // 设置为API类型，并清除本地文件配置
                    prefs.edit {
                        putString("background_image_type", "api")
                            .putString("background_image_url", url.trim())
                            .remove("background_image_local_path")
                    }

                    // 发送广播通知 PcView 更新背景图片
                    val broadcastIntent = Intent("com.limelight.REFRESH_BACKGROUND_IMAGE")
                    requireActivity().sendBroadcast(broadcastIntent)
                } else {
                    // 恢复默认
                    prefs.edit {
                        putString("background_image_type", "default")
                            .remove("background_image_url")
                    }

                    // 发送广播通知 PcView 更新背景图片
                    val broadcastIntent = Intent("com.limelight.REFRESH_BACKGROUND_IMAGE")
                    requireActivity().sendBroadcast(broadcastIntent)
                }

                true // 允许保存
            }

            // hide on-screen controls category on non touch screen devices
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                val category = findPreference<PreferenceCategory>("category_onscreen_controls")!!
                screen.removePreference(category)
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    requireActivity().packageManager.hasSystemFeature("com.nvidia.feature.shield")) {
                val category = findPreference<PreferenceCategory>("category_input_settings")!!
                category.removePreference(findPreference("checkbox_absolute_mouse_mode")!!)
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val category = findPreference<PreferenceCategory>("category_gamepad_settings")!!
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors")!!)
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                val category = findPreference<PreferenceCategory>("category_gamepad_settings")!!
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback")!!)
            }

            // Hide USB driver options on devices without USB host support
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                val category = findPreference<PreferenceCategory>("category_gamepad_settings")!!
                category.removePreference(findPreference("checkbox_usb_bind_all")!!)
                category.removePreference(findPreference("checkbox_usb_driver")!!)
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !requireActivity().packageManager.hasSystemFeature("android.software.picture_in_picture") ||
                    requireActivity().packageManager.hasSystemFeature("com.amazon.software.fireos")) {
                val category = findPreference<PreferenceCategory>("category_screen_position")!!
                category.removePreference(findPreference("checkbox_enable_pip")!!)
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (requireActivity().packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
                val category = findPreference<PreferenceCategory>("category_help")!!
                screen.removePreference(category)
            }*/
            val categoryGamepadSettings = findPreference<PreferenceCategory>("category_gamepad_settings")!!
            // Remove the vibration options if the device can't vibrate
            if (!(requireActivity().getSystemService(VIBRATOR_SERVICE) as Vibrator).hasVibrator()) {
                categoryGamepadSettings.removePreference(findPreference("checkbox_vibrate_fallback")!!)
                categoryGamepadSettings.removePreference(findPreference("seekbar_vibrate_fallback_strength")!!)
                // The entire OSC category may have already been removed by the touchscreen check above
                val category = findPreference<PreferenceCategory>("category_onscreen_controls")
                category?.removePreference(findPreference("checkbox_vibrate_osc")!!)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !(requireActivity().getSystemService(VIBRATOR_SERVICE) as Vibrator).hasAmplitudeControl()) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                categoryGamepadSettings.removePreference(findPreference("seekbar_vibrate_fallback_strength")!!)
            }

            // 获取目标显示器（优先使用外接显示器）
            val display = getTargetDisplay()
            var maxSupportedFps = display.refreshRate

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var maxSupportedResW = 0

                // Add a native resolution with any insets included for users that don't want content
                // behind the notch of their display
                var hasInsets = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutout: DisplayCutout? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use the much nicer Display.getCutout() API on Android 10+
                        display.cutout
                    } else {
                        // Android 9 only
                        displayCutoutP
                    }

                    if (cutout != null) {
                        val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                        val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop

                        if (widthInsets != 0 || heightInsets != 0) {
                            val metrics = DisplayMetrics()
                            display.getRealMetrics(metrics)

                            val width =
                                (metrics.widthPixels - widthInsets).coerceAtLeast(metrics.heightPixels - heightInsets)
                            val height =
                                (metrics.widthPixels - widthInsets).coerceAtMost(metrics.heightPixels - heightInsets)

                            addNativeResolutionEntries(width, height, false)
                            hasInsets = true
                        }
                    }
                }

                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                for (candidate in display.supportedModes) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    val width = candidate.physicalWidth.coerceAtLeast(candidate.physicalHeight)
                    val height = candidate.physicalWidth.coerceAtMost(candidate.physicalHeight)

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                            (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets)
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840
                    } else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560
                    } else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920
                    }

                    if (candidate.refreshRate > maxSupportedFps) {
                        maxSupportedFps = candidate.refreshRate
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(requireContext(), GlPreferences.readPreferences(requireContext()).glRenderer)

                val avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1)
                val hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)

                @Suppress("UNUSED_VARIABLE")

                if (avcDecoder != null) {
                    val avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").videoCapabilities?.supportedWidths

                    if (avcWidthRange != null) {
                        LimeLog.info("AVC supported width range: ${avcWidthRange.lower} - ${avcWidthRange.upper}")

                        // If 720p is not reported as supported, ignore all results from this API
                        if (avcWidthRange.contains(1280)) {
                            if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                                maxSupportedResW = 3840
                            } else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                                maxSupportedResW = 1920
                            } else if (maxSupportedResW < 1280) {
                                maxSupportedResW = 1280
                            }
                        }
                    }
                }

                if (hevcDecoder != null) {
                    val hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").videoCapabilities?.supportedWidths

                    if (hevcWidthRange != null) {
                        LimeLog.info("HEVC supported width range: ${hevcWidthRange.lower} - ${hevcWidthRange.upper}")

                        // If 720p is not reported as supported, ignore all results from this API
                        if (hevcWidthRange.contains(1280)) {
                            if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                                maxSupportedResW = 3840
                            } else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                                maxSupportedResW = 1920
                            } else if (maxSupportedResW < 1280) {
                                maxSupportedResW = 1280
                            }
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: $maxSupportedResW")

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P) {
                            val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_720P)
                            resetBitrateToDefault(prefs, null, null)
                        }
                    }
                    // Never remove 720p
                }
            } else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                val width = metrics.widthPixels.coerceAtLeast(metrics.heightPixels)
                val height = metrics.widthPixels.coerceAtMost(metrics.heightPixels)
                addNativeResolutionEntries(width, height, false)
            }

            if (!PreferenceConfiguration.readPreferences(requireActivity()).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 162) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "165") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "144")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
                if (maxSupportedFps < 141) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "144") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "120")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
                if (maxSupportedFps < 118) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "90")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90") {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "60")
                        resetBitrateToDefault(prefs, null, null)
                    }
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps)

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference<Preference>(PreferenceConfiguration.UNLOCK_FPS_STRING)!!.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        // HACK: We need to let the preference change succeed before reinitializing to ensure
                        // it's reflected in the new layout.
                        val h = Handler(Looper.getMainLooper())
                        h.postDelayed({
                            // Ensure the activity is still open when this timeout expires
                            val settingsActivity = this@SettingsFragment.activity as? StreamSettings
                            settingsActivity?.reloadSettings()
                        }, 500)

                        // Allow the original preference change to take place
                        true
                    }

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS")
                val category = findPreference<PreferenceCategory>("category_screen_position")!!
                // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                val hdrHighBrightnessPref = findPreference<Preference>("checkbox_enable_hdr_high_brightness")
                if (hdrHighBrightnessPref != null) {
                    category.removePreference(hdrHighBrightnessPref)
                }
                val hdrPref = findPreference<Preference>("checkbox_enable_hdr")
                if (hdrPref != null) {
                    category.removePreference(hdrPref)
                }
            } else {
                // 获取目标显示器的 HDR 能力（优先使用外接显示器）
                val targetDisplay = getTargetDisplay()
                val hdrCaps = targetDisplay.hdrCapabilities

                // We must now ensure our display is compatible with HDR10
                var foundHdr10 = false
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true
                            break
                        }
                    }
                }

                // Check for HLG support as well
                var foundHlg = false
                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HLG) {
                            foundHlg = true
                            break
                        }
                    }
                }

                val category = findPreference<PreferenceCategory>("category_screen_position")!!
                val hdrPref = findPreference<CheckBoxPreference>("checkbox_enable_hdr")
                val hdrHighBrightnessPref = findPreference<CheckBoxPreference>("checkbox_enable_hdr_high_brightness")
                val hdrModePref = findPreference<ListPreference>("list_hdr_mode")

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities")
                    // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                    if (hdrModePref != null) {
                        category.removePreference(hdrModePref)
                    }
                    if (hdrHighBrightnessPref != null) {
                        category.removePreference(hdrHighBrightnessPref)
                    }
                    if (hdrPref != null) {
                        category.removePreference(hdrPref)
                    }
                } else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                    if (hdrPref != null) {
                        hdrPref.isEnabled = false
                        hdrPref.isChecked = false
                        hdrPref.summary = "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR"
                    }
                    // 同时禁用 HDR 高亮度选项
                    if (hdrHighBrightnessPref != null) {
                        hdrHighBrightnessPref.isEnabled = false
                        hdrHighBrightnessPref.isChecked = false
                    }
                    // 同时禁用 HDR 模式选项
                    if (hdrModePref != null) {
                        hdrModePref.isEnabled = false
                    }
                } else {
                    // HDR is supported, configure the HDR mode preference
                    if (hdrModePref != null) {
                        // If HLG is not supported, remove it from the options
                        if (!foundHlg) {
                            LimeLog.info("Display does not support HLG, limiting to HDR10 only")
                            // Keep only HDR10 option
                            hdrModePref.entries = arrayOf<CharSequence>(getString(R.string.hdr_mode_hdr10))
                            hdrModePref.entryValues = arrayOf<CharSequence>("1")
                            hdrModePref.value = "1"
                        }

                        // Update summary to show current selection
                        hdrModePref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                            val value = newValue as String
                            val listPref = preference as ListPreference
                            val idx = listPref.findIndexOfValue(value)
                            if (idx >= 0) {
                                preference.setSummary(listPref.entries[idx])
                            }
                            true
                        }

                        // Set initial summary
                        val idx = hdrModePref.findIndexOfValue(hdrModePref.value)
                        if (idx >= 0) {
                            hdrModePref.summary = hdrModePref.entries[idx]
                        }
                    }
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference<Preference>(PreferenceConfiguration.RESOLUTION_PREF_STRING)!!.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { preference, newValue ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        val valueStr = newValue as String

                        // Detect if this value is the native resolution option
                        val values = (preference as ListPreference).entryValues
                        var isNativeRes = true
                        for (i in values.indices) {
                            // Look for a match prior to the start of the native resolution entries
                            if (valueStr == values[i].toString() && i < nativeResolutionStartIndex) {
                                isNativeRes = false
                                break
                            }
                        }

                        // If this is native resolution, show the warning dialog
                        if (isNativeRes) {
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
                    Preference.OnPreferenceChangeListener { preference, newValue ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                        val valueStr = newValue as String

                        // If this is native frame rate, show the warning dialog
                        val values = (preference as ListPreference).entryValues
                        if (nativeFramerateShown && values[values.size - 1].toString() == newValue) {
                            Dialog.displayDialog(requireActivity(),
                                    resources.getString(R.string.title_native_fps_dialog),
                                    resources.getString(R.string.text_native_res_dialog),
                                    false)
                        }

                        // Write the new bitrate value
                        resetBitrateToDefault(prefs, null, valueStr)

                        // Allow the original preference change to take place
                        true
                    }
            findPreference<Preference>(PreferenceConfiguration.IMPORT_CONFIG_STRING)!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        intent.type = "*/*"
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, 2)
                        false
                    }

            val exportPreference = findPreference<ListPreference>(PreferenceConfiguration.EXPORT_CONFIG_STRING)!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val superConfigDatabaseHelper = SuperConfigDatabaseHelper(context)
                val configIdList = superConfigDatabaseHelper.queryAllConfigIds()
                val configMap: MutableMap<String, String> = HashMap()
                for (configId in configIdList) {
                    val configName = superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME, "default") as String
                    val configIdString = configId.toString()
                    configMap[configIdString] = configName
                }
                val nameEntries = configMap.values.toTypedArray<CharSequence>()
                val nameEntryValues = configMap.keys.toTypedArray<CharSequence>()
                exportPreference.entries = nameEntries
                exportPreference.entryValues = nameEntryValues

                exportPreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    exportConfigString = superConfigDatabaseHelper.exportConfig((newValue as String).toLong())
                    val fileName = configMap[newValue]
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_TITLE, "$fileName.mdat")
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 1)
                    false
                }
            }

            addCustomResolutionsEntries()
            val mergePreference = findPreference<ListPreference>(PreferenceConfiguration.MERGE_CONFIG_STRING)!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val superConfigDatabaseHelper = SuperConfigDatabaseHelper(context)
                val configIdList = superConfigDatabaseHelper.queryAllConfigIds()
                val configMap: MutableMap<String, String> = HashMap()
                for (configId in configIdList) {
                    val configName = superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME, "default") as String
                    val configIdString = configId.toString()
                    configMap[configIdString] = configName
                }
                val nameEntries = configMap.values.toTypedArray<CharSequence>()
                val nameEntryValues = configMap.keys.toTypedArray<CharSequence>()
                mergePreference.entries = nameEntries
                mergePreference.entryValues = nameEntryValues

                mergePreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    exportConfigString = newValue as String
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.type = "*/*"
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 3)
                    false
                }
            }

            findPreference<Preference>(PreferenceConfiguration.ABOUT_AUTHOR)!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        val intent = Intent(Intent.ACTION_VIEW,
                            getString(R.string.author_web).toUri())
                        startActivity(intent)
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

            // 对于没有触摸屏的设备，只提供本地鼠标指针选项
            val mouseModePresetPref = findPreference<ListPreference>(PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING)!!
            if (!requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                // 只显示本地鼠标指针选项
                mouseModePresetPref.entries = arrayOf<CharSequence>(getString(R.string.native_mouse_mode_preset_native))
                mouseModePresetPref.entryValues = arrayOf<CharSequence>("native")
                mouseModePresetPref.value = "native"

                // 强制设置为本地鼠标指针模式
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                prefs.edit {
                    putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, false)
                    putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, false)
                    putBoolean(
                        PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                        true
                    )
                }
            }

            // 添加本地鼠标模式预设选择监听器
            mouseModePresetPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val preset = newValue as String
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.requireActivity())
                prefs.edit {

                    // 根据预设值自动设置相关配置
                    when (preset) {
                        "enhanced" -> {
                            // 增强式多点触控
                            putBoolean(
                                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
                                true
                            )
                            putBoolean(
                                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                                false
                            )
                        }

                        "classic" -> {
                            // 经典鼠标模式
                            putBoolean(
                                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                                false
                            )
                        }

                        "trackpad" -> {
                            // 触控板模式
                            putBoolean(
                                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
                                true
                            )
                            putBoolean(
                                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                                false
                            )
                        }

                        "native" -> {
                            // 本地鼠标指针
                            putBoolean(
                                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
                                false
                            )
                            putBoolean(
                                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                                true
                            )
                        }
                    }
                }

                // 显示提示信息
                val presetName = when (preset) {
                    "enhanced" -> getString(R.string.native_mouse_mode_preset_enhanced)
                    "classic" -> getString(R.string.native_mouse_mode_preset_classic)
                    "trackpad" -> getString(R.string.native_mouse_mode_preset_trackpad)
                    "native" -> getString(R.string.native_mouse_mode_preset_native)
                    else -> ""
                }
                Toast.makeText(activity,
                        getString(R.string.toast_preset_applied, presetName),
                        Toast.LENGTH_SHORT).show()

                true
            }

            availableCategories.clear()
            availableCategories.addAll(buildAvailableCategories(screen))

            val requestedCategoryKey = arguments?.getString(ARG_CATEGORY_KEY)
            val usePersistentSidebarLayout = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            currentCategoryKey = if (requestedCategoryKey == null) {
                if (usePersistentSidebarLayout) {
                    availableCategories.firstOrNull()?.key
                } else {
                    null
                }
            } else {
                availableCategories.firstOrNull { it.key == requestedCategoryKey }?.key
                    ?: availableCategories.firstOrNull()?.key
            }

            if (currentCategoryKey != null) {
                currentCategoryIndex = availableCategories.indexOfFirst { it.key == currentCategoryKey }
                        .takeIf { it >= 0 } ?: 0
                retainSelectedCategory(screen, currentCategoryKey!!)
                flattenSelectedCategory(screen, currentCategoryKey!!)
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            when (preference) {
                is SeekBarPreference -> {
                    val f = SeekBarPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "SeekBarPreference")
                }
                is CustomResolutionsPreference -> {
                    val f = CustomResolutionsPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "CustomResolutionsPreference")
                }
                is ConfirmDeleteOscPreference -> {
                    val f = ConfirmDeleteOscDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "ConfirmDeleteOscPreference")
                }
                is IconListPreference -> {
                    val f = IconListPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    f.setTargetFragment(this, 0)
                    f.show(parentFragmentManager, "IconListPreference")
                }
                else -> super.onDisplayPreferenceDialog(preference)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            //导出配置文件
            if (requestCode == 1 && resultCode == RESULT_OK) {
                val uri = data?.data

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        // 将字符串写入文件
                        val outputStream = requireContext().contentResolver.openOutputStream(uri!!)
                        if (outputStream != null) {
                            outputStream.write(exportConfigString.toByteArray())
                            outputStream.close()
                            Toast.makeText(context, "导出配置文件成功", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        Toast.makeText(context, "导出配置文件失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            //导入配置文件
            if (requestCode == 2 && resultCode == RESULT_OK) {
                val importUri = data?.data

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        requireContext().contentResolver.openInputStream(importUri!!).use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                val stringBuilder = StringBuilder()
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    stringBuilder.append(line).append("\n")
                                }
                                val fileContent = stringBuilder.toString()
                                val superConfigDatabaseHelper = SuperConfigDatabaseHelper(context)
                                val errorCode = superConfigDatabaseHelper.importConfig(fileContent)
                                when (errorCode) {
                                    0 -> {
                                        Toast.makeText(context, "导入配置文件成功", Toast.LENGTH_SHORT).show()
                                        //更新导出配置文件列表
                                        val exportPref = findPreference<ListPreference>(PreferenceConfiguration.EXPORT_CONFIG_STRING)!!
                                        val configIdList = superConfigDatabaseHelper.queryAllConfigIds()
                                        val configMap: MutableMap<String, String> = HashMap()
                                        for (configId in configIdList) {
                                            val configName = superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME, "default") as String
                                            val configIdString = configId.toString()
                                            configMap[configIdString] = configName
                                        }
                                        val nameEntries = configMap.values.toTypedArray<CharSequence>()
                                        val nameEntryValues = configMap.keys.toTypedArray<CharSequence>()
                                        exportPref.entries = nameEntries
                                        exportPref.entryValues = nameEntryValues
                                    }
                                    -1, -2 -> Toast.makeText(context, "读取配置文件失败", Toast.LENGTH_SHORT).show()
                                    -3 -> Toast.makeText(context, "配置文件版本不匹配", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Toast.makeText(context, "读取配置文件失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (requestCode == 3 && resultCode == RESULT_OK) {
                val importUri = data?.data

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        requireContext().contentResolver.openInputStream(importUri!!).use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                val stringBuilder = StringBuilder()
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    stringBuilder.append(line).append("\n")
                                }
                                val fileContent = stringBuilder.toString()
                                val superConfigDatabaseHelper = SuperConfigDatabaseHelper(context)
                                val errorCode = superConfigDatabaseHelper.mergeConfig(fileContent, exportConfigString.toLong())
                                when (errorCode) {
                                    0 -> Toast.makeText(context, "合并配置文件成功", Toast.LENGTH_SHORT).show()
                                    -1, -2 -> Toast.makeText(context, "读取配置文件失败", Toast.LENGTH_SHORT).show()
                                    -3 -> Toast.makeText(context, "配置文件版本不匹配", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Toast.makeText(context, "读取配置文件失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 处理本地图片选择
            if (requestCode == LocalImagePickerPreference.PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
                val pickerPreference = LocalImagePickerPreference.instance
                pickerPreference?.handleImagePickerResult(data)
            }
        }
    }

    private fun loadBackgroundImage() {
        val imageView = findViewById<ImageView>(R.id.settingsBackgroundImage)

        // Limit decoded bitmap size to screen dimensions to avoid
        // "Canvas: trying to draw too large bitmap" on older devices
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)

        // 模糊 + 半透明黑色蒙版，单次解码完成（合并到一个 Glide pipeline）
        val transformations = MultiTransformation<Bitmap>(
                BlurTransformation(2, 3),
                ColorFilterTransformation(Color.argb(120, 0, 0, 0))
        )
        val options = RequestOptions()
                .override(width, height)
                .transform(transformations)
                .diskCacheStrategy(DiskCacheStrategy.ALL)

        // 候选 URL（含原始与所有代理变体）。Glide 缓存键以 URL 为基础，
        // 因此可能上次走代理 A 命中、原始 URL 在缓存中并不存在；这里逐个尝试，
        // 任一变体在缓存中就立即贴图，体验等同本地资源。
        val candidates = mutableListOf<String>().apply {
            add(SETTINGS_BG_URL)
            try { addAll(UpdateManager.buildProxiedUrls(SETTINGS_BG_URL)) } catch (_: Exception) {}
        }.distinct()

        tryCachedThenNetwork(imageView, options, candidates, 0)
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
            index: Int
    ) {
        if (isDestroyed || isFinishing) return
        if (index >= candidates.size) {
            loadBackgroundImageFromNetwork(imageView, options, candidates)
            return
        }
        Glide.with(this)
                .load(candidates[index])
                .apply(options.clone().onlyRetrieveFromCache(true))
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        imageView.setImageDrawable(resource)
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        tryCachedThenNetwork(imageView, options, candidates, index + 1)
                    }
                })
    }

    private fun loadBackgroundImageFromNetwork(
            imageView: ImageView,
            options: RequestOptions,
            preBuiltCandidates: List<String>?
    ) {
        Thread {
            // 代理列表可能在调用前还未就绪，需要刷新一次
            UpdateManager.ensureProxyListUpdated(this)
            val candidates = preBuiltCandidates?.takeIf { it.isNotEmpty() }
                    ?: UpdateManager.buildProxiedUrls(SETTINGS_BG_URL)
            for (url in candidates) {
                try {
                    if (isDestroyed || isFinishing) return@Thread
                    // 后台同步预热：把图解码到 Glide 缓存中（包含 blur+mask 变换）；
                    // 失败则尝试下一条代理，不会污染 UI
                    val ready = Glide.with(applicationContext)
                            .asDrawable()
                            .load(url)
                            .apply(options)
                            .submit()
                            .get()
                    if (ready != null) {
                        runOnUiThread {
                            if (isDestroyed || isFinishing) return@runOnUiThread
                            // 用同一份缓存渲染，加 400ms 渐入避免突兀 pop-in
                            Glide.with(this@StreamSettings)
                                    .load(url)
                                    .apply(options)
                                    .transition(DrawableTransitionOptions.withCrossFade(400))
                                    .into(imageView)
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    // Try next proxy
                }
            }
        }.start()
    }
}
