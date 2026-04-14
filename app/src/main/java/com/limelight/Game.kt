@file:Suppress("DEPRECATION")
package com.limelight

import com.limelight.binding.PlatformBinding
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.audio.AudioDiagnostics
import com.limelight.binding.audio.AudioVibrationService
import com.limelight.binding.audio.MicrophoneManager
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.GameInputDevice
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.KeyboardUIController
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.touch.AbsoluteTouchContext
import com.limelight.binding.input.touch.NativeTouchContext
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.evdev.EvdevListener
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.binding.video.PerformanceInfo
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.services.StreamNotificationService
import com.limelight.ui.CursorView
import com.limelight.ui.GameGestures
import com.limelight.ui.StreamView
import com.limelight.utils.Dialog
import com.limelight.utils.PanZoomHandler
import com.limelight.utils.FullscreenProgressOverlay
import com.limelight.utils.UiHelper
import com.limelight.utils.NetHelper
import com.limelight.utils.AnalyticsManager
import com.limelight.utils.AppCacheManager
import com.limelight.utils.AppSettingsManager
import com.limelight.services.KeyboardAccessibilityService

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.preference.PreferenceManager
import android.util.Rational
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnSystemUiVisibilityChangeListener
import android.view.View.OnTouchListener
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.annotation.RequiresApi

