package com.limelight;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.audio.AudioDiagnostics;
import com.limelight.binding.audio.AudioVibrationService;
import com.limelight.binding.audio.MicrophoneManager;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.KeyboardUIController;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.NativeTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.PerformanceInfo;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.services.StreamNotificationService;
import com.limelight.ui.CursorView;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.Dialog;
import com.limelight.utils.PanZoomHandler;
import com.limelight.utils.FullscreenProgressOverlay;
import com.limelight.utils.UiHelper;
import com.limelight.utils.NetHelper;
import com.limelight.utils.AnalyticsManager;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.AppSettingsManager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.util.Rational;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.app.NotificationManager;

import androidx.core.app.NotificationManagerCompat;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;


import com.limelight.services.KeyboardAccessibilityService;

public class Game extends Activity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener, KeyboardAccessibilityService.KeyEventCallback {
    // 这个标志位用于区分事件是来自无障碍服务还是来自UI（如StreamView）
    private boolean isEventFromAccessibilityService = false;

    public static final int REFERENCE_HORIZ_RES = 1280;
    public static final int REFERENCE_VERT_RES = 720;

    ControllerHandler controllerHandler;
    TouchInputHandler touchInputHandler;
    private KeyboardTranslator keyboardTranslator;
    VirtualController virtualController;
    PanZoomHandler panZoomHandler;
    private AudioVibrationService audioVibrationService;

    public interface PerformanceInfoDisplay {
        void display(Map<String, String> performanceAttrs);
    }

    private ControllerManager controllerManager;
    private KeyboardUIController standaloneKeyboardUI;
    private final List<PerformanceInfoDisplay> performanceInfoDisplays = new ArrayList<>();

    MicrophoneManager microphoneManager;

    // 麦克风按钮
    ImageButton micButton;

    PreferenceConfiguration prefConfig;
    OrientationManager orientationManager;
    private SharedPreferences tombstonePrefs;

    NvConnection conn;
    FullscreenProgressOverlay progressOverlay;
    boolean displayedFailureDialog = false;
    boolean connecting = false;
    boolean connected = false;
    private boolean autoEnterPip = false;
    private boolean surfaceCreated = false;
    boolean attemptedConnection = false;
    AnalyticsManager analyticsManager;
    long streamStartTime;           // 串流开始的时间戳
    long accumulatedStreamTime;     // 累计的有效串流时间（排除后台暂停）
    long lastActiveTime;            // 上次活跃的时间戳（用于计算暂停时间）
    boolean isStreamingActive;      // 串流是否处于活跃状态
    private int suppressPipRefCount = 0;
    String pcName;
    String appName;
    NvApp app;
    private float desiredRefreshRate;
    AppSettingsManager appSettingsManager;
    String computerUuid;

    InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    boolean grabbedInput = true;
    boolean cursorVisible = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    StreamView streamView;
    private StreamView externalStreamView; // 外接显示器的StreamView
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;

    // ESC键双击相关变量
    private static final long ESC_DOUBLE_PRESS_INTERVAL = 500; // 500毫秒内按第二次ESC才有效
    private long lastEscPressTime = 0;
    private boolean hasShownEscHint = false;

    NotificationOverlayManager notificationOverlayManager;

    // 性能覆盖层管理器
    private PerformanceOverlayManager performanceOverlayManager;

    // 光标服务管理器
    CursorServiceManager cursorServiceManager;

    // 悬浮球处理器
    FloatBallHandler floatBallHandler;

    // 连接回调处理器
    ConnectionCallbackHandler connectionCallbackHandler;

    /**
     * 获取或创建虚拟键盘控制器
     */
    private KeyboardUIController getOrCreateKeyboardUIController() {
        if (controllerManager != null) {
            KeyboardUIController kUI = controllerManager.getKeyboardUIController();
            if (kUI != null) return kUI;
        }
        if (standaloneKeyboardUI == null) {
            FrameLayout keyboardContainer = findViewById(R.id.virtual_full_keyboard_container);
            if (keyboardContainer != null) {
                standaloneKeyboardUI = new KeyboardUIController(keyboardContainer, new KeyboardUIController.OnKeyboardEventListener() {
                    @Override
                    public void sendKeyEvent(boolean down, short keyCode) {
                        if (controllerManager != null && controllerManager.getElementController() != null) {
                            controllerManager.getElementController().sendKeyEvent(down, keyCode);
                        } else {
                            // 直接调用 Game 类的键盘事件发送方法
                            keyboardEvent(down, keyCode);
                        }
                    }

                    @Override
                    public void rumbleSingleVibrator(short lowFreq, short highFreq, int duration) {
                        if (controllerManager != null && controllerManager.getElementController() != null) {
                            controllerManager.getElementController().rumbleSingleVibrator(lowFreq, highFreq, duration);
                        }
                    }
                }, this);
            }
        }
        return standaloneKeyboardUI;
    }
    /**
     * 切换虚拟全键盘显示状态
     */
    public void toggleVirtualKeyboard() {
        KeyboardUIController kUI = getOrCreateKeyboardUIController();
        if (kUI != null) {
            kUI.toggle();
        }
    }

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;

    String currentHostAddress; // 保存当前连接的IP
    private boolean shouldResumeSession = false;


    // 极端恢复模式开关：进入后台时保持连接不断开
    // 不断开连接模式开关：进入后台时保持连接不断开
    private boolean isExtremeResumeEnabled = false;
    private boolean isChangingResolution = false; // 是否正在改变分辨率
    private AndroidAudioRenderer audioRenderer;

    public enum BackKeyMenuMode {
        GAME_MENU,     // 游戏菜单模式
        CROWN_MODE,    // 王冠模式
        NO_MENU        // 无菜单模式
    }


    private BackKeyMenuMode currentBackKeyMenu = BackKeyMenuMode.GAME_MENU; // 默认为游戏菜单模式

    public void setcurrentBackKeyMenu(BackKeyMenuMode currentBackKeyMenu) {
        this.currentBackKeyMenu = currentBackKeyMenu;
    }

    private boolean areElementsVisible = true; // 用于追踪显隐状态

