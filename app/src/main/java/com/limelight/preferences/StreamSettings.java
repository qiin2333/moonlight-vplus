package com.limelight.preferences;

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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.ListView;
import android.preference.PreferenceGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.JustifyContent;

import androidx.annotation.NonNull;

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

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

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

        // 加载背景图片
        loadBackgroundImage();
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

        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

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
    protected void onDestroy() {
        super.onDestroy();
        
        // 清理外接显示器管理器
        if (externalDisplayManager != null) {
            externalDisplayManager.cleanup();
            externalDisplayManager = null;
        }
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        finish();

        // Language changes are handled via configuration changes in Android 13+,
        // so manual activity relaunching is no longer required.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
            if (!newPrefs.language.equals(previousPrefs.language)) {
                // Restart the PC view to apply UI changes
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
        
        // 保存分类和对应的 Tab TextView 的映射
        private Map<PreferenceCategory, TextView> categoryTabMap = new HashMap<>();
        private Map<PreferenceCategory, TextView> categoryGridTabMap = new HashMap<>();
        private PreferenceCategory currentVisibleCategory = null;
        // 保存导航滚动视图的引用
        private HorizontalScrollView navScrollView = null;
        private ScrollView navGridScrollView = null;

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
            if (activity == null) return;

            // 获取视图组件
            LinearLayout navContainer = activity.findViewById(R.id.settings_nav_container);
            FlexboxLayout navGridContainer = activity.findViewById(R.id.settings_nav_grid_container);
            navScrollView = activity.findViewById(R.id.settings_nav_scroll);
            navGridScrollView = activity.findViewById(R.id.settings_nav_grid_scroll);
            ImageView toggleButton = activity.findViewById(R.id.settings_nav_toggle);

            if (navContainer == null || navGridContainer == null || navScrollView == null || 
                navGridScrollView == null || toggleButton == null) {
                return;
            }

            // 配置 Flexbox 自动换行
            navGridContainer.setFlexWrap(FlexWrap.WRAP);
            navGridContainer.setFlexDirection(FlexDirection.ROW);
            navGridContainer.setJustifyContent(JustifyContent.FLEX_START);

            navContainer.removeAllViews();
            navGridContainer.removeAllViews();

            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;

            // 创建收起按钮
            navGridContainer.addView(createCollapseButton(activity, navScrollView, toggleButton, navGridScrollView));

            // 添加分类按钮
            int margin = dpToPx(6);
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (!(pref instanceof PreferenceCategory)) continue;
                
                PreferenceCategory category = (PreferenceCategory) pref;
                if (category.getTitle() == null) continue;

                String title = category.getTitle().toString();

                // 水平模式 Tab
                TextView tabHorizontal = createTab(activity, title);
                LinearLayout.LayoutParams lpHorizontal = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                lpHorizontal.rightMargin = dpToPx(12);
                tabHorizontal.setLayoutParams(lpHorizontal);
                tabHorizontal.setOnClickListener(v -> scrollToCategory(category));
                navContainer.addView(tabHorizontal);
                categoryTabMap.put(category, tabHorizontal);

                // 网格模式 Tab
                TextView tabGrid = createTab(activity, title);
                FlexboxLayout.LayoutParams lpFlex = new FlexboxLayout.LayoutParams(
                        FlexboxLayout.LayoutParams.WRAP_CONTENT,
                        FlexboxLayout.LayoutParams.WRAP_CONTENT
                );
                lpFlex.setMargins(margin, margin, margin, margin);
                tabGrid.setLayoutParams(lpFlex);
                tabGrid.setOnClickListener(v -> scrollToCategory(category));
                navGridContainer.addView(tabGrid);
                categoryGridTabMap.put(category, tabGrid);
            }

            // 展开按钮点击事件
            toggleButton.setOnClickListener(v -> {
                navScrollView.setVisibility(View.GONE);
                toggleButton.setVisibility(View.GONE);
                navGridScrollView.setVisibility(View.VISIBLE);
            });
            
            // 添加滚动监听
            new Handler().post(() -> {
                View fragmentView = getView();
                if (fragmentView != null) {
                    ListView listView = fragmentView.findViewById(android.R.id.list);
                    if (listView != null) {
                        setupScrollListener(listView);
                    }
                }
            });
        }

        private ImageView createCollapseButton(Activity activity, HorizontalScrollView navScroll, 
                                               ImageView toggleButton, ScrollView navGridScroll) {
            ImageView collapseBtn = new ImageView(activity);
            collapseBtn.setImageResource(R.drawable.ic_list_view);
            collapseBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            collapseBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
            collapseBtn.setMinimumHeight(dpToPx(28));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#33FFFFFF"));
            bg.setCornerRadius(dpToPx(16));
            collapseBtn.setBackground(bg);

            int margin = dpToPx(6);
            FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    FlexboxLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(margin, margin, margin, margin);
            collapseBtn.setLayoutParams(lp);

            collapseBtn.setOnClickListener(v -> {
                navScroll.setVisibility(View.VISIBLE);
                toggleButton.setVisibility(View.VISIBLE);
                navGridScroll.setVisibility(View.GONE);
            });

            return collapseBtn;
        }
        
        private void setupScrollListener(ListView listView) {
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                    if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        updateVisibleCategory((ListView) view);
                    }
                }
                
                @Override
                public void onScroll(android.widget.AbsListView view, int firstVisibleItem, 
                                    int visibleItemCount, int totalItemCount) {
                    updateVisibleCategory((ListView) view);
                }
            });
            updateVisibleCategory(listView);
        }
        
        private void updateVisibleCategory(ListView listView) {
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null || listView == null) return;
            
            int firstVisiblePosition = listView.getFirstVisiblePosition();
            int lastVisiblePosition = firstVisiblePosition + listView.getChildCount() - 1;
            
            PreferenceCategory newVisibleCategory = null;
            int categoryPosition = -1;
            
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (!(pref instanceof PreferenceCategory)) continue;
                
                PreferenceCategory category = (PreferenceCategory) pref;
                int position = findAdapterPositionForPreference(category);
                
                if (position >= 0 && position <= lastVisiblePosition &&
                    (position >= firstVisiblePosition || position > categoryPosition)) {
                    newVisibleCategory = category;
                    categoryPosition = position;
                }
            }
            
            if (newVisibleCategory != currentVisibleCategory) {
                if (currentVisibleCategory != null) {
                    updateTabHighlight(currentVisibleCategory, false);
                }
                currentVisibleCategory = newVisibleCategory;
                if (currentVisibleCategory != null) {
                    updateTabHighlight(currentVisibleCategory, true);
                }
            }
        }
        
        private void updateTabHighlight(PreferenceCategory category, boolean highlight) {
            TextView tabHorizontal = categoryTabMap.get(category);
            TextView tabGrid = categoryGridTabMap.get(category);
            
            int bgColor = highlight ? Color.parseColor("#66FFFFFF") : Color.parseColor("#33FFFFFF");
            float alpha = highlight ? 1.0f : 0.7f;
            
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dpToPx(16));
            bg.setColor(bgColor);
            
            if (tabHorizontal != null) {
                tabHorizontal.setBackground(bg);
                tabHorizontal.setAlpha(alpha);
                if (highlight) {
                    tabHorizontal.setTextColor(Color.WHITE);
                    // 确保高亮的 Tab 在导航栏中可见
                    scrollNavToTab(tabHorizontal, navScrollView);
                }
            }
            if (tabGrid != null) {
                GradientDrawable bgGrid = new GradientDrawable();
                bgGrid.setCornerRadius(dpToPx(16));
                bgGrid.setColor(bgColor);
                tabGrid.setBackground(bgGrid);
                tabGrid.setAlpha(alpha);
                if (highlight) {
                    tabGrid.setTextColor(Color.WHITE);
                }
            }
        }
        
        // 滚动水平导航栏，确保指定的 Tab 可见
        private void scrollNavToTab(TextView tab, HorizontalScrollView scrollView) {
            if (tab == null || scrollView == null) {
                return;
            }
            
            // 使用 post 确保布局已完成
            scrollView.post(() -> {
                // 获取 Tab 相对于 HorizontalScrollView 内容（LinearLayout）的位置
                int tabLeft = tab.getLeft();
                int tabRight = tab.getRight();
                int scrollWidth = scrollView.getWidth();
                int scrollX = scrollView.getScrollX();
                int padding = dpToPx(12);
                
                // 计算 Tab 在屏幕上的可见位置
                int tabVisibleLeft = tabLeft - scrollX;
                int tabVisibleRight = tabRight - scrollX;
                
                // 如果 Tab 的左侧在可见区域外（左侧被遮挡）
                if (tabVisibleLeft < padding) {
                    // 滚动使 Tab 左侧可见，并留出边距
                    scrollView.smoothScrollTo(tabLeft - padding, 0);
                }
                // 如果 Tab 的右侧在可见区域外（右侧被遮挡）
                else if (tabVisibleRight > scrollWidth - padding) {
                    // 滚动使 Tab 右侧可见，并留出边距
                    scrollView.smoothScrollTo(tabRight - scrollWidth + padding, 0);
                }
            });
        }

        // 辅助方法：创建统一样式的 Tab (避免代码重复)
        private TextView createTab(Activity activity, String text) {
            TextView tab = new TextView(activity);
            tab.setText(text);
            tab.setTextColor(Color.WHITE);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setSingleLine(true);
            tab.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#33FFFFFF"));
            bg.setCornerRadius(dpToPx(16));
            tab.setBackground(bg);
            return tab;
        }

        // 辅助方法：跳转
        private void scrollToCategory(PreferenceCategory category) {
            int position = findAdapterPositionForPreference(category);
            if (position >= 0) {
                ListView listView = null;
                View fragmentView = getView();
                if (fragmentView != null) {
                    listView = fragmentView.findViewById(android.R.id.list);
                } else {
                    Activity act = getActivity();
                    if (act != null) {
                        listView = act.findViewById(android.R.id.list);
                    }
                }
                if (listView != null) {
                    listView.smoothScrollToPositionFromTop(position, dpToPx(8));
                }
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

                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_advanced_settings");
                CheckBoxPreference hdrPref = (CheckBoxPreference) findPreference("checkbox_enable_hdr");
                CheckBoxPreference hdrHighBrightnessPref = (CheckBoxPreference) findPreference("checkbox_enable_hdr_high_brightness");

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    // 必须先移除依赖项，再移除被依赖的项，否则会崩溃
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


