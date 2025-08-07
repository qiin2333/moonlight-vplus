package com.limelight;

import java.io.FileInputStream;
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
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;
import com.limelight.utils.AnalyticsManager;

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
import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import jp.wasabeef.glide.transformations.BlurTransformation;
import jp.wasabeef.glide.transformations.ColorFilterTransformation;

import android.app.AlertDialog;
import android.content.SharedPreferences;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
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
                if (!Environment.isExternalStorageManager()) {
                    saveImage();
                }
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

        settingsButton.setOnClickListener(v -> startActivity(new Intent(PcView.this, StreamSettings.class)));
        addComputerButton.setOnClickListener(v -> {
            Intent i = new Intent(PcView.this, AddComputerManually.class);
            startActivity(i);
        });
        helpButton.setOnClickListener(v -> {
//                HelpLauncher.launchSetupGuide(PcView.this);
            joinQQGroup("LlbLDIF_YolaM4HZyLx0xAXXo04ZmoBM");
        });

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
        Bitmap bitmap = bitmapLruCache.get(getBackgroundImageUrl());
        if (bitmap == null) return;

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
            Toast.makeText(this, "涩图下载失败", Toast.LENGTH_SHORT).show();
        }
        bitmapLruCache.evictAll();
    }

    private boolean copyFile(File source, File dest) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (fis != null) {
                    fis.close();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

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
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

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
            NvHTTP httpConn;
            String message;
            boolean success = false;
            try {
                // Stop updates and wait while pairing
                stopComputerUpdates(true);

                httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), clientName, computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    // Don't display any toast, but open the app list
                    message = null;
                    success = true;
                } else {
                    final String pinStr = PairingManager.generatePinString();

                    // Spin the dialog off in a thread because it blocks
                    Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                            getResources().getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                    getResources().getString(R.string.pair_pairing_help), false);

                    PairingManager pm = httpConn.getPairingManager();

                    PairResult pairResult = pm.pair(httpConn.getServerInfo(true), pinStr);
                    PairState pairState = pairResult.state;
                    String pairName = pairResult.pairName;

                    if (pairState == PairState.PIN_WRONG) {
                        message = getResources().getString(R.string.pair_incorrect_pin);
                    } else if (pairState == PairState.FAILED) {
                        if (computer.runningGameId != 0) {
                            message = getResources().getString(R.string.pair_pc_ingame);
                        } else {
                            message = getResources().getString(R.string.pair_fail);
                        }
                    } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                        message = getResources().getString(R.string.pair_already_in_progress);
                    } else if (pairState == PairState.PAIRED) {
                        // Just navigate to the app view without displaying a toast
                        message = null;
                        success = true;

                        // Pin this certificate for later HTTPS use
                        managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                        // 保存配对名，不使用puterDatabaseManager，使用SharedPreferences保存uuid与pairName的映射关系
                        SharedPreferences sharedPreferences = getSharedPreferences("pair_name_map", MODE_PRIVATE);
                        sharedPreferences.edit().putString(computer.uuid, pairName).apply();

                        // managerBinder.getComputer(computer.uuid).pairName = pairName;
                        // Invalidate reachability information after pairing to force
                        // a refresh before reading pair state again
                        managerBinder.invalidateStateForComputer(computer.uuid);
                    } else {
                        // Should be no other values
                        message = null;
                    }
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                message = e.getMessage();
            }

            Dialog.closeDialogs();

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
            NvHTTP httpConn;
            String message;
            try {
                httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), clientName, computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    httpConn.unpair();
                    if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                        message = getResources().getString(R.string.unpair_success);
                    }
                    else {
                        message = getResources().getString(R.string.unpair_fail);
                    }
                }
                else {
                    message = getResources().getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
                e.printStackTrace();
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
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
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

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
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

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            default:
                return super.onContextItemSelected(item);
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
    public void receiveAbsListView(AbsListView listView) {
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
                doAppList(computer.details, false, false);
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
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
