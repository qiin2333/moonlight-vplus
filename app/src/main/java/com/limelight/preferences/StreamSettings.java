package com.limelight.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;
import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ListView;
import android.preference.PreferenceGroup;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.ExternalDisplayManager;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.utils.AspectRatioConverter;
import com.limelight.utils.Dialog;
import com.limelight.utils.UiHelper;
import com.limelight.utils.UpdateManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.*;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import jp.wasabeef.glide.transformations.BlurTransformation;
import jp.wasabeef.glide.transformations.ColorFilterTransformation;

public class StreamSettings extends Activity {

    private PreferenceConfiguration previousPrefs;
    private int previousDisplayPixelCount;
    private ExternalDisplayManager externalDisplayManager;
    
    // 抽屉菜单相关
    private DrawerLayout drawerLayout; // 竖屏时使用，横屏时为 null
    private RecyclerView categoryList;
    private CategoryAdapter categoryAdapter;
    private List<CategoryItem> categories = new ArrayList<>();
    private int selectedCategoryIndex = 0;
    
    // 状态保存键
    private static final String KEY_SELECTED_CATEGORY = "selected_category_index";

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

    @SuppressLint("SuspiciousIndentation")
    void reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
            previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
		getFragmentManager().beginTransaction().replace(
				R.id.preference_container, new SettingsFragment()
		).commitAllowingStateLoss();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用带阴影的主题
        getTheme().applyStyle(R.style.PreferenceThemeWithShadow, true);
        
        super.onCreate(savedInstanceState);

        previousPrefs = PreferenceConfiguration.readPreferences(this);

        // 初始化外接显示器管理器
        if (previousPrefs.useExternalDisplay) {
            externalDisplayManager = new ExternalDisplayManager(this, previousPrefs, null, null, null, null);
            externalDisplayManager.initialize();
        }

        UiHelper.setLocale(this);

        // 设置自定义布局
        setContentView(R.layout.activity_stream_settings);
        
        // 确保状态栏透明
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        UiHelper.notifyNewRootView(this);
        
        // 恢复保存的状态（屏幕旋转时）
        if (savedInstanceState != null) {
            selectedCategoryIndex = savedInstanceState.getInt(KEY_SELECTED_CATEGORY, 0);
        }

        // 初始化抽屉菜单
        initDrawerMenu();

        // 加载背景图片
        loadBackgroundImage();
        
