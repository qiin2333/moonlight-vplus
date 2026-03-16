package com.limelight;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerService;
import com.limelight.dialogs.AddressSelectionDialog;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairResult;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.services.KeyboardAccessibilityService;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.AnalyticsManager;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.EasyTierController;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.Iperf3Tester;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;
import com.limelight.utils.UpdateManager;
import com.squareup.seismic.ShakeDetector;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.net.Uri;
import android.net.VpnService;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.LruCache;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.AbsListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import jp.wasabeef.glide.transformations.BlurTransformation;
import jp.wasabeef.glide.transformations.ColorFilterTransformation;

public class PcView extends Activity implements AdapterFragmentCallbacks, ShakeDetector.Listener, EasyTierController.VpnPermissionCallback {

    // Constants
    private static final long REFRESH_DEBOUNCE_DELAY = 150;
    private static final long SHAKE_DEBOUNCE_INTERVAL = 3000;
    private static final int MAX_DAILY_REFRESH = 7;
    private static final int VPN_PERMISSION_REQUEST_CODE = 101;
    private static final int QR_SCAN_REQUEST_CODE = 102;

    private static final String REFRESH_PREF_NAME = "RefreshLimit";
    private static final String REFRESH_COUNT_KEY = "refresh_count";
    private static final String REFRESH_DATE_KEY = "refresh_date";
    private static final String SCENE_PREF_NAME = "SceneConfigs";
    private static final String SCENE_KEY_PREFIX = "scene_";

    // Menu item IDs
    private static final int PAIR_ID = 2;
    private static final int UNPAIR_ID = 3;
    private static final int WOL_ID = 4;
    private static final int DELETE_ID = 5;
    private static final int RESUME_ID = 6;
    private static final int QUIT_ID = 7;
    private static final int VIEW_DETAILS_ID = 8;
    private static final int FULL_APP_LIST_ID = 9;
    private static final int TEST_NETWORK_ID = 10;
    private static final int GAMESTREAM_EOL_ID = 11;
    private static final int SLEEP_ID = 12;
    private static final int IPERF3_TEST_ID = 13;
    private static final int SECONDARY_SCREEN_ID = 14;
    private static final int DISABLE_IPV6_ID = 15;

    // UI Components
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private AbsListView pcListView;
    private ImageView backgroundImageView;

    // State
    private boolean isFirstLoad = true;
    private boolean freezeUpdates;
    private boolean runningPolling;
    private boolean inForeground;
    private boolean completeOnCreateCalled;
    private long lastShakeTime;

    // Helpers
    private ShortcutHelper shortcutHelper;
    private EasyTierController easyTierController;
    private AnalyticsManager analyticsManager;
    private ShakeDetector shakeDetector;
    private AddressSelectionDialog currentAddressDialog;
    private BroadcastReceiver backgroundImageRefreshReceiver;

    // Managers
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private LruCache<String, Bitmap> bitmapLruCache;

    // Handlers
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRefreshRunnable;

    public String clientName;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    (ComputerManagerService.ComputerManagerBinder) binder;

            new Thread(() -> {
                localBinder.waitForReady();
                managerBinder = localBinder;
                startComputerUpdates();
                new AndroidCryptoProvider(PcView.this).getClientCertificate();
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    // Lifecycle Methods

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //自动获取无障碍权限
        try {
            ComponentName cn = new ComponentName(this, KeyboardAccessibilityService.class);
            String myService = cn.flattenToString();
            String enabledServices = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabledServices == null || !enabledServices.contains(myService)) {
                if (enabledServices == null || enabledServices.isEmpty()) {
                    enabledServices = myService;
                } else {
                    enabledServices += ":" + myService;
                }

                // 这里可能会抛异常
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices);
            }
        } catch (SecurityException e) {
            // 没无障碍权限
        }

        easyTierController = new EasyTierController(this, this);
        inForeground = true;
        initBitmapCache();

        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            initGlRenderer(glPrefs);
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (completeOnCreateCalled) {
            initializeViews();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiHelper.showDecoderCrashDialog(this);
        inForeground = true;
        startComputerUpdates();

        if (analyticsManager != null) {
            analyticsManager.startUsageTracking();
        }
        startShakeDetector();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        stopComputerUpdates(false);

        if (analyticsManager != null) {
            analyticsManager.stopUsageTracking();
        }
        stopShakeDetector();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Dialog.closeDialogs();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (easyTierController != null) {
            easyTierController.onDestroy();
        }
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
        if (currentAddressDialog != null) {
            currentAddressDialog.dismiss();
            currentAddressDialog = null;
        }
        unregisterBackgroundReceiver();

        if (analyticsManager != null) {
            analyticsManager.cleanup();
        }
        if (pendingRefreshRunnable != null) {
            refreshHandler.removeCallbacks(pendingRefreshRunnable);
            pendingRefreshRunnable = null;
        }
    }

    // Initialization Methods

