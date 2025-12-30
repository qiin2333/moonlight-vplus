package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.ScaledBitmap;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.NvHTTP.DisplayInfo;
import com.limelight.binding.PlatformBinding;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.ui.AdapterRecyclerBridge;
import com.limelight.ui.SelectionIndicatorAnimator;
import com.limelight.utils.BackgroundImageManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;
import com.limelight.utils.AppSettingsManager;
import com.limelight.LimeLog;
import com.limelight.Game;
import com.limelight.binding.PlatformBinding;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.os.Handler;
import android.os.Looper;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends Activity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();
    private ImageView appBackgroundImage;
    private BackgroundImageManager backgroundImageManager;
    private int selectedPosition = -1; // 跟踪当前选中的位置
    private String computerName; // 存储计算机名称

    // 选中框动画相关
    private SelectionIndicatorAnimator selectionAnimator;
    private RecyclerView currentRecyclerView;
    private AdapterRecyclerBridge currentAdapterBridge;
    private boolean isFirstFocus = true; // 跟踪是否是第一次获得焦点

    // 防抖相关变量
    private final Handler backgroundChangeHandler = new Handler(Looper.getMainLooper());
    private Runnable backgroundChangeRunnable;
    private static final int BACKGROUND_CHANGE_DELAY = 300; // 300ms防抖延迟

    // 上一次设置相关
    private AppSettingsManager appSettingsManager;
    private LinearLayout lastSettingsInfo;
    private TextView lastSettingsText;
    private CheckBox useLastSettingsCheckbox;
    
    // 显示器选择相关
    private LinearLayout displaySelectionInfo;
    private android.widget.RadioGroup displayRadioGroup;
    private List<DisplayInfo> availableDisplays;
    private static final int VIRTUAL_DISPLAY_ID = 212333;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_VDD = 3;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int HIDE_APP_ID = 7;
    private final static int START_WITH_LAST_SETTINGS_ID = 8;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";
    public final static String SELECTED_ADDRESS_EXTRA = "SelectedAddress";
    public final static String SELECTED_PORT_EXTRA = "SelectedPort";

    private final static int DEFAULT_VERTICAL_SPAN_COUNT = 2;
    private final static int DEFAULT_HORIZONTAL_SPAN_COUNT = 1;
    private final static int VERTICAL_SINGLE_ROW_THRESHOLD = 5; // 竖屏时，app数量小于等于4个时使用1行

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }
                    
                    // 如果Intent中传递了选中的地址，则使用该地址覆盖activeAddress
                    String selectedAddress = getIntent().getStringExtra(SELECTED_ADDRESS_EXTRA);
                    int selectedPort = getIntent().getIntExtra(SELECTED_PORT_EXTRA, -1);
                    if (selectedAddress != null && selectedPort > 0) {
                        computer.activeAddress = new ComputerDetails.AddressTuple(selectedAddress, selectedPort);
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(() -> {
                        if (isFinishing() || isChangingConfigurations()) {
                            return;
                        }

                        // Despite my best efforts to catch all conditions that could
                        // cause the activity to be destroyed when we try to commit
                        // I haven't been able to, so we have this try-catch block.
                        try {
                            getFragmentManager().beginTransaction()
                                    .replace(R.id.appFragmentContainer, new AdapterFragment())
                                    .commitAllowingStateLoss();
                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();

                // 延迟检查布局，等待Fragment重新创建完成
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (currentRecyclerView != null) {
                        checkAndUpdateLayout(currentRecyclerView);
                    }
                }, 100);
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(details -> {
            // Do nothing if updates are suspended
            if (suspendGridUpdates) {
                return;
            }

            // Don't care about other computers
            if (!details.uuid.equalsIgnoreCase(uuidString)) {
                return;
            }

            if (details.state == ComputerDetails.State.OFFLINE) {
                // The PC is unreachable now
                runOnUiThread(() -> {
                    // Display a toast to the user and quit the activity
                    Toast.makeText(AppView.this, getResources().getText(R.string.lost_connection), Toast.LENGTH_SHORT).show();
                    finish();
                });

                return;
            }

            // Close immediately if the PC is no longer paired
            if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                runOnUiThread(() -> {
                    // Disable shortcuts referencing this PC for now
                    shortcutHelper.disableComputerShortcut(details,
                            getResources().getString(R.string.scut_not_paired));

                    // Display a toast to the user and quit the activity
                    Toast.makeText(AppView.this, getResources().getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show();
                    finish();
                });

                return;
            }

            // App list is the same or empty
            if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                // Let's check if the running app ID changed
                if (details.runningGameId != lastRunningAppId) {
                    // Update the currently running game using the app ID
                    lastRunningAppId = details.runningGameId;
                    updateUiWithServerinfo(details);
                }

                return;
            }

            lastRunningAppId = details.runningGameId;
            lastRawApplist = details.rawAppList;

            try {
                updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                updateUiWithServerinfo(details);

                if (blockingLoadSpinner != null) {
                    blockingLoadSpinner.dismiss();
                    blockingLoadSpinner = null;
                }
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        setContentView(R.layout.activity_app_view);

        // Initialize background image view
        appBackgroundImage = findViewById(R.id.appBackgroundImage);
        backgroundImageManager = new BackgroundImageManager(this, appBackgroundImage);

        // Initialize app settings manager and UI components
        appSettingsManager = new AppSettingsManager(this);
        lastSettingsInfo = findViewById(R.id.lastSettingsInfo);
        lastSettingsText = findViewById(R.id.lastSettingsText);
        useLastSettingsCheckbox = findViewById(R.id.useLastSettingsCheckbox);

        // Initialize display selection UI components
        displaySelectionInfo = findViewById(R.id.displaySelectionInfo);
        displayRadioGroup = findViewById(R.id.displayRadioGroup);

        // Set up event listeners
        useLastSettingsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appSettingsManager.setUseLastSettingsEnabled(isChecked);
        });

        // Initialize selection indicator animator
        View selectionIndicator = findViewById(R.id.selectionIndicator);
        selectionAnimator = new SelectionIndicatorAnimator(
                selectionIndicator,
                null, // RecyclerView will be set later
                null, // Adapter will be set later
                findViewById(android.R.id.content)
        );
        selectionAnimator.setPositionProvider(() -> selectedPosition);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        // Setup settings button
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(AppView.this, com.limelight.preferences.StreamSettings.class);
            startActivity(intent);
        });

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        // Delay checking displays to allow service connection to complete
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkDisplaysAndUpdateUI();
        }, 500);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    @SuppressLint("SetTextI18n")
    private void updateTitle(String appName) {
        TextView label = findViewById(R.id.appListText);
        if (appName != null && !appName.isEmpty()) {
            // 检查当前是否为横屏
            boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

            // 根据屏幕方向选择分隔符
            String separator = isLandscape ? " - " : "\n";
            String text = computerName + separator + appName;

            SpannableString spannableString = new SpannableString(text);
            int appNameStart = computerName.length() + 1; // +1 是分隔符

            // 设置应用名称的字体大小
            spannableString.setSpan(
                    new RelativeSizeSpan(0.85f),
                    appNameStart,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            label.setText(spannableString);
        } else {
            label.setText(computerName);
        }
    }

    /**
     * 防抖的背景切换方法
     *
     * @param app 要切换背景的应用对象
     */
    private void changeBackgroundWithDebounce(AppView.AppObject app) {
        // 取消之前的延迟任务
        if (backgroundChangeRunnable != null) {
            backgroundChangeHandler.removeCallbacks(backgroundChangeRunnable);
        }

        // 创建新的延迟任务
        backgroundChangeRunnable = () -> {
            if (app != null && appGridAdapter != null && appGridAdapter.getLoader() != null) {
                setAppAsBackground(app);
            }
            backgroundChangeRunnable = null;
        };

        // 延迟执行背景切换
        backgroundChangeHandler.postDelayed(backgroundChangeRunnable, BACKGROUND_CHANGE_DELAY);
    }

    /**
     * 设置指定应用为背景
     *
     * @param appObject 应用对象
     */
    private void setAppAsBackground(AppView.AppObject appObject) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }

        if (backgroundImageManager != null && appBackgroundImage != null) {
            CachedAppAssetLoader loader = appGridAdapter.getLoader();
            CachedAppAssetLoader.LoaderTuple tuple = new CachedAppAssetLoader.LoaderTuple(computer, appObject.app);

            // 尝试从内存缓存获取bitmap
            ScaledBitmap cachedBitmap = loader.getBitmapFromCache(tuple);
            if (cachedBitmap != null && cachedBitmap.bitmap != null) {
                backgroundImageManager.setBackgroundSmoothly(cachedBitmap.bitmap);
            } else {
                // 如果缓存中没有，异步加载
                ImageView tempImageView = new ImageView(this);
                loader.populateImageView(appObject, tempImageView, null, false, () -> {
                    if (tempImageView.getDrawable() instanceof BitmapDrawable) {
                        Bitmap bitmap = ((BitmapDrawable) tempImageView.getDrawable()).getBitmap();
                        if (bitmap != null) {
                            backgroundImageManager.setBackgroundSmoothly(bitmap);
                        }
                    }
                });
            }
        }
    }

    /**
     * 计算最优的spanCount
     *
     * @param orientation 屏幕方向
     * @return 最优的行数
     */
    private int calculateOptimalSpanCount(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return DEFAULT_HORIZONTAL_SPAN_COUNT;
        } else {
            // 竖屏：根据app数量固定阈值判断
            if (appGridAdapter == null) {
                return DEFAULT_VERTICAL_SPAN_COUNT;
            }

            int appCount = appGridAdapter.getCount();
            if (appCount == 0) {
                return DEFAULT_VERTICAL_SPAN_COUNT;
            }

            if (appCount <= VERTICAL_SINGLE_ROW_THRESHOLD) {
                return DEFAULT_HORIZONTAL_SPAN_COUNT;
            } else {
                return DEFAULT_VERTICAL_SPAN_COUNT;
            }
        }
    }

    /**
     * 处理选中项变化
     *
     * @param position 选中位置
     * @param app      选中的应用对象
     */
    private void handleSelectionChange(int position, AppObject app) {
        selectedPosition = position;
        updateTitle(app.app.getAppName());
        appGridAdapter.setSelectedPosition(position);
        appGridAdapter.notifyDataSetChanged();

        // 防抖切换背景
        changeBackgroundWithDebounce(app);

        // 移动选中框动画
        if (selectionAnimator != null) {
            selectionAnimator.moveToPosition(position, isFirstFocus);
            isFirstFocus = false; // 第一次后设置为false
        }

        updateLastSettingsInfo(app);
    }

    /**
     * 更新上一次设置信息显示
     *
     * @param app 应用对象
     */
    private void updateLastSettingsInfo(AppObject app) {
        if (appSettingsManager == null || computer == null) {
            return;
        }

        String settingsSummary = appSettingsManager.getSettingsSummary(computer.uuid, app.app);
        String noneSettingsText = getString(R.string.app_last_settings_none);

        boolean hasValidSettings = settingsSummary != null && !settingsSummary.equals(noneSettingsText);

        if (hasValidSettings) {
            String displayText = getString(R.string.app_last_settings_title) + " " + settingsSummary;
            lastSettingsText.setText(displayText);
            lastSettingsInfo.setVisibility(View.VISIBLE);

            // 同步复选框状态(避免不必要的更新)
            boolean useLastSettings = appSettingsManager.isUseLastSettingsEnabled();
            if (useLastSettingsCheckbox.isChecked() != useLastSettings) {
                useLastSettingsCheckbox.setChecked(useLastSettings);
            }
        } else {
            lastSettingsInfo.setVisibility(View.GONE);
        }
    }

    /**
     * 启动串流，如果勾选了使用上一次设置则使用上一次设置
     *
     * @param app 应用对象
     */
    private void startStreamWithLastSettingsIfEnabled(AppObject app) {
        String displayGuid = null;
        boolean useVdd = false;
        
        if (displaySelectionInfo.getVisibility() == View.VISIBLE && availableDisplays != null) {
            int selectedId = displayRadioGroup.getCheckedRadioButtonId();
            if (selectedId == VIRTUAL_DISPLAY_ID) {
                useVdd = true;
            } else if (selectedId >= 0 && selectedId < availableDisplays.size()) {
                DisplayInfo selectedDisplay = availableDisplays.get(selectedId);
                displayGuid = (selectedDisplay.guid != null && !selectedDisplay.guid.isEmpty())
                        ? selectedDisplay.guid : selectedDisplay.name;
            }
        }
        
        // 设置useVdd标志
        if (computer != null) {
            computer.useVdd = useVdd;
        }
        
        doStartStream(app, displayGuid);
    }
    
    /**
     * 检查显示器并更新UI
     */
    private void checkDisplaysAndUpdateUI() {
        if (computer == null || computer.activeAddress == null || managerBinder == null) {
            displaySelectionInfo.setVisibility(View.GONE);
            return;
        }
        
        new Thread(() -> {
            try {
                NvHTTP httpConn = new NvHTTP(computer.activeAddress, computer.httpsPort,
                        managerBinder.getUniqueId(), "", computer.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                
                List<DisplayInfo> displays = httpConn.getDisplays();
                
                runOnUiThread(() -> {
                    if (displays != null && displays.size() > 0) {
                        updateDisplaySelectionUI(displays);
                    } else {
                        displaySelectionInfo.setVisibility(View.GONE);
                    }
                });
            } catch (Exception e) {
                LimeLog.warning("Failed to get displays: " + e.getMessage());
                runOnUiThread(() -> displaySelectionInfo.setVisibility(View.GONE));
            }
        }).start();
    }
    
    /**
     * 更新显示器选择UI
     *
     * @param displays 显示器列表
     */
    private void updateDisplaySelectionUI(List<DisplayInfo> displays) {
        availableDisplays = displays;
        displayRadioGroup.removeAllViews();
        
        LimeLog.info("Displays: " + displays.size());
        
        // 添加所有物理显示器选项
        for (int i = 0; i < displays.size(); i++) {
            DisplayInfo display = displays.get(i);
            String displayName = (display.name != null && !display.name.isEmpty())
                    ? display.name : "Display " + (display.index + 1);
            LimeLog.info("Display " + (display.index + 1) + ": " + display.name + " (guid: " + display.guid + ")");
            
            displayRadioGroup.addView(createDisplayRadioButton(i, displayName));
        }
        
        displayRadioGroup.addView(createDisplayRadioButton(
                VIRTUAL_DISPLAY_ID, 
                getResources().getString(R.string.applist_menu_start_with_vdd)));
        
        displayRadioGroup.clearCheck();
        displaySelectionInfo.setVisibility(View.VISIBLE);
    }
    
    /**
     * 创建显示器选择单选按钮
     *
     * @param id 按钮ID
     * @param text 按钮文本
     * @return 配置好的单选按钮
     */
    private RadioButton createDisplayRadioButton(int id, String text) {
        RadioButton radioButton = new RadioButton(this);
        radioButton.setId(id);
        radioButton.setText(text);
        radioButton.setTextColor(0xCCFFFFFF);
        radioButton.setTextSize(12);
        radioButton.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        radioButton.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        radioButton.setPadding(0, 0, 20, 0);
        return radioButton;
    }
    
    /**
     * 执行启动串流
     *
     * @param app 应用对象
     * @param displayName 选择的显示器名称，如果为null则不指定显示器
     */
    private void doStartStream(AppObject app, String displayName) {
        if (appSettingsManager != null && computer != null) {
            // 使用AppSettingsManager统一管理启动逻辑
            Intent startIntent = appSettingsManager.createStartIntentWithLastSettingsIfEnabled(
                    this, app.app, computer, managerBinder);
            if (displayName != null) {
                startIntent.putExtra(Game.EXTRA_DISPLAY_NAME, displayName);
            }
            startActivity(startIntent);
        } else {
            // 回退到默认方式启动
            if (displayName != null) {
                Intent startIntent = ServerHelper.createStartIntent(this, app.app, computer, managerBinder);
                startIntent.putExtra(Game.EXTRA_DISPLAY_NAME, displayName);
                startActivity(startIntent);
            } else {
                if (computer != null) {
                    ServerHelper.doStart(this, app.app, computer, managerBinder);
                }
            }
        }
    }

    /**
     * 获取当前使用的item宽度
     *
     * @return item宽度（像素）
     */
    private int getCurrentItemWidth() {
        // 获取当前显示模式
        boolean isLargeMode = isLargeItemMode();

        // 根据模式返回对应的宽度
        if (isLargeMode) {
            // 大图标模式：180dp
            return (int) (180 * getResources().getDisplayMetrics().density);
        } else {
            // 小图标模式：120dp
            return (int) (120 * getResources().getDisplayMetrics().density);
        }
    }

    /**
     * 判断当前是否为大图标模式
     *
     * @return true为大图标模式，false为小图标模式
     */
    private boolean isLargeItemMode() {
        // 根据PreferenceConfiguration判断显示模式
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        return !prefs.smallIconMode; // smallIconMode为false表示大图标模式
    }

    /**
     * 检查并更新布局（竖屏时根据app数量调整行数）
     */
    private void checkAndUpdateLayout(RecyclerView recyclerView) {
        if (recyclerView == null || appGridAdapter == null) {
            return;
        }

        // 检查LayoutManager是否已经设置
        if (recyclerView.getLayoutManager() == null) {
            return;
        }

        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            int currentSpanCount = ((GridLayoutManager) recyclerView.getLayoutManager()).getSpanCount();
            int optimalSpanCount = calculateOptimalSpanCount(orientation);

            if (currentSpanCount != optimalSpanCount) {
                // 需要更新布局
                GridLayoutManager newGlm = new GridLayoutManager(this, optimalSpanCount, GridLayoutManager.HORIZONTAL, false);
                recyclerView.setLayoutManager(newGlm);
            }
        }

        // 屏幕旋转后，延迟重新计算选中框位置，等待布局完成
        if (selectionAnimator != null && selectedPosition >= 0) {
            recyclerView.post(() -> {
                selectionAnimator.moveToPosition(selectedPosition, false);
            });
        }
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache xxxx");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        // Cancel any pending image loading operations
        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }

        // Clear background image to prevent memory leaks
        if (backgroundImageManager != null) {
            backgroundImageManager.clearBackground();
        }

        // 清理防抖Handler
        if (backgroundChangeRunnable != null) {
            backgroundChangeHandler.removeCallbacks(backgroundChangeRunnable);
            backgroundChangeRunnable = null;
        }

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }

        // 清理AdapterRecyclerBridge
        if (currentAdapterBridge != null) {
            currentAdapterBridge.cleanup();
            currentAdapterBridge = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        // 重置焦点状态
        // resetFocusState();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = -1;
        View targetView = null;

        if (menuInfo instanceof AdapterContextMenuInfo) {
            // AbsListView的情况
            AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
            position = info.position;
            targetView = info.targetView;
        } else if (v instanceof RecyclerView) {
            // RecyclerView的情况，需要从当前选中的位置获取
            if (selectedPosition >= 0 && selectedPosition < appGridAdapter.getCount()) {
                position = selectedPosition;
                RecyclerView rv = (RecyclerView) v;
                RecyclerView.ViewHolder viewHolder = rv.findViewHolderForAdapterPosition(selectedPosition);
                if (viewHolder != null) {
                    targetView = viewHolder.itemView;
                }
            }
        } else if (selectedPosition >= 0) {
            position = selectedPosition;
        }

        if (position < 0 || position >= appGridAdapter.getCount()) return;

        AppObject selectedApp = (AppObject) appGridAdapter.getItem(position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            // Add "Start with Last Settings" option if last settings exist
            if (appSettingsManager != null && appSettingsManager.hasLastSettings(computer.uuid, selectedApp.app)) {
                menu.add(Menu.NONE, START_WITH_LAST_SETTINGS_ID, 1, getResources().getString(R.string.applist_menu_start_with_last_settings));
            }
            
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 2, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            if (targetView != null) {
                ImageView appImageView = targetView.findViewById(R.id.grid_image);
                if (appImageView != null) {
                    // We have a grid ImageView, so we must be in grid-mode
                    BitmapDrawable drawable = (BitmapDrawable) appImageView.getDrawable();
                    if (drawable != null && drawable.getBitmap() != null) {
                        // We have a bitmap loaded too
                        menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                    }
                }
            }
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = -1;
        View targetView = null;

        ContextMenuInfo menuInfo = item.getMenuInfo();
        if (menuInfo instanceof AdapterContextMenuInfo) {
            // AbsListView的情况
            AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
            position = info.position;
            targetView = info.targetView;
        } else {
            // RecyclerView的情况，使用当前选中的位置
            position = selectedPosition;
        }

        if (position < 0 || position >= appGridAdapter.getCount()) return false;

        final AppObject app = (AppObject) appGridAdapter.getItem(position);
        switch (item.getItemId()) {
            case START_WITH_QUIT:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this,
                        () -> startStreamWithLastSettingsIfEnabled(app),
                        null);
                return true;

            case START_OR_RESUME_ID:
                // Resume is the same as start for us
                startStreamWithLastSettingsIfEnabled(app);
                return true;

            case START_WITH_LAST_SETTINGS_ID:
                // Start with last settings (force use last settings for this launch)
                if (appSettingsManager != null && computer != null) {
                    Intent startIntent = appSettingsManager.createStartIntentWithLastSettingsIfEnabled(
                            this, app.app, computer, managerBinder);
                    startActivity(startIntent);
                }
                return true;

            case QUIT_ID:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, () -> {
                    suspendGridUpdates = true;
                    ServerHelper.doQuit(this, computer, app.app, managerBinder, () -> {
                        // Trigger a poll immediately
                        suspendGridUpdates = false;
                        if (poller != null) {
                            poller.pollNow();
                        }
                    });
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDetailsDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;

            case HIDE_APP_ID:
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case CREATE_SHORTCUT_ID:
                // 对于RecyclerView，我们需要从缓存中获取bitmap
                Bitmap appBitmap = null;

                // 首先尝试从目标视图获取bitmap
                if (targetView != null) {
                    ImageView appImageView = targetView.findViewById(R.id.grid_image);
                    if (appImageView != null && appImageView.getDrawable() instanceof BitmapDrawable) {
                        BitmapDrawable drawable = (BitmapDrawable) appImageView.getDrawable();
                        appBitmap = drawable.getBitmap();
                    }
                }

                // 如果从视图获取失败,尝试从缓存获取
                if (appBitmap == null && appGridAdapter != null && appGridAdapter.getLoader() != null) {
                    CachedAppAssetLoader.LoaderTuple tuple = new CachedAppAssetLoader.LoaderTuple(computer, app.app);
                    ScaledBitmap cachedBitmap = appGridAdapter.getLoader().getBitmapFromCache(tuple);
                    if (cachedBitmap != null) {
                        appBitmap = cachedBitmap.bitmap;
                    }
                }

                // 创建快捷方式
                if (appBitmap != null) {
                    if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBitmap)) {
                        Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        runOnUiThread(() -> {
                boolean updated = false;
                boolean hasRunningApp = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        hasRunningApp = true;
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                // if (!hasRunningApp) loadDefaultImage();

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                    // Also refresh RecyclerView if it exists - use more efficient update
                    if (currentRecyclerView != null && currentRecyclerView.getAdapter() != null) {
                        // 使用更精确的更新方式，只更新可见的项目
                        currentRecyclerView.getAdapter().notifyItemRangeChanged(0, appGridAdapter.getCount());
                    }
                }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        runOnUiThread(() -> {
            // Prepare list of AppObjects in server order
            List<AppObject> newAppObjects = new ArrayList<>();

            // Create AppObjects from server list, preserving order
            for (NvApp app : appList) {
                // Look for existing AppObject to preserve running state
                AppObject existingApp = null;
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject candidate = (AppObject) appGridAdapter.getItem(i);
                    if (candidate.app.getAppId() == app.getAppId()) {
                        existingApp = candidate;
                        // Update app properties if needed
                        if (!candidate.app.getAppName().equals(app.getAppName())) {
                            candidate.app.setAppName(app.getAppName());
                        }
                        break;
                    }
                }

                if (existingApp != null) {
                    // Use existing AppObject to preserve state (like isRunning)
                    newAppObjects.add(existingApp);
                } else {
                    // Create new AppObject for new app
                    AppObject newAppObject = new AppObject(app);
                    newAppObjects.add(newAppObject);

                    // Enable shortcuts for new apps
                    shortcutHelper.enableAppShortcut(computer, app);
                }
            }

            // Handle removed apps - disable shortcuts
            for (int i = 0; i < appGridAdapter.getCount(); i++) {
                AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                boolean stillExists = false;

                for (NvApp app : appList) {
                    if (existingApp.app.getAppId() == app.getAppId()) {
                        stillExists = true;
                        break;
                    }
                }

                if (!stillExists) {
                    shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                }
            }

            // Rebuild the entire list in server order
            appGridAdapter.rebuildAppList(newAppObjects);
            appGridAdapter.notifyDataSetChanged();

            // Set first app's cover as background if no current background
            setFirstAppAsBackground(newAppObjects);

            // 检查并更新布局（竖屏时根据app数量调整行数）
            if (currentRecyclerView != null) {
                checkAndUpdateLayout(currentRecyclerView);
                
                // 重新计算居中布局
                int orientation = getResources().getConfiguration().orientation;
                int spanCount = calculateOptimalSpanCount(orientation);
                setupCenterAlignment(currentRecyclerView, spanCount);
            }
        });
    }

    private void setFirstAppAsBackground(List<AppObject> appObjects) {
        // Check if activity is still valid
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        
        // Only set background if we don't have one already and there are apps
        if (backgroundImageManager.getCurrentBackground() == null && 
            !appObjects.isEmpty() && 
            appBackgroundImage != null) {
            
            AppObject firstApp = appObjects.get(0);
            
            // Don't set background for hidden apps unless we're showing hidden apps
            if (!firstApp.isHidden || showHiddenApps) {
                if (appGridAdapter != null && appGridAdapter.getLoader() != null) {
                    setFirstAppBackgroundImage(firstApp);
                }
            }
        }
    }
    
    private void setFirstAppBackgroundImage(AppObject firstApp) {
        CachedAppAssetLoader loader = appGridAdapter.getLoader();
        CachedAppAssetLoader.LoaderTuple tuple = new CachedAppAssetLoader.LoaderTuple(computer, firstApp.app);
        
        // Try memory cache first for immediate display
        ScaledBitmap cachedBitmap = loader.getBitmapFromCache(tuple);
        if (cachedBitmap != null && cachedBitmap.bitmap != null) {
            backgroundImageManager.setBackgroundSmoothly(cachedBitmap.bitmap);
        } else {
            // Load asynchronously if not in cache
            ImageView tempImageView = new ImageView(this);
            loader.populateImageView(firstApp, tempImageView, null, false, () -> {
                if (tempImageView.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) tempImageView.getDrawable()).getBitmap();
                    if (bitmap != null) {
                        backgroundImageManager.setBackgroundSmoothly(bitmap);
                    }
                }
            });
        }
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    public void receiveAbsListView(AbsListView listView) {
        // Backwards-compatible wrapper: if a RecyclerView was passed as a View,
        // AdapterFragmentCallbacks signature was generalized but compile-time this
        // method remains for binary compat. Delegate to the View-based method.
        receiveAdapterView(listView);
    }

    @Override
    public void receiveAbsListView(View view) {
        // Implementation for the generalized interface method
        receiveAdapterView(view);
    }

    // New generalized receiver to accept RecyclerView or legacy AbsListView
    public void receiveAdapterView(View view) {
        if (view instanceof RecyclerView) {
            setupRecyclerView((RecyclerView) view);
        } else if (view instanceof AbsListView) {
            setupAbsListView((AbsListView) view);
        }
    }

    private void setupRecyclerView(RecyclerView rv) {
        currentRecyclerView = rv;

        // 更新selectionAnimator的RecyclerView和Adapter引用
        if (selectionAnimator != null) {
            selectionAnimator.updateReferences(rv, appGridAdapter);
        }

        // 创建并设置bridge adapter
        setupBridgeAdapter(rv);

        // 配置布局管理器
        setupLayoutManager(rv);

        // 优化RecyclerView性能
        optimizeRecyclerViewPerformance(rv);

        // 设置事件监听器
        setupRecyclerViewListeners(rv);

        // 应用UI配置
        UiHelper.applyStatusBarPadding(rv);
        registerForContextMenu(rv);
    }

    private void setupBridgeAdapter(RecyclerView rv) {
        AdapterRecyclerBridge bridge = new AdapterRecyclerBridge(this, appGridAdapter);
        rv.setAdapter(bridge);

        // 清理之前的bridge并保存新的引用
        if (currentAdapterBridge != null) {
            currentAdapterBridge.cleanup();
        }
        currentAdapterBridge = bridge;

        // 设置点击监听器
        bridge.setOnItemClickListener(this::handleItemClick);

        // 设置按键监听器
        bridge.setOnItemKeyListener(this::handleItemKey);

        // 设置长按监听器
        bridge.setOnItemLongClickListener(this::handleItemLongClick);
    }

    private void setupLayoutManager(RecyclerView rv) {
        int orientation = getResources().getConfiguration().orientation;
        int spanCount = calculateOptimalSpanCount(orientation);
        GridLayoutManager glm = new GridLayoutManager(this, spanCount, GridLayoutManager.HORIZONTAL, false);
        rv.setLayoutManager(glm);

        // 设置预加载
        glm.setInitialPrefetchItemCount(4);
        
        // 设置居中布局
        setupCenterAlignment(rv, spanCount);
    }

    /**
     * 设置RecyclerView的居中对齐
     */
    private void setupCenterAlignment(RecyclerView rv, int spanCount) {
        rv.post(() -> {
            if (appGridAdapter == null) {
                return;
            }
            
            int itemCount = appGridAdapter.getCount();
            int totalRows = (int) Math.ceil((double) itemCount / spanCount);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int actualItemSize = getCurrentItemWidth();
            
            // 如果RecyclerView已经有子视图,优先使用实际测量的尺寸
            if (rv.getChildCount() > 0) {
                View firstChild = rv.getChildAt(0);
                if (firstChild != null && firstChild.getWidth() > 0) {
                    actualItemSize = firstChild.getWidth();
                }
            }

            // 计算并设置居中padding
            int totalWidth = actualItemSize * totalRows;
            int horizontalPadding = totalWidth < screenWidth ? (screenWidth - totalWidth) / 2 : 0;
            rv.setPadding(horizontalPadding, rv.getPaddingTop(), horizontalPadding, rv.getPaddingBottom());
        });
    }

    private void optimizeRecyclerViewPerformance(RecyclerView rv) {
        // 基础性能优化
        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(15);
        rv.setDrawingCacheEnabled(true);
        rv.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        rv.setNestedScrollingEnabled(false);

        // 滑动性能优化
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setItemAnimator(null);

        // 硬件加速
        rv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 回收池优化
        RecyclerView.RecycledViewPool pool = rv.getRecycledViewPool();
        pool.setMaxRecycledViews(0, 20);
    }

    private void setupRecyclerViewListeners(RecyclerView rv) {
        // 添加滚动监听器
        rv.addOnScrollListener(createScrollListener());

        // 添加子项焦点变化监听
        rv.addOnChildAttachStateChangeListener(createChildAttachStateChangeListener(rv));
    }

    private RecyclerView.OnScrollListener createScrollListener() {
        return new RecyclerView.OnScrollListener() {
            private long lastUpdateTime = 0;
            private static final long MIN_UPDATE_INTERVAL = 16; // 约60fps

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (selectionAnimator != null) {
                        selectionAnimator.showIndicator();
                        updateSelectionPosition();
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING || 
                          newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    if (selectionAnimator != null) {
                        selectionAnimator.hideIndicator();
                    }
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                lastUpdateTime = System.currentTimeMillis();
            }
        };
    }

    private RecyclerView.OnChildAttachStateChangeListener createChildAttachStateChangeListener(RecyclerView rv) {
        return new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                view.setOnFocusChangeListener((v, hasFocus) -> {
                    if (!hasFocus) return;

                    // 延迟处理焦点变化，确保点击事件优先处理
                    v.post(() -> {
                        if (!v.hasFocus()) return;
                        
                        int pos = rv.getChildAdapterPosition(v);
                        if (pos < 0 || pos >= appGridAdapter.getCount()) return;

                        AppObject app = (AppObject) appGridAdapter.getItem(pos);
                        handleSelectionChange(pos, app);
                    });
                });
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                view.setOnFocusChangeListener(null);
            }
        };
    }

    private void handleItemClick(int position, Object item) {
        AppObject app = (AppObject) item;
        handleSelectionChange(position, app);

        if (lastRunningAppId != 0) {
            showContextMenuForPosition(position);
        } else {
            startStreamWithLastSettingsIfEnabled(app);
        }
    }

    private boolean handleItemKey(int position, Object item, int keyCode, android.view.KeyEvent event) {
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            return false;
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_X ||
                keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y) {
            AppObject app = (AppObject) item;
            handleSelectionChange(position, app);
            showContextMenuForPosition(position);
            return true;
        }

        return false;
    }

    private boolean handleItemLongClick(int position, Object item) {
        AppObject app = (AppObject) item;
        handleSelectionChange(position, app);
        return showContextMenuForPosition(position);
    }

    private boolean showContextMenuForPosition(int position) {
        if (currentRecyclerView == null) return false;

        RecyclerView.ViewHolder viewHolder = currentRecyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder != null) {
            openContextMenu(viewHolder.itemView);
            return true;
        }
        return false;
    }

    private void updateSelectionPosition() {
        if (selectedPosition >= 0 && selectionAnimator != null) {
            // 尝试更新到当前选中位置
            boolean positionUpdated = selectionAnimator.updatePosition(selectedPosition);
            
            // 如果更新失败（item滑出屏幕外），隐藏焦点框
            if (!positionUpdated) {
                selectionAnimator.hideIndicator();
            }
        }
    }

    private void setupAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener((arg0, arg1, pos, id) -> {
            AppObject app = (AppObject) appGridAdapter.getItem(pos);
            handleSelectionChange(pos, app);

            if (lastRunningAppId != 0) {
                openContextMenu(arg1);
            } else {
                startStreamWithLastSettingsIfEnabled(app);
            }
        });

        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