        // 设置版本号
        setupVersionInfo();
    }
    
    /**
     * 设置版本号显示
     */
    private void setupVersionInfo() {
        TextView versionText = findViewById(R.id.drawer_version);
        if (versionText != null) {
            try {
                String versionName = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                versionText.setText("v" + versionName);
            } catch (PackageManager.NameNotFoundException e) {
                versionText.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 初始化抽屉菜单
     * 竖屏使用 DrawerLayout，横屏使用并排的 LinearLayout
     */
    private void initDrawerMenu() {
        // 横屏时 drawer_layout 是 LinearLayout，不是 DrawerLayout
        View rootView = findViewById(R.id.drawer_layout);
        if (rootView instanceof DrawerLayout) {
            drawerLayout = (DrawerLayout) rootView;
        } else {
            drawerLayout = null; // 横屏时为 null
        }
        
        categoryList = findViewById(R.id.category_list);
        
        setupMenuToggle();
        setupCategoryList();
        setupDrawerListener();
    }
    
    /**
     * 设置菜单按钮（仅竖屏有效）
     */
    private void setupMenuToggle() {
        ImageView menuToggle = findViewById(R.id.settings_menu_toggle);
        if (menuToggle != null) {
            menuToggle.setOnClickListener(v -> openDrawer());
            menuToggle.setFocusable(true);
            menuToggle.setFocusableInTouchMode(false);
        }
    }
    
    /**
     * 设置分类列表
     */
    private void setupCategoryList() {
        if (categoryList != null) {
            categoryList.setLayoutManager(new LinearLayoutManager(this));
            categoryAdapter = new CategoryAdapter();
            categoryList.setAdapter(categoryAdapter);
            categoryList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            categoryList.setFocusable(true);
        }
    }
    
    /**
     * 设置抽屉监听器（仅竖屏有效）
     */
    private void setupDrawerListener() {
        if (drawerLayout == null) return;
        
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                focusSelectedCategory();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                focusPreferenceList();
            }
        });
    }
    
    /**
     * 打开抽屉（仅竖屏有效）
     */
    private void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(findViewById(R.id.drawer_menu));
        }
    }
    
    /**
     * 聚焦到选中的分类项
     */
    private void focusSelectedCategory() {
        if (categoryList != null && categoryAdapter != null && categoryAdapter.getItemCount() > 0) {
            categoryList.post(() -> {
                RecyclerView.ViewHolder vh = categoryList.findViewHolderForAdapterPosition(selectedCategoryIndex);
                if (vh != null && vh.itemView != null) {
                    vh.itemView.requestFocus();
                }
            });
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UpdateManager.INSTALL_PERMISSION_REQUEST_CODE) {
            UpdateManager.onInstallPermissionResult(this);
        }
    }

    /**
     * 聚焦到设置列表
     */
    private void focusPreferenceList() {
        View preferenceContainer = findViewById(R.id.preference_container);
        if (preferenceContainer != null) {
            preferenceContainer.requestFocus();
        }
    }
    
    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /**
     * 分类数据项
     */
    static class CategoryItem {
        String key;
        String title;
        String emoji;

        CategoryItem(String key, String title, String emoji) {
            this.key = key;
            this.title = title;
            this.emoji = emoji;
        }
    }
    
    /**
     * 获取分类对应的 emoji（每个分类唯一）
     */
    private static String getEmojiForCategory(String key) {
        switch (key) {
            case "category_basic_settings": return "⚙️";      // 基本设置
            case "category_screen_position": return "📐";     // 屏幕位置
            case "category_audio_settings": return "🔊";      // 音频
            case "category_gamepad_settings": return "🎮";    // 手柄
            case "category_input_settings": return "⌨️";      // 输入
            case "category_enhanced_touch": return "👆";      // 触摸增强
            case "category_onscreen_controls": return "🎛️";   // 屏幕控制
            case "category_crown_features": return "👑";      // 皇冠功能
            case "category_host_settings": return "🖥️";       // 主机
            case "category_ui_settings": return "🎨";         // 界面
            case "category_advanced_settings": return "🔧";   // 高级
            case "category_help": return "❓";                // 帮助
            default: return "📋";
        }
    }

    /**
     * 分类菜单适配器
     */
    class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView title;
            View indicator;
            View root;

            ViewHolder(View itemView) {
                super(itemView);
                root = itemView.findViewById(R.id.category_item_root);
                title = itemView.findViewById(R.id.category_title);
                indicator = itemView.findViewById(R.id.category_indicator);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_menu, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CategoryItem item = categories.get(position);
            // 抽屉菜单显示 emoji + 标题
            holder.title.setText(item.emoji + " " + item.title);
            
            // 高亮选中项
            boolean isSelected = position == selectedCategoryIndex;
            updateItemAppearance(holder, isSelected, false);

            // 点击事件
            holder.root.setOnClickListener(v -> selectCategory(holder.getAdapterPosition(), item));

            // 焦点变化事件（控制器支持）
            holder.root.setOnFocusChangeListener((v, hasFocus) -> {
                boolean selected = holder.getAdapterPosition() == selectedCategoryIndex;
                updateItemAppearance(holder, selected, hasFocus);
            });
        }

        /**
         * 更新菜单项的外观（选中/焦点状态）- 精致风格
         */
        private void updateItemAppearance(ViewHolder holder, boolean isSelected, boolean hasFocus) {
            // 使用项目公共粉色主题
            int pinkPrimary = getResources().getColor(R.color.theme_pink_primary);    // #FF6B9D
            int white = Color.WHITE;
            int lightGray = Color.parseColor("#BBBBBB");
            int dimGray = Color.parseColor("#888888");
            
            // 指示器显示（小圆点）
            holder.indicator.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            
            // 文字颜色和样式
            if (isSelected) {
                holder.title.setTextColor(white);
                holder.title.setAlpha(1.0f);
            } else if (hasFocus) {
                holder.title.setTextColor(pinkPrimary);
                holder.title.setAlpha(1.0f);
            } else {
                holder.title.setTextColor(lightGray);
                holder.title.setAlpha(0.9f);
            }
            
            // 箭头透明度和颜色
            ImageView arrow = holder.root.findViewById(R.id.category_arrow);
            if (arrow != null) {
                if (isSelected) {
                    arrow.setAlpha(1.0f);
                    arrow.setColorFilter(pinkPrimary);
                } else if (hasFocus) {
                    arrow.setAlpha(0.9f);
                    arrow.setColorFilter(pinkPrimary);
                } else {
                    arrow.setAlpha(0.4f);
                    arrow.setColorFilter(dimGray);
                }
            }
        }

        /**
         * 选择分类
         */
        private void selectCategory(int position, CategoryItem item) {
            if (position < 0 || position >= categories.size()) return;
            
            int oldIndex = selectedCategoryIndex;
            selectedCategoryIndex = position;
            
            // 确保 oldIndex 有效再通知更新
            if (oldIndex >= 0 && oldIndex < categories.size()) {
                notifyItemChanged(oldIndex);
            }
            notifyItemChanged(selectedCategoryIndex);
            
            // 滚动到对应分类
            scrollToCategory(item.key);
            
            // 竖屏时关闭抽屉（横屏时 drawerLayout 为 null，无需处理）
            if (drawerLayout != null) {
                drawerLayout.closeDrawers();
            }
        }

        @Override
        public int getItemCount() {
            return categories.size();
        }
    }

    /**
     * 滚动到指定分类
     */
    void scrollToCategory(String categoryKey) {
        SettingsFragment fragment = (SettingsFragment) getFragmentManager()
                .findFragmentById(R.id.preference_container);
        if (fragment != null) {
            fragment.scrollToCategoryByKey(categoryKey);
        }
    }

    /**
     * 通知 Activity 分类已加载
     */
    void onCategoriesLoaded(List<CategoryItem> loadedCategories) {
        this.categories.clear();
        this.categories.addAll(loadedCategories);
        
        // 验证并校正 selectedCategoryIndex（屏幕旋转后恢复时可能越界）
        if (selectedCategoryIndex >= categories.size()) {
            selectedCategoryIndex = Math.max(0, categories.size() - 1);
        }
        
        if (categoryAdapter != null) {
            categoryAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 更新选中的分类
     */
    void updateSelectedCategory(int index) {
        if (index != selectedCategoryIndex && index >= 0 && index < categories.size()) {
            int oldIndex = selectedCategoryIndex;
            selectedCategoryIndex = index;
            if (categoryAdapter != null) {
                // 确保 oldIndex 有效再通知更新
                if (oldIndex >= 0 && oldIndex < categories.size()) {
                    categoryAdapter.notifyItemChanged(oldIndex);
                }
                categoryAdapter.notifyItemChanged(selectedCategoryIndex);
            }
        }
    }

    /**
     * 更新抽屉布局模式（仅竖屏有效）
     * 横屏使用并排的 LinearLayout，不需要 DrawerLayout 操作
     * 竖屏：默认关闭，可通过菜单按钮打开
     */
    private void updateDrawerMode() {
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return;
        
        // 以下代码仅在竖屏时执行
        View drawerMenu = findViewById(R.id.drawer_menu);
        ImageView menuToggle = findViewById(R.id.settings_menu_toggle);
        
        // 竖屏：可收起抽屉
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, drawerMenu);
        drawerLayout.setScrimColor(0x99000000);
        
        // 关闭抽屉
        if (drawerLayout.isDrawerOpen(drawerMenu)) {
            drawerLayout.closeDrawer(drawerMenu, false);
        }
        
        if (menuToggle != null) {
            menuToggle.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                displayCutoutP = insets.getDisplayCutout();
            }
        }

        // 设置抽屉模式
        updateDrawerMode();

        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // 更新抽屉模式
        updateDrawerMode();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
                reloadSettings();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (handleDrawerKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
    private boolean handleDrawerKeyEvent(int keyCode) {
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return false;
        
        // 以下代码仅在竖屏时执行
        View drawerMenu = findViewById(R.id.drawer_menu);
        boolean isDrawerOpen = drawerLayout.isDrawerOpen(drawerMenu);
        
        // L1/L2：打开抽屉
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L1 || 
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2) {
            if (!isDrawerOpen) {
                drawerLayout.openDrawer(drawerMenu);
                return true;
            }
        }
        
        // R1/R2：关闭抽屉
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R1 ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            if (isDrawerOpen) {
                drawerLayout.closeDrawer(drawerMenu);
                return true;
            }
        }
        
        // D-pad 左键：打开抽屉
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!isDrawerOpen) {
                drawerLayout.openDrawer(drawerMenu);
                return true;
            }
        }
        
        // D-pad 右键：关闭抽屉（从抽屉内）
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (isDrawerOpen) {
                View focusedView = getCurrentFocus();
                if (focusedView != null && isViewInsideDrawer(focusedView)) {
                    drawerLayout.closeDrawer(drawerMenu);
                    return true;
                }
            }
        }
        
        // B 键（手柄）：关闭抽屉
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B) {
            if (isDrawerOpen) {
                drawerLayout.closeDrawer(drawerMenu);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查视图是否在抽屉内
     */
    private boolean isViewInsideDrawer(View view) {
        View drawerMenu = findViewById(R.id.drawer_menu);
        if (drawerMenu == null) return false;
        
        View current = view;
        while (current != null) {
            if (current == drawerMenu) return true;
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存选中的分类索引，用于屏幕旋转后恢复
        outState.putInt(KEY_SELECTED_CATEGORY, selectedCategoryIndex);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (externalDisplayManager != null) {
            externalDisplayManager.cleanup();
            externalDisplayManager = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (handleBackForDrawer()) {
            return;
        }
        
        finish();
        handleLanguageChange();
    }
    
    /**
     * 处理返回键时的抽屉关闭逻辑（仅竖屏有效）
     */
    private boolean handleBackForDrawer() {
        // 横屏时 drawerLayout 为 null（使用并排布局），直接返回
        if (drawerLayout == null) return false;
        
        // 以下代码仅在竖屏时执行
        View drawerMenu = findViewById(R.id.drawer_menu);
        if (!drawerLayout.isDrawerOpen(drawerMenu)) return false;
        
        drawerLayout.closeDrawer(drawerMenu);
        return true;
    }
    
    /**
     * 处理语言变更后的界面刷新（Android 13 以下）
     */
    private void handleLanguageChange() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
            if (!newPrefs.language.equals(previousPrefs.language)) {
                Intent intent = new Intent(this, PcView.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, null);
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragment {

        private int nativeResolutionStartIndex = Integer.MAX_VALUE;
        private boolean nativeFramerateShown = false;

        private String exportConfigString = null;
        
        // 分类列表（用于抽屉菜单同步）
        private final List<PreferenceCategory> categoryList = new ArrayList<>();
        private int currentCategoryIndex = 0;
        // 标记是否正在手动滚动（点击分类触发的滚动）
        private boolean isManualScrolling = false;

        /**
         * 获取目标显示器（优先使用外接显示器）
         */
        private Display getTargetDisplay() {
            StreamSettings settingsActivity = (StreamSettings) getActivity();
            if (settingsActivity != null && settingsActivity.externalDisplayManager != null) {
                return settingsActivity.externalDisplayManager.getTargetDisplay();
            }
            return getActivity().getWindowManager().getDefaultDisplay();
        }

        private void setValue(String preferenceKey, String value) {
            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            pref.setValue(value);
        }

        private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
            CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
            CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

            // Add the new option
            newEntries[newEntries.length - 1] = newEntryName;
            newValues[newValues.length - 1] = newEntryValue;

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait) {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            String newName;

            if (insetsRemoved) {
                newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
            }
            else {
                newName = getResources().getString(R.string.resolution_prefix_native);
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                if (portrait) {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
                }
                else {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
                }
            }

            newName += " ("+nativeWidth+"x"+nativeHeight+")";

            String newValue = nativeWidth+"x"+nativeHeight;

            // Check if the native resolution is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (newValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            if (pref.getEntryValues().length < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.getEntryValues().length;
            }
            appendPreferenceEntry(pref, newName, newValue);
        }

        private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true);
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false);
        }
        private void addCustomResolutionsEntries() {
            SharedPreferences storage = this.getActivity().getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE, Context.MODE_PRIVATE);
            Set<String> stored = storage.getStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, null);
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            List<CharSequence> preferencesList = Arrays.asList(pref.getEntryValues());

            if(stored == null || stored.isEmpty()) {
                return;
            }

            Comparator<String> lengthComparator = (s1, s2) -> {
                String[] s1Size = s1.split("x");
                String[] s2Size = s2.split("x");

                int w1 = Integer.parseInt(s1Size[0]);
                int w2 = Integer.parseInt(s2Size[0]);

                int h1 = Integer.parseInt(s1Size[1]);
                int h2 = Integer.parseInt(s2Size[1]);

                if (w1 == w2) {
                    return Integer.compare(h1, h2);
                }
                return Integer.compare(w1, w2);
            };

            ArrayList<String> list = new ArrayList<>(stored);
            Collections.sort(list, lengthComparator);

            for (String storedResolution : list) {
                if(preferencesList.contains(storedResolution)){
                    continue;
                }
                String[] resolution = storedResolution.split("x");
                int width = Integer.parseInt(resolution[0]);
                int height = Integer.parseInt(resolution[1]);
                String aspectRatio = AspectRatioConverter.getAspectRatio(width,height);
                String displayText = "Custom ";

                if(aspectRatio != null){
                    displayText+=aspectRatio+" ";
                }

                displayText+="("+storedResolution+")";

                appendPreferenceEntry(pref, displayText, storedResolution);
            }
        }

        private void addNativeFrameRateEntry(float framerate) {
            int frameRateRounded = Math.round(framerate);
            if (frameRateRounded == 0) {
                return;
            }

            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            String fpsValue = Integer.toString(frameRateRounded);
            String fpsName = getResources().getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

            // Check if the native frame rate is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (fpsValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false;
                    return;
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue);
            nativeFramerateShown = true;
        }

        private void removeValue(String preferenceKey, String value, Runnable onMatched) {
            int matchingCount = 0;

            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            // Count the number of matching entries we'll be removing
            for (CharSequence seq : pref.getEntryValues()) {
                if (seq.toString().equalsIgnoreCase(value)) {
                    matchingCount++;
                }
            }

            // Create the new arrays
            CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
            CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
            int outIndex = 0;
            for (int i = 0; i < pref.getEntryValues().length; i++) {
                if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                    // Skip matching values
                    continue;
                }

                entries[outIndex] = pref.getEntries()[i];
                entryValues[outIndex] = pref.getEntryValues()[i];
                outIndex++;
            }

            if (pref.getValue().equalsIgnoreCase(value)) {
                onMatched.run();
            }

            // Update the preference with the new list
            pref.setEntries(entries);
            pref.setEntryValues(entryValues);
        }

        private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
            if (res == null) {
                res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
            }

            prefs.edit()
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            PreferenceConfiguration.getDefaultBitrate(res, fps))
                    .apply();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            if (view != null) {
                // 确保列表背景透明
                view.setBackgroundColor(Color.TRANSPARENT);
                
                // 减少顶部间距，让设置内容更贴近导航栏
                int topPadding = view.getPaddingTop();
                int reducedPadding = Math.max(0, topPadding - (int) (16 * getResources().getDisplayMetrics().density));
                view.setPadding(view.getPaddingLeft(), reducedPadding, 
                                view.getPaddingRight(), view.getPaddingBottom());
            }
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            Activity activity = getActivity();
            if (activity == null || !(activity instanceof StreamSettings)) return;

            StreamSettings settingsActivity = (StreamSettings) activity;
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;

            // 收集所有分类
            categoryList.clear();
            List<CategoryItem> items = new ArrayList<>();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (!(pref instanceof PreferenceCategory)) continue;
                
                PreferenceCategory category = (PreferenceCategory) pref;
                if (category.getTitle() == null) continue;

                String title = category.getTitle().toString();
                String key = category.getKey() != null ? category.getKey() : "category_" + i;
                String emoji = getEmojiForCategory(key);
                
                categoryList.add(category);
                items.add(new CategoryItem(key, title, emoji));
            }

            // 通知 Activity 分类已加载
            settingsActivity.onCategoriesLoaded(items);
            
            // 添加滚动监听
            new Handler().post(() -> {
                View fragmentView = getView();
                if (fragmentView != null) {
                    ListView listView = fragmentView.findViewById(android.R.id.list);
                    if (listView != null) {
                        setupScrollListener(listView, settingsActivity);
                    }
                }
            });
        }

        /**
         * 根据 key 滚动到指定分类
         */
        void scrollToCategoryByKey(String categoryKey) {
            for (int i = 0; i < categoryList.size(); i++) {
                PreferenceCategory category = categoryList.get(i);
                String key = category.getKey() != null ? category.getKey() : "category_" + i;
                if (key.equals(categoryKey)) {
                    scrollToCategoryAtIndex(i);
                    return;
                }
            }
        }

        /**
         * 滚动到指定索引的分类
         */
        private void scrollToCategoryAtIndex(int index) {
            if (index < 0 || index >= categoryList.size()) return;
            
            PreferenceCategory category = categoryList.get(index);
            int position = findAdapterPositionForPreference(category);
            if (position >= 0) {
                isManualScrolling = true;
                currentCategoryIndex = index;
                
                ListView listView = null;
                View fragmentView = getView();
                if (fragmentView != null) {
                    listView = fragmentView.findViewById(android.R.id.list);
                }
                if (listView != null) {
                    listView.smoothScrollToPositionFromTop(position, dpToPx(2));
                }
            }
        }

        /**
         * 设置滚动监听
         */
        private void setupScrollListener(ListView listView, StreamSettings settingsActivity) {
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                    if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        isManualScrolling = false;
                        updateVisibleCategory((ListView) view, settingsActivity);
                    }
                }
                
                @Override
                public void onScroll(android.widget.AbsListView view, int firstVisibleItem, 
                                    int visibleItemCount, int totalItemCount) {
                    if (!isManualScrolling) {
                        updateVisibleCategory((ListView) view, settingsActivity);
                    }
                }
            });
            updateVisibleCategory(listView, settingsActivity);
        }

        /**
         * 更新当前可见分类
         */
        private void updateVisibleCategory(ListView listView, StreamSettings settingsActivity) {
            if (listView == null || categoryList.isEmpty()) return;
            
            int firstVisiblePosition = listView.getFirstVisiblePosition();
            int lastVisiblePosition = firstVisiblePosition + listView.getChildCount() - 1;
            
            int newCategoryIndex = -1;
            int categoryPosition = -1;
            
            for (int i = 0; i < categoryList.size(); i++) {
                PreferenceCategory category = categoryList.get(i);
                int position = findAdapterPositionForPreference(category);
                
                if (position >= 0 && position <= lastVisiblePosition &&
                    (position >= firstVisiblePosition || position > categoryPosition)) {
                    newCategoryIndex = i;
                    categoryPosition = position;
                }
            }
            
            if (newCategoryIndex >= 0 && newCategoryIndex != currentCategoryIndex) {
                currentCategoryIndex = newCategoryIndex;
                settingsActivity.updateSelectedCategory(currentCategoryIndex);
            }
        }

        private int dpToPx(int dp) {
            float density = getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }

        private static class PositionCounter {
            int position = 0;
            boolean found = false;
        }

        private int findAdapterPositionForPreference(Preference target) {
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null || target == null) {
                return -1;
            }

            PositionCounter counter = new PositionCounter();
            computePosition(screen, target, counter, true);
            return counter.found ? counter.position : -1;
        }

        private void computePosition(PreferenceGroup group, Preference target, PositionCounter counter, boolean isRoot) {
            if (counter.found) {
                return;
            }

            final int count = group.getPreferenceCount();
            for (int i = 0; i < count; i++) {
                Preference pref = group.getPreference(i);

                // 适配器包含每个 Preference 自身
                counter.position++;
                if (pref == target) {
                    counter.found = true;
                    return;
                }

                if (pref instanceof PreferenceGroup) {
                    computePosition((PreferenceGroup) pref, target, counter, false);
                    if (counter.found) {
                        return;
                    }
                }
            }
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // 添加阴影主题
            getActivity().getTheme().applyStyle(R.style.PreferenceThemeWithShadow, true);
            
            addPreferencesFromResource(R.xml.preferences);
            PreferenceScreen screen = getPreferenceScreen();
            
            // 为 LocalImagePickerPreference 设置 Fragment 实例，确保 onActivityResult 回调正确
            LocalImagePickerPreference localImagePicker = (LocalImagePickerPreference) findPreference("local_image_picker");
            if (localImagePicker != null) {
                localImagePicker.setFragment(this);
            }
            
            // 为背景图片API URL设置监听器，保存时设置类型为"api"
            android.preference.EditTextPreference backgroundImageUrlPref = 
                (android.preference.EditTextPreference) findPreference("background_image_url");
            if (backgroundImageUrlPref != null) {
                backgroundImageUrlPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String url = (String) newValue;
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
                    
                    if (url != null && !url.trim().isEmpty()) {
                        // 设置为API类型，并清除本地文件配置
                        prefs.edit()
                            .putString("background_image_type", "api")
                            .putString("background_image_url", url.trim())
                            .remove("background_image_local_path")
                            .apply();
                        
                        // 发送广播通知 PcView 更新背景图片
                        Intent broadcastIntent = new Intent("com.limelight.REFRESH_BACKGROUND_IMAGE");
                        getActivity().sendBroadcast(broadcastIntent);
                    } else {
                        // 恢复默认
                        prefs.edit()
                            .putString("background_image_type", "default")
                            .remove("background_image_url")
                            .apply();
                        
                        // 发送广播通知 PcView 更新背景图片
                        Intent broadcastIntent = new Intent("com.limelight.REFRESH_BACKGROUND_IMAGE");
                        getActivity().sendBroadcast(broadcastIntent);
                    }
                    
                    return true; // 允许保存
                });
            }

            // hide on-screen controls category on non touch screen devices
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_onscreen_controls");
                screen.removePreference(category);
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_input_settings");
                category.removePreference(findPreference("checkbox_absolute_mouse_mode"));
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors"));
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback"));
            }

            // Hide USB driver options on devices without USB host support
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_usb_bind_all"));
                category.removePreference(findPreference("checkbox_usb_driver"));
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !getActivity().getPackageManager().hasSystemFeature("android.software.picture_in_picture") ||
                    getActivity().getPackageManager().hasSystemFeature("com.amazon.software.fireos")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_ui_settings");
                category.removePreference(findPreference("checkbox_enable_pip"));
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_help");
                screen.removePreference(category);
            }*/
            PreferenceCategory category_gamepad_settings =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            // Remove the vibration options if the device can't vibrate
            if (!((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasVibrator()) {
                category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"));
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
                // The entire OSC category may have already been removed by the touchscreen check above
                PreferenceCategory category = (PreferenceCategory) findPreference("category_onscreen_controls");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_vibrate_osc"));
                }
            }
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasAmplitudeControl() ) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
            }

            // 获取目标显示器（优先使用外接显示器）
            Display display = getTargetDisplay();
            float maxSupportedFps = display.getRefreshRate();

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int maxSupportedResW = 0;

                // Add a native resolution with any insets included for users that don't want content
                // behind the notch of their display
                boolean hasInsets = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use the much nicer Display.getCutout() API on Android 10+
                        cutout = display.getCutout();
                    }
                    else {
                        // Android 9 only
                        cutout = displayCutoutP;
                    }

                    if (cutout != null) {
                        int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                        int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                        if (widthInsets != 0 || heightInsets != 0) {
                            DisplayMetrics metrics = new DisplayMetrics();
                            display.getRealMetrics(metrics);

                            int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                            int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                            addNativeResolutionEntries(width, height, false);
                            hasInsets = true;
                        }
                    }
                }

                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                // For example, a p201 device reports:
                // AVC Decoder: OMX.amlogic.avc.decoder.awesome
                // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
                // AVC supported width range: 64 - 384
                // HEVC supported width range: 64 - 544
                for (Display.Mode candidate : display.getSupportedModes()) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                    int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                            (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets);
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560;
                    }
                    else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }

                    if (candidate.getRefreshRate() > maxSupportedFps) {
                        maxSupportedFps = candidate.getRefreshRate();
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(getContext()).glRenderer);

                MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
                MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

                if (avcDecoder != null) {
                    Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                if (hevcDecoder != null) {
                    Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K, () -> {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P);
                            resetBitrateToDefault(prefs, null, null);
                        });
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P, () -> {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P);
                            resetBitrateToDefault(prefs, null, null);
                        });
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P, new Runnable() {
                            @Override
                            public void run() {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                                setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_720P);
                                resetBitrateToDefault(prefs, null, null);
                            }
                        });
                    }
                    // Never remove 720p
                }
            }
            else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                int width = Math.max(metrics.widthPixels, metrics.heightPixels);
                int height = Math.min(metrics.widthPixels, metrics.heightPixels);
                addNativeResolutionEntries(width, height, false);
            }

            if (!PreferenceConfiguration.readPreferences(this.getActivity()).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 162) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "165", () -> {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "144");
                        resetBitrateToDefault(prefs, null, null);
                    });
                }
                if (maxSupportedFps < 141) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "144", () -> {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "120");
                        resetBitrateToDefault(prefs, null, null);
                    });
                }
                if (maxSupportedFps < 118) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120", () -> {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "90");
                        resetBitrateToDefault(prefs, null, null);
                    });
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90", () -> {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                        setValue(PreferenceConfiguration.FPS_PREF_STRING, "60");
                        resetBitrateToDefault(prefs, null, null);
                    });
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps);

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING).setOnPreferenceChangeListener((preference, newValue) -> {
                // HACK: We need to let the preference change succeed before reinitializing to ensure
                // it's reflected in the new layout.
                final Handler h = new Handler();
                h.postDelayed(() -> {
                    // Ensure the activity is still open when this timeout expires
                    StreamSettings settingsActivity = (StreamSettings) SettingsFragment.this.getActivity();
                    if (settingsActivity != null) {
                        settingsActivity.reloadSettings();
                    }
                }, 500);

                // Allow the original preference change to take place
                return true;
            });

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS");
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_advanced_settings");
                // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                Preference hdrHighBrightnessPref = findPreference("checkbox_enable_hdr_high_brightness");
                if (hdrHighBrightnessPref != null) {
                    category.removePreference(hdrHighBrightnessPref);
                }
                Preference hdrPref = findPreference("checkbox_enable_hdr");
                if (hdrPref != null) {
                    category.removePreference(hdrPref);
                }
            }
            else {
                // 获取目标显示器的 HDR 能力（优先使用外接显示器）
                Display targetDisplay = getTargetDisplay();
                Display.HdrCapabilities hdrCaps = targetDisplay.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true;
                            break;
                        }
                    }
                }

                // Check for HLG support as well
                boolean foundHlg = false;
                if (hdrCaps != null) {
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HLG) {
                            foundHlg = true;
                            break;
                        }
                    }
                }

                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_advanced_settings");
                CheckBoxPreference hdrPref = (CheckBoxPreference) findPreference("checkbox_enable_hdr");
                CheckBoxPreference hdrHighBrightnessPref = (CheckBoxPreference) findPreference("checkbox_enable_hdr_high_brightness");
                ListPreference hdrModePref = (ListPreference) findPreference("list_hdr_mode");

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
                    if (hdrModePref != null) {
                        category.removePreference(hdrModePref);
                    }
                    if (hdrHighBrightnessPref != null) {
                        category.removePreference(hdrHighBrightnessPref);
                    }
                    if (hdrPref != null) {
                        category.removePreference(hdrPref);
                    }
                }
                else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware");
                    if (hdrPref != null) {
                        hdrPref.setEnabled(false);
                        hdrPref.setChecked(false);
                        hdrPref.setSummary("Update the firmware on your NVIDIA SHIELD Android TV to enable HDR");
                    }
                    // 同时禁用 HDR 高亮度选项
                    if (hdrHighBrightnessPref != null) {
                        hdrHighBrightnessPref.setEnabled(false);
                        hdrHighBrightnessPref.setChecked(false);
                    }
                    // 同时禁用 HDR 模式选项
                    if (hdrModePref != null) {
                        hdrModePref.setEnabled(false);
                    }
                }
                else {
                    // HDR is supported, configure the HDR mode preference
                    if (hdrModePref != null) {
                        // If HLG is not supported, remove it from the options
                        if (!foundHlg) {
                            LimeLog.info("Display does not support HLG, limiting to HDR10 only");
                            // Keep only HDR10 option
                            hdrModePref.setEntries(new CharSequence[]{getString(R.string.hdr_mode_hdr10)});
                            hdrModePref.setEntryValues(new CharSequence[]{"1"});
                            hdrModePref.setValue("1");
                        }
                        
                        // Update summary to show current selection
                        hdrModePref.setOnPreferenceChangeListener((preference, newValue) -> {
                            String value = (String) newValue;
                            ListPreference listPref = (ListPreference) preference;
                            int index = listPref.findIndexOfValue(value);
                            if (index >= 0) {
                                preference.setSummary(listPref.getEntries()[index]);
                            }
                            return true;
                        });
                        
                        // Set initial summary
                        int index = hdrModePref.findIndexOfValue(hdrModePref.getValue());
                        if (index >= 0) {
                            hdrModePref.setSummary(hdrModePref.getEntries()[index]);
                        }
                    }
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener((preference, newValue) -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                String valueStr = (String) newValue;

                // Detect if this value is the native resolution option
                CharSequence[] values = ((ListPreference)preference).getEntryValues();
                boolean isNativeRes = true;
                for (int i = 0; i < values.length; i++) {
                    // Look for a match prior to the start of the native resolution entries
                    if (valueStr.equals(values[i].toString()) && i < nativeResolutionStartIndex) {
                        isNativeRes = false;
                        break;
                    }
                }

                // If this is native resolution, show the warning dialog
                if (isNativeRes) {
                    Dialog.displayDialog(getActivity(),
                            getResources().getString(R.string.title_native_res_dialog),
                            getResources().getString(R.string.text_native_res_dialog),
                            false);
                }

                // Write the new bitrate value
                resetBitrateToDefault(prefs, valueStr, null);

                // Allow the original preference change to take place
                return true;
            });
            findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener((preference, newValue) -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                String valueStr = (String) newValue;

                // If this is native frame rate, show the warning dialog
                CharSequence[] values = ((ListPreference)preference).getEntryValues();
                if (nativeFramerateShown && values[values.length - 1].toString().equals(newValue.toString())) {
                    Dialog.displayDialog(getActivity(),
                            getResources().getString(R.string.title_native_fps_dialog),
                            getResources().getString(R.string.text_native_res_dialog),
                            false);
                }

                // Write the new bitrate value
                resetBitrateToDefault(prefs, null, valueStr);

                // Allow the original preference change to take place
                return true;
            });
            findPreference(PreferenceConfiguration.IMPORT_CONFIG_STRING).setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 2);
                return false;
            });



            ListPreference exportPreference = (ListPreference) findPreference(PreferenceConfiguration.EXPORT_CONFIG_STRING);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                SuperConfigDatabaseHelper superConfigDatabaseHelper = new SuperConfigDatabaseHelper(getContext());
                List<Long> configIdList = superConfigDatabaseHelper.queryAllConfigIds();
                Map<String, String> configMap = new HashMap<>();
                for (Long configId : configIdList){
                    String configName = (String) superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME,"default");
                    String configIdString = String.valueOf(configId);
                    configMap.put(configIdString,configName);
                }
                CharSequence[] nameEntries = configMap.values().toArray(new String[0]);
                CharSequence[] nameEntryValues = configMap.keySet().toArray(new String[0]);
                exportPreference.setEntries(nameEntries);
                exportPreference.setEntryValues(nameEntryValues);

                exportPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    exportConfigString = superConfigDatabaseHelper.exportConfig(Long.parseLong((String) newValue));
                    String fileName = configMap.get(newValue);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_TITLE, fileName + ".mdat");
                    startActivityForResult(intent, 1);
                    return false;
                });

            }

            addCustomResolutionsEntries();
            ListPreference mergePreference = (ListPreference) findPreference(PreferenceConfiguration.MERGE_CONFIG_STRING);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                SuperConfigDatabaseHelper superConfigDatabaseHelper = new SuperConfigDatabaseHelper(getContext());
                List<Long> configIdList = superConfigDatabaseHelper.queryAllConfigIds();
                Map<String, String> configMap = new HashMap<>();
                for (Long configId : configIdList){
                    String configName = (String) superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME,"default");
                    String configIdString = String.valueOf(configId);
                    configMap.put(configIdString,configName);
                }
                CharSequence[] nameEntries = configMap.values().toArray(new String[0]);
                CharSequence[] nameEntryValues = configMap.keySet().toArray(new String[0]);
                mergePreference.setEntries(nameEntries);
                mergePreference.setEntryValues(nameEntryValues);

                mergePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    exportConfigString = (String) newValue;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, 3);
                    return false;
                });

            }

            findPreference(PreferenceConfiguration.ABOUT_AUTHOR).setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_web)));
                startActivity(intent);
                return true;
            });

            // 添加检查更新选项的点击事件
            findPreference("check_for_updates").setOnPreferenceClickListener(preference -> {
                UpdateManager.checkForUpdates(getActivity(), true);
                return true;
            });

            // 编解码与屏幕能力检测
            findPreference("capability_diagnostic").setOnPreferenceClickListener(preference -> {
                Intent capIntent = new Intent(getActivity(), CapabilityDiagnosticActivity.class);
                startActivity(capIntent);
                return true;
            });

            // 对于没有触摸屏的设备，只提供本地鼠标指针选项
            ListPreference mouseModePresetPref = (ListPreference) findPreference(PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING);
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                // 只显示本地鼠标指针选项
                mouseModePresetPref.setEntries(new CharSequence[]{getString(R.string.native_mouse_mode_preset_native)});
                mouseModePresetPref.setEntryValues(new CharSequence[]{"native"});
                mouseModePresetPref.setValue("native");
                
                // 强制设置为本地鼠标指针模式
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, false);
                editor.putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, false);
                editor.putBoolean(PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, true);
                editor.apply();
            }

            // 添加本地鼠标模式预设选择监听器
            mouseModePresetPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String preset = (String) newValue;
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                SharedPreferences.Editor editor = prefs.edit();
                
                // 根据预设值自动设置相关配置
                switch (preset) {
                    case "enhanced":
                        // 增强式多点触控
                        editor.putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, true);
                        editor.putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, false);
                        break;
                    case "classic":
                        // 经典鼠标模式
                        editor.putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, false);
                        break;
                    case "trackpad":
                        // 触控板模式
                        editor.putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, true);
                        editor.putBoolean(PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, false);
                        break;
                    case "native":
                        // 本地鼠标指针
                        editor.putBoolean(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING, false);
                        editor.putBoolean(PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, true);
                        break;
                }
                editor.apply();
                
                // 显示提示信息
                String presetName = "";
                switch (preset) {
                    case "enhanced":
                        presetName = getString(R.string.native_mouse_mode_preset_enhanced);
                        break;
                    case "classic":
                        presetName = getString(R.string.native_mouse_mode_preset_classic);
                        break;
                    case "trackpad":
                        presetName = getString(R.string.native_mouse_mode_preset_trackpad);
                        break;
                    case "native":
                        presetName = getString(R.string.native_mouse_mode_preset_native);
                        break;
                }
                Toast.makeText(getActivity(), 
                    getString(R.string.toast_preset_applied, presetName), 
                    Toast.LENGTH_SHORT).show();
                
                return true;
            });

        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            //导出配置文件
            if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        // 将字符串写入文件
                        OutputStream outputStream = getContext().getContentResolver().openOutputStream(uri);
                        if (outputStream != null) {
                            outputStream.write(exportConfigString.getBytes());
                            outputStream.close();
                            Toast.makeText(getContext(),"导出配置文件成功",Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        Toast.makeText(getContext(),"导出配置文件失败",Toast.LENGTH_SHORT).show();
                    }
                }

            }
            //导入配置文件
            if (requestCode == 2 && resultCode == Activity.RESULT_OK) {
                Uri importUri = data.getData();

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try (InputStream inputStream = getContext().getContentResolver().openInputStream(importUri);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        StringBuilder stringBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stringBuilder.append(line).append("\n");
                        }
                        String fileContent = stringBuilder.toString();
                        SuperConfigDatabaseHelper superConfigDatabaseHelper = new SuperConfigDatabaseHelper(getContext());
                        int errorCode = superConfigDatabaseHelper.importConfig(fileContent);
                        switch (errorCode){
                            case 0:
                                Toast.makeText(getContext(),"导入配置文件成功",Toast.LENGTH_SHORT).show();
                                //更新导出配置文件列表
                                ListPreference exportPreference = (ListPreference) findPreference(PreferenceConfiguration.EXPORT_CONFIG_STRING);
                                List<Long> configIdList = superConfigDatabaseHelper.queryAllConfigIds();
                                Map<String, String> configMap = new HashMap<>();
                                for (Long configId : configIdList){
                                    String configName = (String) superConfigDatabaseHelper.queryConfigAttribute(configId, PageConfigController.COLUMN_STRING_CONFIG_NAME,"default");
                                    String configIdString = String.valueOf(configId);
                                    configMap.put(configIdString,configName);
                                }
                                CharSequence[] nameEntries = configMap.values().toArray(new String[0]);
                                CharSequence[] nameEntryValues = configMap.keySet().toArray(new String[0]);
                                exportPreference.setEntries(nameEntries);
                                exportPreference.setEntryValues(nameEntryValues);
                                break;
                            case -1:
                            case -2:
                                Toast.makeText(getContext(),"读取配置文件失败",Toast.LENGTH_SHORT).show();
                                break;
                            case -3:
                                Toast.makeText(getContext(),"配置文件版本不匹配",Toast.LENGTH_SHORT).show();
                                break;
                        }

                    } catch (IOException e) {
                        Toast.makeText(getContext(),"读取配置文件失败",Toast.LENGTH_SHORT).show();
                    }
                }
            }

            if (requestCode == 3 && resultCode == Activity.RESULT_OK) {
                Uri importUri = data.getData();

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try (InputStream inputStream = getContext().getContentResolver().openInputStream(importUri);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        StringBuilder stringBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stringBuilder.append(line).append("\n");
                        }
                        String fileContent = stringBuilder.toString();
                        SuperConfigDatabaseHelper superConfigDatabaseHelper = new SuperConfigDatabaseHelper(getContext());
                        int errorCode = superConfigDatabaseHelper.mergeConfig(fileContent,Long.parseLong(exportConfigString));
                        switch (errorCode){
                            case 0:
                                Toast.makeText(getContext(),"合并配置文件成功",Toast.LENGTH_SHORT).show();
                                break;
                            case -1:
                            case -2:
                                Toast.makeText(getContext(),"读取配置文件失败",Toast.LENGTH_SHORT).show();
                                break;
                            case -3:
                                Toast.makeText(getContext(),"配置文件版本不匹配",Toast.LENGTH_SHORT).show();
                                break;
                        }

                    } catch (IOException e) {
                        Toast.makeText(getContext(),"读取配置文件失败",Toast.LENGTH_SHORT).show();
                    }
                }
            }

            // 处理本地图片选择
            if (requestCode == LocalImagePickerPreference.PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
                LocalImagePickerPreference pickerPreference = LocalImagePickerPreference.getInstance();
                if (pickerPreference != null) {
                    pickerPreference.handleImagePickerResult(data);
                }
            }

        }

    }

    private void loadBackgroundImage() {
        ImageView imageView = findViewById(R.id.settingsBackgroundImage);

        runOnUiThread(() -> Glide.with(this)
            .load("https://raw.gitmirror.com/qiin2333/qiin.github.io/assets/img/moonlight-bg2.webp")
            .apply(RequestOptions.bitmapTransform(new BlurTransformation(2, 3)))
            .transform(new ColorFilterTransformation(Color.argb(120, 0, 0, 0)))
            .into(imageView));
    }
}