import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class Game : Activity(), SurfaceHolder.Callback,
    OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
    OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
    PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener,
    KeyboardAccessibilityService.KeyEventCallback {

    // 这个标志位用于区分事件是来自无障碍服务还是来自UI（如StreamView）
    var isEventFromAccessibilityService = false

    lateinit var controllerHandler: ControllerHandler
    lateinit var touchInputHandler: TouchInputHandler
    var virtualController: VirtualController? = null
    lateinit var panZoomHandler: PanZoomHandler
    private var audioVibrationService: AudioVibrationService? = null

    interface PerformanceInfoDisplay {
        fun display(performanceAttrs: Map<String, String>)
    }

    var controllerManager: ControllerManager? = null
    private var standaloneKeyboardUI: KeyboardUIController? = null
    private val performanceInfoDisplays = ArrayList<PerformanceInfoDisplay>()

    var microphoneManager: MicrophoneManager? = null
    var micButton: ImageButton? = null
    lateinit var prefConfig: PreferenceConfiguration
    lateinit var orientationManager: OrientationManager
    private lateinit var tombstonePrefs: SharedPreferences

    var conn: NvConnection? = null
    var progressOverlay: FullscreenProgressOverlay? = null

    var displayedFailureDialog = false
    var connecting = false
    var connected = false
    private var autoEnterPip = false
    private var surfaceCreated = false
    var attemptedConnection = false
    var analyticsManager: AnalyticsManager? = null
    var streamStartTime: Long = 0
    var accumulatedStreamTime: Long = 0
    var lastActiveTime: Long = 0
    var isStreamingActive = false
    private var suppressPipRefCount = 0
    var pcName: String? = null
    var appName: String? = null
    lateinit var app: NvApp
    private var desiredRefreshRate = 0f
    var appSettingsManager: AppSettingsManager? = null
    var computerUuid: String? = null

    lateinit var inputCaptureProvider: InputCaptureProvider
    var grabbedInput = true
    var cursorVisible = false
    lateinit var streamView: StreamView
    private var externalStreamView: StreamView? = null
    private var previousTimeMillis: Long = 0
    private var previousRxBytes: Long = 0

    lateinit var notificationOverlayManager: NotificationOverlayManager
    private var performanceOverlayManager: PerformanceOverlayManager? = null
    lateinit var cursorServiceManager: CursorServiceManager
    lateinit var floatBallHandler: FloatBallHandler
    lateinit var connectionCallbackHandler: ConnectionCallbackHandler
    lateinit var keyboardInputHandler: KeyboardInputHandler

    private var decoderRenderer: MediaCodecDecoderRenderer? = null
    private var reportedCrash = false

    private var highPerfWifiLock: WifiManager.WifiLock? = null
    private var lowLatencyWifiLock: WifiManager.WifiLock? = null

    var currentHostAddress: String? = null
    private var shouldResumeSession = false
    private var isExtremeResumeEnabled = false
    private var isChangingResolution = false
    private var audioRenderer: AndroidAudioRenderer? = null

    enum class BackKeyMenuMode {
        GAME_MENU, CROWN_MODE, NO_MENU
    }

    private var currentBackKeyMenu = BackKeyMenuMode.GAME_MENU

    fun setcurrentBackKeyMenu(currentBackKeyMenu: BackKeyMenuMode) {
        this.currentBackKeyMenu = currentBackKeyMenu
    }

    private var areElementsVisible = true

    fun toggleVirtualControllerVisibility() {
        if (controllerManager != null) {
            areElementsVisible = !areElementsVisible
            if (areElementsVisible) {
                controllerManager?.elementController?.showAllElementsForTest()
                Toast.makeText(this, getString(R.string.toast_elements_visible), Toast.LENGTH_SHORT).show()
            } else {
                controllerManager?.elementController?.hideAllElementsForTest()
                Toast.makeText(this, getString(R.string.toast_elements_hidden), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleBackKeyMenuType() {
        when (currentBackKeyMenu) {
            BackKeyMenuMode.GAME_MENU -> {
                currentBackKeyMenu = BackKeyMenuMode.CROWN_MODE
                areElementsVisible = true
                controllerManager?.elementController?.showAllElementsForTest()
                Toast.makeText(this, getString(R.string.toast_back_key_menu_switch_2), Toast.LENGTH_SHORT).show()
            }
            BackKeyMenuMode.CROWN_MODE -> {
                currentBackKeyMenu = BackKeyMenuMode.GAME_MENU
                Toast.makeText(this, getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show()
            }
            BackKeyMenuMode.NO_MENU -> {
                currentBackKeyMenu = BackKeyMenuMode.GAME_MENU
            }
        }
    }

    var isTouchOverrideEnabled = false

    fun getisTouchOverrideEnabled(): Boolean = isTouchOverrideEnabled

    fun setisTouchOverrideEnabled(isTouchOverrideEnabled: Boolean) {
        this.isTouchOverrideEnabled = isTouchOverrideEnabled
    }

    var usbDriverServiceManager: UsbDriverServiceManager? = null
    var externalDisplayManager: ExternalDisplayManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cancelKeepAliveNotification()
        isChangingResolution = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        UiHelper.setLocale(this)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.decorView.setOnSystemUiVisibilityChangeListener(this)

        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.activity_game)
        window.decorView.findViewById<View>(android.R.id.content).isFocusable = true

        prefConfig = PreferenceConfiguration.readPreferences(this)
        orientationManager = OrientationManager(
            this,
            prefConfig.width,
            prefConfig.height,
            prefConfig.rotableScreen,
            prefConfig.onscreenController || prefConfig.onscreenKeyboard
        ) { currentTargetDisplay }
        tombstonePrefs = getSharedPreferences("DecoderTombstone", 0)

        val globalPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        isExtremeResumeEnabled = globalPrefs.getBoolean("checkbox_extreme_resume", false) &&
                isResumeStreamEnabled

        if (isResumeStreamEnabled) {
            checkNotificationPermission()
        }

        appSettingsManager = AppSettingsManager(this)
        computerUuid = intent.getStringExtra(EXTRA_PC_UUID)
        applyLastSettingsToCurrentSession()

        val customScreenMode = intent.getIntExtra(EXTRA_SCREEN_COMBINATION_MODE, -1)
        if (customScreenMode != -1) {
            prefConfig.screenCombinationMode = customScreenMode
        }
        val customVddScreenMode = intent.getIntExtra(EXTRA_VDD_SCREEN_COMBINATION_MODE, -1)
        if (customVddScreenMode != -1) {
            prefConfig.vddScreenCombinationMode = customVddScreenMode
        }

        NativeTouchContext.INTIAL_ZONE_PIXELS = prefConfig.longPressflatRegionPixels.toFloat()
        NativeTouchContext.ENABLE_ENHANCED_TOUCH = prefConfig.enableEnhancedTouch
        NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = if (prefConfig.enhancedTouchOnWhichSide) -1 else 1
        NativeTouchContext.ENHANCED_TOUCH_ZONE_DIVIDER = prefConfig.enhanceTouchZoneDivider * 0.01f
        NativeTouchContext.POINTER_VELOCITY_FACTOR = prefConfig.pointerVelocityFactor * 0.01f

        orientationManager.setPreferredOrientation()

        if (prefConfig.stretchVideo || DisplayModeManager.shouldIgnoreInsetsForResolution(
                windowManager.defaultDisplay, prefConfig.width, prefConfig.height
            )
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        streamView = findViewById(R.id.surfaceView)
        streamView.setOnGenericMotionListener(this)
        streamView.setOnKeyListener(this)
        streamView.setInputCallbacks(this)

        panZoomHandler = PanZoomHandler(this, this, streamView, prefConfig)

        val backgroundTouchView = findViewById<View>(R.id.backgroundTouchView)
        backgroundTouchView.setOnTouchListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sourceFlags = InputDevice.SOURCE_CLASS_BUTTON or
                    InputDevice.SOURCE_CLASS_JOYSTICK or
                    InputDevice.SOURCE_CLASS_POINTER or
                    InputDevice.SOURCE_CLASS_POSITION or
                    InputDevice.SOURCE_CLASS_TRACKBALL
            streamView.requestUnbufferedDispatch(sourceFlags)
            backgroundTouchView.requestUnbufferedDispatch(sourceFlags)
        }

        notificationOverlayManager = NotificationOverlayManager(
            findViewById(R.id.notificationOverlay),
            findViewById(R.id.notificationText)
        ) { prefConfig.bitrate }

        micButton = findViewById(R.id.micButton)

        performanceOverlayManager = PerformanceOverlayManager(this, prefConfig)
        performanceOverlayManager?.initialize()

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener { _, event ->
                touchInputHandler.handleMotionEvent(null, event)
            }
        }

        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connMgr.isActiveNetworkMetered) {
            displayTransientMessage(resources.getString(R.string.conn_metered))
        }

        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock")
            highPerfWifiLock?.setReferenceCounted(false)
            highPerfWifiLock?.acquire()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock")
                lowLatencyWifiLock?.setReferenceCounted(false)
                lowLatencyWifiLock?.acquire()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        appName = intent.getStringExtra(EXTRA_APP_NAME)
        pcName = intent.getStringExtra(EXTRA_PC_NAME)
        analyticsManager = AnalyticsManager.getInstance(this)

        val appId = intent.getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
        val appSupportsHdr = intent.getBooleanExtra(EXTRA_APP_HDR, false)
        val cmdList = intent.getStringExtra(EXTRA_APP_CMD)

        app = NvApp(appName ?: "app", appId, appSupportsHdr)
        if (cmdList != null) {
            app.setCmdList(cmdList)
        }

        if (appId != StreamConfiguration.INVALID_APP_ID && appName != null && appName != "app") {
            val cacheManager = AppCacheManager(this)
            cacheManager.saveAppInfo(intent.getStringExtra(EXTRA_PC_UUID), app)
        }

        showProgressOverlay()

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish()
            return
        }

        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this, glPrefs.glRenderer)

        createConnectionAndHandler()
        keyboardInputHandler = KeyboardInputHandler(this)
        keyboardInputHandler.keyboardTranslator = KeyboardTranslator()

        audioVibrationService = AudioVibrationService(this)
        audioVibrationService?.controllerHandler = controllerHandler
        audioVibrationService?.setSettings(
            prefConfig.enableAudioVibration,
            prefConfig.audioVibrationStrength,
            prefConfig.audioVibrationMode,
            prefConfig.audioVibrationScene
        )
        MoonBridge.setBassEnergyListener { intensity, lowFreqRatio ->
            audioVibrationService?.handleBassEnergy(intensity, lowFreqRatio)
        }
        MoonBridge.setBassEnergyEnabled(prefConfig.enableAudioVibration)
        MoonBridge.setBassEnergySceneMode(prefConfig.audioVibrationScene)

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardInputHandler.keyboardTranslator, null)

        touchInputHandler = TouchInputHandler(this)
        touchInputHandler.initTouchContexts(conn!!, streamView, prefConfig)

        val cursorOverlayView = findViewById<CursorView>(R.id.cursorOverlay)
        cursorServiceManager = CursorServiceManager(
            streamView, cursorOverlayView, prefConfig, touchInputHandler.relativeTouchContextMap,
            object : CursorServiceManager.UiCallback {
                override fun runOnUi(runnable: Runnable) {
                    runOnUiThread(runnable)
                }
                override fun isActivityAlive(): Boolean {
                    return !isFinishing && !isDestroyed
                }
            }
        )

        streamView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                cursorServiceManager.syncCursorWithStream()
            }
        }
        streamView.post { cursorServiceManager.syncCursorWithStream() }

        if (prefConfig.onscreenController) {
            virtualController = VirtualController(
                controllerHandler,
                streamView.parent as FrameLayout,
                this
            )
            setupVirtualControllerGyro()
        }

        if (prefConfig.onscreenKeyboard) {
            controllerManager = ControllerManager(streamView.parent as FrameLayout, this)

            val keyboardContainer = findViewById<FrameLayout>(R.id.virtual_full_keyboard_container)
            if (keyboardContainer != null) {
                val kUI = KeyboardUIController(keyboardContainer, createKeyboardEventListener(), this)
                controllerManager?.keyboardUIController = kUI
            }

            controllerManager?.refreshLayout()
        }

        if (prefConfig.usbDriver) {
            bindUsbDriverService()
        }

        if (decoderRenderer?.isAvcSupported() != true) {
            if (progressOverlay != null) {
                progressOverlay?.dismiss()
                progressOverlay = null
            }
            Dialog.displayDialog(
                this, resources.getString(R.string.conn_error_title),
                "This device or ROM doesn't support hardware accelerated H.264 playback.", true
            )
            return
        }

        streamView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        DisplayPositionManager(this, prefConfig, streamView).setupDisplayPosition()

        setupExternalDisplay()

        floatBallHandler = FloatBallHandler(this, prefConfig)
        floatBallHandler.initialize()

        connectionCallbackHandler = ConnectionCallbackHandler(this)
    }

    private fun getOrCreateKeyboardUIController(): KeyboardUIController? {
        if (controllerManager != null) {
            val kUI = controllerManager?.keyboardUIController
            if (kUI != null) return kUI
        }
        if (standaloneKeyboardUI == null) {
            val keyboardContainer = findViewById<FrameLayout>(R.id.virtual_full_keyboard_container)
            if (keyboardContainer != null) {
                standaloneKeyboardUI = KeyboardUIController(keyboardContainer, createKeyboardEventListener(), this)
            }
        }
        return standaloneKeyboardUI
    }

    fun toggleVirtualKeyboard() {
        getOrCreateKeyboardUIController()?.toggle()
    }

    // region ---- Extracted helpers to reduce duplication ----

    /** Resolve the display currently used for rendering (external or built-in). */
    val currentTargetDisplay: Display
        get() = externalDisplayManager?.getTargetDisplay() ?: windowManager.defaultDisplay

    /** Re-point all absolute/relative touch contexts at [view]. */
    private fun retargetTouchContexts(view: StreamView?) {
        for (i in 0 until TouchInputHandler.TOUCH_CONTEXT_LENGTH) {
            val absCtx = touchInputHandler.absoluteTouchContextMap[i]
            if (absCtx is AbsoluteTouchContext) absCtx.setTargetView(view!!)
            val relCtx = touchInputHandler.relativeTouchContextMap[i]
            if (relCtx is RelativeTouchContext) relCtx.setTargetView(view!!)
        }
    }

    /** Wire listeners on an arbitrary StreamView (main or external). */
    private fun setupStreamViewListeners(view: StreamView) {
        view.setOnGenericMotionListener(this)
        view.setOnKeyListener(this)
        view.setInputCallbacks(this)
        findViewById<View>(R.id.backgroundTouchView)?.setOnTouchListener(this)
        view.holder.addCallback(this)
    }

    /** Create the ExternalDisplayManager callback (shared by onCreate & prepareConnection). */
    private fun createExternalDisplayCallback(): ExternalDisplayManager.ExternalDisplayCallback {
        return object : ExternalDisplayManager.ExternalDisplayCallback {
            override fun onExternalDisplayConnected(display: Display) {
                LimeLog.info("External display connected, reinitializing input capture provider")
                inputCaptureProvider?.disableCapture()
                inputCaptureProvider = InputCaptureManager.getInputCaptureProviderForExternalDisplay(this@Game, this@Game)
            }

            override fun onExternalDisplayDisconnected() {
                externalStreamView = null
                LimeLog.info("External display disconnected, cleared externalStreamView")
                retargetTouchContexts(this@Game.streamView)
                inputCaptureProvider?.disableCapture()
                inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this@Game, this@Game)
            }

            override fun onStreamViewReady(streamView: StreamView) {
                externalStreamView = streamView
                retargetTouchContexts(streamView)
                setupStreamViewListeners(streamView)
                LimeLog.info("External display StreamView ready: ${streamView.width}x${streamView.height}")
            }
        }
    }

    /** Parse the server certificate from DER bytes in the launch intent. */
    private fun parseServerCert(): X509Certificate? {
        val derCertData = intent.getByteArrayExtra(EXTRA_SERVER_CERT) ?: return null
        return try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
        } catch (e: CertificateException) {
            e.printStackTrace()
            null
        }
    }

    /** Show the fullscreen connecting progress overlay. */
    private fun showProgressOverlay() {
        progressOverlay = FullscreenProgressOverlay(this, app)
        val computer = ComputerDetails()
        computer.name = pcName
        computer.uuid = intent.getStringExtra(EXTRA_PC_UUID)
        progressOverlay?.computer = computer
        progressOverlay?.show(
            resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.conn_establishing_msg)
        )
    }

    /** Shared keyboard-event listener used by KeyboardUIController instances. */
    private fun createKeyboardEventListener(): KeyboardUIController.OnKeyboardEventListener {
        return object : KeyboardUIController.OnKeyboardEventListener {
            override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                if (controllerManager?.elementController != null) {
                    controllerManager?.elementController?.sendKeyEvent(down, keyCode)
                } else {
                    keyboardEvent(down, keyCode)
                }
            }
            override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) {
                controllerManager?.elementController?.rumbleSingleVibrator(lowFreq, highFreq, duration)
            }
        }
    }

    /** Set up gyro callbacks on the virtual controller. */
    private fun setupVirtualControllerGyro() {
        val vc = virtualController ?: return
        vc.refreshLayout()
        vc.show()
        vc.setGyroEnabled(!prefConfig.gyroToMouse)
        controllerHandler.setVirtualControllerGyroCallbacks(
            { vc.setGyroEnabled(false) },
            { vc.setGyroEnabled(true) }
        )
    }

    /** Whether the resume-stream preference is enabled. */
    private val isResumeStreamEnabled: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("checkbox_resume_stream", false)

    /**
     * Parse intent extras, build stream config, create NvConnection + ControllerHandler.
     * Shared by [onCreate] (first launch) and [prepareConnection] (resume reconnect).
     */
    private fun createConnectionAndHandler() {
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 0)
        val uniqueId = intent.getStringExtra(EXTRA_UNIQUEID) ?: ""
        val pairName = intent.getStringExtra(EXTRA_PAIR_NAME) ?: ""
        val pcUseVdd = intent.getBooleanExtra(EXTRA_PC_USEVDD, false)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        val serverCert = parseServerCert()

        val streamConfigResult = buildStreamConfiguration(
            host, port, httpsPort, uniqueId, pairName, pcUseVdd, serverCert, displayName
        )
        val config = streamConfigResult.config

        conn = NvConnection(
            applicationContext,
            ComputerDetails.AddressTuple(host, port),
            httpsPort, uniqueId, pairName, config,
            PlatformBinding.getCryptoProvider(this), serverCert, displayName
        )
        orientationManager.connection = conn
        controllerHandler = ControllerHandler(this, conn!!, this, prefConfig)
    }

    /** Create or re-create ExternalDisplayManager with the standard callback. */
    private fun setupExternalDisplay() {
        externalDisplayManager = ExternalDisplayManager(this, prefConfig, conn!!, decoderRenderer!!, pcName ?: "", appName ?: "")
        externalDisplayManager?.callback = createExternalDisplayCallback()
        externalDisplayManager?.initialize()
    }

    /** Bind or re-bind the USB driver service with the current controllerHandler. */
    private fun bindUsbDriverService() {
        usbDriverServiceManager?.stopAndUnbind()
        usbDriverServiceManager = UsbDriverServiceManager(this, this)
        usbDriverServiceManager?.controllerHandler = controllerHandler
        usbDriverServiceManager?.bind()
    }

    // endregion

    private fun buildStreamConfiguration(
        host: String?, port: Int, httpsPort: Int,
        uniqueId: String?, pairName: String?,
        pcUseVdd: Boolean, serverCert: X509Certificate?,
        displayName: String?
    ): StreamConfigResult {
        val glPrefs = GlPreferences.readPreferences(this)
        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        var willStreamHdr = false
        if (prefConfig.enableHdr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val hdrCaps = currentTargetDisplay.hdrCapabilities

                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true
                            break
                        }
                    }
                }
                if (!willStreamHdr) {
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show()
            }
        }

        if (decoderRenderer == null) {
            decoderRenderer = MediaCodecDecoderRenderer(
                this, prefConfig,
                CrashListener {
                    tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit()
                    reportedCrash = true
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered,
                willStreamHdr,
                glPrefs.glRenderer,
                this
            )
        }

        if (willStreamHdr && decoderRenderer?.isHevcMain10Supported() != true && decoderRenderer?.isAv1Main10Supported() != true) {
            willStreamHdr = false
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show()
        }

        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && decoderRenderer?.isHevcSupported() != true) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show()
        }
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && decoderRenderer?.isAv1Supported() != true) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show()
        }

        var supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264
        if (decoderRenderer?.isHevcSupported() == true) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265
            if (willStreamHdr && decoderRenderer?.isHevcMain10Supported() == true) {
                supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265_MAIN10
            }
        }
        if (decoderRenderer?.isAv1Supported() == true) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            if (willStreamHdr && decoderRenderer?.isAv1Main10Supported() == true) {
                supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN10
            }
        }

        var gamepadMask = ControllerHandler.getAttachedControllerMask(this).toInt()
        if (!prefConfig.multiController) {
            gamepadMask = 1
        }
        if (prefConfig.onscreenController) {
            gamepadMask = gamepadMask or 1
        }

        val displayRefreshRate = prepareDisplayForRendering()
        LimeLog.info("Display refresh rate: $displayRefreshRate Hz")

        performanceOverlayManager?.setActualDisplayRefreshRate(displayRefreshRate)

        val clientRefreshRateX100 = Math.round(displayRefreshRate * 100)

        val roundedRefreshRate = Math.round(displayRefreshRate)
        var chosenFrameRate = prefConfig.fps
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    LimeLog.info("Using drop mode for FPS > Hz")
                } else if (roundedRefreshRate <= 49) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    LimeLog.info("Bogus refresh rate: $roundedRefreshRate")
                } else {
                    chosenFrameRate = roundedRefreshRate - 1
                    LimeLog.info("Adjusting FPS target for screen to $chosenFrameRate")
                }
            }
        }

        val config = StreamConfiguration.Builder()
            .setResolution(prefConfig.width, prefConfig.height)
            .setLaunchRefreshRate(prefConfig.fps)
            .setRefreshRate(chosenFrameRate)
            .setApp(app)
            .setBitrate(prefConfig.bitrate)
            .setResolutionScale(prefConfig.resolutionScale)
            .setEnableSops(prefConfig.enableSops)
            .enableLocalAudioPlayback(prefConfig.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(supportedVideoFormats)
            .setAttachedGamepadMask(gamepadMask)
            .setClientRefreshRateX100(clientRefreshRateX100)
            .setAudioConfiguration(prefConfig.audioConfiguration)
            .setColorSpace(decoderRenderer?.getPreferredColorSpace() ?: 0)
            .setColorRange(
                if (willStreamHdr && prefConfig.hdrMode == MoonBridge.HDR_MODE_HLG)
                    MoonBridge.COLOR_RANGE_FULL
                else decoderRenderer?.getPreferredColorRange() ?: 0
            )
            .setHdrMode(if (willStreamHdr) prefConfig.hdrMode else MoonBridge.HDR_MODE_SDR)
            .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
            .setUseVdd(pcUseVdd)
            .setEnableMic(prefConfig.enableMic)
            .setControlOnly(prefConfig.controlOnly)
            .setCustomScreenMode(prefConfig.screenCombinationMode)
            .setCustomVddScreenMode(prefConfig.vddScreenCombinationMode)
            .build()

        LimeLog.info("Stream config: hdr=$willStreamHdr hdrMode=${prefConfig.hdrMode} fullRange=${prefConfig.fullRange}")

        return StreamConfigResult(config, displayRefreshRate, clientRefreshRateX100)
    }

    private class StreamConfigResult(
        val config: StreamConfiguration,
        val displayRefreshRate: Float,
        val clientRefreshRateX100: Int
    )

    private fun prepareConnection() {
        cursorServiceManager.destroyLocalCursorRenderers()
        runOnUiThread {
            val cursorOverlay = findViewById<CursorView>(R.id.cursorOverlay)
            cursorOverlay?.resetToDefault()
            cursorOverlay?.hide()
            notificationOverlayManager.reset()
        }

        orientationManager.reset()

        if (decoderRenderer != null) {
            try {
                decoderRenderer?.prepareForStop()
            } catch (_: Exception) {}
            decoderRenderer = null
        }

        createConnectionAndHandler()

        audioVibrationService?.controllerHandler = controllerHandler

        if (prefConfig.usbDriver) {
            bindUsbDriverService()
        } else {
            usbDriverServiceManager?.refreshListener()
        }

        touchInputHandler.initTouchContexts(conn!!, streamView, prefConfig)

        if (virtualController != null && prefConfig.onscreenController) {
            setupVirtualControllerGyro()
        }

        if (controllerManager != null) {
            if (prefConfig.onscreenKeyboard) {
                controllerManager?.refreshLayout()
            } else {
                controllerManager = null
            }
        }

        if (microphoneManager != null) {
            microphoneManager?.stopMicrophoneStream()
        }
        microphoneManager = MicrophoneManager(this, conn!!, prefConfig.enableMic)
        microphoneManager?.setStateListener(object : MicrophoneManager.MicrophoneStateListener {
            override fun onMicrophoneStateChanged(isActive: Boolean) {
                LimeLog.info("麦克风状态改变: ${if (isActive) "激活" else "暂停"}")
            }
            override fun onPermissionRequested() {
                LimeLog.info("麦克风权限请求已发送")
            }
        })

        setupExternalDisplay()
    }

    override fun onResume() {
        super.onResume()
        KeyboardAccessibilityService.setIntercepting(true)
        val service = KeyboardAccessibilityService.instance
        if (service != null) {
            service.keyEventCallback = this
        } else {
            LimeLog.warning("KeyboardAccessibilityService is not running.")
        }

        if (microphoneManager != null && micButton != null) {
            microphoneManager?.updateMicrophoneButtonState()
        }
        floatBallHandler.show()
    }

    override fun onKeyEvent(event: KeyEvent) {
        isEventFromAccessibilityService = true
        if (event.action == KeyEvent.ACTION_DOWN) {
            onKeyDown(event.keyCode, event)
        } else if (event.action == KeyEvent.ACTION_UP) {
            onKeyUp(event.keyCode, event)
        }
        isEventFromAccessibilityService = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        microphoneManager?.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == KEEP_ALIVE_NOTIFICATION_ID) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                StreamNotificationService.start(this, pcName, appName)
            } else {
                Toast.makeText(this, getString(R.string.toast_no_notification_permission), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        orientationManager.onConfigurationChanged()

        virtualController?.refreshLayout()
        controllerManager?.refreshLayout()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode) {
                virtualController?.hide()
                performanceOverlayManager?.hideOverlayImmediate()
                notificationOverlayManager.setHiding(true)
                microphoneManager?.setEnableMic(false)
                controllerHandler?.disableSensors()
                UiHelper.notifyStreamEnteringPiP(this)
            } else {
                virtualController?.show()
                performanceOverlayManager?.applyRequestedVisibility()
                notificationOverlayManager.setHiding(false)
                notificationOverlayManager.applyVisibility()
                microphoneManager?.setEnableMic(prefConfig.enableMic)
                controllerHandler.enableSensors()
                controllerHandler.onSensorsReenabled()
                UiHelper.notifyStreamExitingPiP(this)
            }
        }

        performanceOverlayManager?.onConfigurationChanged()
        refreshDisplayPosition()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(prefConfig.width, prefConfig.height))
            .setSourceRectHint(
                Rect(
                    streamView.left, streamView.top,
                    streamView.right, streamView.bottom
                )
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName)
                if (pcName != null) {
                    builder.setSubtitle(pcName)
                }
            } else if (pcName != null) {
                builder.setTitle(pcName)
            }
        }

        return builder.build()
    }

    fun updatePipAutoEnter() {
        if (!prefConfig.enablePip) return

        val autoEnter = connected && suppressPipRefCount == 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter))
        } else {
            autoEnterPip = autoEnter
        }
    }

    fun setMetaKeyCaptureState(enabled: Boolean) {
        try {
            val semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager")
            val getInstanceMethod = semWindowManager.getMethod("getInstance")
            val manager = getInstanceMethod.invoke(null)

            if (manager != null) {
                val parameterTypes = arrayOf<Class<*>>(ComponentName::class.java, Boolean::class.javaPrimitiveType!!)
                val requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", *parameterTypes)
                requestMetaKeyEventMethod.invoke(manager, this.componentName, enabled)
            } else {
                LimeLog.warning("SemWindowManager.getInstance() returned null")
            }
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
        } catch (_: InvocationTargetException) {
        } catch (_: IllegalAccessException) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (isResumeStreamEnabled) {
            if (!autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                shouldResumeSession = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                shouldResumeSession = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    enterPictureInPictureMode(getPictureInPictureParams(false))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onPictureInPictureRequested(): Boolean {
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false))
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        keyboardInputHandler.clearModifierState()
        inputCaptureProvider.onWindowFocusChanged(hasFocus)
    }

    private fun prepareDisplayForRendering(): Float {
        val display = currentTargetDisplay

        val result = DisplayModeManager.selectBestDisplayMode(display, prefConfig)

        val windowLayoutParams = window.attributes
        if (result.preferredModeId >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                windowLayoutParams.preferredDisplayModeId = result.preferredModeId
            }
            window.attributes = windowLayoutParams
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            windowLayoutParams.preferredRefreshRate = result.refreshRate
            window.attributes = windowLayoutParams
        }

        updateStreamViewSize(prefConfig.width, prefConfig.height, result.aspectRatioMatch)
        desiredRefreshRate = result.refreshRate
        return result.refreshRate
    }

    @SuppressLint("InlinedApi")
    private val hideSystemUiRunnable = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    fun hideSystemUi(delay: Int) {
        val h = window.decorView.handler
        if (h != null) {
            h.removeCallbacks(hideSystemUiRunnable)
            h.postDelayed(hideSystemUiRunnable, delay.toLong())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Deprecated("Deprecated in Java")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer?.notifyVideoBackground()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer?.notifyVideoForeground()
        }
        hideSystemUi(50)
    }

    override fun onDestroy() {
        cancelKeepAliveNotification()
        orientationManager.cleanup()
        floatBallHandler.release()

        super.onDestroy()

        if (conn != null && connected) {
            connectionCallbackHandler.stopConnection()
        }

        controllerHandler?.destroy()

        if (audioVibrationService != null) {
            audioVibrationService?.stop()
            MoonBridge.setBassEnergyEnabled(false)
            MoonBridge.setBassEnergyListener(null)
        }

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(keyboardInputHandler.keyboardTranslator)

        lowLatencyWifiLock?.release()
        highPerfWifiLock?.release()
        usbDriverServiceManager?.stopAndUnbind()
        inputCaptureProvider.destroy()
        externalDisplayManager?.cleanup()
        microphoneManager?.stopMicrophoneStream()
    }

    override fun onPause() {
        floatBallHandler.hide()
        KeyboardAccessibilityService.setIntercepting(false)
        KeyboardAccessibilityService.instance?.keyEventCallback = null

        if (isFinishing) {
            controllerHandler?.stop()
            setInputGrabState(false)
        }
        super.onPause()
    }

    fun changeResolution() {
        isChangingResolution = true
        this.recreate()
    }

    override fun onStop() {
        super.onStop()

        if ((isExtremeResumeEnabled || isChangingResolution) && !isFinishing) {
            LimeLog.info("Extreme Resume: onStop intercepted.")
            if (!isChangingResolution && isResumeStreamEnabled) {
                showKeepAliveNotification()
            }
            return
        }

        if (isStreamingActive && lastActiveTime > 0) {
            accumulatedStreamTime += System.currentTimeMillis() - lastActiveTime
            isStreamingActive = false
            LimeLog.info("串流时长计时暂停，已累计: ${accumulatedStreamTime / 1000} 秒")
        }

        if (!shouldResumeSession && !isFinishing) {
            if (isResumeStreamEnabled) {
                shouldResumeSession = true
                LimeLog.info("检测到应用进入后台（非主动退出），已标记为待恢复会话")
            }
        }

        if (progressOverlay != null) {
            progressOverlay?.dismiss()
            progressOverlay = null
        }
        Dialog.closeDialogs()

        if (virtualController != null) {
            virtualController?.hide()
            virtualController?.cleanup()
        }

        var decoderMessage = getDecoderFormatLabel()

        if (conn != null) {
            displayedFailureDialog = true
            connectionCallbackHandler.stopConnection()
            showLatencyToast(decoderMessage)

            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                    .putInt("CrashCount", 0)
                    .putInt("LastNotifiedCrashCount", 0)
                    .apply()
            }
        }

        reportStreamAnalytics(decoderMessage)

        if (shouldResumeSession && isResumeStreamEnabled) {
            showKeepAliveNotification()
            LimeLog.info("应用进入后台，保持 Activity 存活以备快速恢复。连接已断开。")
        } else {
            finish()
        }
    }

    private fun getDecoderFormatLabel(): String {
        if (decoderRenderer == null) return "UNKNOWN"
        val videoFormat = (decoderRenderer?.getActiveVideoFormat() ?: 0)
        var label = when {
            (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 -> "H.264"
            (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> "HEVC"
            (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> "AV1"
            else -> "UNKNOWN"
        }
        if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
            label += " HDR"
        }
        return label
    }

    private fun showLatencyToast(decoderMessage: String) {
        if (!prefConfig.enableLatencyToast) return
        val averageEndToEndLat = (decoderRenderer?.getAverageEndToEndLatency() ?: 0)
        val averageDecoderLat = (decoderRenderer?.getAverageDecoderLatency() ?: 0)
        var message: String? = null
        if (averageEndToEndLat > 0) {
            message = resources.getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms"
            if (averageDecoderLat > 0) {
                message += " (" + resources.getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
            }
        } else if (averageDecoderLat > 0) {
            message = resources.getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms"
        }

        if (message != null) {
            message += " [$decoderMessage]"
        }

        if (prefConfig.enableMic && microphoneManager != null) {
            val micStats = AudioDiagnostics.getCurrentStats(this)
            message = if (message != null) "$message [mic]$micStats" else micStats
        }

        val surfaceFlingerStats = decoderRenderer?.getSurfaceFlingerStats() ?: ""
        if (surfaceFlingerStats.isNotEmpty()) {
            message = if (message != null) "$message\n$surfaceFlingerStats" else surfaceFlingerStats
        }

        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun reportStreamAnalytics(decoderMessage: String) {
        if (analyticsManager == null || pcName == null || streamStartTime <= 0) return

        var effectiveStreamDuration = accumulatedStreamTime
        if (isStreamingActive && lastActiveTime > 0) {
            effectiveStreamDuration += System.currentTimeMillis() - lastActiveTime
        }
        val totalElapsedTime = System.currentTimeMillis() - streamStartTime

        var resolutionWidth = 0
        var resolutionHeight = 0
        var averageEndToEndLatency = 0
        var averageDecoderLatency = 0
        if (decoderRenderer != null) {
            resolutionWidth = prefConfig.width
            resolutionHeight = prefConfig.height
            averageEndToEndLatency = (decoderRenderer?.getAverageEndToEndLatency() ?: 0)
            averageDecoderLatency = (decoderRenderer?.getAverageDecoderLatency() ?: 0)
        }

        analyticsManager?.logGameStreamEnd(
            pcName ?: "", appName, effectiveStreamDuration,
            decoderMessage, resolutionWidth, resolutionHeight,
            averageEndToEndLatency, averageDecoderLatency
        )
        LimeLog.info("串流统计 - 有效时长: ${effectiveStreamDuration / 1000}秒, 总耗时: ${totalElapsedTime / 1000}秒")
        streamStartTime = 0
        accumulatedStreamTime = 0
        isStreamingActive = false
    }

    fun setInputGrabState(grab: Boolean) {
        if (grab) {
            inputCaptureProvider.enableCapture()
            if (cursorVisible) {
                inputCaptureProvider.showCursor()
            }
        } else {
            inputCaptureProvider.disableCapture()
        }
        setMetaKeyCaptureState(grab)
        grabbedInput = grab
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return keyboardInputHandler.handleKeyDown(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyboardInputHandler.handleKeyUp(event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return keyboardInputHandler.handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        return keyboardInputHandler.handleKeyDown(event)
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        return keyboardInputHandler.handleKeyUp(event)
    }

    val relativeTouchContextMap: Array<TouchContext?>
        get() = touchInputHandler.relativeTouchContextMap

    fun setTouchMode(enableRelativeTouch: Boolean) {
        touchInputHandler.setTouchMode(enableRelativeTouch)
    }

    fun setEnhancedTouch(enableRelativeTouch: Boolean) {
        touchInputHandler.setEnhancedTouch(enableRelativeTouch)
    }

    override fun toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay")
        streamView.clearFocus()
        val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.toggleSoftInput(0, 0)
    }

    fun enableNativeMousePointer(enable: Boolean) {
        LimeLog.info("Setting native mouse pointer: $enable")
        prefConfig.enableNativeMousePointer = enable

        if (enable) {
            inputCaptureProvider.disableCapture()
            cursorVisible = true
            inputCaptureProvider?.showCursor()
            setMetaKeyCaptureState(true)
            cursorServiceManager.refreshLocalCursorState(true)
            val cursorOverlay = findViewById<CursorView>(R.id.cursorOverlay)
            cursorOverlay?.hide()
        } else {
            cursorVisible = false
            inputCaptureProvider?.hideCursor()
            setInputGrabState(true)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return touchInputHandler.handleMotionEvent(null, event) || super.onGenericMotionEvent(event)
    }

    override fun onGenericMotion(view: View, event: MotionEvent): Boolean {
        return touchInputHandler.handleMotionEvent(view, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!prefConfig.syncTouchEventWithDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.requestUnbufferedDispatch(event)
            }
        }
        return touchInputHandler.handleMotionEvent(view, event)
    }

    override fun stageStarting(stage: String) {
        connectionCallbackHandler.stageStarting(stage)
    }

    override fun stageComplete(stage: String) {
        connectionCallbackHandler.stageComplete(stage)
    }

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        connectionCallbackHandler.stageFailed(stage, portFlags, errorCode)
    }

    override fun connectionTerminated(errorCode: Int) {
        connectionCallbackHandler.connectionTerminated(errorCode)
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        connectionCallbackHandler.connectionStatusUpdate(connectionStatus)
    }

    override fun connectionStarted() {
        connectionCallbackHandler.connectionStarted()
    }

    override fun onStart() {
        super.onStart()

        if (!isStreamingActive && streamStartTime > 0) {
            lastActiveTime = System.currentTimeMillis()
            isStreamingActive = true
            LimeLog.info("串流时长计时恢复，之前累计: ${accumulatedStreamTime / 1000} 秒")
        }

        if (isExtremeResumeEnabled && connected) {
            LimeLog.info("Extreme Resume: Returning to foreground with active connection.")
            if (progressOverlay != null) {
                progressOverlay?.dismiss()
                progressOverlay = null
            }
            hideSystemUi(500)
            return
        }

        if (shouldResumeSession) {
            LimeLog.info("从后台恢复，正在快速重连...")
            Dialog.closeDialogs()
            shouldResumeSession = false
            displayedFailureDialog = false

            showProgressOverlay()

            try {
                prepareConnection()
            } catch (e: Exception) {
                LimeLog.severe("Failed to prepare connection: ${e.message}")
                finish()
                return
            }

            attemptedConnection = false
            connecting = false
            connected = false
            orientationManager.connected = false

            streamView?.requestLayout()
            streamView?.invalidate()
        }
    }

    override fun displayMessage(message: String) {
        runOnUiThread { Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show() }
    }

    override fun displayTransientMessage(message: String) {
        if (!prefConfig.disableWarnings) displayMessage(message)
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        LimeLog.info(String.format(null as Locale?, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor))
        controllerManager?.elementController?.gameVibrator(lowFreqMotor, highFreqMotor)
        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        LimeLog.info(String.format(null as Locale?, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger))
        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        LimeLog.info("Display HDR mode: ${if (enabled) "enabled" else "disabled"}")
        decoderRenderer?.setHdrMode(enabled, hdrMetadata)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notifySystemHdrStatus(enabled)
        }
    }

    private fun notifySystemHdrStatus(hdrEnabled: Boolean) {
        runOnUiThread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.colorMode = if (hdrEnabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                }

                val params = window.attributes
                if (hdrEnabled) {
                    if (prefConfig?.enableHdrHighBrightness == true) {
                        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    }
                } else {
                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.attributes = params

                LimeLog.info("ColorOS HDR notification: Window color mode and brightness updated for HDR ${if (hdrEnabled) "enabled" else "disabled"}")
            } catch (e: Exception) {
                LimeLog.warning("Failed to notify ColorOS system HDR status: ${e.message}")
            }
        }
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun onResolutionChanged(width: Int, height: Int) {
        val alignedWidth = width and 1.inv()
        val alignedHeight = height and 1.inv()

        val baseWidth: Int
        val baseHeight: Int

        if (prefConfig.resolutionScale != 100) {
            baseWidth = (alignedWidth * 100 / prefConfig.resolutionScale) and 1.inv()
            baseHeight = (alignedHeight * 100 / prefConfig.resolutionScale) and 1.inv()
            LimeLog.info("Resolution scale conversion: actual=${alignedWidth}x${alignedHeight}, base=${baseWidth}x${baseHeight}, scale=${prefConfig.resolutionScale}%")
        } else {
            baseWidth = alignedWidth
            baseHeight = alignedHeight
        }

        orientationManager.syncOrientationOnFirstFrame(baseWidth, baseHeight)

        if (prefConfig.width == baseWidth && prefConfig.height == baseHeight) {
            return
        }

        LimeLog.info("Resolution changed: ${prefConfig.width}x${prefConfig.height} -> ${baseWidth}x${baseHeight}")

        prefConfig.width = baseWidth
        prefConfig.height = baseHeight

        if (connected && decoderRenderer != null) {
            decoderRenderer?.onResolutionChanged(baseWidth, baseHeight)
        }

        val isLandscape = baseWidth > baseHeight
        runOnUiThread {
            Toast.makeText(this, getString(R.string.host_resolution_changed, baseWidth, baseHeight), Toast.LENGTH_SHORT).show()
            orientationManager.onServerResolutionChanged(isLandscape)
            updateStreamViewSize(baseWidth, baseHeight)
        }
    }

    private fun updateStreamViewSize(width: Int, height: Int, forceFixedSize: Boolean) {
        val screenSize = Point()
        currentTargetDisplay.getRealSize(screenSize)

        val exceedsScreenSize = width > screenSize.x || height > screenSize.y
        val useFixedSize = (prefConfig.stretchVideo && !exceedsScreenSize) || forceFixedSize

        if (useFixedSize) {
            streamView.setDesiredAspectRatio(0.0)
            streamView.holder.setFixedSize(width, height)
            LimeLog.info("Set fixed surface size: ${width}x${height} (screen: ${screenSize.x}x${screenSize.y})")
        } else {
            if (exceedsScreenSize) {
                LimeLog.info("Host resolution ${width}x${height} exceeds screen size ${screenSize.x}x${screenSize.y}, using aspect ratio scaling")
            }
            streamView.holder.setSizeFromLayout()
            streamView.setDesiredAspectRatio(width.toDouble() / height)
            streamView.requestLayout()
        }
    }

    private fun updateStreamViewSize(width: Int, height: Int) {
        updateStreamViewSize(width, height, false)
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!surfaceCreated) {
            throw IllegalStateException("Surface changed before creation!")
        }

        decoderRenderer?.setRenderTarget(holder)

        if (!attemptedConnection) {
            attemptedConnection = true
            UiHelper.notifyStreamConnecting(this)

            this.audioRenderer = AndroidAudioRenderer(this, prefConfig.enableAudioFx, prefConfig.enableSpatializer)
            conn?.start(this.audioRenderer!!, decoderRenderer!!, this)

            streamView?.post { cursorServiceManager.syncCursorWithStream() }
        } else if (connected && isExtremeResumeEnabled) {
            streamView?.post { cursorServiceManager.syncCursorWithStream() }
            audioRenderer?.resumeProcessing()
            decoderRenderer?.resumeProcessing()
        }

        panZoomHandler.handleSurfaceChange()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreated = true

        val desiredFrameRate: Float = if (DisplayModeManager.mayReduceRefreshRate(prefConfig) || desiredRefreshRate < prefConfig.fps) {
            prefConfig.fps.toFloat()
        } else {
            desiredRefreshRate
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!surfaceCreated) {
            throw IllegalStateException("Surface destroyed before creation!")
        }

        cursorServiceManager.destroyLocalCursorRenderers()

        if (attemptedConnection) {
            if (isExtremeResumeEnabled && !isFinishing) {
                val globalPrefs = PreferenceManager.getDefaultSharedPreferences(this)
                if (!globalPrefs.getBoolean("checkbox_background_audio", false)) {
                    audioRenderer?.pauseProcessing()
                    LimeLog.info("Extreme Resume: Audio muted for background.")
                }
                decoderRenderer?.pauseProcessing()
                return
            } else {
                decoderRenderer?.prepareForStop()
                if (connected) {
                    connectionCallbackHandler.stopConnection()
                }
            }
        }
    }

    fun showKeepAliveNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    KEEP_ALIVE_NOTIFICATION_ID
                )
                return
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val isResumeEnabled = prefs.getBoolean("checkbox_resume_stream", false)
                val hasRequestedOptimization = prefs.getBoolean("pref_battery_optimization_requested", false)

                if (isResumeEnabled && !hasRequestedOptimization) {
                    if (ContextCompat.checkSelfPermission(this, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            try {
                                startActivity(intent)
                                prefs.edit().putBoolean("pref_battery_optimization_requested", true).apply()
                            } catch (e: Exception) {
                                LimeLog.warning("Cannot open battery optimization settings: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        StreamNotificationService.start(this, pcName, appName)
    }

    fun cancelKeepAliveNotification() {
        StreamNotificationService.stop(this)
    }

    fun refreshLocalCursorState(enabled: Boolean) {
        cursorServiceManager?.refreshLocalCursorState(enabled)
    }

    override fun mouseMove(deltaX: Int, deltaY: Int) {
        conn?.sendMouseMove(deltaX.toShort(), deltaY.toShort())
    }

    override fun mouseButtonEvent(buttonId: Int, down: Boolean) {
        val buttonIndex: Byte = when (buttonId) {
            EvdevListener.BUTTON_LEFT -> MouseButtonPacket.BUTTON_LEFT
            EvdevListener.BUTTON_MIDDLE -> MouseButtonPacket.BUTTON_MIDDLE
            EvdevListener.BUTTON_RIGHT -> MouseButtonPacket.BUTTON_RIGHT
            EvdevListener.BUTTON_X1 -> MouseButtonPacket.BUTTON_X1
            EvdevListener.BUTTON_X2 -> MouseButtonPacket.BUTTON_X2
            else -> {
                LimeLog.warning("Unhandled button: $buttonId")
                return
            }
        }
        if (down) conn?.sendMouseButtonDown(buttonIndex)
        else conn?.sendMouseButtonUp(buttonIndex)
    }

    override fun mouseVScroll(amount: Byte) {
        conn?.sendMouseScroll(amount)
    }

    override fun mouseHScroll(amount: Byte) {
        conn?.sendMouseHScroll(amount)
    }

    override fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        keyboardInputHandler.keyboardEvent(buttonDown, keyCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onSystemUiVisibilityChange(visibility: Int) {
        if (!connected) return
        if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 ||
            (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
        ) {
            hideSystemUi(2000)
        }
    }

    override fun onPerfUpdateV(performanceInfo: PerformanceInfo) {
        performanceOverlayManager?.updatePerformanceInfo(performanceInfo)
    }

    override fun isPerfOverlayVisible(): Boolean {
        return performanceOverlayManager?.isPerfOverlayVisible() == true
    }

    override fun onPerfUpdateWG(performanceInfo: PerformanceInfo) {
        runOnUiThread {
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val timeMillis = System.currentTimeMillis()
            val timeMillisInterval = timeMillis - previousTimeMillis

            if (timeMillisInterval > 0 && timeMillisInterval < 5000) {
                performanceInfo.bandWidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval)
            }

            previousTimeMillis = timeMillis
            previousRxBytes = currentRxBytes

            if (controllerManager != null && performanceInfoDisplays.isNotEmpty()) {
                val perfAttrs = HashMap<String, String>()
                perfAttrs[getString(R.string.perf_decoder)] = performanceInfo.decoder ?: ""
                perfAttrs[getString(R.string.perf_resolution)] = "${performanceInfo.initialWidth}x${performanceInfo.initialHeight}"
                perfAttrs[getString(R.string.perf_fps)] = String.format("%.0f", performanceInfo.totalFps)
                perfAttrs[getString(R.string.perf_frame_loss)] = String.format("%.1f", performanceInfo.lostFrameRate)
                perfAttrs[getString(R.string.perf_network_rtt)] = String.format("%d", (performanceInfo.rttInfo shr 32).toInt())
                perfAttrs[getString(R.string.perf_host_latency)] = String.format("%.2f", performanceInfo.aveHostProcessingLatency)
                perfAttrs[getString(R.string.perf_decode_time)] = String.format("%.2f", performanceInfo.decodeTimeMs)
                perfAttrs[getString(R.string.perf_bandwidth)] = performanceInfo.bandWidth ?: ""
                perfAttrs[getString(R.string.perf_render_latency)] = String.format("%.2f", performanceInfo.renderingLatencyMs)
                for (display in performanceInfoDisplays) {
                    display.display(perfAttrs)
                }
            }
        }
    }

    fun removePerformanceInfoDisplay(display: PerformanceInfoDisplay) {
        performanceInfoDisplays.remove(display)
    }

    override fun onUsbPermissionPromptStarting() {
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    override fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    override fun showGameMenu(device: GameInputDevice?) {
        when (currentBackKeyMenu) {
            BackKeyMenuMode.CROWN_MODE -> {
                if (controllerManager != null && prefConfig.onscreenKeyboard) {
                    controllerManager?.superPagesController?.returnOperation()
                }
            }
            BackKeyMenuMode.NO_MENU -> {}
            BackKeyMenuMode.GAME_MENU -> {
                GameMenu(this, app, conn!!, device)
            }
        }
    }

    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> keyboardInputHandler.handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> keyboardInputHandler.handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> keyboardInputHandler.handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    fun disconnect() {
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showGameMenu(null)
    }

    fun togglePerformanceOverlay() {
        if (performanceOverlayManager == null) return

        if (!prefConfig.enablePerfOverlay) {
            prefConfig.enablePerfOverlay = true
            prefConfig.perfOverlayLocked = false
            performanceOverlayManager?.applyOverlayState()
        } else if (!prefConfig.perfOverlayLocked) {
            prefConfig.perfOverlayLocked = true
            performanceOverlayManager?.applyOverlayState()
        } else {
            prefConfig.enablePerfOverlay = false
            prefConfig.perfOverlayLocked = false
            performanceOverlayManager?.applyOverlayState()
        }
    }

    fun toggleMicrophoneButton() {
        if (micButton != null) {
            if (micButton?.visibility == View.VISIBLE) {
                micButton?.visibility = View.GONE
                Toast.makeText(this, getString(R.string.toast_mic_button_hidden), Toast.LENGTH_SHORT).show()
            } else {
                micButton?.visibility = View.VISIBLE
                Toast.makeText(this, getString(R.string.toast_mic_button_shown), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleVirtualController() {
        if (virtualController != null && virtualController?.elements?.isNotEmpty() == true) {
            val isVisible = virtualController?.elements?.get(0)?.visibility == View.VISIBLE
            if (isVisible) {
                virtualController?.hide()
                Toast.makeText(this, getString(R.string.toast_virtual_controller_hidden), Toast.LENGTH_SHORT).show()
            } else {
                virtualController?.show()
                Toast.makeText(this, getString(R.string.toast_virtual_controller_shown), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_virtual_controller_not_enabled), Toast.LENGTH_SHORT).show()
        }
    }

    fun initializeControllerManager() {
        if (controllerManager == null) {
            controllerManager = ControllerManager(streamView.parent as FrameLayout, this)
            controllerManager?.refreshLayout()
        }
    }

    var isCrownFeatureEnabled: Boolean
        get() = prefConfig.onscreenKeyboard
        set(value) {
            prefConfig.onscreenKeyboard = value
            if (value) {
                if (controllerManager != null) {
                    controllerManager?.show()
                } else {
                    initializeControllerManager()
                }
            } else {
                controllerManager?.hide()
            }
        }

    fun addPerformanceInfoDisplay(performanceInfoDisplay: PerformanceInfoDisplay) {
        performanceInfoDisplays.add(performanceInfoDisplay)
    }

    fun refreshDisplayPosition() {
        DisplayPositionManager(this, prefConfig, streamView).refreshDisplayPosition(surfaceCreated)
    }

    val activeStreamView: StreamView?
        get() {
            if (externalDisplayManager?.isUsingExternalDisplay() == true && externalStreamView != null) {
                return externalStreamView
            }
            return streamView
        }

    fun getHandleMotionEvent(streamView: StreamView, event: MotionEvent): Boolean {
        return touchInputHandler.handleMotionEvent(streamView, event)
    }

    private fun applyLastSettingsToCurrentSession() {
        if (appSettingsManager != null) {
            val applied = appSettingsManager?.applyLastSettingsFromIntent(intent, prefConfig) == true
            if (applied) {
                Toast.makeText(this, getString(R.string.app_last_settings_start_with_last), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Toast.makeText(this, getString(R.string.toast_enable_notification_for_bg), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        val REFERENCE_HORIZ_RES = 1280
        val REFERENCE_VERT_RES = 720

        val EXTRA_HOST = "Host"
        val EXTRA_PORT = "Port"
        val EXTRA_HTTPS_PORT = "HttpsPort"
        val EXTRA_APP_NAME = "AppName"
        val EXTRA_APP_ID = "AppId"
        val EXTRA_UNIQUEID = "UniqueId"
        val EXTRA_PC_UUID = "UUID"
        val EXTRA_PC_NAME = "PcName"
        val EXTRA_PAIR_NAME = "PairName"
        val EXTRA_APP_HDR = "HDR"
        val EXTRA_SERVER_CERT = "ServerCert"
        val EXTRA_PC_USEVDD = "usevdd"
        val EXTRA_APP_CMD = "CmdList"
        val EXTRA_DISPLAY_NAME = "DisplayName"
        val EXTRA_SCREEN_COMBINATION_MODE = "Screen combination mode"
        val EXTRA_VDD_SCREEN_COMBINATION_MODE = "VDD screen combination mode"

        private const val KEEP_ALIVE_NOTIFICATION_ID = 1001
    }
}