    /**
     * 切换虚拟控制器（虚拟按键）的可见性。
     */
    public void toggleVirtualControllerVisibility() {
        if (controllerManager != null) {
            areElementsVisible = !areElementsVisible;
            if (areElementsVisible) {
                controllerManager.getElementController().showAllElementsForTest();
                Toast.makeText(this, getString(R.string.toast_elements_visible), Toast.LENGTH_SHORT).show();
            } else {
                controllerManager.getElementController().hideAllElementsForTest();
                Toast.makeText(this, getString(R.string.toast_elements_hidden), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 王冠功能配置切换
     */
    public void toggleBackKeyMenuType() {
        switch (currentBackKeyMenu) {
            case GAME_MENU:
                currentBackKeyMenu = BackKeyMenuMode.CROWN_MODE;
                areElementsVisible = true;
                controllerManager.getElementController().showAllElementsForTest();
                Toast.makeText(this, getString(R.string.toast_back_key_menu_switch_2), Toast.LENGTH_SHORT).show();
                break;
            case CROWN_MODE:
                currentBackKeyMenu = BackKeyMenuMode.GAME_MENU;
                Toast.makeText(this, getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show();
                break;
            case NO_MENU:
                currentBackKeyMenu = BackKeyMenuMode.GAME_MENU;
                break;
        }
    }

    /**
     * 提供对 ControllerManager 的公共访问。
     *
     * @return ControllerManager 实例，如果未初始化则可能为 null。
     */
    public ControllerManager getControllerManager() {
        return this.controllerManager;
    }

    public boolean isTouchOverrideEnabled = false;

    public boolean getisTouchOverrideEnabled() {
        return isTouchOverrideEnabled;
    }

    public void setisTouchOverrideEnabled(boolean isTouchOverrideEnabled) {
        this.isTouchOverrideEnabled = isTouchOverrideEnabled;
    }

    UsbDriverServiceManager usbDriverServiceManager;

    // 性能覆盖层的各项视图由 PerformanceOverlayManager 管理

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_PORT = "Port";
    public static final String EXTRA_HTTPS_PORT = "HttpsPort";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_PAIR_NAME = "PairName";
    public static final String EXTRA_APP_HDR = "HDR";
    public static final String EXTRA_SERVER_CERT = "ServerCert";
    public static final String EXTRA_PC_USEVDD = "usevdd";
    public static final String EXTRA_APP_CMD = "CmdList";
    public static final String EXTRA_DISPLAY_NAME = "DisplayName";
    public static final String EXTRA_SCREEN_COMBINATION_MODE = "Screen combination mode";
    public static final String EXTRA_VDD_SCREEN_COMBINATION_MODE = "VDD screen combination mode";

    ExternalDisplayManager externalDisplayManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 防止上次异常退出导致通知残留，启动时先清理一次
        cancelKeepAliveNotification();

        // 重置分辨率修改标志位，恢复正常状态
        isChangingResolution = false;

        // 这一行告诉 Android 系统，这个窗口需要硬件加速，并且不要在后台进行不必要的缓冲
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        // Hack: allows use keyboard by dpad or controller
        getWindow().getDecorView().findViewById(android.R.id.content).setFocusable(true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
        orientationManager = new OrientationManager(
                this,
                prefConfig.width,
                prefConfig.height,
                prefConfig.rotableScreen,
                prefConfig.onscreenController || prefConfig.onscreenKeyboard,
                () -> externalDisplayManager != null
                        ? externalDisplayManager.getTargetDisplay()
                        : getWindowManager().getDefaultDisplay()
        );
        tombstonePrefs = Game.this.getSharedPreferences("DecoderTombstone", 0);
        // 读取不断开恢复模式配置
        SharedPreferences globalPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        isExtremeResumeEnabled = globalPrefs.getBoolean("checkbox_extreme_resume", false) && globalPrefs.getBoolean("checkbox_resume_stream", false);

        if(globalPrefs.getBoolean("checkbox_resume_stream", false)) {
            checkNotificationPermission();
        }

        // Initialize app settings manager
        appSettingsManager = new AppSettingsManager(this);

        // Save computer UUID for later use
        computerUuid = getIntent().getStringExtra(EXTRA_PC_UUID);

        // 检查是否使用上一次设置并应用（不覆盖全局配置）
        applyLastSettingsToCurrentSession();
        
        // 检查是否有自定义的屏幕组合模式设置（通过 Intent 传递）
        int customScreenMode = getIntent().getIntExtra(EXTRA_SCREEN_COMBINATION_MODE, -1);
        if (customScreenMode != -1) {
            prefConfig.screenCombinationMode = customScreenMode;
        }

        int customVddScreenMode = getIntent().getIntExtra(EXTRA_VDD_SCREEN_COMBINATION_MODE, -1);
        if (customVddScreenMode != -1) {
            prefConfig.vddScreenCombinationMode = customVddScreenMode;
        }

        // Set flat region size for long press jitter elimination.
        NativeTouchContext.INTIAL_ZONE_PIXELS = prefConfig.longPressflatRegionPixels;
        NativeTouchContext.ENABLE_ENHANCED_TOUCH = prefConfig.enableEnhancedTouch;
        if (prefConfig.enhancedTouchOnWhichSide) {
            NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = -1;
        } else {
            NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = 1;
        }
        NativeTouchContext.ENHANCED_TOUCH_ZONE_DIVIDER = prefConfig.enhanceTouchZoneDivider * 0.01f;
        NativeTouchContext.POINTER_VELOCITY_FACTOR = prefConfig.pointerVelocityFactor * 0.01f;
        // NativeTouchContext.POINTER_FIXED_X_VELOCITY = prefConfig.pointerFixedXVelocity;

        // Enter landscape unless we're on a square screen
        orientationManager.setPreferredOrientation();

        if (prefConfig.stretchVideo || DisplayModeManager.shouldIgnoreInsetsForResolution(
                getWindowManager().getDefaultDisplay(), prefConfig.width, prefConfig.height)) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        // Listen for non-touch events on the game surface
        streamView = findViewById(R.id.surfaceView);
        streamView.setOnGenericMotionListener(this);
        streamView.setOnKeyListener(this);
        streamView.setInputCallbacks(this);

        panZoomHandler = new PanZoomHandler(this, this, streamView, prefConfig);

        // 光标同步监听器将在 cursorServiceManager 初始化后注册（见 touch context 初始化之后）

        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        View backgroundTouchView = findViewById(R.id.backgroundTouchView);
        backgroundTouchView.setOnTouchListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
            backgroundTouchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }

        notificationOverlayManager = new NotificationOverlayManager(
                findViewById(R.id.notificationOverlay),
                findViewById(R.id.notificationText),
                () -> prefConfig.bitrate
        );

        micButton = findViewById(R.id.micButton);

        // 初始化性能覆盖层管理器
        performanceOverlayManager = new PerformanceOverlayManager(this, prefConfig);
        performanceOverlayManager.initialize();

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener(touchInputHandler::handleMotionEvent);
        }

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock");
            highPerfWifiLock.setReferenceCounted(false);
            highPerfWifiLock.acquire();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock");
                lowLatencyWifiLock.setReferenceCounted(false);
                lowLatencyWifiLock.acquire();
            }
        } catch (SecurityException e) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace();
        }

        appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        pcName = Game.this.getIntent().getStringExtra(EXTRA_PC_NAME);

        // 初始化统计分析管理器
        analyticsManager = AnalyticsManager.getInstance(this);

        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        int port = Game.this.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        int httpsPort = Game.this.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0); // 0 is treated as unknown
        int appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        String pairName = Game.this.getIntent().getStringExtra(EXTRA_PAIR_NAME);
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        boolean pcUseVdd = Game.this.getIntent().getBooleanExtra(EXTRA_PC_USEVDD, false);
        byte[] derCertData = Game.this.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);
        String cmdList = Game.this.getIntent().getStringExtra(EXTRA_APP_CMD);
        String displayName = Game.this.getIntent().getStringExtra(EXTRA_DISPLAY_NAME);

        app = new NvApp(appName != null ? appName : "app", appId, appSupportsHdr);
        if (cmdList != null) {
            app.setCmdList(cmdList);
        }

        // 保存应用信息到SharedPreferences，供下次从捷径恢复时使用
        if (appId != StreamConfiguration.INVALID_APP_ID && appName != null && !appName.equals("app")) {
            AppCacheManager cacheManager = new AppCacheManager(this);
            cacheManager.saveAppInfo(getIntent().getStringExtra(EXTRA_PC_UUID), app);
        }

        // Start the progress overlay
        progressOverlay = new FullscreenProgressOverlay(this, app);

        // 设置computer信息
        ComputerDetails computer = new ComputerDetails();
        computer.name = pcName;
        computer.uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
        progressOverlay.setComputer(computer);

        progressOverlay.show(getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg));

        X509Certificate serverCert = null;
        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // 构建流配置（包含解码器初始化、刷新率计算等共同逻辑）
        StreamConfigResult streamConfigResult = buildStreamConfiguration(
                host, port, httpsPort, uniqueId, pairName, pcUseVdd, serverCert, displayName);
        StreamConfiguration config = streamConfigResult.config;

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, pairName, config,
                PlatformBinding.getCryptoProvider(this), serverCert, displayName);
        orientationManager.setConnection(conn);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        keyboardTranslator = new KeyboardTranslator();

        // Initialize audio-driven vibration service
        audioVibrationService = new AudioVibrationService(this);
        audioVibrationService.setControllerHandler(controllerHandler);
        audioVibrationService.setSettings(
                prefConfig.enableAudioVibration,
                prefConfig.audioVibrationStrength,
                prefConfig.audioVibrationMode,
                prefConfig.audioVibrationScene
        );
        MoonBridge.setBassEnergyListener((intensity, lowFreqRatio) -> {
            audioVibrationService.handleBassEnergy(intensity, lowFreqRatio);
        });
        // Configure native bass energy analyzer
        MoonBridge.setBassEnergyEnabled(prefConfig.enableAudioVibration);
        MoonBridge.setBassEnergySceneMode(prefConfig.audioVibrationScene);

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);


        // Initialize touch input handler and touch contexts
        touchInputHandler = new TouchInputHandler(this);
        touchInputHandler.initTouchContexts(conn, streamView, prefConfig);

        // 初始化光标服务管理器
        CursorView cursorOverlayView = findViewById(R.id.cursorOverlay);
        cursorServiceManager = new CursorServiceManager(
                streamView, cursorOverlayView, prefConfig, touchInputHandler.relativeTouchContextMap,
                new CursorServiceManager.UiCallback() {
                    @Override
                    public void runOnUi(Runnable runnable) {
                        runOnUiThread(runnable);
                    }
                    @Override
                    public boolean isActivityAlive() {
                        return !isFinishing() && !isDestroyed();
                    }
                });

        // 添加监听器 (应对屏幕旋转、大小变化)
        streamView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                cursorServiceManager.syncCursorWithStream();
            }
        });
        streamView.post(() -> cursorServiceManager.syncCursorWithStream());

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout) streamView.getParent(),
                    this);
            virtualController.refreshLayout();
            virtualController.show();

            virtualController.setGyroEnabled(!prefConfig.gyroToMouse);

            // When gyro-mouse mode is active, ControllerHandler registers its own sensor listener
            // (with screen-rotation correction). Suspend VirtualController's listener to avoid
            // double-registration on the same sensor which causes erratic mouse movement.
            final VirtualController vc = virtualController;
            controllerHandler.setVirtualControllerGyroCallbacks(
                    () -> vc.setGyroEnabled(false),
                    () -> vc.setGyroEnabled(true)
            );
        }

        if (prefConfig.onscreenKeyboard) {
            // create virtual onscreen keyboard
            controllerManager = new ControllerManager((FrameLayout) streamView.getParent(), this);
            
            FrameLayout keyboardContainer = findViewById(R.id.virtual_full_keyboard_container);
            if (keyboardContainer != null) {
                KeyboardUIController kUI = new KeyboardUIController(keyboardContainer, new KeyboardUIController.OnKeyboardEventListener() {
                    @Override
                    public void sendKeyEvent(boolean down, short keyCode) {
                        if (controllerManager != null && controllerManager.getElementController() != null) {
                            controllerManager.getElementController().sendKeyEvent(down, keyCode);
                        } else {
                            keyboardEvent(down, keyCode);
                        }
                    }

                    @Override
                    public void rumbleSingleVibrator(short lowFreq, short highFreq, int duration) {
                        if (controllerManager != null && controllerManager.getElementController() != null) {
                            controllerManager.getElementController().rumbleSingleVibrator(lowFreq, highFreq, duration);
                        }
                    }
                }, this);
                controllerManager.setKeyboardUIController(kUI);
            }
            
            controllerManager.refreshLayout();
        }

        if (prefConfig.usbDriver) {
            // Start the USB driver
            usbDriverServiceManager = new UsbDriverServiceManager(this, this);
            usbDriverServiceManager.setControllerHandler(controllerHandler);
            usbDriverServiceManager.bind();
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (progressOverlay != null) {
                progressOverlay.dismiss();
                progressOverlay = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        // The connection will be started when the surface gets created
        streamView.getHolder().addCallback(this);

        // 允许内容延伸到刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Set up display position
        new DisplayPositionManager(this, prefConfig, streamView).setupDisplayPosition();

        // 初始化外接显示器管理器
        externalDisplayManager = new ExternalDisplayManager(this, prefConfig, conn, decoderRenderer, pcName, appName);
        externalDisplayManager.setCallback(new ExternalDisplayManager.ExternalDisplayCallback() {
            @Override
            public void onExternalDisplayConnected(Display display) {
                // 外接显示器连接时的处理
                LimeLog.info("External display connected, reinitializing input capture provider");

                // 重新初始化输入捕获提供者以支持外接显示器
                if (inputCaptureProvider != null) {
                    inputCaptureProvider.disableCapture();
                }
                inputCaptureProvider = InputCaptureManager.getInputCaptureProviderForExternalDisplay(Game.this, Game.this);
            }

            @Override
            public void onExternalDisplayDisconnected() {
                // 外接显示器断开时的处理
                externalStreamView = null;
                LimeLog.info("External display disconnected, cleared externalStreamView");

                // 恢复触控上下文的目标视图为平板的 StreamView
                for (int i = 0; i < TouchInputHandler.TOUCH_CONTEXT_LENGTH; i++) {
                    if (touchInputHandler.absoluteTouchContextMap[i] instanceof AbsoluteTouchContext) {
                        ((AbsoluteTouchContext) touchInputHandler.absoluteTouchContextMap[i]).setTargetView(Game.this.streamView);
                    }
                    if (touchInputHandler.relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                        ((RelativeTouchContext) touchInputHandler.relativeTouchContextMap[i]).setTargetView(Game.this.streamView);
                    }
                }

                // 重新初始化输入捕获提供者回到标准模式
                if (inputCaptureProvider != null) {
                    inputCaptureProvider.disableCapture();
                }
                inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(Game.this, Game.this);
            }

            @Override
            public void onStreamViewReady(StreamView streamView) {
                // 保存外接显示器的StreamView引用
                externalStreamView = streamView;

                // 更新触控上下文的目标视图为外接显示器的 StreamView
                for (int i = 0; i < TouchInputHandler.TOUCH_CONTEXT_LENGTH; i++) {
                    if (touchInputHandler.absoluteTouchContextMap[i] instanceof AbsoluteTouchContext) {
                        ((AbsoluteTouchContext) touchInputHandler.absoluteTouchContextMap[i]).setTargetView(streamView);
                    }
                    if (touchInputHandler.relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                        ((RelativeTouchContext) touchInputHandler.relativeTouchContextMap[i]).setTargetView(streamView);
                    }
                }

                // 外接显示器StreamView准备就绪时的处理
                streamView.setOnGenericMotionListener(Game.this);
                streamView.setOnKeyListener(Game.this);
                streamView.setInputCallbacks(Game.this);

                // 设置触摸监听
                View backgroundTouchView = findViewById(R.id.backgroundTouchView);
                if (backgroundTouchView != null) {
                    backgroundTouchView.setOnTouchListener(Game.this);
                }

                // 设置Surface回调
                streamView.getHolder().addCallback(Game.this);

                LimeLog.info("External display StreamView ready: " + streamView.getWidth() + "x" + streamView.getHeight());
            }
        });
        externalDisplayManager.initialize();

        // 初始化悬浮球
        floatBallHandler = new FloatBallHandler(this, prefConfig);
        floatBallHandler.initialize();

        // 初始化连接回调处理器
        connectionCallbackHandler = new ConnectionCallbackHandler(this);
    }

    /**
     * 构建流配置对象，包含解码器初始化、刷新率计算等共同逻辑
     *
     * @param host        主机地址
     * @param port        端口
     * @param httpsPort   HTTPS端口
     * @param uniqueId    唯一ID
     * @param pairName    配对名称
     * @param pcUseVdd    是否使用VDD
     * @param serverCert  服务器证书
     * @param displayName 显示器名称（可为null）
     * @return StreamConfiguration对象和刷新率信息的包装类
     */
    private StreamConfigResult buildStreamConfiguration(String host, int port, int httpsPort,
                                                        String uniqueId, String pairName,
                                                        boolean pcUseVdd, X509Certificate serverCert,
                                                        String displayName) {
        // 重新读取首选项和网络状态
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // Check if the user has enabled HDR
        boolean willStreamHdr = false;
        if (prefConfig.enableHdr) {
            // Start our HDR checklist
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Display display = externalDisplayManager != null ?
                        externalDisplayManager.getTargetDisplay() : getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true;
                            break;
                        }
                    }
                }

                if (!willStreamHdr) {
                    // Nope, no HDR for us :(
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
            }
        }

        // 创建解码器渲染器（如果不存在则创建）
        // 注意：在 prepareConnection 中，调用者应该先销毁旧的解码器并设置为 null
        if (decoderRenderer == null) {
            decoderRenderer = new MediaCodecDecoderRenderer(
                    this,
                    prefConfig,
                    e -> {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again
                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    },
                    tombstonePrefs.getInt("CrashCount", 0),
                    connMgr.isActiveNetworkMetered(),
                    willStreamHdr,
                    glPrefs.glRenderer,
                    this);
        }

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false;
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show();
        }

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (willStreamHdr && decoderRenderer.isHevcMain10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        float displayRefreshRate = prepareDisplayForRendering();
        LimeLog.info("Display refresh rate: " + displayRefreshRate + " Hz");

        if (performanceOverlayManager != null) {
            performanceOverlayManager.setActualDisplayRefreshRate(displayRefreshRate);
        }

        int clientRefreshRateX100 = Math.round(displayRefreshRate * 100);

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        int chosenFrameRate = prefConfig.fps; //将此处chosenFrameRate赋值为5时， 视频刷新率降低到5，但直接观察远端桌面可知，触控刷新率并未下降，窗口仍可流畅拖动。
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
                } else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate);
                }
            }
        }

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(chosenFrameRate)  //将此处chosenFrameRate替换为5时， 视频刷新率降低到5，但直接观察远端桌面可知，触控刷新率并未下降，窗口仍可流畅拖动。
                .setApp(app)
                .setBitrate(prefConfig.bitrate)
                .setResolutionScale(prefConfig.resolutionScale)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100(clientRefreshRateX100)
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                // HLG requires FULL range for correct OETF/EOTF. Override user preference.
                .setColorRange(willStreamHdr && prefConfig.hdrMode == MoonBridge.HDR_MODE_HLG ?
                        MoonBridge.COLOR_RANGE_FULL : decoderRenderer.getPreferredColorRange())
                .setHdrMode(willStreamHdr ? prefConfig.hdrMode : MoonBridge.HDR_MODE_SDR)
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                .setUseVdd(pcUseVdd)
                .setEnableMic(prefConfig.enableMic)
                .setControlOnly(prefConfig.controlOnly)
                .setCustomScreenMode(prefConfig.screenCombinationMode)
                .setCustomVddScreenMode(prefConfig.vddScreenCombinationMode)
                .build();

        LimeLog.info("Stream config: hdr=" + willStreamHdr +
                " hdrMode=" + prefConfig.hdrMode + " fullRange=" + prefConfig.fullRange);

        return new StreamConfigResult(config, displayRefreshRate, clientRefreshRateX100);
    }

    /**
     * 流配置结果包装类
     */
    private static class StreamConfigResult {
        final StreamConfiguration config;
        final float displayRefreshRate;
        final int clientRefreshRateX100;

        StreamConfigResult(StreamConfiguration config, float displayRefreshRate, int clientRefreshRateX100) {
            this.config = config;
            this.displayRefreshRate = displayRefreshRate;
            this.clientRefreshRateX100 = clientRefreshRateX100;
        }
    }

    private void prepareConnection() {
        // 1. 清理旧的光标资源
        cursorServiceManager.destroyLocalCursorRenderers();
        runOnUiThread(() -> {
            CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
            if (cursorOverlay != null) {
                cursorOverlay.resetToDefault();
                cursorOverlay.hide();
            }

            // 清理可能残留的网络质量提示
            notificationOverlayManager.reset();
        });

        // 重置旋转状态，以便重新检测初始方向
        orientationManager.reset();

        // 2. 获取 Intent 参数
        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        int port = Game.this.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        int httpsPort = Game.this.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0);
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        String pairName = Game.this.getIntent().getStringExtra(EXTRA_PAIR_NAME);
        boolean pcUseVdd = Game.this.getIntent().getBooleanExtra(EXTRA_PC_USEVDD, false);
        String displayName = Game.this.getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        byte[] derCertData = Game.this.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);

        X509Certificate serverCert = null;
        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
        }

        // 3. 重新初始化解码器环境
        // 销毁旧的解码器（如果存在）并创建新的实例
        // 旧的 renderer 内部的 MediaCodec 可能处于 Released 状态，无法复用
        if (decoderRenderer != null) {
            // 确保旧的资源被清理 (虽然 onStop 可能已经清理过，但双重保险)
            try {
                decoderRenderer.prepareForStop();
            } catch (Exception ignored) {
            }
            decoderRenderer = null; // 重置为null，让buildStreamConfiguration创建新实例
        }

        // 构建流配置（包含解码器初始化、刷新率计算等共同逻辑）
        StreamConfigResult streamConfigResult = buildStreamConfiguration(
                host, port, httpsPort, uniqueId, pairName, pcUseVdd, serverCert, displayName);
        StreamConfiguration config = streamConfigResult.config;

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, pairName, config,
                PlatformBinding.getCryptoProvider(this), serverCert, displayName);
        orientationManager.setConnection(conn);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);

        // 重新创建 ControllerHandler
        controllerHandler.stop();
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);

        // 更新音频振动服务的 controllerHandler 引用
        if (audioVibrationService != null) {
            audioVibrationService.setControllerHandler(controllerHandler);
        }

        //  重新绑定 USB 驱动服务
        // 因为 stopConnection 时解绑了，这里必须重新 bind，而不是直接 setListener
        if (prefConfig.usbDriver) {
            if (usbDriverServiceManager != null) {
                usbDriverServiceManager.stopAndUnbind();
            }
            usbDriverServiceManager = new UsbDriverServiceManager(this, this);
            usbDriverServiceManager.setControllerHandler(controllerHandler);
            usbDriverServiceManager.bind();
        } else if (usbDriverServiceManager != null) {
            usbDriverServiceManager.refreshListener();
        }

        // 重新初始化触控
        // 必须在 ControllerManager 初始化之前完成，因为 ControllerManager 会调用它来设置灵敏度
        touchInputHandler.initTouchContexts(conn, streamView, prefConfig);

        //  重建虚拟手柄和屏幕键盘管理器
        // 必须这样做，因为它们需要绑定新的 controllerHandler 和 conn
        if (virtualController != null) {
            if (prefConfig.onscreenController) {
                // 这里调用 refreshLayout 确保位置正确
                virtualController.refreshLayout();
                virtualController.show();
                // 鼠标模式下 ControllerHandler 自己管理传感器，不启用 VirtualController 的监听
                virtualController.setGyroEnabled(!prefConfig.gyroToMouse);
                final VirtualController vc = virtualController;
                controllerHandler.setVirtualControllerGyroCallbacks(
                        () -> vc.setGyroEnabled(false),
                        () -> vc.setGyroEnabled(true)
                );
            }
        }

        if (controllerManager != null) {
            // 处理王冠模式/虚拟键盘
            if (prefConfig.onscreenKeyboard) {
                controllerManager.refreshLayout();
            } else {
                // 如果配置变成了关闭，确保变量被清空
                controllerManager = null;
            }
        }

        // 重建麦克风管理器 (绑定新连接)
        if (microphoneManager != null) {
            microphoneManager.stopMicrophoneStream();
        }
        microphoneManager = new MicrophoneManager(this, conn, prefConfig.enableMic);
        microphoneManager.setStateListener(new MicrophoneManager.MicrophoneStateListener() {
            @Override
            public void onMicrophoneStateChanged(boolean isActive) {
                LimeLog.info("麦克风状态改变: " + (isActive ? "激活" : "暂停"));
            }

            @Override
            public void onPermissionRequested() {
                LimeLog.info("麦克风权限请求已发送");
            }
        });

        // 初始化外接显示器管理器
        externalDisplayManager = new ExternalDisplayManager(this, prefConfig, conn, decoderRenderer, pcName, appName);
        externalDisplayManager.setCallback(new ExternalDisplayManager.ExternalDisplayCallback() {
            @Override
            public void onExternalDisplayConnected(Display display) {
                // 外接显示器连接时的处理
                LimeLog.info("External display connected, reinitializing input capture provider");

                // 重新初始化输入捕获提供者以支持外接显示器
                if (inputCaptureProvider != null) {
                    inputCaptureProvider.disableCapture();
                }
                inputCaptureProvider = InputCaptureManager.getInputCaptureProviderForExternalDisplay(Game.this, Game.this);
            }

            @Override
            public void onExternalDisplayDisconnected() {
                // 外接显示器断开时的处理
                externalStreamView = null;
                LimeLog.info("External display disconnected, cleared externalStreamView");

                // 恢复触控上下文的目标视图为平板的 StreamView
                for (int i = 0; i < TouchInputHandler.TOUCH_CONTEXT_LENGTH; i++) {
                    if (touchInputHandler.absoluteTouchContextMap[i] instanceof AbsoluteTouchContext) {
                        ((AbsoluteTouchContext) touchInputHandler.absoluteTouchContextMap[i]).setTargetView(Game.this.streamView);
                    }
                    if (touchInputHandler.relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                        ((RelativeTouchContext) touchInputHandler.relativeTouchContextMap[i]).setTargetView(Game.this.streamView);
                    }
                }

                // 重新初始化输入捕获提供者回到标准模式
                if (inputCaptureProvider != null) {
                    inputCaptureProvider.disableCapture();
                }
                inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(Game.this, Game.this);
            }

            @Override
            public void onStreamViewReady(StreamView streamView) {
                // 保存外接显示器的StreamView引用
                externalStreamView = streamView;

                // 更新触控上下文的目标视图为外接显示器的 StreamView
                for (int i = 0; i < TouchInputHandler.TOUCH_CONTEXT_LENGTH; i++) {
                    if (touchInputHandler.absoluteTouchContextMap[i] instanceof AbsoluteTouchContext) {
                        ((AbsoluteTouchContext) touchInputHandler.absoluteTouchContextMap[i]).setTargetView(streamView);
                    }
                    if (touchInputHandler.relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                        ((RelativeTouchContext) touchInputHandler.relativeTouchContextMap[i]).setTargetView(streamView);
                    }
                }

                // 外接显示器StreamView准备就绪时的处理
                streamView.setOnGenericMotionListener(Game.this);
                streamView.setOnKeyListener(Game.this);
                streamView.setInputCallbacks(Game.this);

                // 设置触摸监听
                View backgroundTouchView = findViewById(R.id.backgroundTouchView);
                if (backgroundTouchView != null) {
                    backgroundTouchView.setOnTouchListener(Game.this);
                }

                // 设置Surface回调
                streamView.getHolder().addCallback(Game.this);

                LimeLog.info("External display StreamView ready: " + streamView.getWidth() + "x" + streamView.getHeight());
            }
        });
        externalDisplayManager.initialize();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 当 Activity 回到前台时，通知服务开始拦截键盘事件。
        KeyboardAccessibilityService.setIntercepting(true);

        // 获取服务实例并注册回调，这样我们就能收到从服务传来的按键事件。
        KeyboardAccessibilityService service = KeyboardAccessibilityService.getInstance();
        if (service != null) {
            service.setKeyEventCallback(this);
        } else {
            LimeLog.warning("KeyboardAccessibilityService is not running.");
        }
        // END: ACCESSIBILITY SERVICE INTEGRATION

        // 刷新麦克风按钮图标（以便应用最新的颜色配置）
        if (microphoneManager != null && micButton != null) {
            microphoneManager.updateMicrophoneButtonState();
        }

        // 显示浮球
        floatBallHandler.show();
    }

    /**
     * 实现 KeyEventCallback 接口的方法。
     * 所有被无障碍服务拦截的按键事件最终都会通过这个方法到达这里。
     *
     * @param event 从服务传来的按键事件。
     */
    @Override
    public void onKeyEvent(KeyEvent event) {
        // 在调用处理方法之前，设置标志位
        isEventFromAccessibilityService = true;

        // 将事件分发到已有的处理逻辑中
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            onKeyDown(event.getKeyCode(), event);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            onKeyUp(event.getKeyCode(), event);
        }

        // 处理完毕后，重置标志位
        isEventFromAccessibilityService = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 将权限结果传递给麦克风管理器
        if (microphoneManager != null) {
            microphoneManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }

        if (requestCode == KEEP_ALIVE_NOTIFICATION_ID) {
            // Check if the permission was granted
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户给了权限，立即启动服务
                StreamNotificationService.start(this, pcName, appName);
            } else {
                // Permission denied, show a toast message
                Toast.makeText(this, getString(R.string.toast_no_notification_permission), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // 旋转/方向管理（包括服务端旋转恢复、用户旋转通知）
        orientationManager.onConfigurationChanged();

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController.refreshLayout();
        }
        if (controllerManager != null) {
            // Refresh layout of OSC for possible new screen size
            controllerManager.refreshLayout();
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode()) {

                if (virtualController != null) {
                    virtualController.hide();
                }

                if (performanceOverlayManager != null) {
                    performanceOverlayManager.hideOverlayImmediate();
                }
                notificationOverlayManager.setHiding(true);

                // 隐藏麦克风按钮
                if (microphoneManager != null) {
                    microphoneManager.setEnableMic(false);
                }

                // Disable sensors while in PiP mode
                if (controllerHandler != null) {
                    controllerHandler.disableSensors();
                }

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this);
            } else {

                // Restore overlays to previous state when leaving PiP

                if (virtualController != null) {
                    virtualController.show();
                }

                if (performanceOverlayManager != null) {
                    performanceOverlayManager.applyRequestedVisibility();
                }
                notificationOverlayManager.setHiding(false);
                notificationOverlayManager.applyVisibility();

                // 恢复麦克风按钮
                if (microphoneManager != null) {
                    microphoneManager.setEnableMic(prefConfig.enableMic);
                }

                // Enable sensors again after exiting PiP
                if (controllerHandler != null) {
                    controllerHandler.enableSensors();

                    // 恢复陀螺仪功能（如果之前启用了）
                    controllerHandler.onSensorsReenabled();
                }

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this);
            }
        }

        // 屏幕方向变化时重新配置性能覆盖层布局
        if (performanceOverlayManager != null) {
            performanceOverlayManager.onConfigurationChanged();
        }

        // Re-apply display position
        refreshDisplayPosition();
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams getPictureInPictureParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(prefConfig.width, prefConfig.height))
                        .setSourceRectHint(new Rect(
                                streamView.getLeft(), streamView.getTop(),
                                streamView.getRight(), streamView.getBottom()));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName);
                if (pcName != null) {
                    builder.setSubtitle(pcName);
                }
            } else if (pcName != null) {
                builder.setTitle(pcName);
            }
        }

        return builder.build();
    }

    public void updatePipAutoEnter() {
        if (!prefConfig.enablePip) {
            return;
        }

        boolean autoEnter = connected && suppressPipRefCount == 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter));
        } else {
            autoEnterPip = autoEnter;
        }
    }

    public void setMetaKeyCaptureState(boolean enabled) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            Class<?> semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager");
            Method getInstanceMethod = semWindowManager.getMethod("getInstance");
            Object manager = getInstanceMethod.invoke(null);

            if (manager != null) {
                Class<?>[] parameterTypes = new Class<?>[2];
                parameterTypes[0] = ComponentName.class;
                parameterTypes[1] = boolean.class;
                Method requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", parameterTypes);
                requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
            } else {
                LimeLog.warning("SemWindowManager.getInstance() returned null");
            }
        } catch (ClassNotFoundException e) {
            // This is expected on non-Samsung devices - silently ignore
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            // Log other unexpected errors
            LimeLog.warning("Failed to set meta key capture state: " + e.getMessage());
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        // 获取用户设置，判断是否启用“快速恢复串流”
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isResumeEnabled = prefs.getBoolean("checkbox_resume_stream", false);

        // 只有在开关开启时，才允许标记 resume
        if (isResumeEnabled) {
            // 如果没有进入画中画模式，则标记为需要在回来时恢复会话
            if (!autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                shouldResumeSession = true;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 自动 PiP，如果系统没有触发 PiP，我们假设是后台
                // 注意：如果 Android 12 自动进入了 PiP，Activity 不会 Stop，也就不会触发恢复逻辑，这是符合预期的
                shouldResumeSession = true;
            }
        }

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    public boolean onPictureInPictureRequested() {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false));
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0;

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    private float prepareDisplayForRendering() {
        Display display = externalDisplayManager != null ?
                externalDisplayManager.getTargetDisplay() : getWindowManager().getDefaultDisplay();

        // 使用 DisplayModeManager 计算最佳显示模式
        DisplayModeManager.DisplayModeResult result =
                DisplayModeManager.selectBestDisplayMode(display, prefConfig);

        // 应用显示模式结果
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        if (result.preferredModeId >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                windowLayoutParams.preferredDisplayModeId = result.preferredModeId;
            }
            getWindow().setAttributes(windowLayoutParams);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Pre-M: 通过 preferredRefreshRate 设置
            windowLayoutParams.preferredRefreshRate = result.refreshRate;
            getWindow().setAttributes(windowLayoutParams);
        }

        updateStreamViewSize(prefConfig.width, prefConfig.height, result.aspectRatioMatch);

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = result.refreshRate;

        return result.refreshRate;
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = () -> {
        // TODO: Do we want to use WindowInsetsController here on R+ instead of
        // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

        // In multi-window mode on N+, we need to drop our layout flags or we'll
        // be drawing underneath the system UI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
            Game.this.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            // Use immersive mode
            Game.this.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    };

    void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoBackground();
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @Override
    protected void onDestroy() {
        // 将取消通知提到最前面执行，确保无论后续是否崩溃，通知都能消失
        cancelKeepAliveNotification();

        // 清理旋转管理器的 Handler 回调
        orientationManager.cleanup();

        // 隐藏并释放悬浮球
        floatBallHandler.release();

        super.onDestroy();

        // 确保在 Activity 彻底销毁时停止连接（因为 onStop 可能跳过了它）
        if (conn != null && connected) {
            connectionCallbackHandler.stopConnection();
        }

        if (controllerHandler != null) {
            controllerHandler.destroy();
        }
        if (audioVibrationService != null) {
            audioVibrationService.stop();
            MoonBridge.setBassEnergyEnabled(false);
            MoonBridge.setBassEnergyListener(null);
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }

        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock.release();
        }
        if (highPerfWifiLock != null) {
            highPerfWifiLock.release();
        }

        if (usbDriverServiceManager != null) {
            usbDriverServiceManager.stopAndUnbind();
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();

        // 清理外接显示器管理器
        if (externalDisplayManager != null) {
            externalDisplayManager.cleanup();
        }

        // 清理麦克风流
        if (microphoneManager != null) {
            microphoneManager.stopMicrophoneStream();
        }
    }

    @Override
    protected void onPause() {
        // 隐藏浮球
        floatBallHandler.hide();

        // 当 Activity 进入后台时，必须停止拦截，否则会影响手机的正常使用！
        KeyboardAccessibilityService.setIntercepting(false);

        // 注销回调，防止内存泄漏。
        KeyboardAccessibilityService service = KeyboardAccessibilityService.getInstance();
        if (service != null) {
            service.setKeyEventCallback(null);
        }

        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false);
        }

        super.onPause();
    }

    /**
     * 在不离开游戏界面的情况下修改分辨率
     */
    public void changeResolution() {
        // 1. 设置标志位：告诉 onStop 不要断开连接
        isChangingResolution = true;

        // 2. 执行重启。
        // 流程：recreate() -> onPause() -> onStop() [被拦截，连接保留] -> onDestroy()
        // -> onCreate() -> onStart() -> surfaceChanged() [画面恢复到新尺寸]
        this.recreate();
    }

    @Override
    protected void onStop() {
        super.onStop();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isResumeEnabled = prefs.getBoolean("checkbox_resume_stream", false);
        if ((isExtremeResumeEnabled || isChangingResolution) && !isFinishing()) {
            LimeLog.info("Extreme Resume: onStop intercepted.");
            // 只有在不是修改分辨率的情况下（即真的是切到后台了），才发通知
            if (!isChangingResolution && isResumeEnabled) {
                showKeepAliveNotification();
            }
            return;
        }

        // 暂停串流时长计时（进入后台时串流实际上是暂停的）
        if (isStreamingActive && lastActiveTime > 0) {
            accumulatedStreamTime += System.currentTimeMillis() - lastActiveTime;
            isStreamingActive = false;
            LimeLog.info("串流时长计时暂停，已累计: " + (accumulatedStreamTime / 1000) + " 秒");
        }

        // 检查是否是因为进入后台（包括锁屏、滑到任务栏、Home键）导致的应用停止
        // 只要 Activity 不是正在 Finishing（即不是用户点了退出或崩溃），且开启了快速恢复，就标记为需要恢复
        if (!shouldResumeSession && !isFinishing()) {
            if (isResumeEnabled) {
                shouldResumeSession = true;
                LimeLog.info("检测到应用进入后台（非主动退出），已标记为待恢复会话");
            }
        }

        if (progressOverlay != null) {
            progressOverlay.dismiss();
            progressOverlay = null;
        }
        Dialog.closeDialogs();

        if (virtualController != null) {
            virtualController.hide();
            virtualController.cleanup(); // 清理陀螺仪传感器监听
        }

        String decoderMessage = "UNKNOWN";
        if (decoderRenderer != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();
            if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                decoderMessage = "H.264";
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                decoderMessage = "HEVC";
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                decoderMessage = "AV1";
            }
            if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                decoderMessage += " HDR";
            }
        }

        if (conn != null) {
            displayedFailureDialog = true;
            connectionCallbackHandler.stopConnection();

            if (prefConfig.enableLatencyToast) {
                int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
                int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
                String message = null;
                if (averageEndToEndLat > 0) {
                    message = getResources().getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms";
                    if (averageDecoderLat > 0) {
                        message += " (" + getResources().getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)";
                    }
                } else if (averageDecoderLat > 0) {
                    message = getResources().getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms";
                }

                // Add the video codec to the post-stream toast
                if (message != null) {
                    message += " [";
                    message += decoderMessage;
                    message += "]";
                }

                // Add microphone quality statistics if microphone was enabled and used
                if (prefConfig.enableMic && microphoneManager != null) {
                    String micStats = AudioDiagnostics.getCurrentStats(this);
                    if (message != null) {
                        message += " [mic]" + micStats;
                    } else {
                        message = micStats;
                    }
                }

                // Add precise-sync mode frame skip statistics
                String surfaceFlingerStats = decoderRenderer.getSurfaceFlingerStats();
                if (surfaceFlingerStats != null) {
                    if (message != null) {
                        message += "\n" + surfaceFlingerStats;
                    } else {
                        message = surfaceFlingerStats;
                    }
                }

                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
        }

        // 记录游戏流媒体结束事件
        if (analyticsManager != null && pcName != null && streamStartTime > 0) {
            // 计算精确的有效串流时长
            // = 已累计的时间 + 当前活跃段时间（如果当前是活跃状态）
            long effectiveStreamDuration = accumulatedStreamTime;
            if (isStreamingActive && lastActiveTime > 0) {
                effectiveStreamDuration += System.currentTimeMillis() - lastActiveTime;
            }
            
            // 同时记录总耗时（包括后台暂停时间）用于对比
            long totalElapsedTime = System.currentTimeMillis() - streamStartTime;

            // 收集性能数据
            int resolutionWidth = 0;
            int resolutionHeight = 0;
            int averageEndToEndLatency = 0;
            int averageDecoderLatency = 0;

            if (decoderRenderer != null) {
                resolutionWidth = prefConfig.width;
                resolutionHeight = prefConfig.height;
                averageEndToEndLatency = decoderRenderer.getAverageEndToEndLatency();
                averageDecoderLatency = decoderRenderer.getAverageDecoderLatency();
            }

            // 使用有效串流时长进行统计
            analyticsManager.logGameStreamEnd(pcName, appName, effectiveStreamDuration,
                    decoderMessage, resolutionWidth, resolutionHeight,
                    averageEndToEndLatency, averageDecoderLatency);

            LimeLog.info("串流统计 - 有效时长: " + (effectiveStreamDuration / 1000) + "秒, 总耗时: " + (totalElapsedTime / 1000) + "秒");

            // 重置统计状态
            streamStartTime = 0;
            accumulatedStreamTime = 0;
            isStreamingActive = false;
        }

        if (shouldResumeSession && isResumeEnabled) {
            showKeepAliveNotification();
            LimeLog.info("应用进入后台，保持 Activity 存活以备快速恢复。连接已断开。");
        } else {
            finish();
        }
    }

    void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        } else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = () -> setInputGrabState(!grabbedInput);

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        } else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        } else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        } else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        } else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        } else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            } else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            } else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        Handler h = getWindow().getDecorView().getHandler();
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250);
                        }
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        finish();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture();
                            grabbedInput = true;
                        }
                        cursorVisible = !cursorVisible;
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor();
                        } else {
                            inputCaptureProvider.hideCursor();
                        }
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event);
    }

    private final Set<Integer> pressedKeys = new HashSet<>();
    // 0代表未按下，1代表按下esc，2代表按下自定义组合键
    private int escState = 0; // 0 = 空闲，1 = ESC已按下，2 = 已进入组合键
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable escConfirmRunnable;
    @Override
    public boolean handleKeyDown(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
                // 如果是系统导航键，则跳过我们的去重逻辑，
                // 让事件继续被正常处理。
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                break;
            default:
                // 只有当事件不是来自服务、服务正在运行、且事件源不是虚拟键盘（即来自物理键盘）时，
                // 才将其判定为重复事件并忽略。
                InputDevice device = event.getDevice();
                if (!isEventFromAccessibilityService &&
                        KeyboardAccessibilityService.getInstance() != null &&
                        (device != null && !device.isVirtual())) {

                    return true;
                }
                break;
        }

        // 自定义组合键，只能其它+esc，esc+其它时，esc抬起时其它才会down
        int keyCode = event.getKeyCode();
        pressedKeys.add(keyCode);
        if (prefConfig.enableCustomKeyMap) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                escState = 1;
