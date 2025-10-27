package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.net.UnknownHostException;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerService;
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
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.Iperf3Tester;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;
import com.limelight.utils.AnalyticsManager;
import com.limelight.utils.UpdateManager;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.CacheHelper;
import com.limelight.dialogs.AddressSelectionDialog;

import org.json.JSONArray;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bumptech.glide.Glide;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import jp.wasabeef.glide.transformations.BlurTransformation;
import jp.wasabeef.glide.transformations.ColorFilterTransformation;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.hardware.SensorManager;

import com.squareup.seismic.ShakeDetector;
import com.easytier.jni.EasyTierManager;

public class PcView extends Activity implements AdapterFragmentCallbacks, ShakeDetector.Listener {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private int selectedPosition = -1;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    
    private ShakeDetector shakeDetector;
    private long lastShakeTime = 0;
    private static final long SHAKE_DEBOUNCE_INTERVAL = 3000; // 3 seconds debounce
    private static final int MAX_DAILY_REFRESH = 7; // Maximum 7 refreshes per day
    private static final String REFRESH_PREF_NAME = "RefreshLimit";
    private static final String REFRESH_COUNT_KEY = "refresh_count";
    private static final String REFRESH_DATE_KEY = "refresh_date";
    
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int SLEEP_ID = 12;
    private final static int IPERF3_TEST_ID = 13;

    public String clientName;
    private LruCache<String, Bitmap> bitmapLruCache;
    private AnalyticsManager analyticsManager;
    private EasyTierManager easyTierManager;
    private static final int VPN_PERMISSION_REQUEST_CODE = 101;
    private static final String EASYTIER_PREFS = "easytier_preferences";
    private static final String KEY_TOML_CONFIG = "toml_config_string";

    // 添加场景配置相关常量
    private static final String SCENE_PREF_NAME = "SceneConfigs";
    private static final String SCENE_KEY_PREFIX = "scene_";

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        clientName = Settings.Global.getString(this.getContentResolver(), "device_name");

        ImageView imageView = findViewById(R.id.pcBackgroundImage);
        String imageUrl = getBackgroundImageUrl();

