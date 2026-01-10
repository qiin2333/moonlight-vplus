package com.limelight;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.audio.AudioDiagnostics;
import com.limelight.binding.audio.MicrophoneManager;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.TouchController;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.LocalCursorRenderer;
import com.limelight.binding.input.touch.NativeTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.PerformanceInfo;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.CursorView;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.Dialog;
import com.limelight.utils.PanZoomHandler;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.FullscreenProgressOverlay;
import com.limelight.utils.UiHelper;
import com.limelight.utils.NetHelper;
import com.limelight.utils.AnalyticsManager;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.AppSettingsManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Rational;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

import com.limelight.services.KeyboardAccessibilityService;

public class Game extends Activity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener, KeyboardAccessibilityService.KeyEventCallback {
    private int lastButtonState = 0;
    // 这个标志位用于区分事件是来自无障碍服务还是来自UI（如StreamView）
    private boolean isEventFromAccessibilityService = false;
    private static final int TOUCH_CONTEXT_LENGTH = 2;

    // Only 2 touches are supported
    private TouchContext[] touchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private final TouchContext[] absoluteTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private final TouchContext[] relativeTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private long multiFingerDownTime = 0;

    // 双指右键检测
    private long twoFingerDownTime = 0;
    private long firstFingerUpTime = 0;
    private boolean twoFingerTapPending = false;
    private boolean twoFingerMoved = false;
    private float twoFingerStartX = 0, twoFingerStartY = 0;
    private static final int TWO_FINGER_TAP_THRESHOLD = 100;
    private static final float TWO_FINGER_MOVE_THRESHOLD = 30f;

    public static final int REFERENCE_HORIZ_RES = 1280;
    public static final int REFERENCE_VERT_RES = 720;

    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int MULTI_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;
    private VirtualController virtualController;
    private PanZoomHandler panZoomHandler;

    public interface PerformanceInfoDisplay {
        void display(Map<String, String> performanceAttrs);
    }

    private ControllerManager controllerManager;
    private List<PerformanceInfoDisplay> performanceInfoDisplays = new ArrayList<>();

    private MicrophoneManager microphoneManager;

    // 麦克风按钮
    private ImageButton micButton;

    PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private NvConnection conn;
    private FullscreenProgressOverlay progressOverlay;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean autoEnterPip = false;
    private boolean surfaceCreated = false;
    private boolean attemptedConnection = false;
    private AnalyticsManager analyticsManager;
    private long streamStartTime;
    private int suppressPipRefCount = 0;
    private String pcName;
    private String appName;
    private NvApp app;
    private float desiredRefreshRate;
    private AppSettingsManager appSettingsManager;
    private String computerUuid;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private StreamView streamView;
    private StreamView externalStreamView; // 外接显示器的StreamView
    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;

    // ESC键双击相关变量
    private static final long ESC_DOUBLE_PRESS_INTERVAL = 500; // 500毫秒内按第二次ESC才有效
    private long lastEscPressTime = 0;
    private boolean hasShownEscHint = false;

    private boolean isHidingOverlays;
    private androidx.cardview.widget.CardView notificationOverlayView;
    private TextView notificationTextView;
    private int requestedNotificationOverlayVisibility = View.GONE;

    // 性能覆盖层管理器
    private PerformanceOverlayManager performanceOverlayManager;

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;
    private Map<Integer, NativeTouchContext.Pointer> nativeTouchPointerMap = new HashMap<>();
    private String currentHostAddress; // 保存当前连接的IP
    private boolean shouldResumeSession = false;

    public enum BackKeyMenuMode {
        GAME_MENU,     // 游戏菜单模式
        CROWN_MODE,    // 王冠模式
        NO_MENU        // 无菜单模式
    }


    private BackKeyMenuMode currentBackKeyMenu = BackKeyMenuMode.GAME_MENU; // 默认为游戏菜单模式

    public void setcurrentBackKeyMenu(BackKeyMenuMode currentBackKeyMenu) {
        this.currentBackKeyMenu = currentBackKeyMenu;
    }