//            Log.d("debug", "Esc: Down");
                // 启动延迟判断是否是单独的ESC键
                escConfirmRunnable = () -> {
                    if (escState == 1) {
//                    Log.d("debug", "Esc: Confirmed as Single");
                        short translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.getDeviceId());
                        conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                        escState = 0;
                    }
                };
                handler.postDelayed(escConfirmRunnable, 200); // 延迟判断
                return true;
            }

            if (escState == 1) {
                // 若在ESC后检测到自定义键按下，取消ESC单键判断
                handler.removeCallbacks(escConfirmRunnable);

                if (keyCode == KeyEvent.KEYCODE_Q) {
//                Log.d("debug", "Esc + Q: Down");
                    escState = 2;
                    return true;
                }
                else if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
//                Log.d("debug", "Esc + num: Down");
                    escState = 2;
                    int fKeyCode = KeyEvent.KEYCODE_F1 + (keyCode - KeyEvent.KEYCODE_1);
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                else if (keyCode == KeyEvent.KEYCODE_0) {
//                Log.d("debug", "Esc + 0: Down -> F10");
                    escState = 2;
                    int fKeyCode = KeyEvent.KEYCODE_F10;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                else if (keyCode == KeyEvent.KEYCODE_MINUS) {
//                Log.d("debug", "Esc + -: Down -> F11");
                    escState = 2;
                    int fKeyCode = KeyEvent.KEYCODE_F11;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                else if (keyCode == KeyEvent.KEYCODE_EQUALS) {
//                Log.d("debug", "Esc + =: Down -> F12");
                    escState = 2;
                    int fKeyCode = KeyEvent.KEYCODE_F12;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                else{
                    // 非自定义组合键，不做处理
                    short translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    escState = 0;
                }
            }
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        // 鼠标中键（同时影响触摸返回）
        if (touchInputHandler.detectMouseMiddle && eventSource == InputDevice.SOURCE_KEYBOARD &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (android.os.SystemClock.uptimeMillis() - touchInputHandler.lastMouseHoverTime < 250) {
                touchInputHandler.detectMouseMiddleDown = true;
                touchInputHandler.detectMouseMiddle = false;
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                return true;
            }
        }

        boolean handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                int unicodeChar = event.getUnicodeChar();
                if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                    conn.sendUtf8Text("" + (char) unicodeChar);
                    return true;
                }

                return false;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean handleKeyUp(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
                // 如果是系统导航键，则跳过我们的去重逻辑。
                break;
            default:
                // 如果是普通游戏按键，则执行去重逻辑。
                InputDevice device = event.getDevice();
                if (!isEventFromAccessibilityService &&
                        KeyboardAccessibilityService.getInstance() != null &&
                        (device != null && !device.isVirtual())) {

                    return true;
                }
                break;
        }

        if (isPhysicalKeyboardConnected()) {
            // ESC键双击逻辑
            if (event.getKeyCode() == prefConfig.escMenuKey && prefConfig.enableEscMenu) {
                long currentTime = System.currentTimeMillis();

                if (currentTime - lastEscPressTime <= ESC_DOUBLE_PRESS_INTERVAL && hasShownEscHint) {
                    // 第二次按ESC，弹出游戏菜单
                    onBackPressed();
                    lastEscPressTime = 0;
                    hasShownEscHint = false;
                    return true; // 消费事件，不发送给主机
                } else {
                    // 第一次按ESC，显示提示但透传给主机
                    String keyName = KeyEvent.keyCodeToString(prefConfig.escMenuKey);
                    if (keyName.startsWith("KEYCODE_")) {
                        keyName = keyName.substring("KEYCODE_".length());
                    }
                    Toast.makeText(this, getString(R.string.toast_press_again_to_open_menu, keyName), Toast.LENGTH_SHORT).show();
                    lastEscPressTime = currentTime;
                    hasShownEscHint = true;
                }
            }
        }

        int keyCode = event.getKeyCode();
        pressedKeys.remove(keyCode);

        if (prefConfig.enableCustomKeyMap) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                handler.removeCallbacks(escConfirmRunnable); // 若未执行则移除
                if (escState == 1) {
                    // 没有组合键，短时间内抬起
//                Log.d("debug", "Esc: Up (no combo)");
                    short translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    // 延迟发送 KEY_UP，不堵塞主线程
                    handler.postDelayed(() -> {
                        conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    }, 50); // 延迟 50ms
                    escState = 0;
                } else if (escState == 2) {
                    // 组合键已触发，不处理ESC
//                Log.d("debug", "Esc: Up (combo)");
                    escState = 0;
                }else{
//                Log.d("debug", "Esc: Up (no custom combo)");
                    short translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    escState = 0;
                }

                return true;
            }
            if(escState == 2){
                if (keyCode == KeyEvent.KEYCODE_Q) {
//                Log.d("debug", "Esc + Q: Up");
                    onBackPressed();
                    return true;
                }
                if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
//                Log.d("debug", "Esc + num: Up");
                    int fKeyCode = KeyEvent.KEYCODE_F1 + (keyCode - KeyEvent.KEYCODE_1);
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_0) {
//                Log.d("debug", "Esc + 0: Up -> F10");
                    int fKeyCode = KeyEvent.KEYCODE_F10;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MINUS) {
//                Log.d("debug", "Esc + -: Up -> F11");
                    int fKeyCode = KeyEvent.KEYCODE_F11;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_EQUALS) {
//                Log.d("debug", "Esc + =: Up -> F12");
                    int fKeyCode = KeyEvent.KEYCODE_F12;
                    short translated = keyboardTranslator.translate(fKeyCode, event.getDeviceId());
                    conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, (byte) 0, MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
                    return true;
                }
            }
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        // 鼠标中键（同时影响触摸返回）
        if (touchInputHandler.detectMouseMiddleDown && eventSource == InputDevice.SOURCE_KEYBOARD &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            touchInputHandler.detectMouseMiddleDown = false;
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
            return true;
        }

        boolean handled = false;
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                int unicodeChar = event.getUnicodeChar();
                return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    private boolean handleKeyMultiple(KeyEvent event) {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false;
        }

        conn.sendUtf8Text(event.getCharacters());
        return true;
    }

    public RelativeTouchContext[] getRelativeTouchContextMap() {
        return touchInputHandler.getRelativeTouchContextMap();
    }

    public void setTouchMode(boolean enableRelativeTouch) {
        touchInputHandler.setTouchMode(enableRelativeTouch);
    }

    public void setEnhancedTouch(boolean enableRelativeTouch) {
        touchInputHandler.setEnhancedTouch(enableRelativeTouch);
    }

    @Override
    public void toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay");

        // Hack: allows use keyboard by dpad or controller
        streamView.clearFocus();

        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    /**
     * 启用或禁用安卓本地鼠标指针
     */
    public void enableNativeMousePointer(boolean enable) {
        LimeLog.info("Setting native mouse pointer: " + enable);

        prefConfig.enableNativeMousePointer = enable;

        if (enable) {
            // 启用本地鼠标指针：释放鼠标捕获但保持键盘捕获
            inputCaptureProvider.disableCapture();
            cursorVisible = true;

            // 显示系统鼠标指针
            if (inputCaptureProvider != null) {
                inputCaptureProvider.showCursor();
            }

            // 保持键盘快捷键捕获，确保Ctrl+Alt+Shift等组合键仍然工作
            setMetaKeyCaptureState(true);

            // 注意：我们不设置 grabbedInput = false，这样按键事件仍能正常处理

            cursorServiceManager.refreshLocalCursorState(true);//开启本地光标服务

            // 切换 CursorView 的可见性
            CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
            if (cursorOverlay != null) {
                cursorOverlay.hide();
            }
        } else {
            // 禁用本地鼠标指针：恢复正常的输入捕获状态
            cursorVisible = false;

            // 隐藏系统鼠标指针
            if (inputCaptureProvider != null) {
                inputCaptureProvider.hideCursor();
            }

            setInputGrabState(true);
        }

    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return touchInputHandler.handleMotionEvent(null, event) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        return touchInputHandler.handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!prefConfig.syncTouchEventWithDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.requestUnbufferedDispatch(event);
            }
        }
        return touchInputHandler.handleMotionEvent(view, event);
    }

    @Override
    public void stageStarting(String stage) {
        connectionCallbackHandler.stageStarting(stage);
    }

    @Override
    public void stageComplete(String stage) {
        connectionCallbackHandler.stageComplete(stage);
    }

    @Override
    public void stageFailed(String stage, int portFlags, int errorCode) {
        connectionCallbackHandler.stageFailed(stage, portFlags, errorCode);
    }

    @Override
    public void connectionTerminated(int errorCode) {
        connectionCallbackHandler.connectionTerminated(errorCode);
    }

    @Override
    public void connectionStatusUpdate(int connectionStatus) {
        connectionCallbackHandler.connectionStatusUpdate(connectionStatus);
    }

    @Override
    public void connectionStarted() {
        connectionCallbackHandler.connectionStarted();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // 恢复串流时长计时（从后台恢复时）
        if (!isStreamingActive && streamStartTime > 0) {
            lastActiveTime = System.currentTimeMillis();
            isStreamingActive = true;
            LimeLog.info("串流时长计时恢复，之前累计: " + (accumulatedStreamTime / 1000) + " 秒");
        }

        // 如果处于不断开连接模式且连接仍然活跃
        if (isExtremeResumeEnabled && connected) {
            LimeLog.info("Extreme Resume: Returning to foreground with active connection.");
            // 确保加载遮罩是隐藏的
            if (progressOverlay != null) {
                progressOverlay.dismiss();
                progressOverlay = null;
            }
            // 恢复系统 UI 隐藏状态
            hideSystemUi(500);
            return;
        }

        if (shouldResumeSession) {
            LimeLog.info("从后台恢复，正在快速重连...");

            // 强制关闭所有残留的 Dialog
            // 即使之前的 connectionTerminated 漏网弹出了对话框，现在也把它关掉
            Dialog.closeDialogs();

            // 重置状态，准备迎接新的连接
            // 只有回到前台准备重连了，我们才再次关心连接失败的弹窗
            shouldResumeSession = false;
            displayedFailureDialog = false;

            // 重新显示加载遮罩
            progressOverlay = new FullscreenProgressOverlay(this, app);
            ComputerDetails computer = new ComputerDetails();
            computer.name = pcName;
            computer.uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
            progressOverlay.setComputer(computer);
            progressOverlay.show(getResources().getString(R.string.conn_establishing_title),
                    getResources().getString(R.string.conn_establishing_msg));


            try {
                // 这个方法内部涉及 InputManager 和 Service 绑定，必须在主线程
                prepareConnection();
            } catch (Exception e) {
                LimeLog.severe("Failed to prepare connection: " + e.getMessage());
                // 如果准备失败，最好结束 Activity 防止状态错乱
                finish();
                return;
            }

            // 重置连接状态标志
            attemptedConnection = false;
            connecting = false;
            connected = false;
            orientationManager.setConnected(false);

            // 通知 SurfaceView 刷新，这会尽快触发 surfaceChanged
            // 从而触发 conn.start()
            if (streamView != null) {
                streamView.requestLayout();
                streamView.invalidate();
            }
        }
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(() -> Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(() -> Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        LimeLog.info(String.format((Locale) null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));
        if (controllerManager != null) {
            controllerManager.getElementController().gameVibrator(lowFreqMotor, highFreqMotor);
        }
        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        LimeLog.info(String.format((Locale) null, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger));

        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        LimeLog.info("Display HDR mode: " + (enabled ? "enabled" : "disabled"));
        decoderRenderer.setHdrMode(enabled, hdrMetadata);

        // 通知系统 HDR 内容状态（在 Android Q+ 上切换 Window color mode）
        // 这有助于部分 OEM（例如小米的 MIUI）在进入 HDR 时启用正确的色彩/亮度路径。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notifySystemHdrStatus(enabled);
        }
    }

    private void notifySystemHdrStatus(boolean hdrEnabled) {
        runOnUiThread(() -> {
            try {
                // 通过 Window 设置色彩模式（该 API 在 Android Q/API29 引入）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (hdrEnabled) {
                        getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
                    } else {
                        getWindow().setColorMode(ActivityInfo.COLOR_MODE_DEFAULT);
                    }
                }

                // 通过WindowManager.LayoutParams设置亮度
                WindowManager.LayoutParams params = getWindow().getAttributes();
                if (hdrEnabled) {
                    // 根据设置决定是否强制高亮度模式
                    if (prefConfig != null && prefConfig.enableHdrHighBrightness) {
                        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
                    }
                    // 设置窗口标志以支持HDR
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    }
                } else {
                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                }
                getWindow().setAttributes(params);

                LimeLog.info("ColorOS HDR notification: Window color mode and brightness updated for HDR " +
                        (hdrEnabled ? "enabled" : "disabled"));

            } catch (Exception e) {
                LimeLog.warning("Failed to notify ColorOS system HDR status: " + e.getMessage());
            }
        });
    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override
    public void onResolutionChanged(int width, int height) {
        // 确保输入是偶数
        final int alignedWidth = width & ~1;
        final int alignedHeight = height & ~1;

        // 计算基础分辨率（如果有缩放）
        final int baseWidth;
        final int baseHeight;

        if (prefConfig.resolutionScale != 100) {
            baseWidth = (alignedWidth * 100 / prefConfig.resolutionScale) & ~1;
            baseHeight = (alignedHeight * 100 / prefConfig.resolutionScale) & ~1;
            LimeLog.info("Resolution scale conversion: actual=" + alignedWidth + "x" + alignedHeight +
                    ", base=" + baseWidth + "x" + baseHeight + ", scale=" + prefConfig.resolutionScale + "%");
        } else {
            baseWidth = alignedWidth;
            baseHeight = alignedHeight;
        }

        // 首次收到分辨率时，同步方向
        orientationManager.syncOrientationOnFirstFrame(baseWidth, baseHeight);

        // 跳过相同分辨率的重复通知
        if (prefConfig.width == baseWidth && prefConfig.height == baseHeight) {
            return;
        }

        LimeLog.info("Resolution changed: " + prefConfig.width + "x" + prefConfig.height +
                " -> " + baseWidth + "x" + baseHeight);

        // 更新内存中的串流基础分辨率
        prefConfig.width = baseWidth;
        prefConfig.height = baseHeight;

        // 通知解码器分辨率变更
        if (connected && decoderRenderer != null) {
            decoderRenderer.onResolutionChanged(baseWidth, baseHeight);
        }

        final boolean isLandscape = baseWidth > baseHeight;

        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.host_resolution_changed, baseWidth, baseHeight),
                    Toast.LENGTH_SHORT).show();

            orientationManager.onServerResolutionChanged(isLandscape);

            updateStreamViewSize(baseWidth, baseHeight);
        });
    }

    /**
     * 设置视频 Surface 的尺寸和缩放模式
     *
     * @param width 视频宽度（像素）
     * @param height 视频高度（像素）
     * @param forceFixedSize 是否强制使用固定尺寸（用于 Android M 以下且宽高比匹配的情况）
     */
    private void updateStreamViewSize(int width, int height, boolean forceFixedSize) {
        if (streamView == null) {
            return;
        }

        // 获取屏幕真实物理尺寸（像素），使用 getRealSize 而不是 getSize
        // getSize 返回的是可用区域（去掉了状态栏和导航栏），getRealSize 返回真实屏幕尺寸
        Display display = externalDisplayManager != null ?
                externalDisplayManager.getTargetDisplay() : getWindowManager().getDefaultDisplay();
        Point screenSize = new Point();
        display.getRealSize(screenSize);

        // 检查主机分辨率是否超过屏幕物理尺寸
        boolean exceedsScreenSize = width > screenSize.x || height > screenSize.y;

        // 决定使用固定尺寸还是按比例缩放：
        // 1. stretchVideo 开启且不超过屏幕尺寸 -> 固定尺寸
        // 2. forceFixedSize (Android M 以下且宽高比匹配) -> 固定尺寸
        // 3. 其他情况 -> 按比例缩放
        boolean useFixedSize = (prefConfig.stretchVideo && !exceedsScreenSize) || forceFixedSize;

        if (useFixedSize) {
            // Surface 固定为视频尺寸
            streamView.setDesiredAspectRatio(0);
            streamView.getHolder().setFixedSize(width, height);
            LimeLog.info("Set fixed surface size: " + width + "x" + height +
                    " (screen: " + screenSize.x + "x" + screenSize.y + ")");
        } else {
            // 保持比例显示，或分辨率超过屏幕时让系统自动缩放
            if (exceedsScreenSize) {
                LimeLog.info("Host resolution " + width + "x" + height +
                        " exceeds screen size " + screenSize.x + "x" + screenSize.y +
                        ", using aspect ratio scaling");
            }
            // 清除之前的固定尺寸设置，确保宽高比缩放正常工作
            streamView.getHolder().setSizeFromLayout();
            streamView.setDesiredAspectRatio((double) width / height);
            streamView.requestLayout();
        }
    }

    /**
     * 设置视频 Surface 尺寸（默认不强制固定尺寸）
     */
    private void updateStreamViewSize(int width, int height) {
        updateStreamViewSize(width, height, false);
    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface changed before creation!");
        }


        if (decoderRenderer != null) {
            // 1. 设置回真正的屏幕 Holder
            decoderRenderer.setRenderTarget(holder);
        }

        if (!attemptedConnection) {
            attemptedConnection = true; // 标记已尝试连接

            // Update GameManager state to indicate we're "loading" while connecting
            UiHelper.notifyStreamConnecting(Game.this);



            // 实例化并保存到成员变量
            this.audioRenderer = new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx, prefConfig.enableSpatializer);

            // 使用成员变量启动连接
            conn.start(this.audioRenderer, decoderRenderer, Game.this);

            if (streamView != null) {
                streamView.post(() -> cursorServiceManager.syncCursorWithStream());
            }
        } else if (connected && isExtremeResumeEnabled) {
            // 恢复时强制同步一次光标位置，防止错位
            if (streamView != null) {
                streamView.post(() -> cursorServiceManager.syncCursorWithStream());
            }

            // 回到前台，恢复音量
            if (audioRenderer != null) {
                audioRenderer.resumeProcessing();
            }

            // 回到前台，恢复视频渲染器
            if (decoderRenderer != null) {
                decoderRenderer.resumeProcessing();
            }
        }

        // 处理缩放手势
        panZoomHandler.handleSurfaceChange();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        float desiredFrameRate;

        surfaceCreated = true;

        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        if (DisplayModeManager.mayReduceRefreshRate(prefConfig) || desiredRefreshRate < prefConfig.fps) {
            desiredFrameRate = prefConfig.fps;
        } else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredFrameRate = desiredRefreshRate;
        }

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }

        // 销毁本地光标渲染器
        cursorServiceManager.destroyLocalCursorRenderers();

        if (attemptedConnection) {
            if (isExtremeResumeEnabled && !isFinishing()) {

                // 如果为true，则静音
                SharedPreferences globalPrefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (!globalPrefs.getBoolean("checkbox_background_audio", false)) {
                    if (audioRenderer != null) {
                        audioRenderer.pauseProcessing();
                        LimeLog.info("Extreme Resume: Audio muted for background.");
                    }
                }

                // 2. 暂停视频解码器并释放硬件资源
                if (decoderRenderer != null) {
                    decoderRenderer.pauseProcessing();
                }
                return; // 安全返回，后台停止处理
            } else {
                // 正常退出
                decoderRenderer.prepareForStop();
                if (connected) {
                    connectionCallbackHandler.stopConnection();
                }
            }
        }
    }

    private static final int KEEP_ALIVE_NOTIFICATION_ID = 1001;

    void showKeepAliveNotification() {
        // 1. Android 13 权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                // 请求权限
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        KEEP_ALIVE_NOTIFICATION_ID);
                return;
            }
        }

        // 2. 请求电池优化白名单（防止被 Doze 模式杀死）
        // 优化方案：仅在用户开启“自动恢复串流”且尚未请求过电池优化时才提示
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean isResumeEnabled = prefs.getBoolean("checkbox_resume_stream", false);
                boolean hasRequestedOptimization = prefs.getBoolean("pref_battery_optimization_requested", false);

                if (isResumeEnabled && !hasRequestedOptimization) {
                    if (ContextCompat.checkSelfPermission(this, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                            == PackageManager.PERMISSION_GRANTED) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(this.getPackageName())) {
                            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + this.getPackageName()));
                            try {
                                startActivity(intent);
                                // 记录已请求过，避免下次再弹
                                prefs.edit().putBoolean("pref_battery_optimization_requested", true).apply();
                            } catch (Exception e) {
                                LimeLog.warning("Cannot open battery optimization settings: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略电池优化白名单请求的异常
        }

        // 3. 启动服务 + 通知
        StreamNotificationService.start(this, pcName, appName);
    }

    void cancelKeepAliveNotification() {
        // 停止通知服务
        StreamNotificationService.stop(this);
    }

    /**
     * 委托给 CursorServiceManager 刷新本地光标状态
     */
    public void refreshLocalCursorState(boolean enabled) {
        if (cursorServiceManager != null) {
            cursorServiceManager.refreshLocalCursorState(enabled);
        }
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId) {
            case EvdevListener.BUTTON_LEFT:
                buttonIndex = MouseButtonPacket.BUTTON_LEFT;
                break;
            case EvdevListener.BUTTON_MIDDLE:
                buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
                break;
            case EvdevListener.BUTTON_RIGHT:
                buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
                break;
            case EvdevListener.BUTTON_X1:
                buttonIndex = MouseButtonPacket.BUTTON_X1;
                break;
            case EvdevListener.BUTTON_X2:
                buttonIndex = MouseButtonPacket.BUTTON_X2;
                break;
            default:
                LimeLog.warning("Unhandled button: " + buttonId);
                return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        } else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseVScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void mouseHScroll(byte amount) {
        conn.sendMouseHScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = keyboardTranslator.translate(keyCode, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), (byte) 0);
            } else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), (byte) 0);
            }
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        } else if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
    }

    @Override
    public void onPerfUpdateV(final PerformanceInfo performanceInfo) {
        if (performanceOverlayManager != null) {
            performanceOverlayManager.updatePerformanceInfo(performanceInfo);
        }
    }

    @Override
    public boolean isPerfOverlayVisible() {
        return performanceOverlayManager != null && performanceOverlayManager.isPerfOverlayVisible();
    }

    @Override
    public void onPerfUpdateWG(final PerformanceInfo performanceInfo) {
        runOnUiThread(() -> {
            long currentRxBytes = TrafficStats.getTotalRxBytes();
            long timeMillis = System.currentTimeMillis();
            long timeMillisInterval = timeMillis - previousTimeMillis;

            // 只在时间间隔合理时计算带宽，避免异常值
            if (timeMillisInterval > 0 && timeMillisInterval < 5000) {
                performanceInfo.bandWidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);
            }

            previousTimeMillis = timeMillis;
            previousRxBytes = currentRxBytes;

            if (controllerManager != null && !performanceInfoDisplays.isEmpty()) {
                Map<String, String> perfAttrs = new HashMap<>();
                perfAttrs.put(getString(R.string.perf_decoder), performanceInfo.decoder);
                perfAttrs.put(getString(R.string.perf_resolution), performanceInfo.initialWidth + "x" + performanceInfo.initialHeight);
                perfAttrs.put(getString(R.string.perf_fps), String.format("%.0f", performanceInfo.totalFps));
                perfAttrs.put(getString(R.string.perf_frame_loss), String.format("%.1f", performanceInfo.lostFrameRate));
                perfAttrs.put(getString(R.string.perf_network_rtt), String.format("%d", (int) (performanceInfo.rttInfo >> 32)));
                perfAttrs.put(getString(R.string.perf_host_latency), String.format("%.2f", performanceInfo.aveHostProcessingLatency));
                perfAttrs.put(getString(R.string.perf_decode_time), String.format("%.2f", performanceInfo.decodeTimeMs));
                perfAttrs.put(getString(R.string.perf_bandwidth), performanceInfo.bandWidth);
                perfAttrs.put(getString(R.string.perf_render_latency), String.format("%.2f", performanceInfo.renderingLatencyMs));
                for (PerformanceInfoDisplay performanceInfoDisplay : performanceInfoDisplays) {
                    performanceInfoDisplay.display(perfAttrs);
                }
            }

        });
    }

    public void removePerformanceInfoDisplay(PerformanceInfoDisplay display) {
        performanceInfoDisplays.remove(display);
    }

    @Override
    public void onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++;
        updatePipAutoEnter();
    }

    @Override
    public void onUsbPermissionPromptCompleted() {
        suppressPipRefCount--;
        updatePipAutoEnter();
    }

    /**
     * 根据当前设置的状态，显示不同的游戏菜单。
     *
     * @param device 可能是触发菜单的输入设备，可以为 null
     */
    public void showGameMenu(GameInputDevice device) {
        switch (currentBackKeyMenu) {
            case CROWN_MODE:
                if (controllerManager != null && prefConfig.onscreenKeyboard) {
                    controllerManager.getSuperPagesController().returnOperation();
                }
                break;
            case NO_MENU:
                // 无操作，直接返回
                break;
            case GAME_MENU:
            default:
                new GameMenu(this, app, conn, device);
                break;
        }
    }


    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return handleKeyDown(keyEvent);
            case KeyEvent.ACTION_UP:
                return handleKeyUp(keyEvent);
            case KeyEvent.ACTION_MULTIPLE:
                return handleKeyMultiple(keyEvent);
            default:
                return false;
        }
    }

    public void disconnect() {
        finish();
    }

    @Override
    public void onBackPressed() {
        // Instead of "closing" the game activity open the game menu. The user has to select
        // "Disconnect" within the game menu to actually disconnect from the remote host.
        //
        // Use the onBackPressed instead of the onKey function, since the onKey function
        // also captures events while having the on-screen keyboard open.  Using onBackPressed
        // ensures that Android properly handles the back key when needed and only open the game
        // menu when the activity would be closed.
        showGameMenu(null);
    }

    private boolean isPhysicalKeyboardConnected() {
        return getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY;
    }

    /**
     * 切换逻辑：关闭 -> 悬浮 -> 固定 -> 关闭
     */
    public void togglePerformanceOverlay() {
        if (performanceOverlayManager == null) {
            return;
        }

        // 1. 当前是【关闭】状态 -> 切换到【悬浮】
        if (!prefConfig.enablePerfOverlay) {
            prefConfig.enablePerfOverlay = true;
            prefConfig.perfOverlayLocked = false;
            performanceOverlayManager.applyOverlayState(); // 应用状态
        }

        // 2. 当前是【悬浮】状态 -> 切换到【固定】
        else if (!prefConfig.perfOverlayLocked) {
            prefConfig.perfOverlayLocked = true;
            performanceOverlayManager.applyOverlayState(); // 应用状态
        }

        // 3. 当前是【固定】状态 -> 切换到【关闭】
        else {
            prefConfig.enablePerfOverlay = false;
            prefConfig.perfOverlayLocked = false; // 重置回默认
            performanceOverlayManager.applyOverlayState(); // 应用状态
        }

        prefConfig.writePreferences(this);
    }

    /**
     * 切换麦克风按钮的显示/隐藏状态
     */
    public void toggleMicrophoneButton() {
        if (micButton != null) {
            if (micButton.getVisibility() == View.VISIBLE) {
                micButton.setVisibility(View.GONE);
                Toast.makeText(this, getString(R.string.toast_mic_button_hidden), Toast.LENGTH_SHORT).show();
            } else {
                micButton.setVisibility(View.VISIBLE);
                Toast.makeText(this, getString(R.string.toast_mic_button_shown), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 切换虚拟手柄覆盖层的显示/隐藏状态
     */
    public void toggleVirtualController() {
        if (virtualController != null && !virtualController.getElements().isEmpty()) {
            // 检查第一个元素的可见性来判断当前状态
            boolean isVisible = virtualController.getElements().get(0).getVisibility() == View.VISIBLE;

            if (isVisible) {
                virtualController.hide();
                Toast.makeText(this, getString(R.string.toast_virtual_controller_hidden), Toast.LENGTH_SHORT).show();
            } else {
                virtualController.show();
                Toast.makeText(this, getString(R.string.toast_virtual_controller_shown), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_virtual_controller_not_enabled), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 初始化控制器管理器（王冠功能）
     */
    public void initializeControllerManager() {
        if (controllerManager == null) {
            controllerManager = new ControllerManager((FrameLayout) streamView.getParent(), this);
            controllerManager.refreshLayout();
        }
    }

    /**
     * 设置王冠功能状态
     */
    public void setCrownFeatureEnabled(boolean enabled) {
        prefConfig.onscreenKeyboard = enabled;
        if (enabled) {
            // 启用王冠模式
            if (controllerManager != null) {
                controllerManager.show();
            } else {
                initializeControllerManager();
            }
        } else {
            // 禁用王冠模式
            if (controllerManager != null) {
                controllerManager.hide();
            }
        }
    }

    /**
     * 获取王冠功能状态
     */
    public boolean isCrownFeatureEnabled() {
        return prefConfig.onscreenKeyboard;
    }

    public ControllerHandler getControllerHandler() {
        return controllerHandler;
    }

    public void addPerformanceInfoDisplay(PerformanceInfoDisplay performanceInfoDisplay) {
        performanceInfoDisplays.add(performanceInfoDisplay);
    }

    // 更新刷新显示位置方法
    public void refreshDisplayPosition() {
        new DisplayPositionManager(this, prefConfig, streamView).refreshDisplayPosition(surfaceCreated);
    }

    public StreamView getStreamView() {
        return streamView;
    }

    /**
     * 获取当前活动的StreamView（优先使用外接显示器的StreamView）
     */
    public StreamView getActiveStreamView() {
        if (externalDisplayManager != null && externalDisplayManager.isUsingExternalDisplay() && externalStreamView != null) {
            return externalStreamView;
        }
        return streamView;
    }

    public boolean getHandleMotionEvent(StreamView streamView, MotionEvent event) {
        return touchInputHandler.handleMotionEvent(streamView, event);
    }

    /**
     * 应用上一次设置到当前会话（不覆盖全局配置）
     */
    private void applyLastSettingsToCurrentSession() {
        if (appSettingsManager != null) {
            // 使用AppSettingsManager统一处理上一次设置的应用
            boolean applied = appSettingsManager.applyLastSettingsFromIntent(getIntent(), prefConfig);

            if (applied) {
                // 显示提示信息
                Toast.makeText(this, getString(R.string.app_last_settings_start_with_last), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Toast.makeText(this, getString(R.string.toast_enable_notification_for_bg), Toast.LENGTH_LONG).show();
            }
        }
    }
}