        // set background image
        new Thread(() -> {
            try {
                final Bitmap bitmap = Glide.with(PcView.this)
                        .asBitmap()
                        .load(imageUrl)
                        .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)
                        .submit()
                        .get();
                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap);
                    runOnUiThread(() -> Glide.with(PcView.this)
                            .load(bitmap)
                            .apply(RequestOptions.bitmapTransform(new BlurTransformation(2, 3)))
                            .transform(new ColorFilterTransformation(Color.argb(120, 0, 0, 0)))
                            .into(imageView));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // 设置长按监听
        imageView.setOnLongClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11及以上需要检查MANAGE_EXTERNAL_STORAGE权限
                if (Environment.isExternalStorageManager()) {
                    saveImage();
                } else {
                    // 请求权限
                    Toast.makeText(this, getResources().getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show();
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                }
            } else {
                // Android 10及以下直接保存
                saveImage();
            }
            return true;
        });

        if (getWindow().getDecorView().getRootView() != null) {
            initSceneButtons();
        }

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);
        ImageButton restoreSessionButton = findViewById(R.id.restoreSessionButton);

        ImageButton easyTierButton = findViewById(R.id.easyTierControlButton);
        if (easyTierButton != null) {
            easyTierButton.setOnClickListener(v -> showEasyTierControlDialog());
        }

        settingsButton.setOnClickListener(v -> startActivity(new Intent(PcView.this, StreamSettings.class)));
        addComputerButton.setOnClickListener(v -> {
            Intent i = new Intent(PcView.this, AddComputerManually.class);
            startActivity(i);
        });
        helpButton.setOnClickListener(v -> {
//                HelpLauncher.launchSetupGuide(PcView.this);
            joinQQGroup("LlbLDIF_YolaM4HZyLx0xAXXo04ZmoBM");
        });
        restoreSessionButton.setOnClickListener(v -> restoreLastSession());

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    private @NonNull String getBackgroundImageUrl() {
        // 获取用户自定义的图片API地址
        String customUrl = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("background_image_url", null);
            
        // 如果没有自定义地址，使用默认地址
        if (customUrl == null || customUrl.isEmpty()) {
            int deviceRotation = this.getWindowManager().getDefaultDisplay().getRotation();
            return deviceRotation == Configuration.ORIENTATION_PORTRAIT ? 
                "https://img-api.pipw.top" : 
                "https://img-api.pipw.top/?phone=true";
        }
        
        // 使用自定义地址
        return customUrl;
    }

    private void saveImage() {
        // 先尝试从缓存获取
        Bitmap bitmap = bitmapLruCache.get(getBackgroundImageUrl());
        
        if (bitmap == null) {
            // 如果缓存中没有，尝试从ImageView获取
            ImageView imageView = findViewById(R.id.pcBackgroundImage);
            if (imageView != null && imageView.getDrawable() != null) {
                Toast.makeText(this, getResources().getString(R.string.downloading_image_please_wait), Toast.LENGTH_SHORT).show();
                
                // 在后台线程重新下载原图
                new Thread(() -> {
                    try {
                        String imageUrl = getBackgroundImageUrl();
                        Bitmap downloadedBitmap = Glide.with(PcView.this)
                                .asBitmap()
                                .load(imageUrl)
                                .submit()
                                .get();
                        
                        if (downloadedBitmap != null) {
                            // 重新放入缓存
                            bitmapLruCache.put(imageUrl, downloadedBitmap);
                            // 保存图片
                            runOnUiThread(() -> saveBitmapToFile(downloadedBitmap));
                        } else {
                            runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.image_download_failed_retry), Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.image_download_failed_with_error, e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                }).start();
                return;
            } else {
                Toast.makeText(this, getResources().getString(R.string.image_not_loaded_please_retry), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        // 如果缓存中有图片，直接保存
        saveBitmapToFile(bitmap);
    }
    
    private void saveBitmapToFile(Bitmap bitmap) {
        if (bitmap == null) {
            Toast.makeText(this, getResources().getString(R.string.image_invalid), Toast.LENGTH_SHORT).show();
            return;
        }

        // 图片保存路径，这里保存到外部存储的Pictures目录下，可根据需求调整
        String root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
        File myDir = new File(root + "/setu");
        myDir.mkdirs();

        // 文件名设置
        String fileName = "pipw-" + System.currentTimeMillis() + ".png";
        File file = new File(myDir, fileName);

        try {
            FileOutputStream outputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            outputStream.close();
            refreshSystemPic(PcView.this, file);
            Toast.makeText(this, getResources().getString(R.string.image_saved_successfully), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getResources().getString(R.string.image_save_failed_with_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
        // 不再清空所有缓存，只移除当前图片（可选）
        // bitmapLruCache.remove(getBackgroundImageUrl());
    }

    // 刷新图库的方法
    private void refreshSystemPic(Context context, File file) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        intent.setData(contentUri);
        context.sendBroadcast(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initEasyTierManager();

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create cache for images
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        bitmapLruCache = new LruCache<>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                // 计算每个Bitmap占用的内存大小（以KB为单位）
                return value.getByteCount() / 1024;
            }
        };

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(() -> completeOnCreate());
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
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void initSceneButtons() {
        try {
            int[] sceneButtonIds = {
                R.id.scene1Btn, R.id.scene2Btn, 
                R.id.scene3Btn, R.id.scene4Btn, R.id.scene5Btn
            };

            for (int i = 0; i < sceneButtonIds.length; i++) {
                final int sceneNumber = i + 1;
                ImageButton btn = findViewById(sceneButtonIds[i]);
                
                if (btn == null) {
                    LimeLog.warning("Scene button "+ sceneNumber +" (ID: "+getResources().getResourceName(sceneButtonIds[i])+") not found!");
                    continue;
                }

                btn.setOnClickListener(v -> applySceneConfiguration(sceneNumber));
                btn.setOnLongClickListener(v -> {
                    showSaveConfirmationDialog(sceneNumber);
                    return true;
                });
            }
        } catch (Exception e) {
            LimeLog.warning("Scene init failed: "+ e);
            e.printStackTrace();
        }
    }

    @SuppressLint("DefaultLocale")
    private void applySceneConfiguration(int sceneNumber) {
        try {
            SharedPreferences prefs = getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE);
            String configJson = prefs.getString(SCENE_KEY_PREFIX + sceneNumber, null);
            
            if (configJson != null) {
                JSONObject config = new JSONObject(configJson);
                // 解析配置参数
                int width = config.optInt("width", 1920);
                int height = config.optInt("height", 1080);
                int fps = config.optInt("fps", 60);
                int bitrate = config.optInt("bitrate", 10000);
                String videoFormat = config.optString("videoFormat", "auto");
                boolean enableHdr = config.optBoolean("enableHdr", false);
                boolean enablePerfOverlay = config.optBoolean("enablePerfOverlay", false);
                
                // 使用副本配置进行操作
                PreferenceConfiguration configPrefs = PreferenceConfiguration.readPreferences(this).copy();
                configPrefs.width = width;
                configPrefs.height = height;
                configPrefs.fps = fps;
                configPrefs.bitrate = bitrate;
                configPrefs.videoFormat = PreferenceConfiguration.FormatOption.valueOf(videoFormat);
                configPrefs.enableHdr = enableHdr;
                configPrefs.enablePerfOverlay = enablePerfOverlay;
                
                // 保存并检查结果
                if (!configPrefs.writePreferences(this)) {
                    Toast.makeText(this, getResources().getString(R.string.config_save_failed), Toast.LENGTH_SHORT).show();
                    return;
                }
                
                pcGridAdapter.updateLayoutWithPreferences(this, configPrefs);
                
                Toast.makeText(this, getResources().getString(R.string.scene_config_applied,
                    sceneNumber, width, height, fps, bitrate / 1000.0, videoFormat, enableHdr ? "On" : "Off"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getResources().getString(R.string.scene_not_configured, sceneNumber), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.config_apply_failed), Toast.LENGTH_SHORT).show());
        }
    }

    private void showSaveConfirmationDialog(int sceneNumber) {
        new AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(getResources().getString(R.string.save_to_scene, sceneNumber))
            .setMessage(getResources().getString(R.string.overwrite_current_config))
            .setPositiveButton(getResources().getString(R.string.dialog_button_save), (dialog, which) -> saveCurrentConfiguration(sceneNumber))
            .setNegativeButton(getResources().getString(R.string.dialog_button_cancel), null)
            .show();
    }

    private void saveCurrentConfiguration(int sceneNumber) {
        try {
            PreferenceConfiguration configPrefs = PreferenceConfiguration.readPreferences(this);
            JSONObject config = new JSONObject();
            config.put("width", configPrefs.width);
            config.put("height", configPrefs.height);
            config.put("fps", configPrefs.fps);
            config.put("bitrate", configPrefs.bitrate);
            config.put("videoFormat", configPrefs.videoFormat.toString());
            config.put("enableHdr", configPrefs.enableHdr);
            config.put("enablePerfOverlay", configPrefs.enablePerfOverlay);
            
            // 保存到SharedPreferences
            getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(SCENE_KEY_PREFIX + sceneNumber, config.toString())
                .apply();
            
            Toast.makeText(this, getResources().getString(R.string.scene_saved_successfully, sceneNumber), Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, getResources().getString(R.string.config_save_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // 初始化统计分析管理器
        analyticsManager = AnalyticsManager.getInstance(this);
        analyticsManager.logAppLaunch();

        // 检查应用更新
        UpdateManager.checkForUpdatesOnStartup(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));
        
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        shakeDetector = new ShakeDetector(this);
        shakeDetector.setSensitivity(ShakeDetector.SENSITIVITY_MEDIUM); // 设置中等灵敏度

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(details -> {
                if (!freezeUpdates) {
                    PcView.this.runOnUiThread(() -> updateComputer(details));

                    // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                    if (details.pairState == PairState.PAIRED) {
                        shortcutHelper.createAppViewShortcutForOnlineHost(details);
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (easyTierManager != null) {
            easyTierManager.stop();
        }

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
        
        // 清理统计分析资源
        if (analyticsManager != null) {
            analyticsManager.cleanup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
        
        // 开始记录使用时长
        if (analyticsManager != null) {
            analyticsManager.startUsageTracking();
        }
        
        if (shakeDetector != null) {
            try {
                shakeDetector.start((SensorManager) getSystemService(SENSOR_SERVICE));
            } catch (SecurityException e) {
                // Android 12+ 需要 HIGH_SAMPLING_RATE_SENSORS 权限
                LimeLog.warning("shakeDetector start failed: " + e.getMessage());
                // 不显示错误，静默失败即可
            } catch (Exception e) {
                LimeLog.warning("shakeDetector start failed: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
        
        // 停止记录使用时长
        if (analyticsManager != null) {
            analyticsManager.stopUsageTracking();
        }
        
        if (shakeDetector != null) {
            try {
                shakeDetector.stop();
            } catch (Exception e) {
                LimeLog.warning("shakeDetector stop failed: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = -1;
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
        } else if (v != null && v.getTag() instanceof Integer) {
            position = (Integer) v.getTag();
        } else if (selectedPosition >= 0) {
            position = selectedPosition;
        }

        if (position < 0) return;

        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
            menu.add(Menu.NONE, SLEEP_ID, 8, getResources().getString(R.string.send_sleep_command));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, IPERF3_TEST_ID, 6, getResources().getString(R.string.network_bandwidth_test));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message = null;
            boolean success = false;
            
            try {
                // Stop updates and wait while pairing
                stopComputerUpdates(true);

                NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, 
                    managerBinder.getUniqueId(), 
                    clientName, 
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(PcView.this)
                );
                
                if (httpConn.getPairState() == PairState.PAIRED) {
                    // Already paired, open the app list directly
                    success = true;
                } else {
                    // Generate PIN and show pairing dialog
                    final String pinStr = PairingManager.generatePinString();
                    Dialog.displayDialog(
                        PcView.this, 
                        getResources().getString(R.string.pair_pairing_title),
                        getResources().getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                            getResources().getString(R.string.pair_pairing_help), 
                        false
                    );

                    PairingManager pm = httpConn.getPairingManager();
                    PairResult pairResult = pm.pair(httpConn.getServerInfo(true), pinStr);
                    PairState pairState = pairResult.state;

                    switch (pairState) {
                        case PIN_WRONG:
                            message = getResources().getString(R.string.pair_incorrect_pin);
                            break;
                        case FAILED:
                            message = computer.runningGameId != 0 
                                ? getResources().getString(R.string.pair_pc_ingame)
                                : getResources().getString(R.string.pair_fail);
                            break;
                        case ALREADY_IN_PROGRESS:
                            message = getResources().getString(R.string.pair_already_in_progress);
                            break;
                        case PAIRED:
                            success = true;
                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                            
                            // Save pair name using SharedPreferences
                            SharedPreferences sharedPreferences = getSharedPreferences("pair_name_map", MODE_PRIVATE);
                            sharedPreferences.edit().putString(computer.uuid, pairResult.pairName).apply();
                            
                            // Invalidate reachability information after pairing
                            managerBinder.invalidateStateForComputer(computer.uuid);
                            break;
                    }
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                message = getResources().getString(R.string.pair_fail);
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                message = e.getMessage();
            } finally {
                Dialog.closeDialogs();
            }

            final String toastMessage = message;
            final boolean toastSuccess = success;
            runOnUiThread(() -> {
                if (toastMessage != null) {
                    Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                }

                if (toastSuccess) {
                    // Open the app list after a successful pairing attempt
                    doAppList(computer, true, false);
                } else {
                    // Start polling again if we're still in the foreground
                    startComputerUpdates();
                }
            });
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            String message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = getResources().getString(R.string.wol_waking_msg);
            } catch (IOException e) {
                message = getResources().getString(R.string.wol_fail);
            }

            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), clientName, computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                
                PairState pairState = httpConn.getPairState();
                if (pairState == PairState.PAIRED) {
                    httpConn.unpair();
                    message = httpConn.getPairState() == PairState.NOT_PAIRED 
                            ? getResources().getString(R.string.unpair_success)
                            : getResources().getString(R.string.unpair_fail);
                } else {
                    message = getResources().getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
                e.printStackTrace();
            } catch (InterruptedException e) {
                // Thread was interrupted during unpair
                message = getResources().getString(R.string.error_interrupted);
            }

            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        
        // 如果activeAddress与默认地址不同，说明用户选择了特定地址，需要传递这个信息
        if (computer.activeAddress != null) {
            i.putExtra(AppView.SELECTED_ADDRESS_EXTRA, computer.activeAddress.address);
            i.putExtra(AppView.SELECTED_PORT_EXTRA, computer.activeAddress.port);
        }
        
        startActivity(i);
    }

    /**
     * 显示地址选择对话框
     */
    private void showAddressSelectionDialog(ComputerDetails computer) {
        AddressSelectionDialog dialog = new AddressSelectionDialog(this, computer, address -> {
            // 使用选中的地址创建临时ComputerDetails对象
            ComputerDetails tempComputer = new ComputerDetails(computer);
            tempComputer.activeAddress = address;

            // 使用选中的地址进入应用列表
            doAppList(tempComputer, false, false);
        });
        
        dialog.show();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = -1;
        ContextMenuInfo menuInfo = item.getMenuInfo();
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
        }

        if (position < 0) {
            position = this.selectedPosition;
        }

        if (position < 0) return super.onContextItemSelected(item);

        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, () -> {
                    if (managerBinder == null) {
                        Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                        return;
                    }
                    removeComputer(computer.details);
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // 尝试获取完整的NvApp对象（包括cmdList）
                NvApp actualApp = getNvAppById(computer.details.runningGameId, computer.details.uuid);
                if (actualApp != null) {
                    ServerHelper.doStart(this, actualApp, computer.details, managerBinder);
                } else {
                    // 如果找不到完整的应用信息，使用基本的NvApp对象作为备用
                    ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                }
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, () -> ServerHelper.doQuit(PcView.this, computer.details,
                        new NvApp("app", 0, false), managerBinder, null), null);
                return true;
            
            case SLEEP_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.pcSleep(PcView.this, computer.details, managerBinder, null);
                return true;
            
            case VIEW_DETAILS_ID:
                Dialog.displayDetailsDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case IPERF3_TEST_ID:
                try {
                    // 1. 直接在UI线程获取地址对象 (因为此操作不耗时)
                    ComputerDetails.AddressTuple addressTuple = ServerHelper.getCurrentAddressFromComputer(computer.details);

                    // 2. 从对象中提取IP地址字符串
                    String currentIp = addressTuple.address;

                    // 3. 直接创建并显示对话框
                    new Iperf3Tester(PcView.this, currentIp).show();

                } catch (IOException e) {
                    // 捕获因 activeAddress 为 null 导致的异常
                    e.printStackTrace();
                    Toast.makeText(this, getResources().getString(R.string.unable_to_get_pc_address, e.getMessage()), Toast.LENGTH_LONG).show();
                }
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    /**
     * 一键恢复上一次会话
     * 持续查找主机直到找到有运行游戏的主机为止
     */
    private void restoreLastSession() {
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // 持续查找有运行游戏的在线主机
        ComputerDetails targetComputer = null;
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);
            if (computer.details.state == ComputerDetails.State.ONLINE && 
                computer.details.pairState == PairState.PAIRED &&
                computer.details.runningGameId != 0) {
                targetComputer = computer.details;
                break; // 找到有运行游戏的主机就停止查找
            }
        }

        if (targetComputer == null) {
            Toast.makeText(this, getResources().getString(R.string.no_online_computer_with_running_game), Toast.LENGTH_SHORT).show();
            return;
        }

        // 恢复会话
        NvApp actualApp = getNvAppById(targetComputer.runningGameId, targetComputer.uuid);
        if (actualApp != null) {
            Toast.makeText(this, getResources().getString(R.string.restoring_session, targetComputer.name), Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, actualApp, targetComputer, managerBinder);
        } else {
            // 使用基本的NvApp对象作为备用
            Toast.makeText(this, getResources().getString(R.string.restoring_session, targetComputer.name), Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, new NvApp("app", targetComputer.runningGameId, false), targetComputer, managerBinder);
        }
    }

    /**
     * 根据应用ID获取完整的NvApp对象（包括cmdList）
     * @param appId 应用ID
     * @param uuidString PC的UUID
     * @return 完整的NvApp对象，如果找不到则返回null
     */
    private NvApp getNvAppById(int appId, String uuidString) {
        try {
            // 首先尝试从缓存的应用列表中获取
            String rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            if (!rawAppList.isEmpty()) {
                List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(rawAppList));
                for (NvApp app : applist) {
                    if (app.getAppId() == appId) {
                        // 保存这个应用信息到SharedPreferences，供下次使用
                        AppCacheManager cacheManager = new AppCacheManager(this);
                        cacheManager.saveAppInfo(uuidString, app);
                        return app;
                    }
                }
            }
            
            // 如果在应用列表中找不到，尝试从SharedPreferences获取
            AppCacheManager cacheManager = new AppCacheManager(this);
            return cacheManager.getAppInfo(uuidString, appId);
        } catch (IOException | XmlPullParserException e) {
            // 如果读取缓存失败，尝试从SharedPreferences获取
            e.printStackTrace();
            AppCacheManager cacheManager = new AppCacheManager(this);
            return cacheManager.getAppInfo(uuidString, appId);
        }
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(View view) {
        // Generalized interface implementation
        receiveAdapterView(view);
    }

    public void receiveAdapterView(View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView) {
            // Update selectionAnimator's RecyclerView and Adapter references
        }
        else if (view instanceof AbsListView) {
            AbsListView listView = (AbsListView) view;
            listView.setAdapter(pcGridAdapter);
            listView.setOnItemClickListener((arg0, arg1, pos, id) -> {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    // 检查是否有多个可用地址
                    if (computer.details.hasMultipleAddresses()) {
                        showAddressSelectionDialog(computer.details);
                    } else {
                        doAppList(computer.details, false, false);
                    }
                }
            });
            UiHelper.applyStatusBarPadding(listView);
            registerForContextMenu(listView);
        }
    }

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

    @Override
    public void hearShake() {
        long currentTime = System.currentTimeMillis();
        
        // Debounce: Check if enough time has passed since last shake
        if (currentTime - lastShakeTime < SHAKE_DEBOUNCE_INTERVAL) {
            long remainingSeconds = (SHAKE_DEBOUNCE_INTERVAL - (currentTime - lastShakeTime)) / 1000;
            runOnUiThread(() -> 
                Toast.makeText(PcView.this, getResources().getString(R.string.please_wait_seconds, remainingSeconds), Toast.LENGTH_SHORT).show()
            );
            return;
        }
        
        // Check daily limit
        if (!canRefreshToday()) {
            runOnUiThread(() -> 
                Toast.makeText(PcView.this, getResources().getString(R.string.daily_limit_reached), Toast.LENGTH_LONG).show()
            );
            return;
        }
        
        lastShakeTime = currentTime;
        
        // Increment counter and get remaining
        incrementRefreshCount();
        int remaining = getRemainingRefreshCount();
        
        runOnUiThread(() -> {
            String message = getResources().getString(R.string.refreshing_with_remaining, remaining);
            Toast.makeText(PcView.this, message, Toast.LENGTH_SHORT).show();
            refreshBackgroundImage();
        });
    }
    
    /**
     * Get today's date string (YYYY-MM-DD)
     */
    private String getTodayDateString() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }
    
    /**
     * Check if user can refresh (within daily limit)
     * @return true if can refresh, false if limit reached
     */
    private boolean canRefreshToday() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = prefs.getInt(REFRESH_COUNT_KEY, 0);
        
        // New day, reset counter
        if (!today.equals(savedDate)) {
            prefs.edit()
                .putString(REFRESH_DATE_KEY, today)
                .putInt(REFRESH_COUNT_KEY, 0)
                .apply();
            return true;
        }
        
        // Check if within limit
        return count < MAX_DAILY_REFRESH;
    }
    
    /**
     * Get remaining refresh count for today
     */
    private int getRemainingRefreshCount() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = prefs.getInt(REFRESH_COUNT_KEY, 0);
        
        // New day
        if (!today.equals(savedDate)) {
            return MAX_DAILY_REFRESH;
        }
        
        return Math.max(0, MAX_DAILY_REFRESH - count);
    }
    
    /**
     * Increment refresh count
     */
    private void incrementRefreshCount() {
        SharedPreferences prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE);
        String today = getTodayDateString();
        String savedDate = prefs.getString(REFRESH_DATE_KEY, "");
        int count = prefs.getInt(REFRESH_COUNT_KEY, 0);
        
        // Ensure date is today
        if (!today.equals(savedDate)) {
            count = 0;
        }
        
        prefs.edit()
            .putString(REFRESH_DATE_KEY, today)
            .putInt(REFRESH_COUNT_KEY, count + 1)
            .apply();
    }
    
    /**
     * Refresh background image
     */
    private void refreshBackgroundImage() {
        ImageView imageView = findViewById(R.id.pcBackgroundImage);
        if (imageView == null) return;
        
        String imageUrl = getBackgroundImageUrl();
        
        bitmapLruCache.remove(imageUrl);
        
        // Reload the image in a background thread
        new Thread(() -> {
            try {
                final Bitmap bitmap = Glide.with(PcView.this)
                        .asBitmap()
                        .load(imageUrl)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .submit()
                        .get();
                        
                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap);
                    runOnUiThread(() -> {
                        Glide.with(PcView.this)
                                .load(bitmap)
                                .apply(RequestOptions.bitmapTransform(new BlurTransformation(2, 3)))
                                .transform(new ColorFilterTransformation(Color.argb(120, 0, 0, 0)))
                                .into(imageView);
                        int remaining = getRemainingRefreshCount();
                        String message = getResources().getString(R.string.background_refreshed_with_remaining, remaining);
                        Toast.makeText(PcView.this, message, Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.refresh_failed_please_retry), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.refresh_failed_with_error, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /****************
     *
     * 发起添加群流程。群号：第四串流基地(460965258) 的 key 为： JfhuyTDZFsHrOXaWEEX6YGH9FHh3xGzR
     * 调用 joinQQGroup(JfhuyTDZFsHrOXaWEEX6YGH9FHh3xGzR) 即可发起手Q客户端申请加群 第四串流基地(460965258)
     *
     * @param key 由官网生成的key
     * @return 返回true表示呼起手Q成功，返回false表示呼起失败
     ******************/
    public boolean joinQQGroup(String key) {
        Intent intent = new Intent();
        intent.setData(Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + key));
        // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // 未安装手Q或安装的版本不支持
            return false;
        }
    }

    /**
     * 获取 EasyTier 的 TOML 配置字符串。
     * 优先从 SharedPreferences 读取，如果不存在，则返回一个硬编码的默认配置。
     * @return TOML 格式的配置字符串
     */
    private String getEasyTierConfig() {
        // 1. 获取 SharedPreferences 实例
        SharedPreferences prefs = getSharedPreferences(EASYTIER_PREFS, MODE_PRIVATE);

        // 2. 定义默认配置
        String defaultConfig = "instance_name = \"Default\"\n" +
                "hostname = \"moonlight-V+\"\n" +
                "ipv4 = \"10.0.0.1/24\"\n" +
                "dhcp = false\n" +
                "listeners = [\"tcp://0.0.0.0:11010\", \"udp://0.0.0.0:11010\", \"wg://0.0.0.0:11011\"]\n" +
                "rpc_portal = \"0.0.0.0:0\"\n" +
                "\n" +
                "[network_identity]\n" +
                "network_name = \"easytier\"\n" +
                "network_secret = \"\"\n" +
                "\n" +
                "[[peer]]\n" +
                "uri = \"tcp://public.easytier.top:11010\"\n" +
                "\n" +
                "[flags]\n";

        // 3. 尝试读取已保存的配置，如果不存在，则使用 defaultConfig
        return prefs.getString(KEY_TOML_CONFIG, defaultConfig);
    }

    /** 主数据容器，存放所有要在对话框显示的信息 */
    private static class EasyTierDisplayInfo {
        String hostname;
        String version;
        String virtualIp;
        String publicIp;
        String natType;
        List<FinalPeerInfo> finalPeerList = new ArrayList<>();
    }

    /**
     * 存储最终整合好的、用于显示的对等节点信息
     */
    private static class FinalPeerInfo {
        final String hostname, virtualIp, connectionDetails, latency, traffic, version, natType, instId;
        final boolean isDirectConnection;
        final boolean isInSameSubnet;
        final int routeCost;
        final long nextHopPeerId, peerId;

        FinalPeerInfo(String hostname, String virtualIp, boolean isDirectConnection, boolean isInSameSubnet, String connectionDetails, String latency, String traffic, String version, String natType, int routeCost, long nextHopPeerId, long peerId, String instId) {
            this.hostname = hostname;
            this.virtualIp = virtualIp;
            this.isDirectConnection = isDirectConnection;
            this.isInSameSubnet = isInSameSubnet;
            this.connectionDetails = connectionDetails;
            this.latency = latency;
            this.traffic = traffic;
            this.version = version;
            this.natType = natType;
            this.routeCost = routeCost;
            this.nextHopPeerId = nextHopPeerId;
            this.peerId = peerId;
            this.instId = instId;
        }
    }

    /**
     * 存储从 'routes' 数组解析出的中间数据
     */
    private static class RouteData {
        final long peerId, nextHopPeerId;
        final String hostname, virtualIp, version, natType, instId;
        final int pathLatency, cost;

        RouteData(long peerId, String hostname, String virtualIp, long nextHopPeerId, int pathLatency, int cost, String version, String natType, String instId) {
            this.peerId = peerId;
            this.hostname = hostname;
            this.virtualIp = virtualIp;
            this.nextHopPeerId = nextHopPeerId;
            this.pathLatency = pathLatency;
            this.cost = cost;
            this.version = version;
            this.natType = natType;
            this.instId = instId;
        }
    }

    /**
     * 存储从 'peers' 数组解析出的直连信息
     */
    private static class PeerConnectionData {
        final long peerId, latencyUs, rxBytes, txBytes;
        final String physicalAddr;

        PeerConnectionData(long peerId, String physicalAddr, long latencyUs, long rxBytes, long txBytes) {
            this.peerId = peerId;
            this.physicalAddr = physicalAddr;
            this.latencyUs = latencyUs;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }

    private EasyTierDisplayInfo parseNetworkInfoForDialog(String jsonString, String instanceName) {
        EasyTierDisplayInfo displayInfo = new EasyTierDisplayInfo();
        try {
            JSONObject root = new JSONObject(jsonString);
            JSONObject instance = root.getJSONObject("map").getJSONObject(instanceName);

            // --- A. 解析本机信息  ---
            JSONObject myNode = instance.getJSONObject("my_node_info");
            String myIp = null;
            int myPrefix = 0;
            displayInfo.hostname = myNode.getString("hostname");
            displayInfo.version = myNode.getString("version");
            JSONObject virtualIpv4 = myNode.optJSONObject("virtual_ipv4");
            if (virtualIpv4 != null) {
                myPrefix = virtualIpv4.getInt("network_length");
                myIp = ipFromInt(virtualIpv4.getJSONObject("address").getInt("addr"));
            }
            displayInfo.virtualIp = (virtualIpv4 != null) ? (ipFromInt(virtualIpv4.getJSONObject("address").getInt("addr")) + "/" + virtualIpv4.getInt("network_length")) : "获取中...";
            JSONObject stunInfo = myNode.getJSONObject("stun_info");
            // 循环遍历 stun_info.public_ip 数组，它可能包含 IPv4 和 IPv6
            StringBuilder ipBuilder = new StringBuilder();
            JSONArray publicIps = stunInfo.optJSONArray("public_ip");
            if (publicIps != null && publicIps.length() > 0) {
                for (int i = 0; i < publicIps.length(); i++) {
                    if (i > 0) {
                        ipBuilder.append("\n"); // 从第二个 IP 开始，在前面加换行符
                    }
                    ipBuilder.append(publicIps.getString(i));
                }
                displayInfo.publicIp = ipBuilder.toString();
            } else {
                displayInfo.publicIp
                        = "N/A";
            }
            displayInfo.natType = parseNatType(stunInfo.getInt("udp_nat_type"));

            // --- B. 分别解析 routes 和 peers 到 Map 中 (核心逻辑) ---
            Map<Long, RouteData> routesMap = parseRoutesToJavaMap(instance.getJSONArray("routes"));
            Map<Long, PeerConnectionData> peersMap = parsePeersToJavaMap(instance.getJSONArray("peers"));

            // --- C. 遍历 routes，结合 peers 信息，构建最终的 FinalPeerInfo 列表 ---
            List<FinalPeerInfo> finalPeerList = new ArrayList<>();
            for (RouteData route : routesMap.values()) {
                // --- 在这里进行网段检查 ---
                boolean inSameSubnet = true; // 默认为 true
                if (myIp != null && myPrefix > 0 && !route.virtualIp.equals("无")) {
                    inSameSubnet = isInSameSubnet(myIp, route.virtualIp, myPrefix);
                }

                PeerConnectionData peerConn = peersMap.get(route.peerId);

                if (peerConn != null) {
                    // 情况1: 找到了直接连接 (信息来自 route 和 peerConn)
                    finalPeerList.add(new FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            true, // isDirectConnection
                            inSameSubnet,
                            peerConn.physicalAddr,
                            (peerConn.latencyUs / 1000) + " ms",
                            formatBytes(peerConn.rxBytes) + " / " + formatBytes(peerConn.txBytes),
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ));
                } else {
                    // 情况2: 未找到直接连接，是中继路由 (信息仅来自 route)
                    RouteData nextHop = routesMap.get(route.nextHopPeerId);
                    String nextHopHostname = (nextHop != null) ? nextHop.hostname : "未知";
                    finalPeerList.add(new FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            false,
                            inSameSubnet,
                            "通过 " + nextHopHostname,
                            route.pathLatency + " ms (路径)",
                            "N/A", // traffic
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ));
                }
            }

            // --- D. 排序并存入最终的显示对象 ---
            finalPeerList.sort(Comparator.comparing(p -> p.hostname));
            displayInfo.finalPeerList = finalPeerList;

        } catch (Exception e) {
            LimeLog.warning("PcView_Parser解析JSON失败:" + e);
            displayInfo.hostname = "解析错误";
            displayInfo.version = e.getMessage();
            return displayInfo;
        }
        return displayInfo;
    }

    private Map<Long, RouteData> parseRoutesToJavaMap(JSONArray routesJson) throws Exception {
        Map<Long, RouteData> map = new HashMap<>();
        for (int i = 0; i < routesJson.length(); i++) {
            JSONObject route = routesJson.getJSONObject(i);
            long peerId = route.getLong("peer_id");
            JSONObject ipv4AddrJson = route.optJSONObject("ipv4_addr");
            String virtualIp = (ipv4AddrJson != null) ? ipFromInt(ipv4AddrJson.getJSONObject("address").getInt("addr")) : "无";

            map.put(peerId, new RouteData(
                    peerId,
                    route.getString("hostname"),
                    virtualIp,
                    route.getLong("next_hop_peer_id"),
                    route.getInt("path_latency"),
                    route.getInt("cost"),
                    route.getString("version"),
                    parseNatType(route.getJSONObject("stun_info").getInt("udp_nat_type")),
                    route.getString("inst_id")
            ));
        }
        return map;
    }

    private Map<Long, PeerConnectionData> parsePeersToJavaMap(JSONArray peersJson) throws Exception {
        Map<Long, PeerConnectionData> map = new HashMap<>();
        for (int i = 0; i < peersJson.length(); i++) {
            JSONObject peer = peersJson.getJSONObject(i);
            JSONArray conns = peer.getJSONArray("conns");
            if (conns.length() > 0) {
                JSONObject conn = conns.getJSONObject(0);
                long peerId = conn.getLong("peer_id");
                map.put(peerId, new PeerConnectionData(
                        peerId,
                        conn.getJSONObject("tunnel").getJSONObject("remote_addr").getString("url"),
                        conn.getJSONObject("stats").getLong("latency_us"),
                        conn.getJSONObject("stats").getLong("rx_bytes"),
                        conn.getJSONObject("stats").getLong("tx_bytes")
                ));
            }
        }
        return map;
    }

    private static String ipFromInt(int addr) {
        return ((addr >>> 24) & 0xFF) + "." + ((addr >>> 16) & 0xFF) + "." + ((addr >>> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }


    /**
     * 将 NAT 类型代码解析为人类可读的字符串。
     * @param typeCode 从 JSON 获取的 NAT 类型代码。
     * @return 详细的 NAT 类型描述。
     */
    private static String parseNatType(int typeCode) {
        switch (typeCode) {
            case 0: return "Unknown (未知类型)";
            case 1: return "Open Internet (开放互联网)";
            case 2: return "No PAT (无端口转换)";
            case 3: return "Full Cone (完全锥形)";
            case 4: return "Restricted Cone (限制锥形)";
            case 5: return "Port Restricted (端口限制锥形)";
            case 6: return "Symmetric (对称型)";
            case 7: return "Symmetric UDP Firewall (对称UDP防火墙)";
            case 8: return "Symmetric Easy Inc (对称型-端口递增)";
            case 9: return "Symmetric Easy Dec (对称型-端口递减)";
            default: return "Other Type (" + typeCode + ")";
        }
    }

    /**
     * 显示集成了状态显示和配置编辑的 EasyTier 控制面板。
     */
    private void showEasyTierControlDialog() {
        if (easyTierManager == null) {
            Toast.makeText(this, "EasyTier Manager尚未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_easytier_panel, null);
        builder.setView(dialogView);
        builder.setTitle("EasyTier 控制面板");

        // --- 找到所有 UI 控件 ---
        // 标签按钮
        final Button tabStatusButton = dialogView.findViewById(R.id.tab_button_status);
        final Button tabConfigButton = dialogView.findViewById(R.id.tab_button_config);
        // 标签内容
        final ScrollView statusContent = dialogView.findViewById(R.id.tab_content_status);
        final ScrollView configContent = dialogView.findViewById(R.id.tab_content_config);
        // 状态页内的控件
        final ImageButton refreshButton = dialogView.findViewById(R.id.button_refresh_status);
        final LinearLayout statusContainer = dialogView.findViewById(R.id.panel_status_container);
        // 配置页内的控件
        final EditText editNetworkName = dialogView.findViewById(R.id.edit_network_name);
        final EditText editNetworkSecret = dialogView.findViewById(R.id.edit_network_secret);
        final EditText editIpv4 = dialogView.findViewById(R.id.edit_ipv4);
        final EditText editListeners = dialogView.findViewById(R.id.edit_listeners);
        final EditText editPeers = dialogView.findViewById(R.id.edit_peers);
        final Switch flagUseSmoltcp = dialogView.findViewById(R.id.flag_use_smoltcp);
        final Switch flagLatencyFirst = dialogView.findViewById(R.id.flag_latency_first);
        final Switch flagDisableP2p = dialogView.findViewById(R.id.flag_disable_p2p);
        final Switch flagPrivateMode = dialogView.findViewById(R.id.flag_private_mode);
        final Switch flagEnableIpv6 = dialogView.findViewById(R.id.flag_enable_ipv6);
        final Switch flagEnableKcpProxy = dialogView.findViewById(R.id.flag_enable_kcp_proxy);
        final Switch flagDisableKcpInput = dialogView.findViewById(R.id.flag_disable_kcp_input);
        final Switch flagEnableQuicProxy = dialogView.findViewById(R.id.flag_enable_quic_proxy);
        final Switch flagDisableQuicInput = dialogView.findViewById(R.id.flag_disable_quic_input);
        final Switch flagProxyForwardBySystem = dialogView.findViewById(R.id.flag_proxy_forward_by_system);
        final Switch flagEnableEncryption = dialogView.findViewById(R.id.flag_enable_encryption);
        final Switch flagDisableUdpHolePunching = dialogView.findViewById(R.id.flag_disable_udp_hole_punching);
        final Switch flagDisableSymHolePunching = dialogView.findViewById(R.id.flag_disable_sym_hole_punching);

        // --- 折叠逻辑 ---
        final LinearLayout flagsContainer = dialogView.findViewById(R.id.advanced_flags_container);
        final ImageView flagsArrow = dialogView.findViewById(R.id.advanced_flags_arrow);
        dialogView.findViewById(R.id.advanced_flags_header).setOnClickListener(v -> {
            boolean isVisible = flagsContainer.getVisibility() == View.VISIBLE;
            flagsContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            flagsArrow.setRotation(isVisible ? 0 : 180);
        });

        // --- 标签页切换逻辑 ---
        tabStatusButton.setOnClickListener(v -> {
            statusContent.setVisibility(View.VISIBLE);
            configContent.setVisibility(View.GONE);
            tabStatusButton.setEnabled(false); // 当前选中的标签不可点击
            tabConfigButton.setEnabled(true);
        });
        tabConfigButton.setOnClickListener(v -> {
            statusContent.setVisibility(View.GONE);
            configContent.setVisibility(View.VISIBLE);
            tabStatusButton.setEnabled(true);
            tabConfigButton.setEnabled(false); // 当前选中的标签不可点击
        });
        // 默认选中状态页
        tabStatusButton.performClick();

        // --- 加载配置到配置页 (逻辑不变) ---
        String currentTomlConfig = getEasyTierConfig();
        loadSimpleConfigIntoUi(currentTomlConfig, editNetworkName, editNetworkSecret, editIpv4, editListeners, editPeers,
                flagUseSmoltcp, flagLatencyFirst, flagDisableP2p, flagPrivateMode, flagEnableIpv6,
                flagEnableKcpProxy, flagDisableKcpInput, flagEnableQuicProxy, flagDisableQuicInput,
                flagProxyForwardBySystem, flagEnableEncryption, flagDisableUdpHolePunching, flagDisableSymHolePunching);

        // --- 对话框按钮 ---
        builder.setPositiveButton("启动/停止", null);
        builder.setNeutralButton("保存配置", null);
        builder.setNegativeButton("关闭", null);

        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(dialogInterface -> {
            final Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Button neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            // --- 封装的状态刷新逻辑 ---
            Runnable refreshStatusRunnable = () -> {
                String json = (easyTierManager != null) ? easyTierManager.getLatestNetworkInfoJson() : null;
                updateStatusUi(statusContainer, json);
                boolean isRunningNow = (json != null && !json.isEmpty());
                positiveButton.setText(isRunningNow ? "停止服务" : "启动服务");
            };
            // --- 刷新按钮的点击事件，使用封装的逻辑 ---
            refreshButton.setOnClickListener(v -> {
                refreshStatusRunnable.run();
                Toast.makeText(this, "状态已刷新", Toast.LENGTH_SHORT).show();
            });

            // --- “启动/停止”按钮的逻辑 ---
            positiveButton.setOnClickListener(v -> {
                if (easyTierManager.getLatestNetworkInfoJson() != null) {
                    Toast.makeText(this, "Easytier服务已停止", Toast.LENGTH_SHORT).show();
                    easyTierManager.stop();
                    dialog.dismiss();
                } else {
                    requestVpnPermission();
                    dialog.dismiss();
                }
            });

            // --- “保存配置”按钮的逻辑 ---
            neutralButton.setOnClickListener(v -> {
                // 1. 从 UI 控件构建新的 TOML 字符串
                String newToml = buildSimpleTomlFromUi(editNetworkName, editNetworkSecret, editIpv4, editListeners, editPeers,
                        flagUseSmoltcp, flagLatencyFirst, flagDisableP2p, flagPrivateMode, flagEnableIpv6,
                        flagEnableKcpProxy, flagDisableKcpInput, flagEnableQuicProxy, flagDisableQuicInput,
                        flagProxyForwardBySystem, flagEnableEncryption, flagDisableUdpHolePunching, flagDisableSymHolePunching);

                // 2. 将新的 TOML 字符串保存到 SharedPreferences
                getSharedPreferences(EASYTIER_PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_TOML_CONFIG, newToml)
                        .apply(); // apply() 会在后台异步保存，不会阻塞 UI

                // 3. 重新初始化 EasyTierManager，使其立即加载并持有最新的配置
                //    这样，如果用户接下来点击“启动”，就会使用新配置了。
                initEasyTierManager();

                refreshStatusRunnable.run();

                // 4. 给用户反馈
                Toast.makeText(this, "配置已保存，服务已根据新配置重新初始化。", Toast.LENGTH_LONG).show();
            });

            // 对话框首次显示时，自动刷新一次状态
            refreshButton.performClick();
        });

        dialog.show();
    }

    /**
     * 从 TOML 字符串解析配置并填充到 UI。
     */
    private void loadSimpleConfigIntoUi(String toml,
                                        EditText editNetworkName, EditText editNetworkSecret, EditText editIpv4, EditText editListeners, EditText editPeers,
                                        Switch flagUseSmoltcp, Switch flagLatencyFirst, Switch flagDisableP2p, Switch flagPrivateMode, Switch flagEnableIpv6,
                                        Switch flagEnableKcpProxy, Switch flagDisableKcpInput, Switch flagEnableQuicProxy, Switch flagDisableQuicInput,
                                        Switch flagProxyForwardBySystem, Switch flagEnableEncryption, Switch flagDisableUdpHolePunching, Switch flagDisableSymHolePunching) {
        editNetworkName.setText(extractValue(toml, "network_name", ""));
        editNetworkSecret.setText(extractValue(toml, "network_secret", ""));
        String ipv4Full = extractValue(toml, "ipv4", "");
        if (ipv4Full.contains("/")) {
            editIpv4.setText(ipv4Full.split("/")[0]);
        } else {
            editIpv4.setText(ipv4Full);
        }
        editListeners.setText(extractListAsString(toml, "listeners"));
        editPeers.setText(extractListAsString(toml, "uri"));
        // 加载 Flags
        flagUseSmoltcp.setChecked(Boolean.parseBoolean(extractValue(toml, "use_smoltcp", "false")));
        flagLatencyFirst.setChecked(Boolean.parseBoolean(extractValue(toml, "latency_first", "false")));
        flagDisableP2p.setChecked(Boolean.parseBoolean(extractValue(toml, "disable_p2p", "false")));
        flagPrivateMode.setChecked(Boolean.parseBoolean(extractValue(toml, "private_mode", "false")));
        // 对于 enable_ipv6:
        // 1. EasyTier 核心默认值为 true (启用 IPv6)。
        // 2. 我们从 TOML 中读取 `enable_ipv6` 的值，如果不存在，则使用默认值 "true"。
        boolean isIpv6Enabled = Boolean.parseBoolean(extractValue(toml, "enable_ipv6", "true"));
        // 3. UI 开关“禁用 IPv6”的状态，与 isIpv6Enabled 的值正好相反。
        //    isIpv6Enabled=true  => "禁用"开关应为 false (不勾选)
        //    isIpv6Enabled=false => "禁用"开关应为 true  (勾选)
        flagEnableIpv6.setChecked(!isIpv6Enabled);
        flagEnableKcpProxy.setChecked(Boolean.parseBoolean(extractValue(toml, "enable_kcp_proxy", "false")));
        flagDisableKcpInput.setChecked(Boolean.parseBoolean(extractValue(toml, "disable_kcp_input", "false")));
        flagEnableQuicProxy.setChecked(Boolean.parseBoolean(extractValue(toml, "enable_quic_proxy", "false")));
        flagDisableQuicInput.setChecked(Boolean.parseBoolean(extractValue(toml, "disable_quic_input", "false")));
        flagProxyForwardBySystem.setChecked(Boolean.parseBoolean(extractValue(toml, "proxy_forward_by_system", "false")));
        // 对于 enable_encryption:
        // 1. EasyTier 核心默认值为 true (启用加密)。
        // 2. 我们从 TOML 中读取 `enable_encryption` 的值，如果不存在，则使用默认值 "true"。
        boolean isEncryptionEnabled = Boolean.parseBoolean(extractValue(toml, "enable_encryption", "true"));
        // 3. UI 开关“禁用加密”的状态，与 isEncryptionEnabled 的值正好相反。
        flagEnableEncryption.setChecked(!isEncryptionEnabled);
        flagDisableUdpHolePunching.setChecked(Boolean.parseBoolean(extractValue(toml, "disable_udp_hole_punching", "false")));
        flagDisableSymHolePunching.setChecked(Boolean.parseBoolean(extractValue(toml, "disable_sym_hole_punching", "false")));
    }


    /**
     * 从 UI 控件收集数据并构建TOML 字符串。
     */
    private String buildSimpleTomlFromUi(EditText editNetworkName, EditText editNetworkSecret, EditText editIpv4, EditText editListeners, EditText editPeers,
                                         Switch flagUseSmoltcp, Switch flagLatencyFirst, Switch flagDisableP2p, Switch flagPrivateMode, Switch flagEnableIpv6,
                                         Switch flagEnableKcpProxy, Switch flagDisableKcpInput, Switch flagEnableQuicProxy, Switch flagDisableQuicInput,
                                         Switch flagProxyForwardBySystem, Switch flagEnableEncryption, Switch flagDisableUdpHolePunching, Switch flagDisableSymHolePunching) {
        StringBuilder sb = new StringBuilder();
        sb.append("hostname = \"moonlight-V+\"\n");
        sb.append("instance_name = \"Default\"\n");
        sb.append("dhcp = false\n");
        sb.append("ipv4 = \"").append(editIpv4.getText().toString()).append("/24\"\n");
        appendList(sb, "listeners", editListeners.getText().toString());
        sb.append("rpc_portal = \"0.0.0.0:0\"\n");
        sb.append("\n[network_identity]\n");
        appendString(sb, "network_name", editNetworkName.getText().toString());
        appendString(sb, "network_secret", editNetworkSecret.getText().toString());
        String[] peers = editPeers.getText().toString().split("\n");
        for (String peer : peers) {
            if (!peer.trim().isEmpty()) {
                sb.append("\n[[peer]]\n");
                sb.append("uri = \"").append(peer.trim()).append("\"\n");
            }
        }
        // -- 构建 [flags] 部分 --
        // 只写入与“默认值”不同的标志，以保持配置文件简洁
        sb.append("\n[flags]\n");
        appendFlagIfNotDefault(sb, "use_smoltcp", flagUseSmoltcp.isChecked(), false);
        appendFlagIfNotDefault(sb, "latency_first", flagLatencyFirst.isChecked(), false);
        appendFlagIfNotDefault(sb, "disable_p2p", flagDisableP2p.isChecked(), false);
        appendFlagIfNotDefault(sb, "private_mode", flagPrivateMode.isChecked(), false);
        appendFlagIfNotDefault(sb, "enable_ipv6", !flagEnableIpv6.isChecked(), true);
        appendFlagIfNotDefault(sb, "enable_kcp_proxy", flagEnableKcpProxy.isChecked(), false);
        appendFlagIfNotDefault(sb, "disable_kcp_input", flagDisableKcpInput.isChecked(), false);
        appendFlagIfNotDefault(sb, "enable_quic_proxy", flagEnableQuicProxy.isChecked(), false);
        appendFlagIfNotDefault(sb, "disable_quic_input", flagDisableQuicInput.isChecked(), false);
        appendFlagIfNotDefault(sb, "proxy_forward_by_system", flagProxyForwardBySystem.isChecked(), false);
        appendFlagIfNotDefault(sb, "enable_encryption", !flagEnableEncryption.isChecked(), true);
        appendFlagIfNotDefault(sb, "disable_udp_hole_punching", flagDisableUdpHolePunching.isChecked(), false);
        appendFlagIfNotDefault(sb, "disable_sym_hole_punching", flagDisableSymHolePunching.isChecked(), false);
        return sb.toString();
    }

    private void appendFlagIfNotDefault(StringBuilder sb, String key, boolean value, boolean defaultValue) {
        if (value != defaultValue) {
            sb.append(key).append(" = ").append(value).append("\n");
        }
    }

    /**
     * 动态更新对话框中的状态显示区域。
     */
    private void updateStatusUi(LinearLayout container, String json) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        if (json == null || json.isEmpty()) {
            TextView placeholder = new TextView(this);
            placeholder.setText("服务未运行或正在连接...\n请点击刷新按钮获取最新状态。");
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setPadding(0, 40, 0, 40);
            container.addView(placeholder);
            return;
        }

        EasyTierDisplayInfo displayInfo = parseNetworkInfoForDialog(json, "Default"); // TODO: 实例名

        // --- 动态创建并添加“本机信息”和“STUN信息”的标题和行 ---
        addSectionTitle(container, "本机信息");
        addStatusRow(container, "主机名:", displayInfo.hostname);
        addStatusRow(container, "虚拟 IP:", displayInfo.virtualIp);
        addStatusRow(container, "公网 IP:", displayInfo.publicIp);
        addStatusRow(container, "NAT 类型:", displayInfo.natType);

        // --- 动态创建并添加“对等节点”的标题和详细列表 ---
        addSectionTitle(container, "对等节点 (" + displayInfo.finalPeerList.size() + ")");

        if (displayInfo.finalPeerList.isEmpty()) {
            TextView noPeersText = new TextView(this);
            noPeersText.setText("暂无其他节点");
            noPeersText.setPadding(20, 4, 0, 4);
            container.addView(noPeersText);
        } else {
            for (FinalPeerInfo peer : displayInfo.finalPeerList) {
                // 1. 加载对等节点的模板布局
                View peerView = inflater.inflate(R.layout.dialog_peer_info_item, container, false);

                // 2. 查找模板内的所有 TextView
                TextView hostname = peerView.findViewById(R.id.peer_hostname);
                TextView virtualIp = peerView.findViewById(R.id.peer_value_virtual_ip);
                TextView natType = peerView.findViewById(R.id.peer_value_nat_type);
                TextView connectionLabel = peerView.findViewById(R.id.peer_label_connection);
                TextView connectionValue = peerView.findViewById(R.id.peer_value_connection);
                TextView latency = peerView.findViewById(R.id.peer_value_latency);
                TextView traffic = peerView.findViewById(R.id.peer_value_traffic);

                // 3. 填充主机名和警告
                String title = peer.hostname;
                if (!peer.isInSameSubnet) {
                    title += " (网段不匹配!)";
                    hostname.setTextColor(Color.RED);
                } else if (!peer.isDirectConnection) {
                    title += " (中转)";
                }
                hostname.setText(title);

                // 4. 填充所有其他详细信息
                virtualIp.setText(peer.virtualIp != null ? peer.virtualIp : "N/A");
                natType.setText(peer.natType != null ? peer.natType : "N/A");
                latency.setText(peer.latency != null ? peer.latency : "N/A");
                traffic.setText(peer.traffic != null ? peer.traffic : "N/A");
                String connLabelText = peer.isDirectConnection ? "物理地址:" : "下一跳节点:";
                connectionLabel.setText(connLabelText);
                connectionValue.setText(peer.connectionDetails != null ? peer.connectionDetails : "N/A");

                // 5. 将填充好数据的 Peer 视图添加到主容器中
                container.addView(peerView);
            }
        }
    }

    /**
     * 辅助方法：动态创建一个键值对行并添加到父布局。
     */
    private void addStatusRow(LinearLayout parent, String label, String value) {
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int padding = (int) (8 * getResources().getDisplayMetrics().density);
        rowLayout.setPadding(0, padding, 0, padding);
        rowLayout.setLayoutParams(rowParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                (int) (120 * getResources().getDisplayMetrics().density), // 120dp
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelView.setLayoutParams(labelParams);

        TextView valueView = new TextView(this);
        valueView.setText(value != null ? value : "N/A");
        valueView.setTextIsSelectable(true);

        rowLayout.addView(labelView);
        rowLayout.addView(valueView);
        parent.addView(rowLayout);
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16f);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        // dp to px conversion
        int topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        int bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        params.setMargins(0, topMargin, 0, bottomMargin);
        titleView.setLayoutParams(params);
        parent.addView(titleView);
    }


    //  VPN 权限请求和结果处理逻辑
    /**
     * 检查并请求 VPN 权限。
     */
    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            LimeLog.info("PcView:请求VPN权限...");
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE);
        } else {
            LimeLog.info("PcView:VPN权限已授予，直接启动服务。");
            onActivityResult(VPN_PERMISSION_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                LimeLog.info("PcView:VPN权限已获取，启动EasyTier Manager。");
                // 权限获取成功后，才真正启动 EasyTierManager
                // Manager 内部的 monitorNetworkStatus 会在获取到 IP 后启动 VpnService
                easyTierManager.start();
                Toast.makeText(this, "EasyTier服务正在启动...", Toast.LENGTH_SHORT).show();
            } else {
                LimeLog.warning("PcView:VPN权限被拒绝。");
                Toast.makeText(this, "需要VPN权限才能启动服务。", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 辅助方法：将点分十进制的 IPv4 字符串转换为整数。
     */
    private int ipToInt(String ip) {
        String[] parts = ip.split("\\.");
        return (Integer.parseInt(parts[0]) << 24) |
                (Integer.parseInt(parts[1]) << 16) |
                (Integer.parseInt(parts[2]) << 8) |
                (Integer.parseInt(parts[3]));
    }

    /**
     判断两个 IP 地址是否在同一个子网中。
     */
    private boolean isInSameSubnet(String ip1, String ip2, int prefix) {
        try {
            // 1. 将两个 IP 地址都转换为整数
            int ip1Int = ipToInt(ip1);
            int ip2Int = ipToInt(ip2);

            // 2. 根据前缀长度计算子网掩码
            //    -1 在二进制中是 32 个 1。
            //    左移 (32 - prefix) 位后，高位被 0 填充，低位是 (32 - prefix) 个 0。
            //    例如，prefix = 24，(32-24)=8，-1 << 8 的结果是 11111111 11111111 11111111 00000000
            int mask = -1 << (32 - prefix);

            // 3. 将每个 IP 地址与子网掩码进行“与”运算，得到它们各自的网络地址
            int network1 = ip1Int & mask;
            int network2 = ip2Int & mask;

            // 4. 如果两个网络地址相等，说明它们在同一个子网
            return network1 == network2;
        } catch (Exception e) {
            // 如果 IP 格式不正确导致解析失败，保守地返回 false
            LimeLog.warning("未能检查子网的IP：" + ip1 + ", " + ip2 + e);
            return false;
        }
    }

    /**
     * 辅助方法：从 TOML 字符串中提取单个键的值。
     */
    private String extractValue(String toml, String key, String defaultValue) {
        for (String line : toml.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    return line.split("=", 2)[1].trim().replace("\"", "");
                } catch (Exception e) { /* ignore */ }
            }
        }
        return defaultValue;
    }

    /**
     * 辅助方法：从 TOML 字符串中提取列表类型的值。
     */
    private String extractListAsString(String toml, String key) {
        if ("uri".equals(key)) {
            StringBuilder peers = new StringBuilder();
            for (String line : toml.split("\n")) {
                line = line.trim();
                if (line.startsWith("uri =")) {
                    if (peers.length() > 0) peers.append("\n");
                    peers.append(line.split("=", 2)[1].trim().replace("\"", ""));
                }
            }
            return peers.toString();
        }
        for (String line : toml.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    String list = line.substring(line.indexOf('[') + 1, line.lastIndexOf(']'));
                    return list.replace("\"", "").replace(", ", "\n");
                } catch (Exception e) { /* ignore */ }
            }
        }
        return "";
    }

    /**
     * 辅助方法：向 StringBuilder 中追加一个字符串类型的键值对。
     */
    private void appendString(StringBuilder sb, String key, String value) {
        if (!TextUtils.isEmpty(value)) sb.append(key).append(" = \"").append(value).append("\"\n");
    }

    /**
     * 辅助方法：向 StringBuilder 中追加一个列表类型的键值对。
     */
    private void appendList(StringBuilder sb, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            String[] items = value.split("\n");
            List<String> quotedItems = new ArrayList<>();
            for (String item : items) {
                if (!item.trim().isEmpty()) quotedItems.add("\"" + item.trim() + "\"");
            }
            if (!quotedItems.isEmpty()) sb.append(key).append(" = [").append(TextUtils.join(", ", quotedItems)).append("]\n");
        }
    }

    /**
     * 初始化或重新初始化 EasyTierManager。
     */
    private void initEasyTierManager() {
        String config = getEasyTierConfig();
        String instanceName = "Default";

        // 如果 manager 已经在运行，先停止它
        if (easyTierManager != null && easyTierManager.getLatestNetworkInfoJson() != null) {
            easyTierManager.stop();
        }
        LimeLog.info("使用的easytier配置为：\n" + config);
        easyTierManager = new EasyTierManager(this, instanceName, config);
        LimeLog.info("PcView:" + "EasyTierManager initialized/re-initialized with instance: " + instanceName);
    }

}