    public BackKeyMenuMode getCurrentBackKeyMenu() {
        return currentBackKeyMenu;
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

    private boolean connectedToUsbDriverService = false;
    private UsbDriverService.UsbDriverBinder usbDriverBinder;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            usbDriverBinder = binder;
            binder.setListener(controllerHandler);
            binder.setStateListener(Game.this);
            binder.start();
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
            usbDriverBinder = null;
        }
    };

    private void stopAndUnbindUsbDriverService() {
        if (connectedToUsbDriverService) {
            if (usbDriverBinder != null) {
                try {
                    usbDriverBinder.stop();
                } catch (Exception ignored) {
                }
            }
            try {
                unbindService(usbDriverServiceConnection);
            } catch (Exception ignored) {
            }
            connectedToUsbDriverService = false;
            usbDriverBinder = null;
        }
    }

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

    private ExternalDisplayManager externalDisplayManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        tombstonePrefs = Game.this.getSharedPreferences("DecoderTombstone", 0);

        // Initialize app settings manager
        appSettingsManager = new AppSettingsManager(this);

        // Save computer UUID for later use
        computerUuid = getIntent().getStringExtra(EXTRA_PC_UUID);

        // 检查是否使用上一次设置并应用（不覆盖全局配置）
        applyLastSettingsToCurrentSession();

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
        setPreferredOrientationForCurrentDisplay();

        if (prefConfig.stretchVideo || shouldIgnoreInsetsForResolution(prefConfig.width, prefConfig.height)) {
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

        // 1. 添加监听器 (应对屏幕旋转、大小变化)
        streamView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // 只有当尺寸或位置真的变了才执行
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                syncCursorWithStream();
            }
        });

        // 2. 手动强制执行一次
        // 使用 post 确保在 UI 绘制队列的下一个节拍执行，此时 View 的宽/高已经计算好了
        streamView.post(new Runnable() {
            @Override
            public void run() {
                syncCursorWithStream();
            }
        });

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

        notificationOverlayView = findViewById(R.id.notificationOverlay);
        notificationTextView = findViewById(R.id.notificationText);

        micButton = findViewById(R.id.micButton);

        // 初始化性能覆盖层管理器
        performanceOverlayManager = new PerformanceOverlayManager(this, prefConfig);
        performanceOverlayManager.initialize();

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    return handleMotionEvent(view, motionEvent);
                }
            });
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
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        keyboardTranslator = new KeyboardTranslator();

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);


        // Initialize touch contexts
        for (int i = 0; i < TOUCH_CONTEXT_LENGTH; i++) {
            absoluteTouchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView);
            relativeTouchContextMap[i] = new RelativeTouchContext(conn, i,
                    streamView, prefConfig);
        }
        if (!prefConfig.touchscreenTrackpad) {
            touchContextMap = absoluteTouchContextMap;
        } else {
            touchContextMap = relativeTouchContextMap;
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout) streamView.getParent(),
                    this);
            virtualController.refreshLayout();
            virtualController.show();

            virtualController.setGyroEnabled(true);
        }

        if (prefConfig.onscreenKeyboard) {
            // create virtual onscreen keyboard
            controllerManager = new ControllerManager((FrameLayout) streamView.getParent(), this);
            controllerManager.refreshLayout();
        }

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
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
                    new CrashListener() {
                        @Override
                        public void notifyCrash(Exception e) {
                            // The MediaCodec instance is going down due to a crash
                            // let's tell the user something when they open the app again
                            // We must use commit because the app will crash when we return from this function
                            tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                            reportedCrash = true;
                        }
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
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                .setUseVdd(pcUseVdd)
                .setEnableMic(prefConfig.enableMic)
                .setControlOnly(prefConfig.controlOnly)
                .setCustomScreenMode(prefConfig.screenCombinationMode)
                .build();

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
        destroyLocalCursorRenderers();
        runOnUiThread(() -> {
            CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
            if (cursorOverlay != null) {
                cursorOverlay.resetToDefault();
                cursorOverlay.hide();
            }

            // 清理可能残留的网络质量提示
            if (notificationOverlayView != null) {
                notificationOverlayView.setVisibility(View.GONE);
            }
        });
        // 重置状态变量
        requestedNotificationOverlayVisibility = View.GONE;

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
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);

        // 重新创建 ControllerHandler
        if (controllerHandler != null) controllerHandler.stop();
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);

        //  重新绑定 USB 驱动服务
        // 因为 stopConnection 时解绑了，这里必须重新 bind，而不是直接 setListener
        if (prefConfig.usbDriver) {
            // 如果旧的连接还没断开（理论上 stopConnection 已断开），先断开以防万一
            stopAndUnbindUsbDriverService();

            // 重新绑定服务
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        if (connectedToUsbDriverService && usbDriverBinder != null) {
            usbDriverBinder.setListener(controllerHandler);
        }

        // 重新初始化触控
        // 必须在 ControllerManager 初始化之前完成，因为 ControllerManager 会调用它来设置灵敏度
        // Initialize touch contexts
        for (int i = 0; i < TOUCH_CONTEXT_LENGTH; i++) {
            absoluteTouchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView);
            relativeTouchContextMap[i] = new RelativeTouchContext(conn, i,
                    streamView, prefConfig);
        }
        if (!prefConfig.touchscreenTrackpad) {
            touchContextMap = absoluteTouchContextMap;
        } else {
            touchContextMap = relativeTouchContextMap;
        }

        //  重建虚拟手柄和屏幕键盘管理器
        // 必须这样做，因为它们需要绑定新的 controllerHandler 和 conn
        if (virtualController != null) {
            if (prefConfig.onscreenController) {
                // 这里调用 refreshLayout 确保位置正确
                virtualController.refreshLayout();
                virtualController.show();
                virtualController.setGyroEnabled(true);
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

    private void setPreferredOrientationForCurrentDisplay() {
        Display display = externalDisplayManager != null ?
                externalDisplayManager.getTargetDisplay() : getWindowManager().getDefaultDisplay();

        // 首先确定基于分辨率的所需方向
        int desiredOrientation = Configuration.ORIENTATION_UNDEFINED;

        // 根据配置的宽高比确定横屏或竖屏
        if (prefConfig.width > prefConfig.height) {
            desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
        } else if (prefConfig.height > prefConfig.width) {
            desiredOrientation = Configuration.ORIENTATION_PORTRAIT;
        } else {
            // 宽高相等的情况
            // 如果使用屏幕控制器，默认使用横屏
            if (prefConfig.onscreenController || prefConfig.onscreenKeyboard) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
            }
        }

        if (prefConfig.rotableScreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        } else if (PreferenceConfiguration.isSquarishScreen(display)) {
            // 对于接近正方形的屏幕，应用更复杂的逻辑
            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            } else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            } else {
                // 没有明确的理由锁定为横屏或竖屏时，允许任意方向
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            }
        } else {
            // 对于非方形屏幕，按照分辨率决定方向
            if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            } else {
                // 默认或横屏情况
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 将权限结果传递给麦克风管理器
        if (microphoneManager != null) {
            microphoneManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Set requested orientation for possible new screen size
        setPreferredOrientationForCurrentDisplay();

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
                isHidingOverlays = true;

                if (virtualController != null) {
                    virtualController.hide();
                }

                if (performanceOverlayManager != null) {
                    performanceOverlayManager.hideOverlayImmediate();
                }
                notificationOverlayView.setVisibility(View.GONE);

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
                isHidingOverlays = false;

                // Restore overlays to previous state when leaving PiP

                if (virtualController != null) {
                    virtualController.show();
                }

                if (performanceOverlayManager != null) {
                    performanceOverlayManager.applyRequestedVisibility();
                }
                if (requestedNotificationOverlayVisibility == View.VISIBLE) {
                    notificationOverlayView.setVisibility(View.VISIBLE);
                } else {
                    notificationOverlayView.setVisibility(View.GONE);
                }

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

        // Re-apply display position
        refreshDisplayPosition();
    }

    @TargetApi(Build.VERSION_CODES.O)
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
    @TargetApi(Build.VERSION_CODES.R)
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

    private boolean isRefreshRateEqualMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                refreshRate <= prefConfig.fps + 3;
    }

    private boolean isRefreshRateGoodMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                Math.round(refreshRate) % prefConfig.fps <= 3;
    }

    private boolean shouldIgnoreInsetsForResolution(int width, int height) {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display display = getWindowManager().getDefaultDisplay();
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Ignore insets if this is an exact match for the display resolution
                if ((width == candidate.getPhysicalWidth() && height == candidate.getPhysicalHeight()) ||
                        (height == candidate.getPhysicalWidth() && width == candidate.getPhysicalHeight())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean mayReduceRefreshRate() {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig.reduceRefreshRate);
    }

    private float prepareDisplayForRendering() {
        Display display = externalDisplayManager != null ?
                externalDisplayManager.getTargetDisplay() : getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        float displayRefreshRate;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            boolean isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height);
            boolean refreshRateIsGood = isRefreshRateGoodMatch(bestMode.getRefreshRate());
            boolean refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.getRefreshRate());

            LimeLog.info("Current display mode: " + bestMode.getPhysicalWidth() + "x" +
                    bestMode.getPhysicalHeight() + "x" + bestMode.getRefreshRate());

            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateReduced = candidate.getRefreshRate() < bestMode.getRefreshRate();
                boolean resolutionReduced = candidate.getPhysicalWidth() < bestMode.getPhysicalWidth() ||
                        candidate.getPhysicalHeight() < bestMode.getPhysicalHeight();
                boolean resolutionFitsStream = candidate.getPhysicalWidth() >= prefConfig.width &&
                        candidate.getPhysicalHeight() >= prefConfig.height;

                LimeLog.info("Examining display mode: " + candidate.getPhysicalWidth() + "x" +
                        candidate.getPhysicalHeight() + "x" + candidate.getRefreshRate());

                if (candidate.getPhysicalWidth() > 4096 && prefConfig.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue;
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                    continue;
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.getRefreshRate())) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue;
                } else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                        continue;
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                            continue;
                        }
                    } else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue;
                        }
                    }
                } else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue;
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate;
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate());
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate());
            }

            LimeLog.info("Best display mode: " + bestMode.getPhysicalWidth() + "x" +
                    bestMode.getPhysicalHeight() + "x" + bestMode.getRefreshRate());

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.getMode().getModeId() != bestMode.getModeId()) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || UiHelper.isColorOS() ||
                        display.getMode().getPhysicalWidth() != bestMode.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() != bestMode.getPhysicalHeight()) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(windowLayoutParams);
                } else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                }
            } else {
                LimeLog.info("Current display mode is already the best display mode");
            }

            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want a refresh rate
        else {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                LimeLog.info("Examining refresh rate: " + candidate);

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig.fps <= 60) {
                        if (candidate >= 63) {
                            continue;
                        }
                    }

                    bestRefreshRate = candidate;
                }
            }

            LimeLog.info("Selected refresh rate: " + bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;

            // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams);
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double) screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double) prefConfig.height) / prefConfig.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        updateStreamViewSize(prefConfig.width, prefConfig.height, aspectRatioMatch);

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate;

        // Return the selected display refresh rate (from bestMode.getRefreshRate())
        // This is the refresh rate we requested the system to switch to.
        //
        // Important: We intentionally return displayRefreshRate directly instead of
        // checking the current display refresh rate, because:
        // 1. Display mode switching is asynchronous - the current refresh rate may still
        //    be the old value when this method returns
        // 2. The server needs to know the refresh rate we WILL be running at, not the
        //    old refresh rate before the switch completes
        // 3. This value is used for clientRefreshRateX100 which determines the server's
        //    capture and encoding frame rate - it must match our target refresh rate
        return displayRefreshRate;
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
        @Override
        public void run() {
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
        }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
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
        super.onDestroy();

        if (controllerHandler != null) {
            controllerHandler.destroy();
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

        stopAndUnbindUsbDriverService();

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

    @Override
    protected void onStop() {
        super.onStop();

        // 检查是否是因为进入后台（包括锁屏、滑到任务栏、Home键）导致的应用停止
        // 只要 Activity 不是正在 Finishing（即不是用户点了退出或崩溃），且开启了快速恢复，就标记为需要恢复
        if (!shouldResumeSession && !isFinishing()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean isResumeEnabled = prefs.getBoolean("checkbox_resume_stream", false);

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
            stopConnection();

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
            long streamDuration = System.currentTimeMillis() - streamStartTime;

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

            analyticsManager.logGameStreamEnd(pcName, appName, streamDuration,
                    decoderMessage, resolutionWidth, resolutionHeight,
                    averageEndToEndLatency, averageDecoderLatency);
        }

        if (shouldResumeSession) {
            LimeLog.info("应用进入后台，保持 Activity 存活以备快速恢复。连接已断开。");
        } else {
            finish();
        }
    }

    private void setInputGrabState(boolean grab) {
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

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

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

    @Override
    public boolean handleKeyDown(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
                // 如果是系统导航键，则跳过我们的去重逻辑，
                // 让事件继续被正常处理。
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
                    Toast.makeText(this, "再次按 " + keyName + " 键打开串流菜单", Toast.LENGTH_SHORT).show();
                    lastEscPressTime = currentTime;
                    hasShownEscHint = true;
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

    private TouchContext getTouchContext(int actionIndex) {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        } else {
            return null;
        }
    }

    public TouchContext[] getTouchContextMap() {
        return touchContextMap;
    }

    public RelativeTouchContext[] getRelativeTouchContextMap() {
        RelativeTouchContext[] result = new RelativeTouchContext[relativeTouchContextMap.length];
        for (int i = 0; i < relativeTouchContextMap.length; i++) {
            if (relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                result[i] = (RelativeTouchContext) relativeTouchContextMap[i];
            }
        }
        return result;
    }

    /**
     * false : AbsoluteTouchContext
     * true : RelativeTouchContext
     */
    public void setTouchMode(boolean enableRelativeTouch) {

        for (int i = 0; i < touchContextMap.length; i++) {
            if (enableRelativeTouch) {
                prefConfig.touchscreenTrackpad = true;
                prefConfig.enableNativeMousePointer = false;
                touchContextMap = relativeTouchContextMap;
                refreshLocalCursorState(prefConfig.enableLocalCursorRendering); //如果本地光标处于开启状态，则开启本地光标
            } else {
                prefConfig.touchscreenTrackpad = false;
                touchContextMap = absoluteTouchContextMap;
                refreshLocalCursorState(false); //关闭本地光标
            }
        }
    }

    public void setEnhancedTouch(boolean enableRelativeTouch) {
        prefConfig.enableEnhancedTouch = enableRelativeTouch;
        if (prefConfig.enableEnhancedTouch) {
            prefConfig.enableNativeMousePointer = false;
        }

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

            refreshLocalCursorState(true);//开启本地光标服务

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

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                } else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
                return -1;
        }
    }


    /**
     * getStreamViewRelativeNormalizedXY
     * 正确地处理了视图的平移(Pan)和缩放(Zoom)。
     */
    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        StreamView activeStreamView = getActiveStreamView();
        if (activeStreamView == null) {
            return new float[]{0.0f, 0.0f};
        }

        // --- 第一步：获取原始屏幕坐标 ---
        float rawX = event.getX(pointerIndex);
        float rawY = event.getY(pointerIndex);

        // --- 第二步：进行正确的坐标逆变换（同时处理平移和缩放）---
        float scaleX = activeStreamView.getScaleX();
        float scaleY = activeStreamView.getScaleY();

        if (scaleX == 0 || scaleY == 0) {
            return new float[]{0.0f, 0.0f};
        }

        // 计算出在游戏画面中的【绝对像素坐标】
        float absoluteX = (rawX - activeStreamView.getX()) / scaleX;
        float absoluteY = (rawY - activeStreamView.getY()) / scaleY;

        // --- 第三步：将绝对像素坐标归一化为 0-1 的比例 ---
        int streamWidth = activeStreamView.getWidth();
        int streamHeight = activeStreamView.getHeight();

        if (streamWidth == 0 || streamHeight == 0) {
            return new float[]{0.0f, 0.0f};
        }

        float normalizedX = absoluteX / streamWidth;
        float normalizedY = absoluteY / streamHeight;

        // 确保坐标在 [0.0, 1.0] 的范围内，防止越界
        normalizedX = Math.max(0.0f, Math.min(1.0f, normalizedX));
        normalizedY = Math.max(0.0f, Math.min(1.0f, normalizedY));


        return new float[]{normalizedX, normalizedY};
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[]{(float) (r * Math.cos(theta)), (float) (r * Math.sin(theta))};
    }

    private static float cartesianToR(float[] point) {
        return (float) Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float) (Math.PI / 4);
        } else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float) (orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamView.getHeight()) / streamView.getHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamView.getHeight()) / streamView.getHeight();

        // Convert the normalized values back into polar coordinates
        return new float[]{cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian)};
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte) Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                } else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                // 为触控笔事件添加增强触控支持
                if (prefConfig.enableEnhancedTouch) {
                    NativeTouchContext.Pointer pointer = nativeTouchPointerMap.get(event.getPointerId(i));
                    if (pointer != null) {
                        pointer.updatePointerCoords(event, i); // 更新指针坐标
                    }
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte) 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        } else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }

            // 为触控笔事件添加增强触控支持
            if (prefConfig.enableEnhancedTouch) {
                int actionIndex = event.getActionIndex();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_HOVER_ENTER:
                        // 创建新的Pointer实例
                        NativeTouchContext.Pointer pointer = new NativeTouchContext.Pointer(event);
                        nativeTouchPointerMap.put(pointer.getPointerId(), pointer);
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_HOVER_EXIT:
                        // 移除Pointer实例
                        nativeTouchPointerMap.remove(event.getPointerId(actionIndex));
                        break;
                    case MotionEvent.ACTION_HOVER_MOVE:
                        // 更新悬空指针的坐标
                        NativeTouchContext.Pointer hoverPointer = nativeTouchPointerMap.get(event.getPointerId(actionIndex));
                        if (hoverPointer != null) {
                            hoverPointer.updatePointerCoords(event, actionIndex);
                        }
                        break;
                }
            }

            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex); // normalized Coords就是坐标占长或宽的比例，最小0，最大1
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            int pointerCount = event.getPointerCount();
            if (prefConfig.enableEnhancedTouch) {
                for (int i = 0; i < pointerCount; i++) {
                    NativeTouchContext.Pointer pointer = nativeTouchPointerMap.get(event.getPointerId(i));
                    if (pointer != null) {
                        pointer.updatePointerCoords(event, i); // 更新指针坐标
                    }
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            } else {
                for (int i = 0; i < pointerCount; i++) {
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            }
            return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        } else {
            int actionIndex = event.getActionIndex();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiFingerTapChecker(event);
                case MotionEvent.ACTION_DOWN: // first & following finger down.
                    if (prefConfig.enableEnhancedTouch) {
                        NativeTouchContext.Pointer pointer = new NativeTouchContext.Pointer(event); //create a Pointer Instance for new touch pointer, put it into the map.
                        nativeTouchPointerMap.put(pointer.getPointerId(), pointer);
                    }
                    break;
                case MotionEvent.ACTION_UP: // all fingers up
                    // toggle keyboard when all fingers lift up, just like how it works in trackpad mode.
                    if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                        toggleKeyboard();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (prefConfig.enableEnhancedTouch) {
                        nativeTouchPointerMap.remove(event.getPointerId(actionIndex));
                    }
                    break;
            }
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, actionIndex);
        }
    }

    private void multiFingerTapChecker(MotionEvent event) {
        if (event.getPointerCount() == prefConfig.nativeTouchFingersToToggleKeyboard) {
            // number of fingers to tap is defined by prefConfig.nativeTouchFingersToToggleKeyboard, configurable from 3 to 10, and -1(disabled) in menu.

            // Cancel the first and second touches to avoid
            // erroneous events
            // for (TouchContext aTouchContext : touchContextMap) {
            //    aTouchContext.cancelTouch();
            // }
            multiFingerDownTime = event.getEventTime();
        }
    }

    // 处理缩放下的经典鼠标模式

    /**
     * 核心坐标转换函数
     * 将屏幕上的原始触摸坐标，根据 streamView 的平移和缩放状态，转换为游戏内的“真实”坐标。
     */
    private float[] getNormalizedCoordinates(View streamView, float rawX, float rawY) {
        if (streamView == null) {
            return new float[]{rawX, rawY};
        }
        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();

        // 防止除以零
        if (scaleX == 0 || scaleY == 0) {
            return new float[]{rawX, rawY};
        }

        float normalizedX = (rawX - streamView.getX()) / scaleX;
        float normalizedY = (rawY - streamView.getY()) / scaleY;

        return new float[]{normalizedX, normalizedY};
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    private boolean handleMotionEvent(View view, MotionEvent event) {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;

        // 本地鼠标指针模式的特殊处理
        if (prefConfig.enableNativeMousePointer && (eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            // 检查是否为真正的鼠标设备（而不是触摸屏）
            boolean isActualMouse = (eventSource == InputDevice.SOURCE_MOUSE) ||
                    (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    (event.getPointerCount() >= 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
                    (eventSource == 12290); // Samsung DeX mode

            if (isActualMouse) {
                LimeLog.info("Native mouse event (processing): " + event.getActionMasked() +
                        ", source: " + eventSource +
                        ", x: " + event.getX() +
                        ", y: " + event.getY() +
                        ", buttons: " + event.getButtonState());

                // 在本地鼠标指针模式下，直接处理鼠标事件
                updateMousePosition(view, event);

                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }
                if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }
                if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                // 处理滚轮事件
                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    conn.sendMouseHighResScroll((short) (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    conn.sendMouseHighResHScroll((short) (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                lastButtonState = buttonState;
                return true;
            }
            // 如果不是真正的鼠标设备（比如触摸屏），继续让后续代码处理
        }

        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) { //手柄所属条件
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        } else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        } else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {
            // This case is for mice and non-finger touch devices, 非手指触控功能所属判断条件
            if (eventSource == InputDevice.SOURCE_MOUSE ||
                    (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 || // SOURCE_TOUCHPAD虚拟手柄
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                    (event.getPointerCount() >= 1 &&
                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                    eventSource == 12290) // 12290 = Samsung DeX mode desktop mouse
            {
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                // The DeX touchpad on the Fold 4 sends proper right click events using BUTTON_SECONDARY,
                // but doesn't send BUTTON_PRIMARY for a regular click. Instead it sends ACTION_DOWN/UP,
                // so we need to fix that up to look like a sane input event to process it correctly.
                if (eventSource == 12290) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        buttonState |= MotionEvent.BUTTON_PRIMARY;
                    } else if (event.getAction() == MotionEvent.ACTION_UP) {
                        buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    } else {
                        // We may be faking the primary button down from a previous event,
                        // so be sure to add that bit back into the button state.
                        buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);
                    }

                    changedButtons = buttonState ^ lastButtonState;
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true;
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    short deltaX = (short) inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short) inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            StreamView activeStreamView = getActiveStreamView();
                            conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short) activeStreamView.getWidth(), (short) activeStreamView.getHeight());
                        } else {
                            conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                } else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int) xRange.getMax();
                            int yMax = (int) yRange.getMax();

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn.sendMousePosition((short) event.getX(), (short) event.getY(),
                                        (short) xMax, (short) yMax);
                            }
                        }
                    }
                } else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true;
                } else if (view != null) {
                    // Otherwise send absolute position based on the view for SOURCE_CLASS_POINTER
                    updateMousePosition(view, event);
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll((short) (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    conn.sendMouseHighResHScroll((short) (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        } else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        } else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else  //abs touch 和 屏幕虚拟手柄所属的判断条件
            {
                // 如果处于手势模式，则消费事件用于视图操作，然后立即返回
                if (isTouchOverrideEnabled) {
                    panZoomHandler.handleTouchEvent(event);
                    return true; // 事件被完全消费，不传递给游戏
                }
                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                // ---  多点触控模式 ---
                // 检查是否启用了多点触控，并调用 trySendTouchEvent。
                if (!prefConfig.touchscreenTrackpad && prefConfig.enableEnhancedTouch && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }

                if (virtualController != null &&
                        (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                                virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)) {
                    // Ignore presses when the virtual controller is being configured
                    return true;
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                int actionIndex = event.getActionIndex();

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    multiFingerDownTime = event.getEventTime();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                /*if (!prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }*/

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN: {
                        float[] normalizedCoords = getNormalizedCoordinates(streamView, event.getX(actionIndex), event.getY(actionIndex));
                        for (TouchContext touchContext : touchContextMap) {
                            touchContext.setPointerCount(event.getPointerCount());
                        }

                        // 双指右键检测
                        if (event.getPointerCount() == 2 && prefConfig.touchscreenTrackpad) {
                            twoFingerDownTime = event.getEventTime();
                            twoFingerStartX = event.getX(0);
                            twoFingerStartY = event.getY(0);
                            twoFingerMoved = false;
                            twoFingerTapPending = false;
                        }

                        context.touchDownEvent((int) normalizedCoords[0], (int) normalizedCoords[1], event.getEventTime(), true);
                        break;
                    }
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP: {
                        // 对主触摸点进行转换
                        float[] normalizedCoords = getNormalizedCoordinates(streamView, event.getX(actionIndex), event.getY(actionIndex));

                        // 双指右键检测（仅触控板模式）
                        if (event.getPointerCount() == 2 && !twoFingerMoved && prefConfig.touchscreenTrackpad) {
                            if (event.getEventTime() - twoFingerDownTime < TWO_FINGER_TAP_THRESHOLD) {
                                // 第二根手指抬起，立即触发右键
                                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                twoFingerTapPending = false;
                                twoFingerMoved = true;
                                for (TouchContext touchContext : touchContextMap) {
                                    touchContext.setPointerCount(event.getPointerCount() - 1);
                                }
                                return true;
                            } else {
                                firstFingerUpTime = event.getEventTime();
                                twoFingerTapPending = true;
                            }
                        }

                        if (event.getPointerCount() == 1 &&
                                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                            // 双指点击检测：两个手指都抬起时
                            if (twoFingerTapPending && !twoFingerMoved && prefConfig.touchscreenTrackpad) {
                                if (event.getEventTime() - firstFingerUpTime < TWO_FINGER_TAP_THRESHOLD) {
                                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                    twoFingerTapPending = false;
                                    for (TouchContext touchContext : touchContextMap) {
                                        touchContext.cancelTouch();
                                        touchContext.setPointerCount(0);
                                    }
                                    return true;
                                }
                            }
                            twoFingerTapPending = false;

                            // 三指点击：弹出键盘
                            if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                                toggleKeyboard();
                                return true;
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                            context.cancelTouch();
                        } else {
                            context.touchUpEvent((int) normalizedCoords[0], (int) normalizedCoords[1], event.getEventTime());
                        }

                        for (TouchContext touchContext : touchContextMap) {
                            touchContext.setPointerCount(event.getPointerCount() - 1);
                        }
                        if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                            // 对于多点触控的特殊情况，也需要转换第二个触摸点的坐标
                            float[] normalizedSecondaryCoords = getNormalizedCoordinates(streamView, event.getX(1), event.getY(1));
                            context.touchDownEvent(
                                    (int) normalizedSecondaryCoords[0],
                                    (int) normalizedSecondaryCoords[1],
                                    event.getEventTime(), false);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_MOVE:
                        // 双指移动检测
                        if (event.getPointerCount() == 2 && !twoFingerMoved && prefConfig.touchscreenTrackpad) {
                            float dx = event.getX(0) - twoFingerStartX;
                            float dy = event.getY(0) - twoFingerStartY;
                            if (Math.sqrt(dx * dx + dy * dy) > TWO_FINGER_MOVE_THRESHOLD) {
                                twoFingerMoved = true;
                            }
                        }

                        // ACTION_MOVE 的处理需要更仔细，因为它有历史事件
                        // 首先处理历史事件
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            for (TouchContext aTouchContextMap : touchContextMap) {
                                if (aTouchContextMap.getActionIndex() < event.getPointerCount()) {
                                    float[] histCoords = getNormalizedCoordinates(streamView, event.getHistoricalX(aTouchContextMap.getActionIndex(), i), event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                                    aTouchContextMap.touchMoveEvent((int) histCoords[0], (int) histCoords[1], event.getHistoricalEventTime(i));
                                }
                            }
                        }

                        // Now process the current values
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount()) {
                                float[] currentCoords = getNormalizedCoordinates(streamView, event.getX(aTouchContextMap.getActionIndex()), event.getY(aTouchContextMap.getActionIndex()));
                                aTouchContextMap.touchMoveEvent((int) currentCoords[0], (int) currentCoords[1], event.getEventTime());
                            }
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        for (TouchContext aTouchContext : touchContextMap) {
                            aTouchContext.cancelTouch();
                            aTouchContext.setPointerCount(0);
                        }
                        break;
                    default:
                        return false;
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event);

    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        // 获取当前活动的StreamView
        StreamView activeStreamView = getActiveStreamView();

        // X and Y are already relative to the provided view object
        float eventX, eventY;

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView == activeStreamView) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        } else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - activeStreamView.getX();
            eventY = event.getY(0) - activeStreamView.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        if (externalDisplayManager != null && externalDisplayManager.isUsingExternalDisplay()) {
            int streamViewWidth = activeStreamView.getWidth();
            int streamViewHeight = activeStreamView.getHeight();

            // 获取设备的分辨率
            Point size = new Point();
            Display display = getWindowManager().getDefaultDisplay();
            display.getRealSize(size);
            int deviceWidth = size.x;
            int deviceHeight = size.y;

            float scaleX = (float) streamViewWidth / deviceWidth;
            float scaleY = (float) streamViewHeight / deviceHeight;

            float scaledX = eventX * scaleX;
            float scaledY = eventY * scaleY;

            eventX = Math.max(0, Math.min(scaledX, streamViewWidth));
            eventY = Math.max(0, Math.min(scaledY, streamViewHeight));
        } else {
            // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
            // Normalize these to the view size. We can't just drop them because we won't always get an event
            // right at the boundary of the view, so dropping them would result in our cursor never really
            // reaching the sides of the screen.
            eventX = Math.min(Math.max(eventX, 0), activeStreamView.getWidth());
            eventY = Math.min(Math.max(eventY, 0), activeStreamView.getHeight());
        }

        conn.sendMousePosition((short) eventX, (short) eventY, (short) activeStreamView.getWidth(), (short) activeStreamView.getHeight());

//        // 当鼠标移动时，同步更新本地光标的位置
//        CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
//        if (cursorOverlay != null && prefConfig.enableLocalCursorRendering) {
//            cursorOverlay.updateCursorPosition(eventX, eventY);
//        }
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        return handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            // Add a configuration to allow view.requestUnbufferedDispatch to be disabled.
            if (!prefConfig.syncTouchEventWithDisplay) {
                view.requestUnbufferedDispatch(event);
            }
        }
        return handleMotionEvent(view, event); //Y700平板上, onTouch的调用频率为120Hz
    }

    @Override
    public void stageStarting(final String stage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressOverlay != null) {
                    progressOverlay.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
                }
            }
        });
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        // 重置尝试连接标志。
        // 这确保了当 Activity 驻留在后台未销毁，再次回到前台触发 surfaceChanged 时，
        // 代码会认为这是一个新的开始，从而再次执行 conn.start()。
        attemptedConnection = false;

        if (connecting || connected) {
            connecting = connected = false;
            updatePipAutoEnter();

            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // 停止并释放 USB 控制器接管
            stopAndUnbindUsbDriverService();

            // 停止麦克风流
            if (microphoneManager != null) {
                microphoneManager.stopMicrophoneStream();
            }

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this);

            // Save current settings for this app before stopping connection
            if (appSettingsManager != null && computerUuid != null && app != null) {
                appSettingsManager.saveAppLastSettings(computerUuid, app, prefConfig);
            }

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                }
            }.start();

            stopCursorService();
        }
    }

    @Override
    public void stageFailed(final String stage, final int portFlags, final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressOverlay != null) {
                    progressOverlay.dismiss();
                    progressOverlay = null;
                }

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe(stage + " failed: " + errorCode);

                    // If video initialization failed and the surface is still valid, display extra information for the user
                    if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
                        Toast.makeText(Game.this, getResources().getText(R.string.video_decoder_init_failed), Toast.LENGTH_LONG).show();
                    }

                    String dialogText = getResources().getString(R.string.conn_error_msg) + " " + stage + " (error " + errorCode + ")";

                    if (portFlags != 0) {
                        dialogText += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                MoonBridge.stringifyPortFlags(portFlags, "\n");
                    }

                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                        dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked);
                    }

                    Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_error_title), dialogText, true);
                }
            }
        });
    }

    @Override
    public void connectionTerminated(final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Stop processing controller input
                controllerHandler.stop();

                microphoneManager.stopMicrophoneStream();

                // Ungrab input
                setInputGrabState(false);

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe("Connection terminated: " + errorCode);
                    stopConnection();

                    // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        String message;

                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                            // If we got a blocked result, that supersedes any other error message
                            message = getResources().getString(R.string.nettest_text_blocked);
                        } else {
                            switch (errorCode) {
                                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
                                    message = getResources().getString(R.string.no_video_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
                                    message = getResources().getString(R.string.no_frame_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
                                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
                                    message = getResources().getString(R.string.early_termination_error);
                                    break;

                                case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                                    message = getResources().getString(R.string.frame_conversion_error);
                                    break;

                                default:
                                    String errorCodeString;
                                    // We'll assume large errors are hex values
                                    if (Math.abs(errorCode) > 1000) {
                                        errorCodeString = Integer.toHexString(errorCode);
                                    } else {
                                        errorCodeString = Integer.toString(errorCode);
                                    }
                                    message = getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
                                            getResources().getString(R.string.error_code_prefix) + " " + errorCodeString;
                                    break;
                            }
                        }

                        if (portFlags != 0) {
                            message += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                    MoonBridge.stringifyPortFlags(portFlags, "\n");
                        }

                        Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_terminated_title),
                                message, true);
                    } else {
                        finish();
                    }
                }
            }
        });
    }

    @Override
    public void connectionStatusUpdate(final int connectionStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefConfig.disableWarnings) {
                    return;
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    String message;
                    if (prefConfig.bitrate > 5000) {
                        message = getResources().getString(R.string.slow_connection_msg);
                    } else {
                        message = getResources().getString(R.string.poor_connection_msg);
                    }

                    updateNotificationOverlay(connectionStatus, message);
                    requestedNotificationOverlayVisibility = View.VISIBLE;
                } else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    requestedNotificationOverlayVisibility = View.GONE;
                }

                if (!isHidingOverlays) {
                    if (requestedNotificationOverlayVisibility == View.VISIBLE) {
                        notificationOverlayView.setVisibility(View.VISIBLE);
                    } else {
                        notificationOverlayView.setVisibility(View.GONE);
                    }
                }
            }
        });
    }

    @Override
    public void connectionStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressOverlay != null) {
                    progressOverlay.dismiss();
                    progressOverlay = null;
                }

                connected = true;
                connecting = false;
                updatePipAutoEnter();

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                Handler h = new Handler();
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 根据配置决定是否启用原生鼠标指针
                        if (prefConfig.enableNativeMousePointer) {
                            enableNativeMousePointer(true);
                        } else {
                            setInputGrabState(true);
                        }
                    }
                }, 500);

                // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(Game.this);

                hideSystemUi(1000);
            }
        });

        // Report this shortcut being used (off the main thread to prevent ANRs)
        ComputerDetails computer = new ComputerDetails();
        computer.name = pcName;
        computer.uuid = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        ShortcutHelper shortcutHelper = new ShortcutHelper(this);
        shortcutHelper.reportComputerShortcutUsed(computer);
        if (appName != null) {
            // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app);
        }

        // 检查是否启用了HDR并主动设置初始状态
        // 这解决了首次连接时setHdrMode没有被调用的问题
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        if (appSupportsHdr && prefConfig.enableHdr) {
            setHdrMode(true, null);
        }

        // 初始化麦克风管理器
        microphoneManager = new MicrophoneManager(this, conn, prefConfig.enableMic);
        microphoneManager.setStateListener(new MicrophoneManager.MicrophoneStateListener() {
            @Override
            public void onMicrophoneStateChanged(boolean isActive) {
                // 麦克风状态改变时的回调
                LimeLog.info("麦克风状态改变: " + (isActive ? "激活" : "暂停"));
            }

            @Override
            public void onPermissionRequested() {
                // 权限请求时的回调
                LimeLog.info("麦克风权限请求已发送");
            }
        });

        // 初始化麦克风流
        if (prefConfig.enableMic) {
            runOnUiThread(() -> {
                if (!microphoneManager.initializeMicrophoneStream()) {
                    LimeLog.warning("Failed to start microphone stream");
                } else {
                    LimeLog.info("Microphone stream initialized successfully");
                }

                // 更新麦克风按钮状态
                if (micButton != null) {
                    microphoneManager.setMicrophoneButton(micButton);
                    // 确保麦克风默认状态为关闭
                    microphoneManager.setDefaultStateOff();
                }
            });
        }

        // 记录游戏流媒体开始事件
        streamStartTime = System.currentTimeMillis();
        if (analyticsManager != null && pcName != null) {
            analyticsManager.logGameStreamStart(pcName, appName);
        }

        // 1. 获取并保存 IP (存到全局变量)
        this.currentHostAddress = getIntent().getStringExtra(EXTRA_HOST);

        // 2. 调用统一的状态管理方法
        updateCursorServiceState(prefConfig.enableLocalCursorRendering && prefConfig.touchscreenTrackpad);
    }

    @Override
    protected void onStart() {
        super.onStart();

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

            // 重新准备连接对象
            prepareConnection();

            // 重置连接状态标志
            attemptedConnection = false;
            connecting = false;
            connected = false;
        }
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
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
            // 基础分辨率 = 实际分辨率 * 100 / resolutionScale
            baseWidth = (alignedWidth * 100 / prefConfig.resolutionScale) & ~1;
            baseHeight = (alignedHeight * 100 / prefConfig.resolutionScale) & ~1;
            
            LimeLog.info("Resolution scale conversion: actual=" + alignedWidth + "x" + alignedHeight + 
                    ", base=" + baseWidth + "x" + baseHeight + ", scale=" + prefConfig.resolutionScale + "%");
        } else {
            baseWidth = alignedWidth;
            baseHeight = alignedHeight;
        }
        
        // 跳过相同分辨率的重复通知
        if (prefConfig.width == baseWidth && prefConfig.height == baseHeight) {
            return;
        }

        LimeLog.info("Resolution changed from " + prefConfig.width + "x" + prefConfig.height + 
                " to " + baseWidth + "x" + baseHeight + " (actual: " + alignedWidth + "x" + alignedHeight + ")");

        // 更新内存中的串流基础分辨率
        prefConfig.width = baseWidth;
        prefConfig.height = baseHeight;
        
        // 通知解码器分辨率变更
        if (connected && decoderRenderer != null) {
            decoderRenderer.onResolutionChanged(baseWidth, baseHeight);
        }
        
        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.host_resolution_changed, baseWidth, baseHeight), 
                    Toast.LENGTH_SHORT).show();

            // 根据新的宽高比重新决定横竖屏
            setPreferredOrientationForCurrentDisplay();

            // rotableScreen 模式下强制切换方向以匹配主机分辨率
            if (prefConfig.rotableScreen) {
                setRequestedOrientation(baseWidth > baseHeight 
                        ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE 
                        : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            }

            // 刷新视频视图的比例/尺寸
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
        
        // 获取屏幕物理尺寸（像素）
        Point screenSize = new Point();
        getWindowManager().getDefaultDisplay().getSize(screenSize);
        
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

        if (!attemptedConnection) {
            attemptedConnection = true; // 标记已尝试连接

            // Update GameManager state to indicate we're "loading" while connecting
            UiHelper.notifyStreamConnecting(Game.this);

            decoderRenderer.setRenderTarget(holder);

            conn.start(new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx, prefConfig.enableSpatializer),
                    decoderRenderer, Game.this);

            if (streamView != null) {
                streamView.post(this::syncCursorWithStream);
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
        if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig.fps) {
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
        destroyLocalCursorRenderers();

        if (attemptedConnection) {
            // Let the decoder know immediately that the surface is gone
            decoderRenderer.prepareForStop();

            if (connected) {
                stopConnection();
            }
        }
    }

    /**
     * 初始化本地光标渲染器
     * 通过 findViewById 找到 XML 中的 CursorView
     */
    private void initializeLocalCursorRenderers(int width, int height) {
        CursorView cursorOverlay = findViewById(R.id.cursorOverlay);

        if (cursorOverlay == null) {
            return;
        }

        if (relativeTouchContextMap != null) {
            for (TouchContext context : relativeTouchContextMap) {
                if (context instanceof RelativeTouchContext) {
                    RelativeTouchContext relativeContext = (RelativeTouchContext) context;
                    relativeContext.initializeLocalCursorRenderer(cursorOverlay, width, height);
                    // 1. 开关必须开启
                    // 2. 必须处于触控板模式 (touchscreenTrackpad == true)
                    // 3. 必须没开启原生鼠标 (防止冲突)
                    boolean shouldShow = prefConfig.enableLocalCursorRendering
                            && prefConfig.touchscreenTrackpad
                            && !prefConfig.enableNativeMousePointer;

                    relativeContext.setEnableLocalCursorRendering(shouldShow);
                }
            }
        }
    }

    /**
     * 销毁本地光标渲染器
     * 清理所有相对触摸上下文的光标渲染器
     */
    private void destroyLocalCursorRenderers() {
        if (relativeTouchContextMap != null) {
            for (TouchContext context : relativeTouchContextMap) {
                if (context instanceof RelativeTouchContext) {
                    RelativeTouchContext relativeContext = (RelativeTouchContext) context;
                    relativeContext.destroyLocalCursorRenderer();
                }
            }
        }
    }

    public void refreshLocalCursorState(boolean enabled) {
        boolean shouldRender = enabled && !prefConfig.enableNativeMousePointer;

        if (relativeTouchContextMap != null) {
            for (TouchContext context : relativeTouchContextMap) {
                if (context instanceof RelativeTouchContext) {
                    ((RelativeTouchContext) context).setEnableLocalCursorRendering(shouldRender);
                }
            }
        }
        updateCursorServiceState(enabled);
    }

    /**
     * 强制将光标层与视频层 1:1 对齐
     */
    private void syncCursorWithStream() {
        if (streamView == null) return;
        CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
        if (cursorOverlay == null) return;

        // 获取 StreamView 当前的真实位置和大小
        float x = streamView.getX();
        float y = streamView.getY();
        int w = streamView.getWidth();
        int h = streamView.getHeight();

        // 如果视频还没渲染出来（宽高为0），直接返回，等下次
        if (w == 0 || h == 0) return;

        ViewGroup.LayoutParams params = cursorOverlay.getLayoutParams();

        // 1. 强制清除 Gravity
        // 我们要用 setX/setY 绝对定位，所以必须把 Gravity 设为左上角，否则会发生双重偏移
        if (params instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) params).gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        }

        // 2. 同步大小
        boolean needLayout = false;
        if (params.width != w || params.height != h) {
            params.width = w;
            params.height = h;
            needLayout = true;
        }

        if (needLayout) {
            cursorOverlay.setLayoutParams(params);
        }

        // 3. 同步位置
        cursorOverlay.setX(x);
        cursorOverlay.setY(y);

        // 4. 同步渲染器边界
        if (relativeTouchContextMap != null) {
            initializeLocalCursorRenderers(w, h);
        }

        LimeLog.info("CursorFix:" + "Sync executed: W=" + w + " H=" + h + " X=" + x);
    }

    // UDP 相关变量
    private Thread cursorNetworkThread;
    private boolean isCursorNetworking = false;
    private java.net.DatagramSocket cursorSocket;
    private static final int CURSOR_PORT = 5005;

    private String computerIpAddress;


    private android.util.LruCache<Long, android.graphics.Bitmap> cursorCache = new android.util.LruCache<>(100);

    private void startCursorService(String hostIp) {
        if (isCursorNetworking) return;
        this.computerIpAddress = hostIp;
        this.isCursorNetworking = true;

        // 每次启动服务时清空缓存，防止上次残留的数据导致错乱
        if (cursorCache != null) {
            cursorCache.evictAll();
        }

        cursorNetworkThread = new Thread(() -> {
            try {
                // 1. 初始化 Socket
                cursorSocket = new java.net.DatagramSocket();
                cursorSocket.setSoTimeout(1000); // 1秒超时

                java.net.InetAddress serverAddr = java.net.InetAddress.getByName(computerIpAddress);
                byte[] helloData = "CURSOR_HELLO".getBytes("UTF-8");
                java.net.DatagramPacket helloPacket = new java.net.DatagramPacket(
                        helloData, helloData.length, serverAddr, CURSOR_PORT);

                // 增大缓冲区，防止 4K 屏大光标被截断
                byte[] receiveBuffer = new byte[64 * 1024];
                java.net.DatagramPacket receivePacket = new java.net.DatagramPacket(receiveBuffer, receiveBuffer.length);

                LimeLog.info("CursorNet:" + "握手开始于 " + computerIpAddress);

                long lastHelloTime = 0;
                // 初始化为当前时间，避免刚启动就触发超时重置
                long lastReceiveTime = System.currentTimeMillis();

                while (isCursorNetworking) {
                    // 发送握手包 (每2秒一次)
                    long now = System.currentTimeMillis();
                    if (now - lastHelloTime > 2000) {
                        try {
                            cursorSocket.send(helloPacket);
                            LimeLog.info("CursorNet: 已向发送握手数据包 " + computerIpAddress);
                            lastHelloTime = now;
                        } catch (Exception e) {
                            LimeLog.warning("CursorNet: 发送握手数据包失败： " + e.getMessage());
                        }
                    }

                    // 接收数据
                    try {
                        // 重置 packet 长度
                        receivePacket.setLength(receiveBuffer.length);

                        // 阻塞接收
                        cursorSocket.receive(receivePacket);

                        // 只有成功接收到数据后，才更新时间！
                        lastReceiveTime = System.currentTimeMillis();

                        byte[] data = receivePacket.getData();
                        int length = receivePacket.getLength();

                        // 最小包长检测
                        if (length >= 17) {
                            java.nio.ByteBuffer wrapped = java.nio.ByteBuffer.wrap(data);
                            wrapped.order(java.nio.ByteOrder.LITTLE_ENDIAN);

                            byte type = wrapped.get();      // 0=全量, 1=缓存
                            long hash = wrapped.getLong();  // CRC32
                            int hotX = wrapped.getInt();
                            int hotY = wrapped.getInt();

                            android.graphics.Bitmap targetBitmap = null;

                            if (type == 1) {
                                // === 缓存命中 ===
                                targetBitmap = cursorCache.get(hash);
                                LimeLog.info("CursorNet: 收到带有哈希的缓存游标 " + hash);
                            } else if (type == 0) {
                                // === 全量数据 ===
                                int imageOffset = 17;
                                int imageLen = length - imageOffset;
                                if (imageLen > 0) {
                                    targetBitmap = android.graphics.BitmapFactory.decodeByteArray(data, imageOffset, imageLen);
                                    if (targetBitmap != null) {
                                        cursorCache.put(hash, targetBitmap); // 存入缓存
                                        LimeLog.info("CursorNet: 收到带有哈希值的新游标 " + hash + ", size: " + imageLen + " bytes");
                                    }
                                }
                            }

                            if (targetBitmap != null) {
                                final android.graphics.Bitmap finalBmp = targetBitmap;
                                runOnUiThread(() -> {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
                                        // 方案B：当启用了原生指针且API版本符合时，使用 PointerIcon
                                        PointerIcon pointerIcon = PointerIcon.create(finalBmp, hotX, hotY);
                                        streamView.setPointerIcon(pointerIcon);
                                    } else {
                                        // 方案A：使用自定义View绘制
                                        com.limelight.ui.CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
                                        if (cursorOverlay != null) {
                                            cursorOverlay.setCursorBitmap(finalBmp, hotX, hotY);
                                        }
                                    }
                                });
                            } else {
                                LimeLog.warning("CursorNet: 无法解码光标位图, type: " + type + ", hash: " + hash);
                            }
                        } else {
                            LimeLog.warning("CursorNet: 收到的数据包太小: " + length + " bytes");
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // 因为 Python 端现在每 1 秒会发一次心跳包。
                        // 所以，如果我们超过 3 秒 (3000ms) 还没收到任何数据，
                        // 那肯定是因为服务器挂了，或者是网络断了。
                        if (System.currentTimeMillis() - lastReceiveTime > 3000) {
                            LimeLog.warning("CursorNet: 与游标服务器的连接超时");

                            // 为了避免瞬间闪烁，再次确认时间差
                            lastReceiveTime = System.currentTimeMillis(); // 重置计时，避免疯狂触发

                            runOnUiThread(() -> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
                                    // 恢复为默认箭头
                                    streamView.setPointerIcon(PointerIcon.getSystemIcon(Game.this, PointerIcon.TYPE_ARROW));
                                } else {
                                    com.limelight.ui.CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
                                    if (cursorOverlay != null) {
                                        // 只有真的断连了，才会变回默认光标
                                        cursorOverlay.resetToDefault();
                                        LimeLog.warning("CursorNet:" + "服务器超时，正在重置光标。");
                                    }
                                }
                            });
                        }
                    } catch (Exception e) {
                        LimeLog.warning("CursorNet: 接收数据包时出错： " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LimeLog.warning("CursorNet:" + "严重错误： " + e.getMessage());
            } finally {
                if (cursorSocket != null) {
                    cursorSocket.close();
                    cursorSocket = null;
                    LimeLog.info("CursorNet: 套接字已关闭");
                }
            }
        });
        cursorNetworkThread.start();
    }

    private void stopCursorService() {
        isCursorNetworking = false; // 退出循环标志

        // 关闭 Socket
        if (cursorSocket != null) {
            try {
                cursorSocket.close();
            } catch (Exception e) {
            }
            cursorSocket = null;
        }

        // 清空画布 UI
        runOnUiThread(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && prefConfig.enableNativeMousePointer) {
                streamView.setPointerIcon(PointerIcon.getSystemIcon(Game.this, PointerIcon.TYPE_ARROW));
            } else {
                CursorView cursorOverlay = findViewById(R.id.cursorOverlay);
                if (cursorOverlay != null) {
                    cursorOverlay.resetToDefault();
                }
            }
        });
    }

    /**
     * 根据当前配置和运行状态，决定是启动还是停止光标服务
     */
    public void updateCursorServiceState(boolean shouldRun) {

        if (shouldRun) {
            if (!isCursorNetworking && currentHostAddress != null) {
                // 如果没在运行，且有IP，就开始运行
                LimeLog.info("CursorNet: Enabling cursor service during stream");
                startCursorService(currentHostAddress);
            }
        } else {
            if (isCursorNetworking) {
                // 如果正在运行，就停止它
                LimeLog.info("CursorNet: Disabling cursor service during stream");
                stopCursorService();
            }
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
                perfAttrs.put("解码器", performanceInfo.decoder);
                perfAttrs.put("分辨率", performanceInfo.initialWidth + "x" + performanceInfo.initialHeight);
                perfAttrs.put("帧率", String.format("%.0f", performanceInfo.totalFps));
                perfAttrs.put("丢帧率", String.format("%.1f", performanceInfo.lostFrameRate));
                perfAttrs.put("网络延时", String.format("%d", (int) (performanceInfo.rttInfo >> 32)));
                perfAttrs.put("主机延时", String.format("%.2f", performanceInfo.aveHostProcessingLatency));
                perfAttrs.put("解码时间", String.format("%.2f", performanceInfo.decodeTimeMs));
                perfAttrs.put("带宽", performanceInfo.bandWidth);
                perfAttrs.put("渲染延迟", String.format("%.2f", performanceInfo.renderingLatencyMs));
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
            case GAME_MENU:
                new GameMenu(this, app, conn, device);
                break;
            case CROWN_MODE:
                if (controllerManager != null && prefConfig.onscreenKeyboard) {
                    controllerManager.getSuperPagesController().returnOperation();
                }
                break;
            case NO_MENU:
                // 无操作，直接返回
                break;
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
            prefConfig.enablePerfOverlay = true;
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
                Toast.makeText(this, "麦克风按钮已隐藏", Toast.LENGTH_SHORT).show();
            } else {
                micButton.setVisibility(View.VISIBLE);
                Toast.makeText(this, "麦克风按钮已显示", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "虚拟手柄已隐藏", Toast.LENGTH_SHORT).show();
            } else {
                virtualController.show();
                Toast.makeText(this, "虚拟手柄已显示", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "虚拟手柄未启用", Toast.LENGTH_SHORT).show();
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

    /**
     * 刷新性能覆盖层显示项配置（用户更改配置后调用）
     */
    public void refreshPerformanceOverlayConfig() {
        if (performanceOverlayManager != null) {
            performanceOverlayManager.refreshPerformanceOverlayConfig();
        }
    }

    private static byte getModifier(short key) {
        switch (key) {
            case KeyboardTranslator.VK_LSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KeyboardTranslator.VK_LCONTROL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KeyboardTranslator.VK_LWIN:
                return KeyboardPacket.MODIFIER_META;
            case KeyboardTranslator.VK_MENU:
                return KeyboardPacket.MODIFIER_ALT;

            default:
                return 0;
        }
    }

    private void sendKeys(short[] keys) {
        final byte[] modifier = {(byte) 0};

        for (short key : keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);

            // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] |= getModifier(key);
        }

        new Handler().postDelayed((() -> {

            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];

                // Remove the keys modifier before releasing the key
                modifier[0] &= ~getModifier(key);

                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }), 25);
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
        return handleMotionEvent(streamView, event);
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

    private void updateNotificationOverlay(int connectionStatus, String message) {
        if (notificationOverlayView == null || notificationTextView == null) {
            return;
        }

        // Set the text
        notificationTextView.setText(message);

        // Set different colors based on connection status with more transparency
        int backgroundColor;
        if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
            if (prefConfig.bitrate > 5000) {
                // Slow connection - orange warning
                backgroundColor = 0x80FF9800; // Orange with more transparency
            } else {
                // Poor connection - red warning
                backgroundColor = 0x80F44336; // Red with more transparency
            }
        } else {
            // Default color
            backgroundColor = 0x80FF5722; // Orange-red with more transparency
        }

        // Apply background color without animation
        notificationOverlayView.setCardBackgroundColor(backgroundColor);
    }
}
