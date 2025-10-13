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

import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.List;

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
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.LruCache;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
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
                    Toast.makeText(this, "需要存储权限才能保存图片，请在设置中授予权限", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "正在重新下载图片，请稍候...", Toast.LENGTH_SHORT).show();
                
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
                            runOnUiThread(() -> Toast.makeText(PcView.this, "图片下载失败，请重试", Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(PcView.this, "图片下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
                return;
            } else {
                Toast.makeText(this, "图片未加载，请稍后再试", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        // 如果缓存中有图片，直接保存
        saveBitmapToFile(bitmap);
    }
    
    private void saveBitmapToFile(Bitmap bitmap) {
        if (bitmap == null) {
            Toast.makeText(this, "图片无效", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "涩图成功保存到了系统目录(Picture/setu)", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "涩图保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "配置保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                pcGridAdapter.updateLayoutWithPreferences(this, configPrefs);
                
                Toast.makeText(this, String.format("已应用场景%d配置：%dx%d@%dFPS %.2fMbps %s HDR %s",
                    sceneNumber, width, height, fps, bitrate / 1000.0, videoFormat, enableHdr ? "On" : "Off"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "场景"+sceneNumber+"未配置", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(PcView.this, "配置应用失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void showSaveConfirmationDialog(int sceneNumber) {
        new AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle("保存到场景" + sceneNumber)
            .setMessage("是否覆盖当前配置？")
            .setPositiveButton("保存", (dialog, which) -> saveCurrentConfiguration(sceneNumber))
            .setNegativeButton("取消", null)
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
            
            Toast.makeText(this, "场景" + sceneNumber + "保存成功", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, "配置保存失败", Toast.LENGTH_SHORT).show();
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
            menu.add(Menu.NONE, SLEEP_ID, 8, "发送睡眠指令");
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, IPERF3_TEST_ID, 6, "网络带宽测试 (iPerf3)");
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
                throw new RuntimeException(e);
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
                    Toast.makeText(this, "无法获取PC地址: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
     * 默认选择第一个在线且有运行游戏的主机
     */
    private void restoreLastSession() {
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // 查找第一个在线的主机
        ComputerDetails onlineComputer = null;
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);
            if (computer.details.state == ComputerDetails.State.ONLINE && 
                computer.details.pairState == PairState.PAIRED) {
                onlineComputer = computer.details;
                break;
            }
        }

        if (onlineComputer == null) {
            Toast.makeText(this, "no online computer", Toast.LENGTH_SHORT).show();
            return;
        }

        if (onlineComputer.runningGameId == 0) {
            Toast.makeText(this, "Host " + onlineComputer.name + " has no running game", Toast.LENGTH_SHORT).show();
            return;
        }

        // 恢复会话
        NvApp actualApp = getNvAppById(onlineComputer.runningGameId, onlineComputer.uuid);
        if (actualApp != null) {
            Toast.makeText(this, "Restoring session: " + onlineComputer.name, Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, actualApp, onlineComputer, managerBinder);
        } else {
            // 使用基本的NvApp对象作为备用
            Toast.makeText(this, "Restoring session: " + onlineComputer.name, Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, new NvApp("app", onlineComputer.runningGameId, false), onlineComputer, managerBinder);
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
                Toast.makeText(PcView.this, "Please wait " + remainingSeconds + "s", Toast.LENGTH_SHORT).show()
            );
            return;
        }
        
        // Check daily limit
        if (!canRefreshToday()) {
            runOnUiThread(() -> 
                Toast.makeText(PcView.this, "Daily limit reached (5/5). Try again tomorrow!", Toast.LENGTH_LONG).show()
            );
            return;
        }
        
        lastShakeTime = currentTime;
        
        // Increment counter and get remaining
        incrementRefreshCount();
        int remaining = getRemainingRefreshCount();
        
        runOnUiThread(() -> {
            String message = "Refreshing... (" + remaining + " left today)";
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
                        String message = "Background refreshed! (" + remaining + " left today)";
                        Toast.makeText(PcView.this, message, Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(PcView.this, "Refresh failed, please retry", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(PcView.this, "Refresh failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
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
}