    private void initBitmapCache() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        bitmapLruCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private void initGlRenderer(GlPreferences glPrefs) {
        GLSurfaceView surfaceView = new GLSurfaceView(this);
        surfaceView.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                glPrefs.savedFingerprint = Build.FINGERPRINT;
                glPrefs.writePreferences();
                LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);
                runOnUiThread(PcView.this::completeOnCreate);
            }

            @Override
            public void onSurfaceChanged(GL10 gl10, int i, int i1) {
            }

            @Override
            public void onDrawFrame(GL10 gl10) {
            }
        });
        setContentView(surfaceView);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void completeOnCreate() {
        completeOnCreateCalled = true;
        shortcutHelper = new ShortcutHelper(this);
        UiHelper.setLocale(this);

        analyticsManager = AnalyticsManager.getInstance(this);
        analyticsManager.logAppLaunch();
        UpdateManager.checkForUpdatesOnStartup(this);

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));
        pcGridAdapter.setAvatarClickListener(this::handleAvatarClick);

        initShakeDetector();
        registerBackgroundReceiver();
        initializeViews();
    }

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);
        UiHelper.notifyNewRootView(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        clientName = Settings.Global.getString(getContentResolver(), "device_name");
        backgroundImageView = findViewById(R.id.pcBackgroundImage);

        loadBackgroundImage();
        setupBackgroundImageLongPress();
        initSceneButtons();

        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));
        setupButtons();
        setupAdapterFragment();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        addAddComputerCard();
        updateNoPcFoundVisibility();
        handleInitialLoad();
    }

    private void setupButtons() {
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton restoreSessionButton = findViewById(R.id.restoreSessionButton);
        ImageButton aboutButton = findViewById(R.id.aboutButton);
        ImageButton easyTierButton = findViewById(R.id.easyTierControlButton);
        ImageButton toggleUnpairedButton = findViewById(R.id.toggleUnpairedButton);

        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, StreamSettings.class)));
        restoreSessionButton.setOnClickListener(v -> restoreLastSession());

        if (aboutButton != null) {
            aboutButton.setOnClickListener(v -> showAboutDialog());
        }
        if (easyTierButton != null) {
            easyTierButton.setOnClickListener(v -> showEasyTierControlDialog());
        }
        if (toggleUnpairedButton != null) {
            updateToggleUnpairedButtonIcon(toggleUnpairedButton);
            toggleUnpairedButton.setOnClickListener(v -> toggleUnpairedDevices(toggleUnpairedButton));
        }
    }

    private void setupAdapterFragment() {
        getFragmentManager().beginTransaction()
                .replace(R.id.pcFragmentContainer, new AdapterFragment())
                .commitAllowingStateLoss();
    }

    private void updateNoPcFoundVisibility() {
        boolean isEmpty = pcGridAdapter.getCount() == 0 ||
                (pcGridAdapter.getCount() == 1 && PcGridAdapter.isAddComputerCard((ComputerObject) pcGridAdapter.getItem(0)));
        noPcFoundLayout.setVisibility(isEmpty ? View.VISIBLE : View.INVISIBLE);
    }

    private void handleInitialLoad() {
        if (isFirstLoad) {
            if (pendingRefreshRunnable != null) {
                refreshHandler.removeCallbacks(pendingRefreshRunnable);
                pendingRefreshRunnable = null;
            }
            if (pcListView != null) {
                pcGridAdapter.notifyDataSetChanged();
            }
        } else {
            debouncedNotifyDataSetChanged();
        }
    }

    // Background Image Methods

    private void loadBackgroundImage() {
        if (backgroundImageView == null) return;

        String imageUrl = getBackgroundImageUrl();
        new Thread(() -> {
            try {
                Object glideTarget = resolveGlideTarget(imageUrl);
                Bitmap bitmap = Glide.with(this)
                        .asBitmap()
                        .load(glideTarget)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .submit()
                        .get();

                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap);
                    runOnUiThread(() -> applyBlurredBackground(bitmap));
                }
            } catch (ExecutionException e) {
                handleGlideException(e);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private Object resolveGlideTarget(String imageUrl) {
        if (imageUrl.startsWith("http")) {
            return imageUrl;
        }
        File localFile = new File(imageUrl);
        return localFile.exists() ? localFile : getDefaultApiUrl();
    }

    private void applyBlurredBackground(Bitmap bitmap) {
        if (backgroundImageView == null) return;
        Glide.with(this)
                .load(bitmap)
                .apply(RequestOptions.bitmapTransform(new BlurTransformation(2, 3)))
                .transform(new ColorFilterTransformation(Color.argb(120, 0, 0, 0)))
                .into(backgroundImageView);
    }

    private void handleGlideException(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("HttpException") || msg.contains("SocketException") || msg.contains("MediaMetadataRetriever"))) {
                LimeLog.warning("Background image download failed: " + msg);
                return;
            }
        }
        e.printStackTrace();
    }

    private void setupBackgroundImageLongPress() {
        if (backgroundImageView != null) {
            backgroundImageView.setOnLongClickListener(v -> {
                saveImageWithPermissionCheck();
                return true;
            });
        }
    }

    @NonNull
    private String getBackgroundImageUrl() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String type = prefs.getString("background_image_type", "default");

        switch (type) {
            case "api":
                String apiUrl = prefs.getString("background_image_url", null);
                if (apiUrl != null && !apiUrl.isEmpty()) {
                    return apiUrl;
                }
                prefs.edit().putString("background_image_type", "default").apply();
                return getDefaultApiUrl();

            case "local":
                String localPath = prefs.getString("background_image_local_path", null);
                if (localPath != null && new File(localPath).exists()) {
                    return localPath;
                }
                prefs.edit()
                        .putString("background_image_type", "default")
                        .remove("background_image_local_path")
                        .apply();
                return getDefaultApiUrl();

            default:
                return getDefaultApiUrl();
        }
    }

    private String getDefaultApiUrl() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        return rotation == Configuration.ORIENTATION_PORTRAIT
                ? "https://img-api.pipw.top"
                : "https://img-api.pipw.top/?phone=true";
    }

    private void refreshBackgroundImage(boolean isFromShake) {
        if (backgroundImageView == null) return;

        String imageUrl = getBackgroundImageUrl();
        bitmapLruCache.remove(imageUrl);

        new Thread(() -> {
            try {
                Object glideTarget = resolveGlideTarget(imageUrl);
                Bitmap bitmap = Glide.with(this)
                        .asBitmap()
                        .load(glideTarget)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .submit()
                        .get();

                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap);
                    runOnUiThread(() -> {
                        applyBlurredBackground(bitmap);
                        if (isFromShake) {
                            showToast(getString(R.string.background_refreshed_with_remaining, getRemainingRefreshCount()));
                        }
                    });
                } else {
                    runOnUiThread(() -> showToast(getString(R.string.refresh_failed_please_retry)));
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> showToast(getString(R.string.refresh_failed_with_error, e.getMessage())));
            }
        }).start();
    }

    // Image Save Methods

    private void saveImageWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showToast(getString(R.string.storage_permission_required));
            requestStoragePermission();
            return;
        }
        saveImage();
    }

    private void requestStoragePermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void saveImage() {
        Bitmap bitmap = bitmapLruCache.get(getBackgroundImageUrl());

        if (bitmap == null) {
            if (backgroundImageView != null && backgroundImageView.getDrawable() != null) {
                showToast(getString(R.string.downloading_image_please_wait));
                downloadAndSaveImage();
            } else {
                showToast(getString(R.string.image_not_loaded_please_retry));
            }
            return;
        }
        saveBitmapToFile(bitmap);
    }

    private void downloadAndSaveImage() {
        new Thread(() -> {
            try {
                Object glideTarget = resolveGlideTarget(getBackgroundImageUrl());
                Bitmap bitmap = Glide.with(this)
                        .asBitmap()
                        .load(glideTarget)
                        .submit()
                        .get();

                if (bitmap != null) {
                    bitmapLruCache.put(getBackgroundImageUrl(), bitmap);
                    runOnUiThread(() -> saveBitmapToFile(bitmap));
                } else {
                    runOnUiThread(() -> showToast(getString(R.string.image_download_failed_retry)));
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> showToast(getString(R.string.image_download_failed_with_error, e.getMessage())));
            }
        }).start();
    }

    private void saveBitmapToFile(Bitmap bitmap) {
        if (bitmap == null) {
            showToast(getString(R.string.image_invalid));
            return;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "setu");
        if (!dir.exists() && !dir.mkdirs()) {
            showToast(getString(R.string.image_save_failed_with_error, "Failed to create directory"));
            return;
        }

        String fileName = "pipw-" + System.currentTimeMillis() + ".png";
        File file = new File(dir, fileName);

        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            refreshSystemPic(file);
            showToast(getString(R.string.image_saved_successfully));
        } catch (IOException e) {
            e.printStackTrace();
            showToast(getString(R.string.image_save_failed_with_error, e.getMessage()));
        }
    }

    private void refreshSystemPic(File file) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        sendBroadcast(intent);
    }

    // Shake Detection Methods

    private void initShakeDetector() {
        shakeDetector = new ShakeDetector(this);
        shakeDetector.setSensitivity(ShakeDetector.SENSITIVITY_MEDIUM);
    }

    private void startShakeDetector() {
        if (shakeDetector == null) return;
        try {
            shakeDetector.start((SensorManager) getSystemService(SENSOR_SERVICE));
        } catch (Exception e) {
            LimeLog.warning("shakeDetector start failed: " + e.getMessage());
        }
    }

    private void stopShakeDetector() {
        if (shakeDetector == null) return;
        try {
            shakeDetector.stop();
        } catch (Exception e) {
            LimeLog.warning("shakeDetector stop failed: " + e.getMessage());
        }
    }

    @Override
    public void hearShake() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastShakeTime < SHAKE_DEBOUNCE_INTERVAL) {
            long remaining = (SHAKE_DEBOUNCE_INTERVAL - (currentTime - lastShakeTime)) / 1000;
            runOnUiThread(() -> showToast(getString(R.string.please_wait_seconds, remaining)));
            return;
        }

        if (!canRefreshToday()) {
            runOnUiThread(() -> showToast(getString(R.string.daily_limit_reached)));
            return;
        }

        lastShakeTime = currentTime;
        incrementRefreshCount();
        int remaining = getRemainingRefreshCount();

        runOnUiThread(() -> {
            showToast(getString(R.string.refreshing_with_remaining, remaining));
            refreshBackgroundImage(true);
        });
    }

    private String getTodayDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private boolean canRefreshToday() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = prefs.getInt(REFRESH_COUNT_KEY, 0);

        if (!today.equals(savedDate)) {
            prefs.edit()
                    .putString(REFRESH_DATE_KEY, today)
                    .putInt(REFRESH_COUNT_KEY, 0)
                    .apply();
            return true;
        }
        return count < MAX_DAILY_REFRESH;
    }

    private int getRemainingRefreshCount() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = prefs.getInt(REFRESH_COUNT_KEY, 0);

        return today.equals(savedDate) ? Math.max(0, MAX_DAILY_REFRESH - count) : MAX_DAILY_REFRESH;
    }

    private void incrementRefreshCount() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = today.equals(savedDate) ? prefs.getInt(REFRESH_COUNT_KEY, 0) : 0;

        prefs.edit()
                .putString(REFRESH_DATE_KEY, today)
                .putInt(REFRESH_COUNT_KEY, count + 1)
                .apply();
    }

    // Background Receiver Methods

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBackgroundReceiver() {
        backgroundImageRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.limelight.REFRESH_BACKGROUND_IMAGE".equals(intent.getAction())) {
                    refreshBackgroundImage(false);
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.limelight.REFRESH_BACKGROUND_IMAGE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backgroundImageRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(backgroundImageRefreshReceiver, filter);
        }
    }

    private void unregisterBackgroundReceiver() {
        if (backgroundImageRefreshReceiver != null) {
            try {
                unregisterReceiver(backgroundImageRefreshReceiver);
            } catch (IllegalArgumentException e) {
                LimeLog.warning("Failed to unregister background receiver: " + e.getMessage());
            }
        }
    }

    // Computer Manager Methods

    private void startComputerUpdates() {
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(details -> {
                if (!freezeUpdates) {
                    runOnUiThread(() -> updateComputer(details));
                    if (details.pairState == PairState.PAIRED) {
                        shortcutHelper.createAppViewShortcutForOnlineHost(details);
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder == null || !runningPolling) return;

        freezeUpdates = true;
        managerBinder.stopPolling();

        if (wait) {
            managerBinder.waitForPollingStopped();
        }
        runningPolling = false;
    }

    private void debouncedNotifyDataSetChanged() {
        if (pendingRefreshRunnable != null) {
            refreshHandler.removeCallbacks(pendingRefreshRunnable);
        }

        pendingRefreshRunnable = () -> {
            pcGridAdapter.notifyDataSetChanged();
            pendingRefreshRunnable = null;
        };

        refreshHandler.postDelayed(pendingRefreshRunnable, REFRESH_DEBOUNCE_DELAY);
    }

    private void updateComputer(ComputerDetails details) {
        if (PcGridAdapter.ADD_COMPUTER_UUID.equals(details.uuid)) return;

        ComputerObject existingEntry = findComputerByUuid(details.uuid);

        if (existingEntry != null) {
            existingEntry.details = details;
            pcGridAdapter.resort();
        } else {
            addNewComputer(details);
        }

        debouncedNotifyDataSetChanged();
    }

    private ComputerObject findComputerByUuid(String uuid) {
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);
            if (!PcGridAdapter.isAddComputerCard(computer) && uuid != null && uuid.equals(computer.details.uuid)) {
                return computer;
            }
        }
        return null;
    }

    private void addNewComputer(ComputerDetails details) {
        ComputerObject newComputer = new ComputerObject(details);
        pcGridAdapter.addComputer(newComputer);

        boolean isUnpaired = details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairState.NOT_PAIRED;

        if (isUnpaired && !pcGridAdapter.isShowUnpairedDevices()) {
            pcGridAdapter.setShowUnpairedDevices(true);
            updateToggleUnpairedButtonIcon(findViewById(R.id.toggleUnpairedButton));
            showToast(getString(R.string.new_unpaired_device_shown));
        }

        noPcFoundLayout.setVisibility(View.INVISIBLE);

        if (pcListView != null && !isFirstLoad) {
            pcListView.scheduleLayoutAnimation();
        }
    }

    private void removeComputer(ComputerDetails details) {
        if (PcGridAdapter.ADD_COMPUTER_UUID.equals(details.uuid)) return;

        managerBinder.removeComputer(details);
        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);
            if (!PcGridAdapter.isAddComputerCard(computer) && details.equals(computer.details)) {
                shortcutHelper.disableComputerShortcut(details, getString(R.string.scut_deleted_pc));
                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (countRealComputers() == 0) {
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }
                break;
            }
        }
    }

    private int countRealComputers() {
        int count = 0;
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            if (!PcGridAdapter.isAddComputerCard(pcGridAdapter.getRawItem(i))) {
                count++;
            }
        }
        return count;
    }

    private void addAddComputerCard() {
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            if (PcGridAdapter.isAddComputerCard(pcGridAdapter.getRawItem(i))) {
                return;
            }
        }

        ComputerDetails addDetails = new ComputerDetails();
        addDetails.uuid = PcGridAdapter.ADD_COMPUTER_UUID;
        try {
            addDetails.name = getString(R.string.title_add_pc);
        } catch (Exception e) {
            addDetails.name = "添加电脑";
        }
        addDetails.state = ComputerDetails.State.UNKNOWN;

        pcGridAdapter.addComputer(new ComputerObject(addDetails));
        pcGridAdapter.notifyDataSetChanged();

        if (noPcFoundLayout != null) {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
    }

    // Toggle Unpaired Button

    private void toggleUnpairedDevices(ImageButton button) {
        boolean newState = !pcGridAdapter.isShowUnpairedDevices();
        pcGridAdapter.setShowUnpairedDevices(newState);
        updateToggleUnpairedButtonIcon(button);
        showToast(newState ? getString(R.string.unpaired_devices_shown) : getString(R.string.unpaired_devices_hidden));
    }

    private void updateToggleUnpairedButtonIcon(ImageButton button) {
        if (button == null || pcGridAdapter == null) return;
        button.setImageResource(pcGridAdapter.isShowUnpairedDevices()
                ? R.drawable.ic_visibility
                : R.drawable.ic_visibility_off);
    }

    // Scene Configuration Methods

    private void initSceneButtons() {
        try {
            int[] sceneButtonIds = {R.id.scene1Btn, R.id.scene2Btn, R.id.scene3Btn, R.id.scene4Btn, R.id.scene5Btn};

            for (int i = 0; i < sceneButtonIds.length; i++) {
                final int sceneNumber = i + 1;
                ImageButton btn = findViewById(sceneButtonIds[i]);

                if (btn == null) {
                    LimeLog.warning("Scene button " + sceneNumber + " not found!");
                    continue;
                }

                btn.setOnClickListener(v -> applySceneConfiguration(sceneNumber));
                btn.setOnLongClickListener(v -> {
                    showSaveConfirmationDialog(sceneNumber);
                    return true;
                });
            }
        } catch (Exception e) {
            LimeLog.warning("Scene init failed: " + e);
        }
    }

    @SuppressLint("DefaultLocale")
    private void applySceneConfiguration(int sceneNumber) {
        try {
            SharedPreferences prefs = getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE);
            String configJson = prefs.getString(SCENE_KEY_PREFIX + sceneNumber, null);

            if (configJson == null) {
                showToast(getString(R.string.scene_not_configured, sceneNumber));
                return;
            }

            JSONObject config = new JSONObject(configJson);
            PreferenceConfiguration configPrefs = PreferenceConfiguration.readPreferences(this).copy();

            configPrefs.width = config.optInt("width", 1920);
            configPrefs.height = config.optInt("height", 1080);
            configPrefs.fps = config.optInt("fps", 60);
            configPrefs.bitrate = config.optInt("bitrate", 10000);
            configPrefs.videoFormat = PreferenceConfiguration.FormatOption.valueOf(config.optString("videoFormat", "auto"));
            configPrefs.enableHdr = config.optBoolean("enableHdr", false);
            configPrefs.enablePerfOverlay = config.optBoolean("enablePerfOverlay", false);

            if (!configPrefs.writePreferences(this)) {
                showToast(getString(R.string.config_save_failed));
                return;
            }

            pcGridAdapter.updateLayoutWithPreferences(this, configPrefs);
            showToast(getString(R.string.scene_config_applied, sceneNumber, configPrefs.width, configPrefs.height,
                    configPrefs.fps, configPrefs.bitrate / 1000.0, configPrefs.videoFormat.toString(),
                    configPrefs.enableHdr ? "On" : "Off"));

        } catch (Exception e) {
            LimeLog.warning("Scene apply failed: " + e);
            showToast(getString(R.string.config_apply_failed));
        }
    }

    private void showSaveConfirmationDialog(int sceneNumber) {
        new AlertDialog.Builder(this, R.style.AppDialogStyle)
                .setTitle(getString(R.string.save_to_scene, sceneNumber))
                .setMessage(getString(R.string.overwrite_current_config))
                .setPositiveButton(R.string.dialog_button_save, (d, w) -> saveCurrentConfiguration(sceneNumber))
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show();
    }

    private void saveCurrentConfiguration(int sceneNumber) {
        try {
            PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
            JSONObject config = new JSONObject();
            config.put("width", prefs.width);
            config.put("height", prefs.height);
            config.put("fps", prefs.fps);
            config.put("bitrate", prefs.bitrate);
            config.put("videoFormat", prefs.videoFormat.toString());
            config.put("enableHdr", prefs.enableHdr);
            config.put("enablePerfOverlay", prefs.enablePerfOverlay);

            getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(SCENE_KEY_PREFIX + sceneNumber, config.toString())
                    .apply();

            showToast(getString(R.string.scene_saved_successfully, sceneNumber));
        } catch (JSONException e) {
            showToast(getString(R.string.config_save_failed));
        }
    }

    // PC Actions

    private void doPair(ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.pair_pc_offline));
            return;
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        showToast(getString(R.string.pairing));
        new Thread(() -> {
            String message = null;
            boolean success = false;

            try {
                stopComputerUpdates(true);

                NvHTTP httpConn = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        managerBinder.getUniqueId(),
                        clientName,
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(this)
                );

                if (httpConn.getPairState() == PairState.PAIRED) {
                    success = true;
                } else {
                    String pinStr = PairingManager.generatePinString();
                    Dialog.displayDialog(this,
                            getString(R.string.pair_pairing_title),
                            getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" + getString(R.string.pair_pairing_help),
                            false);

                    PairingManager pm = httpConn.getPairingManager();
                    PairResult result = pm.pair(httpConn.getServerInfo(true), pinStr);

                    switch (result.state) {
                        case PIN_WRONG:
                            message = getString(R.string.pair_incorrect_pin);
                            break;
                        case FAILED:
                            message = computer.runningGameId != 0 ? getString(R.string.pair_pc_ingame) : getString(R.string.pair_fail);
                            break;
                        case ALREADY_IN_PROGRESS:
                            message = getString(R.string.pair_already_in_progress);
                            break;
                        case PAIRED:
                            success = true;
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                            getSharedPreferences("pair_name_map", MODE_PRIVATE)
                                    .edit()
                                    .putString(computer.uuid, result.pairName)
                                    .apply();
                            managerBinder.invalidateStateForComputer(computer.uuid);
                            break;
                    }
                }
            } catch (UnknownHostException e) {
                message = getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getString(R.string.error_404);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = getString(R.string.pair_fail);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
            } finally {
                Dialog.closeDialogs();
            }

            String finalMessage = message;
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                if (finalMessage != null) {
                    showToast(finalMessage);
                }
                if (finalSuccess) {
                    doAppList(computer, true, false);
                } else {
                    startComputerUpdates();
                }
            });
        }).start();
    }

    private void showAddComputerDialog() {
        String[] items = {
            getString(R.string.addpc_manual),
            getString(R.string.addpc_qr_scan)
        };
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_add_pc_choose))
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    startActivity(new Intent(this, AddComputerManually.class));
                } else {
                    startQrScan();
                }
            })
            .show();
    }

    private void startQrScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt(getString(R.string.qr_scan_prompt));
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    private void handleQrPairResult(String url) {
        Uri uri = Uri.parse(url);
        if (!"moonlight".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) {
            showToast(getString(R.string.qr_invalid_code));
            return;
        }

        String host = uri.getQueryParameter("host");
        String portStr = uri.getQueryParameter("port");
        String pin = uri.getQueryParameter("pin");

        if (host == null || pin == null) {
            showToast(getString(R.string.qr_invalid_code));
            return;
        }

        int port = NvHTTP.DEFAULT_HTTP_PORT;
        if (portStr != null) {
            try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        showToast(getString(R.string.qr_pairing));
        int finalPort = port;
        new Thread(() -> {
            String message = null;
            boolean success = false;
            ComputerDetails pairedComputer = null;

            try {
                stopComputerUpdates(true);

                // Add the computer first
                ComputerDetails addDetails = new ComputerDetails();
                addDetails.manualAddress = new ComputerDetails.AddressTuple(host, finalPort);
                boolean added = managerBinder != null && managerBinder.addComputerBlocking(addDetails);
                if (!added) {
                    message = getString(R.string.addpc_fail);
                } else {
                    // Find the added computer to get its httpsPort and serverCert
                    ComputerDetails computer = findComputerByAddress(host);
                    if (computer == null) {
                        message = getString(R.string.addpc_fail);
                    } else {
                        NvHTTP httpConn = new NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort,
                            managerBinder.getUniqueId(),
                            clientName,
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(this)
                        );

                        if (httpConn.getPairState() == PairState.PAIRED) {
                            success = true;
                            pairedComputer = computer;
                        } else {
                            PairingManager pm = httpConn.getPairingManager();
                            PairResult result = pm.pair(httpConn.getServerInfo(true), pin);
                            switch (result.state) {
                                case PIN_WRONG:
                                    message = getString(R.string.pair_incorrect_pin);
                                    break;
                                case FAILED:
                                    message = getString(R.string.pair_fail);
                                    break;
                                case ALREADY_IN_PROGRESS:
                                    message = getString(R.string.pair_already_in_progress);
                                    break;
                                case PAIRED:
                                    success = true;
                                    pairedComputer = computer;
                                    managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                                    getSharedPreferences("pair_name_map", MODE_PRIVATE)
                                        .edit()
                                        .putString(computer.uuid, result.pairName)
                                        .apply();
                                    managerBinder.invalidateStateForComputer(computer.uuid);
                                    break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                message = e.getMessage();
            }

            String finalMessage = message;
            boolean finalSuccess = success;
            ComputerDetails finalComputer = pairedComputer;
            runOnUiThread(() -> {
                if (finalMessage != null) {
                    showToast(finalMessage);
                }
                if (finalSuccess) {
                    showToast(getString(R.string.qr_pair_success));
                    if (finalComputer != null) {
                        doAppList(finalComputer, true, false);
                    } else {
                        startComputerUpdates();
                    }
                } else {
                    startComputerUpdates();
                }
            });
        }).start();
    }

    private ComputerDetails findComputerByAddress(String host) {
        if (managerBinder == null) return null;
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject obj = (ComputerObject) pcGridAdapter.getItem(i);
            if (PcGridAdapter.isAddComputerCard(obj)) continue;
            ComputerDetails d = obj.details;
            if (d.manualAddress != null && host.equals(d.manualAddress.address)) return d;
            if (d.localAddress != null && host.equals(d.localAddress.address)) return d;
            if (d.remoteAddress != null && host.equals(d.remoteAddress.address)) return d;
        }
        return null;
    }

    private void doWakeOnLan(ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            showToast(getString(R.string.wol_pc_online));
            return;
        }
        if (computer.macAddress == null) {
            showToast(getString(R.string.wol_no_mac));
            return;
        }

        new Thread(() -> {
            String message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = getString(R.string.wol_waking_msg);
            } catch (IOException e) {
                message = getString(R.string.wol_fail);
            }
            String finalMessage = message;
            runOnUiThread(() -> showToast(finalMessage));
        }).start();
    }

    private void doUnpair(ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.error_pc_offline));
            return;
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        showToast(getString(R.string.unpairing));
        new Thread(() -> {
            String message;
            try {
                NvHTTP httpConn = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        managerBinder.getUniqueId(),
                        clientName,
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(this)
                );

                if (httpConn.getPairState() == PairState.PAIRED) {
                    httpConn.unpair();
                    message = httpConn.getPairState() == PairState.NOT_PAIRED
                            ? getString(R.string.unpair_success)
                            : getString(R.string.unpair_fail);
                } else {
                    message = getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
            } catch (InterruptedException e) {
                message = getString(R.string.error_interrupted);
            }

            String finalMessage = message;
            runOnUiThread(() -> showToast(finalMessage));
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            showToast(getString(R.string.error_pc_offline));
            return;
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);

        if (computer.activeAddress != null) {
            i.putExtra(AppView.SELECTED_ADDRESS_EXTRA, computer.activeAddress.address);
            i.putExtra(AppView.SELECTED_PORT_EXTRA, computer.activeAddress.port);
        }

        startActivity(i);
    }

    private void doSecondaryScreenStream(ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.error_pc_offline));
            return;
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        computer.useVdd = true;
        quickStartStreamWithScreenMode(computer, null, true, 1);
    }

    // Quick Start Stream Methods

    private void handleAvatarClick(ComputerDetails computer, View itemView) {
        quickStartStream(computer, itemView, false);
    }

    private void quickStartStream(ComputerDetails computer, View itemView, boolean isSecondaryScreen) {
        if (computer.state != ComputerDetails.State.ONLINE || computer.pairState != PairState.PAIRED) {
            if (itemView != null) {
                openContextMenu(itemView);
            }
            return;
        }

        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        new Thread(() -> {
            NvApp targetApp = computer.runningGameId != 0
                    ? getNvAppById(computer.runningGameId, computer.uuid)
                    : getFirstAppFromCache(computer.uuid);

            if (targetApp == null) {
                fallbackToAppList(computer);
                return;
            }

            ComputerDetails targetComputer = prepareComputerWithAddress(computer);
            if (targetComputer == null) {
                runOnUiThread(() -> showToast(getString(R.string.error_pc_offline)));
                return;
            }

            if (targetComputer.hasMultipleLanAddresses()) {
                runOnUiThread(() -> showAddressSelectionDialog(targetComputer));
                return;
            }

            NvApp appToStart = targetApp;
            runOnUiThread(() -> ServerHelper.doStart(this, appToStart, targetComputer, managerBinder));
        }).start();
    }

    private void quickStartStreamWithScreenMode(ComputerDetails computer, View itemView, boolean isSecondaryScreen, int screenMode) {
        if (computer.state != ComputerDetails.State.ONLINE || computer.pairState != PairState.PAIRED) {
            if (itemView != null) {
                openContextMenu(itemView);
            }
            return;
        }

        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        new Thread(() -> {
            NvApp targetApp = computer.runningGameId != 0
                    ? getNvAppById(computer.runningGameId, computer.uuid)
                    : getFirstAppFromCache(computer.uuid);

            if (targetApp == null) {
                fallbackToAppList(computer);
                return;
            }

            ComputerDetails targetComputer = prepareComputerWithAddress(computer);
            if (targetComputer == null) {
                runOnUiThread(() -> showToast(getString(R.string.error_pc_offline)));
                return;
            }

            if (targetComputer.hasMultipleLanAddresses()) {
                runOnUiThread(() -> showAddressSelectionDialog(targetComputer));
                return;
            }

            runOnUiThread(() -> {
                Intent intent = ServerHelper.createStartIntent(this, targetApp, targetComputer, managerBinder, null, screenMode);
                startActivity(intent);
            });
        }).start();
    }

    private ComputerDetails prepareComputerWithAddress(ComputerDetails computer) {
        ComputerDetails temp = new ComputerDetails(computer);
        if (temp.activeAddress == null) {
            ComputerDetails.AddressTuple best = temp.selectBestAddress();
            if (best == null) return null;
            temp.activeAddress = best;
        }
        return temp;
    }

    private void fallbackToAppList(ComputerDetails computer) {
        runOnUiThread(() -> {
            ComputerDetails target = prepareComputerWithAddress(computer);
            doAppList(target != null ? target : computer, false, false);
        });
    }

    // App Cache Methods

    private List<NvApp> getAppListFromCache(String uuid) {
        try {
            String rawAppList = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuid));
            return rawAppList.isEmpty() ? null : NvHTTP.getAppListByReader(new StringReader(rawAppList));
        } catch (IOException | XmlPullParserException e) {
            LimeLog.warning("Failed to read app list from cache: " + e.getMessage());
            return null;
        }
    }

    private NvApp getFirstAppFromCache(String uuid) {
        List<NvApp> appList = getAppListFromCache(uuid);
        return (appList != null && !appList.isEmpty()) ? appList.get(0) : null;
    }

    private NvApp getNvAppById(int appId, String uuid) {
        List<NvApp> appList = getAppListFromCache(uuid);
        if (appList != null) {
            for (NvApp app : appList) {
                if (app.getAppId() == appId) {
                    new AppCacheManager(this).saveAppInfo(uuid, app);
                    return app;
                }
            }
        }
        return new AppCacheManager(this).getAppInfo(uuid, appId);
    }

    private void restoreLastSession() {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        ComputerDetails target = null;
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);
            if (computer.details.state == ComputerDetails.State.ONLINE
                    && computer.details.pairState == PairState.PAIRED
                    && computer.details.runningGameId != 0) {
                target = computer.details;
                break;
            }
        }

        if (target == null) {
            showToast(getString(R.string.no_online_computer_with_running_game));
            return;
        }

        NvApp app = getNvAppById(target.runningGameId, target.uuid);
        if (app == null) {
            app = new NvApp("app", target.runningGameId, false);
        }

        showToast(getString(R.string.restoring_session, target.name));
        ServerHelper.doStart(this, app, target, managerBinder);
    }

    private void showAddressSelectionDialog(ComputerDetails computer) {
        AddressSelectionDialog dialog = new AddressSelectionDialog(this, computer, address -> {
            ComputerDetails temp = new ComputerDetails(computer);
            temp.activeAddress = address;
            doAppList(temp, false, false);
        });
        dialog.show();
    }

    // Context Menu

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = getContextMenuPosition(menuInfo, v);
        if (position < 0) return;

        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        if (PcGridAdapter.isAddComputerCard(computer)) return;

        setupContextMenuHeader(menu, computer);
        addContextMenuItems(menu, computer);
    }

    private int getContextMenuPosition(ContextMenuInfo menuInfo, View v) {
        if (menuInfo instanceof AdapterContextMenuInfo) {
            return ((AdapterContextMenuInfo) menuInfo).position;
        }
        if (v != null && v.getTag() instanceof Integer) {
            return (Integer) v.getTag();
        }
        return -1;
    }

    private void setupContextMenuHeader(ContextMenu menu, ComputerObject computer) {
        menu.clearHeader();
        String status;
        switch (computer.details.state) {
            case ONLINE:
                status = getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                status = getString(R.string.pcview_menu_header_offline);
                break;
            default:
                status = getString(R.string.pcview_menu_header_unknown);
        }
        menu.setHeaderTitle(computer.details.name + " - " + status);
    }

    private void addContextMenuItems(ContextMenu menu, ComputerObject computer) {
        ComputerDetails details = computer.details;

        if (details.state == ComputerDetails.State.OFFLINE || details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, R.string.pcview_menu_send_wol);
        } else if (details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, R.string.pcview_menu_pair_pc);
            if (details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, R.string.pcview_menu_eol);
            }
        } else {
            if (details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, R.string.applist_menu_resume);
                menu.add(Menu.NONE, QUIT_ID, 2, R.string.applist_menu_quit);
            }
            if (details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, R.string.pcview_menu_eol);
            }
            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, R.string.pcview_menu_app_list);
            menu.add(Menu.NONE, SECONDARY_SCREEN_ID, 5, R.string.pcview_menu_secondary_screen);
            menu.add(Menu.NONE, SLEEP_ID, 8, R.string.send_sleep_command);
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, R.string.pcview_menu_test_network);
        menu.add(Menu.NONE, IPERF3_TEST_ID, 6, R.string.network_bandwidth_test);
        menu.add(Menu.NONE, DELETE_ID, 6, R.string.pcview_menu_delete_pc);
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7, R.string.pcview_menu_details);

        // 添加IPv6开关选项，根据当前状态显示不同操作
        if (details.ipv6Disabled) {
            menu.add(Menu.NONE, DISABLE_IPV6_ID, 8, R.string.pcview_menu_enable_ipv6);
        } else {
            menu.add(Menu.NONE, DISABLE_IPV6_ID, 8, R.string.pcview_menu_disable_ipv6);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        startComputerUpdates();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = getContextMenuPosition(item.getMenuInfo(), null);
        if (position < 0) return super.onContextItemSelected(item);

        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        if (PcGridAdapter.isAddComputerCard(computer)) return super.onContextItemSelected(item);

        return handleContextMenuAction(item.getItemId(), computer);
    }

    private boolean handleContextMenuAction(int itemId, ComputerObject computer) {
        ComputerDetails details = computer.details;

        switch (itemId) {
            case PAIR_ID:
                doPair(details);
                return true;
            case UNPAIR_ID:
                doUnpair(details);
                return true;
            case WOL_ID:
                doWakeOnLan(details);
                return true;
            case DELETE_ID:
                handleDeletePc(details);
                return true;
            case FULL_APP_LIST_ID:
                doAppList(details, false, true);
                return true;
            case RESUME_ID:
                handleResume(details);
                return true;
            case QUIT_ID:
                handleQuit(details);
                return true;
            case SLEEP_ID:
                handleSleep(details);
                return true;
            case VIEW_DETAILS_ID:
                Dialog.displayDetailsDialog(this, getString(R.string.title_details), details.toString(), false);
                return true;
            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(this);
                return true;
            case IPERF3_TEST_ID:
                handleIperf3Test(details);
                return true;
            case SECONDARY_SCREEN_ID:
                handleSecondaryScreen(details);
                return true;
            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(this);
                return true;
            case DISABLE_IPV6_ID:
                handleToggleIpv6Disabled(details);
                return true;
            default:
                return false;
        }
    }

    private void handleDeletePc(ComputerDetails details) {
        if (ActivityManager.isUserAMonkey()) {
            LimeLog.info("Ignoring delete PC request from monkey");
            return;
        }
        UiHelper.displayDeletePcConfirmationDialog(this, details, () -> {
            if (managerBinder == null) {
                showToast(getString(R.string.error_manager_not_running));
                return;
            }
            removeComputer(details);
        }, null);
    }

    private void handleResume(ComputerDetails details) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }
        NvApp app = getNvAppById(details.runningGameId, details.uuid);
        if (app == null) {
            app = new NvApp("app", details.runningGameId, false);
        }
        ServerHelper.doStart(this, app, details, managerBinder);
    }

    private void handleQuit(ComputerDetails details) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }
        UiHelper.displayQuitConfirmationDialog(this,
                () -> ServerHelper.doQuit(this, details, new NvApp("app", 0, false), managerBinder, null),
                null);
    }

    private void handleSleep(ComputerDetails details) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }
        ServerHelper.pcSleep(this, details, managerBinder, null);
    }

    private void handleIperf3Test(ComputerDetails details) {
        try {
            String ip = ServerHelper.getCurrentAddressFromComputer(details).address;
            new Iperf3Tester(this, ip).show();
        } catch (IOException e) {
            showToast(getString(R.string.unable_to_get_pc_address, e.getMessage()));
        }
    }

    private void handleSecondaryScreen(ComputerDetails details) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }
        doSecondaryScreenStream(details);
    }

    private void handleToggleIpv6Disabled(ComputerDetails details) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running));
            return;
        }

        // 切换IPv6禁用状态
        details.ipv6Disabled = !details.ipv6Disabled;

        // 如果禁用了IPv6，清空所有IPv6相关地址
        if (details.ipv6Disabled) {
            details.ipv6Address = null;

            // 如果activeAddress是IPv6，清空它
            if (ComputerDetails.isIpv6Address(details.activeAddress)) {
                details.activeAddress = null;
            }

            // 从availableAddresses中移除所有IPv6地址
            if (details.availableAddresses != null) {
                details.availableAddresses.removeIf(ComputerDetails::isIpv6Address);
            }
        }

        // 更新数据库
        managerBinder.updateComputer(details);

        // 显示Toast提示用户当前状态
        if (details.ipv6Disabled) {
            showToast(getString(R.string.pcview_ipv6_disabled));
        } else {
            showToast(getString(R.string.pcview_ipv6_enabled));
        }
        // 刷新列表
        startComputerUpdates();
    }

    // Adapter Fragment Callbacks

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(View view) {
        receiveAdapterView(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void receiveAdapterView(View view) {
        if (!(view instanceof AbsListView)) return;

        AbsListView listView = (AbsListView) view;
        pcListView = listView;
        listView.setSelector(android.R.color.transparent);
        listView.setAdapter(pcGridAdapter);

        setupListAnimation(listView);
        handleFirstLoadAnimation(listView);
        setupListItemClick(listView);
        setupGridColumnWidth(view);
        setupEmptyAreaLongPress(listView);

        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
    }

    private void setupListAnimation(AbsListView listView) {
        LayoutAnimationController controller = new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.pc_grid_item_sort), 0.12f);
        controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
        listView.setLayoutAnimation(controller);
    }

    private void handleFirstLoadAnimation(AbsListView listView) {
        if (!isFirstLoad) return;

        listView.setAlpha(0f);
        listView.postDelayed(() -> {
            if (isFirstLoad && pcListView != null && pcListView.getAlpha() == 0f) {
                pcGridAdapter.notifyDataSetChanged();
                pcListView.scheduleLayoutAnimation();
                pcListView.animate().alpha(1f).setDuration(200).start();
                isFirstLoad = false;
            }
        }, 250);
    }

    private void setupListItemClick(AbsListView listView) {
        listView.setOnItemClickListener((parent, view, pos, id) -> {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);

            if (PcGridAdapter.isAddComputerCard(computer)) {
                showAddComputerDialog();
                return;
            }

            if (computer.details.state == ComputerDetails.State.UNKNOWN
                    || computer.details.state == ComputerDetails.State.OFFLINE) {
                openContextMenu(view);
            } else if (computer.details.pairState != PairState.PAIRED) {
                doPair(computer.details);
            } else if (computer.details.hasMultipleLanAddresses()) {
                showAddressSelectionDialog(computer.details);
            } else {
                ComputerDetails temp = prepareComputerWithAddress(computer.details);
                if (temp != null) {
                    doAppList(temp, false, false);
                } else {
                    showToast(getString(R.string.error_pc_offline));
                }
            }
        });
    }

    private void setupGridColumnWidth(View view) {
        if (view instanceof GridView) {
            calculateDynamicColumnWidth((GridView) view);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupEmptyAreaLongPress(AbsListView listView) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (listView.pointToPosition((int) e.getX(), (int) e.getY()) == android.widget.AdapterView.INVALID_POSITION) {
                    saveImageWithPermissionCheck();
                }
            }
        });

        listView.setOnTouchListener((v, event) -> {
            if (listView.pointToPosition((int) event.getX(), (int) event.getY()) == android.widget.AdapterView.INVALID_POSITION) {
                detector.onTouchEvent(event);
            }
            return false;
        });
    }

    private void calculateDynamicColumnWidth(GridView gridView) {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int availableWidth = screenWidth - gridView.getPaddingStart() - gridView.getPaddingEnd();
        int spacingPx = (int) (15f * density);
        int minColumnPx = (int) (180f * density);

        int numColumns = Math.max(1, (availableWidth + spacingPx) / (minColumnPx + spacingPx));
        int columnWidth = (availableWidth - (numColumns - 1) * spacingPx) / numColumns;

        gridView.setColumnWidth(columnWidth);
    }

    // Dialogs

    private void showEasyTierControlDialog() {
        if (easyTierController != null) {
            easyTierController.showControlDialog();
        }
    }

    private void showAboutDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);

        TextView versionText = dialogView.findViewById(R.id.text_version);
        versionText.setText(getVersionInfo());

        TextView appNameText = dialogView.findViewById(R.id.text_app_name);
        appNameText.setText(getAppName());

        TextView descriptionText = dialogView.findViewById(R.id.text_description);
        descriptionText.setText(R.string.about_dialog_description);

        // PcView 继承自 Activity 而非 AppCompatActivity，在 Android 6 等设备上使用
        // R.style.AppDialogStyle（父主题为 Theme.AppCompat.Light.Dialog.Alert）会触发
        // "You need to use a Theme.AppCompat theme" 类崩溃，故此处使用系统 Material 对话框主题。
        int dialogTheme = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme)
                .setView(dialogView)
                .setPositiveButton(R.string.about_dialog_github, (d, w) -> openUrl("https://github.com/qiin2333/moonlight-vplus"))
                .setNeutralButton(R.string.about_dialog_qq, (d, w) -> joinQQGroup("LlbLDIF_YolaM4HZyLx0xAXXo04ZmoBM"))
                .setNegativeButton(R.string.about_dialog_close, (d, w) -> d.dismiss())
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute);
        }
        dialog.show();
    }

    @SuppressLint("DefaultLocale")
    private String getVersionInfo() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return String.format("Version %s (Build %d)", info.versionName, info.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return "Version Unknown";
        }
    }

    private String getAppName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.applicationInfo != null) {
                return info.applicationInfo.loadLabel(getPackageManager()).toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "Moonlight V+";
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public void joinQQGroup(String key) {
        try {
            Intent intent = new Intent();
            intent.setData(Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + key));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    // VPN Permission

    @Override
    public void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE);
        } else {
            onActivityResult(VPN_PERMISSION_REQUEST_CODE, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Handle ZXing scan result
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult != null) {
            if (scanResult.getContents() != null) {
                handleQrPairResult(scanResult.getContents().trim());
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST_CODE && easyTierController != null) {
            easyTierController.handleVpnPermissionResult(resultCode);
        } else if (requestCode == UpdateManager.INSTALL_PERMISSION_REQUEST_CODE) {
            UpdateManager.onInstallPermissionResult(this);
        }
    }

    // Utility

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // Inner Classes

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}