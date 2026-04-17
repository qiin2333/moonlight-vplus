@file:Suppress("DEPRECATION")
package com.limelight

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException

import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerService
import com.limelight.dialogs.AddressSelectionDialog
import com.limelight.grid.PcGridAdapter
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairResult
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManually
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings
import com.limelight.services.KeyboardAccessibilityService
import com.limelight.ui.AdapterFragment
import com.limelight.ui.AdapterFragmentCallbacks
import com.limelight.utils.AnalyticsManager
import com.limelight.utils.AppCacheManager
import com.limelight.utils.CacheHelper
import com.limelight.utils.Dialog
import com.limelight.utils.EasyTierController
import com.limelight.utils.HelpLauncher
import com.limelight.utils.Iperf3Tester
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.UiHelper
import com.limelight.utils.UpdateManager
import com.squareup.seismic.ShakeDetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserException

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.SensorManager
import android.net.Uri
import android.net.VpnService
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.preference.PreferenceManager
import android.provider.Settings
import android.util.LruCache
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.AbsListView
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import jp.wasabeef.glide.transformations.BlurTransformation
import jp.wasabeef.glide.transformations.ColorFilterTransformation

class PcView : Activity(), AdapterFragmentCallbacks, ShakeDetector.Listener, EasyTierController.VpnPermissionCallback {

    // Constants
    companion object {
        private const val REFRESH_DEBOUNCE_DELAY = 150L
        private const val SHAKE_DEBOUNCE_INTERVAL = 3000L
        private const val MAX_DAILY_REFRESH = 7
        private const val VPN_PERMISSION_REQUEST_CODE = 101
        private const val QR_SCAN_REQUEST_CODE = 102

        private const val REFRESH_PREF_NAME = "RefreshLimit"
        private const val REFRESH_COUNT_KEY = "refresh_count"
        private const val REFRESH_DATE_KEY = "refresh_date"
        private const val SCENE_PREF_NAME = "SceneConfigs"
        private const val SCENE_KEY_PREFIX = "scene_"

        // Menu item IDs
        private const val PAIR_ID = 2
        private const val UNPAIR_ID = 3
        private const val WOL_ID = 4
        private const val DELETE_ID = 5
        private const val RESUME_ID = 6
        private const val QUIT_ID = 7
        private const val VIEW_DETAILS_ID = 8
        private const val FULL_APP_LIST_ID = 9
        private const val TEST_NETWORK_ID = 10
        private const val GAMESTREAM_EOL_ID = 11
        private const val SLEEP_ID = 12
        private const val IPERF3_TEST_ID = 13
        private const val SECONDARY_SCREEN_ID = 14
        private const val DISABLE_IPV6_ID = 15
    }

    // UI Components
    private var noPcFoundLayout: RelativeLayout? = null
    private lateinit var pcGridAdapter: PcGridAdapter
    private var pcListView: AbsListView? = null
    private var backgroundImageView: ImageView? = null

    // State
    private var isFirstLoad = true
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var lastShakeTime = 0L

    // Helpers
    private lateinit var shortcutHelper: ShortcutHelper
    private var easyTierController: EasyTierController? = null
    private lateinit var analyticsManager: AnalyticsManager
    private var shakeDetector: ShakeDetector? = null
    private var currentAddressDialog: AddressSelectionDialog? = null
    private var backgroundImageRefreshReceiver: BroadcastReceiver? = null

    // Managers
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private lateinit var bitmapLruCache: LruCache<String, Bitmap>

    // Handlers
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var pendingRefreshRunnable: Runnable? = null

    // 主线程作用域，用于收集 ComputerManagerService 的 Flow。onDestroy 时 cancel。
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingCollectJob: Job? = null

    lateinit var clientName: String

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            uiScope.launch {
                withContext(Dispatchers.IO) {
                    localBinder.waitForReady()
                    AndroidCryptoProvider(this@PcView).getClientCertificate()
                }
                managerBinder = localBinder
                startComputerUpdates()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    // Lifecycle Methods

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //自动获取无障碍权限
        try {
            val cn = ComponentName(this, KeyboardAccessibilityService::class.java)
            val myService = cn.flattenToString()
            var enabledServices = Settings.Secure.getString(contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

            if (enabledServices == null || !enabledServices.contains(myService)) {
                enabledServices = if (enabledServices.isNullOrEmpty()) {
                    myService
                } else {
                    "$enabledServices:$myService"
                }

                // 这里可能会抛异常
                Settings.Secure.putString(contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices)
            }
        } catch (e: SecurityException) {
            // 没无障碍权限
        }

        easyTierController = EasyTierController(this, this)
        inForeground = true
        initBitmapCache()

        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) {
            initGlRenderer(glPrefs)
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer)
            completeOnCreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (completeOnCreateCalled) {
            initializeViews()
        }
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        inForeground = true
        startComputerUpdates()

        analyticsManager?.startUsageTracking()
        startShakeDetector()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates(false)

        analyticsManager?.stopUsageTracking()
        stopShakeDetector()
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
    }

    override fun onDestroy() {
        super.onDestroy()

        uiScope.cancel()
        easyTierController?.onDestroy()
        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
        if (currentAddressDialog != null) {
            currentAddressDialog?.dismiss()
            currentAddressDialog = null
        }
        unregisterBackgroundReceiver()

        analyticsManager?.cleanup()
        if (pendingRefreshRunnable != null) {
            refreshHandler.removeCallbacks(pendingRefreshRunnable!!)
            pendingRefreshRunnable = null
        }
    }

    // Initialization Methods

    private fun initBitmapCache() {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        bitmapLruCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }

    private fun initGlRenderer(glPrefs: GlPreferences) {
        val surfaceView = GLSurfaceView(this)
        surfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
                glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER)
                glPrefs.savedFingerprint = Build.FINGERPRINT
                glPrefs.writePreferences()
                LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)
                runOnUiThread { completeOnCreate() }
            }

            override fun onSurfaceChanged(gl10: GL10, i: Int, i1: Int) {}

            override fun onDrawFrame(gl10: GL10) {}
        })
        setContentView(surfaceView)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun completeOnCreate() {
        completeOnCreateCalled = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        analyticsManager = AnalyticsManager.getInstance(this)
        analyticsManager.logAppLaunch()
        UpdateManager.checkForUpdatesOnStartup(this)

        bindService(Intent(this, ComputerManagerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)

        pcGridAdapter = PcGridAdapter(this, PreferenceConfiguration.readPreferences(this))
        pcGridAdapter.setAvatarClickListener { computer, itemView -> handleAvatarClick(computer, itemView) }

        initShakeDetector()
        registerBackgroundReceiver()
        initializeViews()
    }

    private fun initializeViews() {
        setContentView(R.layout.activity_pc_view)
        UiHelper.notifyNewRootView(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        clientName = Settings.Global.getString(contentResolver, "device_name")
        backgroundImageView = findViewById(R.id.pcBackgroundImage)

        loadBackgroundImage()
        setupBackgroundImageLongPress()
        initSceneButtons()

        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this))
        setupButtons()
        setupAdapterFragment()

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout)
        addAddComputerCard()
        updateNoPcFoundVisibility()
        handleInitialLoad()
    }

    private fun setupButtons() {
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val restoreSessionButton = findViewById<ImageButton>(R.id.restoreSessionButton)
        val aboutButton = findViewById<ImageButton>(R.id.aboutButton)
        val easyTierButton = findViewById<ImageButton>(R.id.easyTierControlButton)
        val toggleUnpairedButton = findViewById<ImageButton>(R.id.toggleUnpairedButton)

        settingsButton.setOnClickListener { startActivity(Intent(this, StreamSettings::class.java)) }
        restoreSessionButton.setOnClickListener { restoreLastSession() }

        aboutButton?.setOnClickListener { showAboutDialog() }
        easyTierButton?.setOnClickListener { showEasyTierControlDialog() }
        toggleUnpairedButton?.let { btn ->
            updateToggleUnpairedButtonIcon(btn)
            btn.setOnClickListener { toggleUnpairedDevices(btn) }
        }
    }

    private fun setupAdapterFragment() {
        fragmentManager.beginTransaction()
                .replace(R.id.pcFragmentContainer, AdapterFragment())
                .commitAllowingStateLoss()
    }

    private fun updateNoPcFoundVisibility() {
        val isEmpty = pcGridAdapter.count == 0 ||
                (pcGridAdapter.count == 1 && PcGridAdapter.isAddComputerCard(pcGridAdapter.getItem(0) as ComputerObject))
        noPcFoundLayout?.visibility = if (isEmpty) View.VISIBLE else View.INVISIBLE
    }

    private fun handleInitialLoad() {
        if (isFirstLoad) {
            if (pendingRefreshRunnable != null) {
                refreshHandler.removeCallbacks(pendingRefreshRunnable!!)
                pendingRefreshRunnable = null
            }
            if (pcListView != null) {
                pcGridAdapter.notifyDataSetChanged()
            }
        } else {
            debouncedNotifyDataSetChanged()
        }
    }

    // Background Image Methods

    private fun loadBackgroundImage() {
        if (backgroundImageView == null) return

        val imageUrl = getBackgroundImageUrl()
        uiScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val glideTarget = resolveGlideTarget(imageUrl)
                    Glide.with(this@PcView as Context)
                            .asBitmap()
                            .load(glideTarget)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .submit()
                            .get()
                }
                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap)
                    applyBlurredBackground(bitmap)
                }
            } catch (e: ExecutionException) {
                handleGlideException(e)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun resolveGlideTarget(imageUrl: String): Any {
        if (imageUrl.startsWith("http")) {
            return imageUrl
        }
        val localFile = File(imageUrl)
        return if (localFile.exists()) localFile else getDefaultApiUrl()
    }

    private fun applyBlurredBackground(bitmap: Bitmap) {
        if (backgroundImageView == null) return
        Glide.with(this as Context)
                .load(bitmap)
                .apply(RequestOptions.bitmapTransform(BlurTransformation(2, 3)))
                .transform(ColorFilterTransformation(Color.argb(120, 0, 0, 0)))
                .into(backgroundImageView!!)
    }

    private fun handleGlideException(e: ExecutionException) {
        val cause = e.cause
        if (cause != null) {
            val msg = cause.message
            if (msg != null && (msg.contains("HttpException") || msg.contains("SocketException") || msg.contains("MediaMetadataRetriever"))) {
                LimeLog.warning("Background image download failed: $msg")
                return
            }
        }
        e.printStackTrace()
    }

    private fun setupBackgroundImageLongPress() {
        backgroundImageView?.setOnLongClickListener {
            saveImageWithPermissionCheck()
            true
        }
    }

    private fun getBackgroundImageUrl(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val type = prefs.getString("background_image_type", "default")

        return when (type) {
            "api" -> {
                val apiUrl = prefs.getString("background_image_url", null)
                if (!apiUrl.isNullOrEmpty()) {
                    apiUrl
                } else {
                    prefs.edit().putString("background_image_type", "default").apply()
                    getDefaultApiUrl()
                }
            }

            "local" -> {
                val localPath = prefs.getString("background_image_local_path", null)
                if (localPath != null && File(localPath).exists()) {
                    localPath
                } else {
                    prefs.edit()
                            .putString("background_image_type", "default")
                            .remove("background_image_local_path")
                            .apply()
                    getDefaultApiUrl()
                }
            }

            else -> getDefaultApiUrl()
        }
    }

    private fun getDefaultApiUrl(): String {
        val rotation = windowManager.defaultDisplay.rotation
        return if (rotation == Configuration.ORIENTATION_PORTRAIT)
            "https://img-api.pipw.top"
        else
            "https://img-api.pipw.top/?phone=true"
    }

    private fun refreshBackgroundImage(isFromShake: Boolean) {
        if (backgroundImageView == null) return

        val imageUrl = getBackgroundImageUrl()
        bitmapLruCache.remove(imageUrl)

        uiScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val glideTarget = resolveGlideTarget(imageUrl)
                    Glide.with(this@PcView as Context)
                            .asBitmap()
                            .load(glideTarget)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .submit()
                            .get()
                }
                if (bitmap != null) {
                    bitmapLruCache.put(imageUrl, bitmap)
                    applyBlurredBackground(bitmap)
                    if (isFromShake) {
                        showToast(getString(R.string.background_refreshed_with_remaining, getRemainingRefreshCount()))
                    }
                } else {
                    showToast(getString(R.string.refresh_failed_please_retry))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast(getString(R.string.refresh_failed_with_error, e.message))
            }
        }
    }

    // Image Save Methods

    private fun saveImageWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showToast(getString(R.string.storage_permission_required))
            requestStoragePermission()
            return
        }
        saveImage()
    }

    private fun requestStoragePermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun saveImage() {
        val bitmap = bitmapLruCache.get(getBackgroundImageUrl())

        if (bitmap == null) {
            if (backgroundImageView != null && backgroundImageView?.drawable != null) {
                showToast(getString(R.string.downloading_image_please_wait))
                downloadAndSaveImage()
            } else {
                showToast(getString(R.string.image_not_loaded_please_retry))
            }
            return
        }
        saveBitmapToFile(bitmap)
    }

    private fun downloadAndSaveImage() {
        uiScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val glideTarget = resolveGlideTarget(getBackgroundImageUrl())
                    Glide.with(this@PcView as Context)
                            .asBitmap()
                            .load(glideTarget)
                            .submit()
                            .get()
                }
                if (bitmap != null) {
                    bitmapLruCache.put(getBackgroundImageUrl(), bitmap)
                    saveBitmapToFile(bitmap)
                } else {
                    showToast(getString(R.string.image_download_failed_retry))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast(getString(R.string.image_download_failed_with_error, e.message))
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap?) {
        if (bitmap == null) {
            showToast(getString(R.string.image_invalid))
            return
        }

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "setu")
        if (!dir.exists() && !dir.mkdirs()) {
            showToast(getString(R.string.image_save_failed_with_error, "Failed to create directory"))
            return
        }

        val fileName = "pipw-${System.currentTimeMillis()}.png"
        val file = File(dir, fileName)

        try {
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            }
            refreshSystemPic(file)
            showToast(getString(R.string.image_saved_successfully))
        } catch (e: IOException) {
            e.printStackTrace()
            showToast(getString(R.string.image_save_failed_with_error, e.message))
        }
    }

    private fun refreshSystemPic(file: File) {
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        intent.data = Uri.fromFile(file)
        sendBroadcast(intent)
    }

    // Shake Detection Methods

    private fun initShakeDetector() {
        shakeDetector = ShakeDetector(this)
        shakeDetector?.setSensitivity(ShakeDetector.SENSITIVITY_MEDIUM)
    }

    private fun startShakeDetector() {
        if (shakeDetector == null) return
        try {
            shakeDetector?.start(getSystemService(SENSOR_SERVICE) as SensorManager)
        } catch (e: Exception) {
            LimeLog.warning("shakeDetector start failed: " + e.message)
        }
    }

    private fun stopShakeDetector() {
        if (shakeDetector == null) return
        try {
            shakeDetector?.stop()
        } catch (e: Exception) {
            LimeLog.warning("shakeDetector stop failed: " + e.message)
        }
    }

    override fun hearShake() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastShakeTime < SHAKE_DEBOUNCE_INTERVAL) {
            val remaining = (SHAKE_DEBOUNCE_INTERVAL - (currentTime - lastShakeTime)) / 1000
            runOnUiThread { showToast(getString(R.string.please_wait_seconds, remaining)) }
            return
        }

        if (!canRefreshToday()) {
            runOnUiThread { showToast(getString(R.string.daily_limit_reached)) }
            return
        }

        lastShakeTime = currentTime
        incrementRefreshCount()
        val remaining = getRemainingRefreshCount()

        runOnUiThread {
            showToast(getString(R.string.refreshing_with_remaining, remaining))
            refreshBackgroundImage(true)
        }
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun canRefreshToday(): Boolean {
        val prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE)
        val today = getTodayDateString()
        val savedDate = prefs.getString(REFRESH_DATE_KEY, "")
        val count = prefs.getInt(REFRESH_COUNT_KEY, 0)

        if (today != savedDate) {
            prefs.edit()
                    .putString(REFRESH_DATE_KEY, today)
                    .putInt(REFRESH_COUNT_KEY, 0)
                    .apply()
            return true
        }
        return count < MAX_DAILY_REFRESH
    }

    private fun getRemainingRefreshCount(): Int {
        val prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE)
        val today = getTodayDateString()
        val savedDate = prefs.getString(REFRESH_DATE_KEY, "")
        val count = prefs.getInt(REFRESH_COUNT_KEY, 0)

        return if (today == savedDate) Math.max(0, MAX_DAILY_REFRESH - count) else MAX_DAILY_REFRESH
    }

    private fun incrementRefreshCount() {
        val prefs = getSharedPreferences(REFRESH_PREF_NAME, MODE_PRIVATE)
        val today = getTodayDateString()
        val savedDate = prefs.getString(REFRESH_DATE_KEY, "")
        val count = if (today == savedDate) prefs.getInt(REFRESH_COUNT_KEY, 0) else 0

        prefs.edit()
                .putString(REFRESH_DATE_KEY, today)
                .putInt(REFRESH_COUNT_KEY, count + 1)
                .apply()
    }

    // Background Receiver Methods

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBackgroundReceiver() {
        backgroundImageRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if ("com.limelight.REFRESH_BACKGROUND_IMAGE" == intent.action) {
                    refreshBackgroundImage(false)
                }
            }
        }

        val filter = IntentFilter("com.limelight.REFRESH_BACKGROUND_IMAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backgroundImageRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(backgroundImageRefreshReceiver, filter)
        }
    }

    private fun unregisterBackgroundReceiver() {
        if (backgroundImageRefreshReceiver != null) {
            try {
                unregisterReceiver(backgroundImageRefreshReceiver)
            } catch (e: IllegalArgumentException) {
                LimeLog.warning("Failed to unregister background receiver: " + e.message)
            }
        }
    }

    // Computer Manager Methods

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        if (runningPolling || !inForeground) return

        freezeUpdates = false
        pollingCollectJob?.cancel()
        pollingCollectJob = uiScope.launch {
            binder.computerUpdates
                .filter { !freezeUpdates }
                .collect { details ->
                    updateComputer(details)
                    if (details.pairState == PairState.PAIRED) {
                        shortcutHelper.createAppViewShortcutForOnlineHost(details)
                    }
                }
        }
        binder.startPolling()
        runningPolling = true
    }

    private fun stopComputerUpdates(wait: Boolean) {
        if (managerBinder == null || !runningPolling) return

        freezeUpdates = true
        pollingCollectJob?.cancel()
        pollingCollectJob = null
        managerBinder?.stopPolling()

        if (wait) {
            managerBinder?.waitForPollingStopped()
        }
        runningPolling = false
    }

    private fun debouncedNotifyDataSetChanged() {
        if (pendingRefreshRunnable != null) {
            refreshHandler.removeCallbacks(pendingRefreshRunnable!!)
        }

        pendingRefreshRunnable = Runnable {
            pcGridAdapter.notifyDataSetChanged()
            pendingRefreshRunnable = null
        }

        refreshHandler.postDelayed(pendingRefreshRunnable!!, REFRESH_DEBOUNCE_DELAY)
    }

    private fun updateComputer(details: ComputerDetails) {
        if (PcGridAdapter.ADD_COMPUTER_UUID == details.uuid) return

        val existingEntry = findComputerByUuid(details.uuid)

        if (existingEntry != null) {
            existingEntry.details = details
            pcGridAdapter.resort()
        } else {
            addNewComputer(details)
        }

        debouncedNotifyDataSetChanged()
    }

    private fun findComputerByUuid(uuid: String?): ComputerObject? {
        for (i in 0 until pcGridAdapter.rawCount) {
            val computer = pcGridAdapter.getRawItem(i)
            if (!PcGridAdapter.isAddComputerCard(computer) && uuid != null && uuid == computer.details.uuid) {
                return computer
            }
        }
        return null
    }

    private fun addNewComputer(details: ComputerDetails) {
        val newComputer = ComputerObject(details)
        pcGridAdapter.addComputer(newComputer)

        val isUnpaired = details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairState.NOT_PAIRED

        if (isUnpaired && !pcGridAdapter.isShowUnpairedDevices()) {
            pcGridAdapter.setShowUnpairedDevices(true)
            updateToggleUnpairedButtonIcon(findViewById(R.id.toggleUnpairedButton))
            showToast(getString(R.string.new_unpaired_device_shown))
        }

        noPcFoundLayout?.visibility = View.INVISIBLE

        if (pcListView != null && !isFirstLoad) {
            pcListView?.scheduleLayoutAnimation()
        }
    }

    private fun removeComputer(details: ComputerDetails) {
        if (PcGridAdapter.ADD_COMPUTER_UUID == details.uuid) return

        managerBinder?.removeComputer(details)
        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid!!)

        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply()

        for (i in 0 until pcGridAdapter.rawCount) {
            val computer = pcGridAdapter.getRawItem(i)
            if (!PcGridAdapter.isAddComputerCard(computer) && details == computer.details) {
                shortcutHelper.disableComputerShortcut(details, getString(R.string.scut_deleted_pc))
                pcGridAdapter.removeComputer(computer)
                pcGridAdapter.notifyDataSetChanged()

                if (countRealComputers() == 0) {
                    noPcFoundLayout?.visibility = View.VISIBLE
                }
                break
            }
        }
    }

    private fun countRealComputers(): Int {
        var count = 0
        for (i in 0 until pcGridAdapter.rawCount) {
            if (!PcGridAdapter.isAddComputerCard(pcGridAdapter.getRawItem(i))) {
                count++
            }
        }
        return count
    }

    private fun addAddComputerCard() {
        for (i in 0 until pcGridAdapter.rawCount) {
            if (PcGridAdapter.isAddComputerCard(pcGridAdapter.getRawItem(i))) {
                return
            }
        }

        val addDetails = ComputerDetails()
        addDetails.uuid = PcGridAdapter.ADD_COMPUTER_UUID
        try {
            addDetails.name = getString(R.string.title_add_pc)
        } catch (e: Exception) {
            addDetails.name = "添加电脑"
        }
        addDetails.state = ComputerDetails.State.UNKNOWN

        pcGridAdapter.addComputer(ComputerObject(addDetails))
        pcGridAdapter.notifyDataSetChanged()

        noPcFoundLayout?.visibility = View.INVISIBLE
    }

    // Toggle Unpaired Button

    private fun toggleUnpairedDevices(button: ImageButton) {
        val newState = !pcGridAdapter.isShowUnpairedDevices()
        pcGridAdapter.setShowUnpairedDevices(newState)
        updateToggleUnpairedButtonIcon(button)
        showToast(if (newState) getString(R.string.unpaired_devices_shown) else getString(R.string.unpaired_devices_hidden))
    }

    private fun updateToggleUnpairedButtonIcon(button: ImageButton?) {
        if (button == null) return
        button.setImageResource(if (pcGridAdapter.isShowUnpairedDevices())
            R.drawable.ic_visibility
        else
            R.drawable.ic_visibility_off)
    }

    // Scene Configuration Methods

    private fun initSceneButtons() {
        try {
            val sceneButtonIds = intArrayOf(R.id.scene1Btn, R.id.scene2Btn, R.id.scene3Btn, R.id.scene4Btn, R.id.scene5Btn)

            for (i in sceneButtonIds.indices) {
                val sceneNumber = i + 1
                val btn = findViewById<ImageButton>(sceneButtonIds[i])

                if (btn == null) {
                    LimeLog.warning("Scene button $sceneNumber not found!")
                    continue
                }

                btn.setOnClickListener { applySceneConfiguration(sceneNumber) }
                btn.setOnLongClickListener {
                    showSaveConfirmationDialog(sceneNumber)
                    true
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Scene init failed: $e")
        }
    }

    @SuppressLint("DefaultLocale")
    private fun applySceneConfiguration(sceneNumber: Int) {
        try {
            val prefs = getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE)
            val configJson = prefs.getString(SCENE_KEY_PREFIX + sceneNumber, null)

            if (configJson == null) {
                showToast(getString(R.string.scene_not_configured, sceneNumber))
                return
            }

            val config = JSONObject(configJson)
            val configPrefs = PreferenceConfiguration.readPreferences(this).copy()

            configPrefs.width = config.optInt("width", 1920)
            configPrefs.height = config.optInt("height", 1080)
            configPrefs.fps = config.optInt("fps", 60)
            configPrefs.bitrate = config.optInt("bitrate", 10000)
            configPrefs.videoFormat = PreferenceConfiguration.FormatOption.valueOf(config.optString("videoFormat", "auto"))
            configPrefs.enableHdr = config.optBoolean("enableHdr", false)
            configPrefs.enablePerfOverlay = config.optBoolean("enablePerfOverlay", false)

            if (!configPrefs.writePreferences(this)) {
                showToast(getString(R.string.config_save_failed))
                return
            }

            pcGridAdapter.updateLayoutWithPreferences(this, configPrefs)
            showToast(getString(R.string.scene_config_applied, sceneNumber, configPrefs.width, configPrefs.height,
                    configPrefs.fps, configPrefs.bitrate / 1000.0, configPrefs.videoFormat.toString(),
                    if (configPrefs.enableHdr) "On" else "Off"))

        } catch (e: Exception) {
            LimeLog.warning("Scene apply failed: $e")
            showToast(getString(R.string.config_apply_failed))
        }
    }

    private fun showSaveConfirmationDialog(sceneNumber: Int) {
        AlertDialog.Builder(this, R.style.AppDialogStyle)
                .setTitle(getString(R.string.save_to_scene, sceneNumber))
                .setMessage(getString(R.string.overwrite_current_config))
                .setPositiveButton(R.string.dialog_button_save) { _, _ -> saveCurrentConfiguration(sceneNumber) }
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show()
    }

    private fun saveCurrentConfiguration(sceneNumber: Int) {
        try {
            val prefs = PreferenceConfiguration.readPreferences(this)
            val config = JSONObject()
            config.put("width", prefs.width)
            config.put("height", prefs.height)
            config.put("fps", prefs.fps)
            config.put("bitrate", prefs.bitrate)
            config.put("videoFormat", prefs.videoFormat.toString())
            config.put("enableHdr", prefs.enableHdr)
            config.put("enablePerfOverlay", prefs.enablePerfOverlay)

            getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(SCENE_KEY_PREFIX + sceneNumber, config.toString())
                    .apply()

            showToast(getString(R.string.scene_saved_successfully, sceneNumber))
        } catch (e: JSONException) {
            showToast(getString(R.string.config_save_failed))
        }
    }

    // PC Actions

    private fun doPair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.pair_pc_offline))
            return
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        showToast(getString(R.string.pairing))
        uiScope.launch {
            var message: String? = null
            var success = false

            try {
                stopComputerUpdates(true)

                val result = withContext(Dispatchers.IO) {
                    val httpConn = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort,
                            (managerBinder?.getUniqueId() ?: ""),
                            clientName,
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(this@PcView)
                    )

                    if (httpConn.getPairState() == PairState.PAIRED) {
                        return@withContext Triple<String?, Boolean, Pair<String, String>?>(null, true, null)
                    }

                    val pinStr = PairingManager.generatePinString()
                    withContext(Dispatchers.Main) {
                        Dialog.displayDialog(this@PcView,
                                getString(R.string.pair_pairing_title),
                                getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" + getString(R.string.pair_pairing_help),
                                false)
                    }

                    val pm = httpConn.pairingManager
                    val pairResult = pm.pair(httpConn.getServerInfo(true), pinStr)

                    when (pairResult.state) {
                        PairState.PIN_WRONG ->
                            Triple<String?, Boolean, Pair<String, String>?>(getString(R.string.pair_incorrect_pin), false, null)
                        PairState.FAILED ->
                            Triple<String?, Boolean, Pair<String, String>?>(
                                if (computer.runningGameId != 0) getString(R.string.pair_pc_ingame) else getString(R.string.pair_fail),
                                false, null
                            )
                        PairState.ALREADY_IN_PROGRESS ->
                            Triple<String?, Boolean, Pair<String, String>?>(getString(R.string.pair_already_in_progress), false, null)
                        PairState.PAIRED -> {
                            managerBinder?.getComputer(computer.uuid!!)!!.serverCert = pm.pairedCert
                            Triple<String?, Boolean, Pair<String, String>?>(null, true, computer.uuid!! to pairResult.pairName)
                        }
                        else -> Triple<String?, Boolean, Pair<String, String>?>(null, false, null)
                    }
                }

                message = result.first
                success = result.second
                result.third?.let { (uuid, pairName) ->
                    getSharedPreferences("pair_name_map", MODE_PRIVATE)
                            .edit()
                            .putString(uuid, pairName)
                            .apply()
                    managerBinder?.invalidateStateForComputer(uuid)
                }
            } catch (e: UnknownHostException) {
                message = getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = getString(R.string.error_404)
            } catch (e: InterruptedException) {
                message = getString(R.string.pair_fail)
            } catch (e: XmlPullParserException) {
                message = e.message
            } catch (e: IOException) {
                message = e.message
            } finally {
                Dialog.closeDialogs()
            }

            if (message != null) {
                showToast(message)
            }
            if (success) {
                doAppList(computer, true, false)
            } else {
                startComputerUpdates()
            }
        }
    }

    private fun showAddComputerDialog() {
        val items = arrayOf(
            getString(R.string.addpc_manual),
            getString(R.string.addpc_qr_scan)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_add_pc_choose))
            .setItems(items) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, AddComputerManually::class.java))
                } else {
                    startQrScan()
                }
            }
            .show()
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt(getString(R.string.qr_scan_prompt))
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    private fun handleQrPairResult(url: String) {
        val uri = Uri.parse(url)
        if ("moonlight" != uri.scheme || "pair" != uri.host) {
            showToast(getString(R.string.qr_invalid_code))
            return
        }

        val host = uri.getQueryParameter("host")
        val portStr = uri.getQueryParameter("port")
        val pin = uri.getQueryParameter("pin")

        if (host == null || pin == null) {
            showToast(getString(R.string.qr_invalid_code))
            return
        }

        var port = NvHTTP.DEFAULT_HTTP_PORT
        if (portStr != null) {
            try { port = portStr.toInt() } catch (ignored: NumberFormatException) {}
        }

        showToast(getString(R.string.qr_pairing))
        val finalPort = port
        uiScope.launch {
            var message: String? = null
            var success = false
            var pairedComputer: ComputerDetails? = null

            try {
                stopComputerUpdates(true)

                val result = withContext(Dispatchers.IO) {
                    // Add the computer first
                    val addDetails = ComputerDetails()
                    addDetails.manualAddress = ComputerDetails.AddressTuple(host, finalPort)
                    val added = managerBinder?.addComputerBlocking(addDetails) == true
                    if (!added) {
                        return@withContext QrPairResult(getString(R.string.addpc_fail), false, null, null)
                    }

                    // addComputerBlocking fills addDetails in-place (uuid, httpsPort, etc.)
                    var computer = managerBinder?.getComputer(addDetails.uuid!!)
                    if (computer == null) {
                        computer = addDetails
                    }

                    val httpConn = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        (managerBinder?.getUniqueId() ?: ""),
                        clientName,
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(this@PcView)
                    )

                    if (httpConn.getPairState() == PairState.PAIRED) {
                        return@withContext QrPairResult(null, true, computer, null)
                    }

                    val pm = httpConn.pairingManager
                    val pairResult = pm.pair(httpConn.getServerInfo(true), pin)
                    when (pairResult.state) {
                        PairState.PIN_WRONG ->
                            QrPairResult(getString(R.string.pair_incorrect_pin), false, null, null)
                        PairState.FAILED ->
                            QrPairResult(getString(R.string.pair_fail), false, null, null)
                        PairState.ALREADY_IN_PROGRESS ->
                            QrPairResult(getString(R.string.pair_already_in_progress), false, null, null)
                        PairState.PAIRED -> {
                            managerBinder?.getComputer(computer.uuid!!)!!.serverCert = pm.pairedCert
                            QrPairResult(null, true, computer, computer.uuid!! to pairResult.pairName)
                        }
                        else -> QrPairResult(null, false, null, null)
                    }
                }

                message = result.message
                success = result.success
                pairedComputer = result.computer
                result.saveName?.let { (uuid, pairName) ->
                    getSharedPreferences("pair_name_map", MODE_PRIVATE)
                        .edit()
                        .putString(uuid, pairName)
                        .apply()
                    managerBinder?.invalidateStateForComputer(uuid)
                }
            } catch (e: Exception) {
                message = e.message
            }

            if (message != null) {
                showToast(message)
            }
            if (success) {
                showToast(getString(R.string.qr_pair_success))
                if (pairedComputer != null) {
                    doAppList(pairedComputer, true, false)
                } else {
                    startComputerUpdates()
                }
            } else {
                startComputerUpdates()
            }
        }
    }

    private data class QrPairResult(
        val message: String?,
        val success: Boolean,
        val computer: ComputerDetails?,
        val saveName: Pair<String, String>?
    )

    private fun findComputerByAddress(host: String): ComputerDetails? {
        if (managerBinder == null) return null
        for (i in 0 until pcGridAdapter.count) {
            val obj = pcGridAdapter.getItem(i) as ComputerObject
            if (PcGridAdapter.isAddComputerCard(obj)) continue
            val d = obj.details
            if (d.manualAddress != null && host == d.manualAddress?.address) return d
            if (d.localAddress != null && host == d.localAddress?.address) return d
            if (d.remoteAddress != null && host == d.remoteAddress?.address) return d
        }
        return null
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            showToast(getString(R.string.wol_pc_online))
            return
        }
        if (computer.macAddress == null) {
            showToast(getString(R.string.wol_no_mac))
            return
        }

        uiScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    WakeOnLanSender.sendWolPacket(computer)
                    getString(R.string.wol_waking_msg)
                } catch (e: IOException) {
                    getString(R.string.wol_fail)
                }
            }
            showToast(message)
        }
    }

    private fun doUnpair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.error_pc_offline))
            return
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        showToast(getString(R.string.unpairing))
        val binder = managerBinder!!
        uiScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val httpConn = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort,
                            binder.getUniqueId(),
                            clientName,
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(this@PcView)
                    )

                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair()
                        if (httpConn.getPairState() == PairState.NOT_PAIRED)
                            getString(R.string.unpair_success)
                        else
                            getString(R.string.unpair_fail)
                    } else {
                        getString(R.string.unpair_error)
                    }
                } catch (e: UnknownHostException) {
                    getString(R.string.error_unknown_host)
                } catch (e: FileNotFoundException) {
                    getString(R.string.error_404)
                } catch (e: XmlPullParserException) {
                    e.message ?: getString(R.string.unpair_fail)
                } catch (e: IOException) {
                    e.message ?: getString(R.string.unpair_fail)
                } catch (e: InterruptedException) {
                    getString(R.string.error_interrupted)
                }
            }
            showToast(message)
        }
    }

    private fun doAppList(computer: ComputerDetails, newlyPaired: Boolean, showHiddenGames: Boolean) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            showToast(getString(R.string.error_pc_offline))
            return
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        val i = Intent(this, AppView::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)

        if (computer.activeAddress != null) {
            i.putExtra(AppView.SELECTED_ADDRESS_EXTRA, computer.activeAddress?.address)
            i.putExtra(AppView.SELECTED_PORT_EXTRA, computer.activeAddress?.port)
        }

        startActivity(i)
    }

    private fun doSecondaryScreenStream(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            showToast(getString(R.string.error_pc_offline))
            return
        }
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        computer.useVdd = true
        quickStartStreamWithScreenMode(computer, null, true, 2)
    }

    // Quick Start Stream Methods

    private fun handleAvatarClick(computer: ComputerDetails, itemView: View) {
        quickStartStream(computer, itemView, false)
    }

    private fun quickStartStream(computer: ComputerDetails, itemView: View?, isSecondaryScreen: Boolean) {
        if (computer.state != ComputerDetails.State.ONLINE || computer.pairState != PairState.PAIRED) {
            if (itemView != null) {
                openContextMenu(itemView)
            }
            return
        }

        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        uiScope.launch {
            val targetApp = withContext(Dispatchers.IO) {
                if (computer.runningGameId != 0)
                    getNvAppById(computer.runningGameId, computer.uuid!!)
                else
                    getFirstAppFromCache(computer.uuid!!)
            }

            if (targetApp == null) {
                fallbackToAppList(computer)
                return@launch
            }

            val targetComputer = prepareComputerWithAddress(computer)
            if (targetComputer == null) {
                showToast(getString(R.string.error_pc_offline))
                return@launch
            }

            if (targetComputer.hasMultipleLanAddresses()) {
                showAddressSelectionDialog(targetComputer)
                return@launch
            }

            val appToStart = targetApp
            ServerHelper.doStart(this@PcView, appToStart, targetComputer, managerBinder!!)
        }
    }

    private fun quickStartStreamWithScreenMode(computer: ComputerDetails, itemView: View?, isSecondaryScreen: Boolean, screenMode: Int) {
        if (computer.state != ComputerDetails.State.ONLINE || computer.pairState != PairState.PAIRED) {
            if (itemView != null) {
                openContextMenu(itemView)
            }
            return
        }

        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        uiScope.launch {
            val targetApp = withContext(Dispatchers.IO) {
                if (computer.runningGameId != 0)
                    getNvAppById(computer.runningGameId, computer.uuid!!)
                else
                    getFirstAppFromCache(computer.uuid!!)
            }

            if (targetApp == null) {
                fallbackToAppList(computer)
                return@launch
            }

            val targetComputer = prepareComputerWithAddress(computer)
            if (targetComputer == null) {
                showToast(getString(R.string.error_pc_offline))
                return@launch
            }

            if (targetComputer.hasMultipleLanAddresses()) {
                showAddressSelectionDialog(targetComputer)
                return@launch
            }

            val intent = ServerHelper.createStartIntent(this@PcView, targetApp, targetComputer, managerBinder!!, null)
            if (screenMode != -1) {
                if (targetComputer.useVdd) {
                    intent.putExtra(Game.EXTRA_VDD_SCREEN_COMBINATION_MODE, screenMode)
                } else {
                    intent.putExtra(Game.EXTRA_SCREEN_COMBINATION_MODE, screenMode)
                }
            }
            startActivity(intent)
        }
    }

    private fun prepareComputerWithAddress(computer: ComputerDetails): ComputerDetails? {
        val temp = ComputerDetails(computer)
        if (temp.activeAddress == null) {
            val best = temp.selectBestAddress() ?: return null
            temp.activeAddress = best
        }
        return temp
    }

    private fun fallbackToAppList(computer: ComputerDetails) {
        runOnUiThread {
            val target = prepareComputerWithAddress(computer)
            doAppList(target ?: computer, false, false)
        }
    }

    // App Cache Methods

    private fun getAppListFromCache(uuid: String): List<NvApp>? {
        try {
            val rawAppList = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(cacheDir, "applist", uuid))
            return if (rawAppList.isEmpty()) null else NvHTTP.getAppListByReader(StringReader(rawAppList))
        } catch (e: IOException) {
            LimeLog.warning("Failed to read app list from cache: " + e.message)
            return null
        } catch (e: XmlPullParserException) {
            LimeLog.warning("Failed to read app list from cache: " + e.message)
            return null
        }
    }

    private fun getFirstAppFromCache(uuid: String): NvApp? {
        val appList = getAppListFromCache(uuid)
        return if (appList != null && appList.isNotEmpty()) appList[0] else null
    }

    private fun getNvAppById(appId: Int, uuid: String): NvApp? {
        val appList = getAppListFromCache(uuid)
        if (appList != null) {
            for (app in appList) {
                if (app.appId == appId) {
                    AppCacheManager(this).saveAppInfo(uuid, app)
                    return app
                }
            }
        }
        return AppCacheManager(this).getAppInfo(uuid, appId)
    }

    private fun restoreLastSession() {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        var target: ComputerDetails? = null
        for (i in 0 until pcGridAdapter.rawCount) {
            val computer = pcGridAdapter.getRawItem(i)
            if (computer.details.state == ComputerDetails.State.ONLINE
                    && computer.details.pairState == PairState.PAIRED
                    && computer.details.runningGameId != 0) {
                target = computer.details
                break
            }
        }

        if (target == null) {
            showToast(getString(R.string.no_online_computer_with_running_game))
            return
        }

        var app = getNvAppById(target.runningGameId, target.uuid!!)
        if (app == null) {
            app = NvApp("app", target.runningGameId, false)
        }

        showToast(getString(R.string.restoring_session, target.name))
        ServerHelper.doStart(this, app, target, managerBinder!!)
    }

    private fun showAddressSelectionDialog(computer: ComputerDetails) {
        val dialog = AddressSelectionDialog(this, computer) { address ->
            val temp = ComputerDetails(computer)
            temp.activeAddress = address
            doAppList(temp, false, false)
        }
        dialog.show()
    }

    // Context Menu

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        stopComputerUpdates(false)
        super.onCreateContextMenu(menu, v, menuInfo)

        val position = getContextMenuPosition(menuInfo, v)
        if (position < 0) return

        val computer = pcGridAdapter.getItem(position) as ComputerObject
        if (PcGridAdapter.isAddComputerCard(computer)) return

        setupContextMenuHeader(menu, computer)
        addContextMenuItems(menu, computer)
    }

    private fun getContextMenuPosition(menuInfo: ContextMenuInfo?, v: View?): Int {
        if (menuInfo is AdapterContextMenuInfo) {
            return menuInfo.position
        }
        if (v != null && v.tag is Int) {
            return v.tag as Int
        }
        return -1
    }

    private fun setupContextMenuHeader(menu: ContextMenu, computer: ComputerObject) {
        menu.clearHeader()
        val status: String
        when (computer.details.state) {
            ComputerDetails.State.ONLINE ->
                status = getString(R.string.pcview_menu_header_online)
            ComputerDetails.State.OFFLINE -> {
                menu.setHeaderIcon(R.drawable.ic_pc_offline)
                status = getString(R.string.pcview_menu_header_offline)
            }
            else ->
                status = getString(R.string.pcview_menu_header_unknown)
        }
        menu.setHeaderTitle(computer.details.name + " - " + status)
    }

    private fun addContextMenuItems(menu: ContextMenu, computer: ComputerObject) {
        val details = computer.details

        if (details.state == ComputerDetails.State.OFFLINE || details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, R.string.pcview_menu_send_wol)
        } else if (details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, R.string.pcview_menu_pair_pc)
            if (details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, R.string.pcview_menu_eol)
            }
        } else {
            if (details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, R.string.applist_menu_resume)
                menu.add(Menu.NONE, QUIT_ID, 2, R.string.applist_menu_quit)
            }
            if (details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, R.string.pcview_menu_eol)
            }
            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, R.string.pcview_menu_app_list)
            menu.add(Menu.NONE, SECONDARY_SCREEN_ID, 5, R.string.pcview_menu_secondary_screen)
            menu.add(Menu.NONE, SLEEP_ID, 8, R.string.send_sleep_command)
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, R.string.pcview_menu_test_network)
        menu.add(Menu.NONE, IPERF3_TEST_ID, 6, R.string.network_bandwidth_test)
        menu.add(Menu.NONE, DELETE_ID, 6, R.string.pcview_menu_delete_pc)
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7, R.string.pcview_menu_details)

        // 添加IPv6开关选项，根据当前状态显示不同操作
        if (details.ipv6Disabled) {
            menu.add(Menu.NONE, DISABLE_IPV6_ID, 8, R.string.pcview_menu_enable_ipv6)
        } else {
            menu.add(Menu.NONE, DISABLE_IPV6_ID, 8, R.string.pcview_menu_disable_ipv6)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        startComputerUpdates()
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val position = getContextMenuPosition(item.menuInfo, null)
        if (position < 0) return super.onContextItemSelected(item)

        val computer = pcGridAdapter.getItem(position) as ComputerObject
        if (PcGridAdapter.isAddComputerCard(computer)) return super.onContextItemSelected(item)

        return handleContextMenuAction(item.itemId, computer)
    }

    private fun handleContextMenuAction(itemId: Int, computer: ComputerObject): Boolean {
        val details = computer.details

        return when (itemId) {
            PAIR_ID -> {
                doPair(details)
                true
            }
            UNPAIR_ID -> {
                doUnpair(details)
                true
            }
            WOL_ID -> {
                doWakeOnLan(details)
                true
            }
            DELETE_ID -> {
                handleDeletePc(details)
                true
            }
            FULL_APP_LIST_ID -> {
                doAppList(details, false, true)
                true
            }
            RESUME_ID -> {
                handleResume(details)
                true
            }
            QUIT_ID -> {
                handleQuit(details)
                true
            }
            SLEEP_ID -> {
                handleSleep(details)
                true
            }
            VIEW_DETAILS_ID -> {
                Dialog.displayDetailsDialog(this, getString(R.string.title_details), details.toString(), false)
                true
            }
            TEST_NETWORK_ID -> {
                ServerHelper.doNetworkTest(this)
                true
            }
            IPERF3_TEST_ID -> {
                handleIperf3Test(details)
                true
            }
            SECONDARY_SCREEN_ID -> {
                handleSecondaryScreen(details)
                true
            }
            GAMESTREAM_EOL_ID -> {
                HelpLauncher.launchGameStreamEolFaq(this)
                true
            }
            DISABLE_IPV6_ID -> {
                handleToggleIpv6Disabled(details)
                true
            }
            else -> false
        }
    }

    private fun handleDeletePc(details: ComputerDetails) {
        if (ActivityManager.isUserAMonkey()) {
            LimeLog.info("Ignoring delete PC request from monkey")
            return
        }
        UiHelper.displayDeletePcConfirmationDialog(this, details, {
            if (managerBinder == null) {
                showToast(getString(R.string.error_manager_not_running))
                return@displayDeletePcConfirmationDialog
            }
            removeComputer(details)
        }, null)
    }

    private fun handleResume(details: ComputerDetails) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }
        var app = getNvAppById(details.runningGameId, details.uuid!!)
        if (app == null) {
            app = NvApp("app", details.runningGameId, false)
        }
        ServerHelper.doStart(this, app, details, managerBinder!!)
    }

    private fun handleQuit(details: ComputerDetails) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }
        UiHelper.displayQuitConfirmationDialog(this,
                { ServerHelper.doQuit(this, details, NvApp("app", 0, false), managerBinder!!, null) },
                null)
    }

    private fun handleSleep(details: ComputerDetails) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }
        ServerHelper.pcSleep(this, details, managerBinder!!, null)
    }

    private fun handleIperf3Test(details: ComputerDetails) {
        try {
            val ip = ServerHelper.getCurrentAddressFromComputer(details).address
            Iperf3Tester(this, ip).show()
        } catch (e: IOException) {
            showToast(getString(R.string.unable_to_get_pc_address, e.message))
        }
    }

    private fun handleSecondaryScreen(details: ComputerDetails) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }
        doSecondaryScreenStream(details)
    }

    private fun handleToggleIpv6Disabled(details: ComputerDetails) {
        if (managerBinder == null) {
            showToast(getString(R.string.error_manager_not_running))
            return
        }

        // 切换IPv6禁用状态
        details.ipv6Disabled = !details.ipv6Disabled

        // 如果禁用了IPv6，清空所有IPv6相关地址
        if (details.ipv6Disabled) {
            details.ipv6Address = null

            // 如果activeAddress是IPv6，清空它
            if (ComputerDetails.isIpv6Address(details.activeAddress)) {
                details.activeAddress = null
            }

            // 从availableAddresses中移除所有IPv6地址
            details.availableAddresses?.removeIf { ComputerDetails.isIpv6Address(it) }
        }

        // 更新数据库
        managerBinder?.updateComputer(details)

        // 显示Toast提示用户当前状态
        if (details.ipv6Disabled) {
            showToast(getString(R.string.pcview_ipv6_disabled))
        } else {
            showToast(getString(R.string.pcview_ipv6_enabled))
        }
        // 刷新列表
        startComputerUpdates()
    }

    // Adapter Fragment Callbacks

    override fun getAdapterFragmentLayoutId(): Int {
        return R.layout.pc_grid_view
    }

    override fun receiveAbsListView(gridView: View) {
        receiveAdapterView(gridView)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun receiveAdapterView(view: View) {
        if (view !is AbsListView) return

        val listView = view
        pcListView = listView
        listView.setSelector(android.R.color.transparent)
        listView.setAdapter(pcGridAdapter)

        setupListAnimation(listView)
        handleFirstLoadAnimation(listView)
        setupListItemClick(listView)
        setupGridColumnWidth(view)
        setupEmptyAreaLongPress(listView)

        UiHelper.applyStatusBarPadding(listView)
        registerForContextMenu(listView)
    }

    private fun setupListAnimation(listView: AbsListView) {
        val controller = LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.pc_grid_item_sort), 0.12f)
        controller.order = LayoutAnimationController.ORDER_NORMAL
        listView.layoutAnimation = controller
    }

    private fun handleFirstLoadAnimation(listView: AbsListView) {
        if (!isFirstLoad) return

        listView.alpha = 0f
        listView.postDelayed({
            if (isFirstLoad && pcListView != null && pcListView?.alpha == 0f) {
                pcGridAdapter.notifyDataSetChanged()
                pcListView?.scheduleLayoutAnimation()
                pcListView?.animate()?.alpha(1f)?.setDuration(200)?.start()
                isFirstLoad = false
            }
        }, 250)
    }

    private fun setupListItemClick(listView: AbsListView) {
        listView.setOnItemClickListener { _, view, pos, _ ->
            val computer = pcGridAdapter.getItem(pos) as ComputerObject

            if (PcGridAdapter.isAddComputerCard(computer)) {
                showAddComputerDialog()
                return@setOnItemClickListener
            }

            if (computer.details.state == ComputerDetails.State.UNKNOWN
                    || computer.details.state == ComputerDetails.State.OFFLINE) {
                openContextMenu(view)
            } else if (computer.details.pairState != PairState.PAIRED) {
                doPair(computer.details)
            } else if (computer.details.hasMultipleLanAddresses()) {
                showAddressSelectionDialog(computer.details)
            } else {
                val temp = prepareComputerWithAddress(computer.details)
                if (temp != null) {
                    doAppList(temp, false, false)
                } else {
                    showToast(getString(R.string.error_pc_offline))
                }
            }
        }
    }

    private fun setupGridColumnWidth(view: View) {
        if (view is GridView) {
            calculateDynamicColumnWidth(view)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEmptyAreaLongPress(listView: AbsListView) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (listView.pointToPosition(e.x.toInt(), e.y.toInt()) == android.widget.AdapterView.INVALID_POSITION) {
                    saveImageWithPermissionCheck()
                }
            }
        })

        listView.setOnTouchListener { _, event ->
            if (listView.pointToPosition(event.x.toInt(), event.y.toInt()) == android.widget.AdapterView.INVALID_POSITION) {
                detector.onTouchEvent(event)
            }
            false
        }
    }

    private fun calculateDynamicColumnWidth(gridView: GridView) {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val availableWidth = screenWidth - gridView.paddingStart - gridView.paddingEnd
        val spacingPx = (15f * density).toInt()
        val minColumnPx = (180f * density).toInt()

        val numColumns = Math.max(1, (availableWidth + spacingPx) / (minColumnPx + spacingPx))
        val columnWidth = (availableWidth - (numColumns - 1) * spacingPx) / numColumns

        gridView.columnWidth = columnWidth
    }

    // Dialogs

    private fun showEasyTierControlDialog() {
        easyTierController?.showControlDialog()
    }

    private fun showAboutDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)

        val versionText = dialogView.findViewById<TextView>(R.id.text_version)
        versionText.text = getVersionInfo()

        val appNameText = dialogView.findViewById<TextView>(R.id.text_app_name)
        appNameText.text = getAppName()

        val descriptionText = dialogView.findViewById<TextView>(R.id.text_description)
        descriptionText.setText(R.string.about_dialog_description)

        // PcView 继承自 Activity 而非 AppCompatActivity，在 Android 6 等设备上使用
        // R.style.AppDialogStyle（父主题为 Theme.AppCompat.Light.Dialog.Alert）会触发
        // "You need to use a Theme.AppCompat theme" 类崩溃，故此处使用系统 Material 对话框主题。
        val dialogTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            android.R.style.Theme_Material_Light_Dialog_Alert
        else
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        val dialog = AlertDialog.Builder(this, dialogTheme)
                .setView(dialogView)
                .setPositiveButton(R.string.about_dialog_github) { _, _ -> openUrl("https://github.com/qiin2333/moonlight-vplus") }
                .setNeutralButton(R.string.about_dialog_qq) { _, _ -> joinQQGroup("LlbLDIF_YolaM4HZyLx0xAXXo04ZmoBM") }
                .setNegativeButton(R.string.about_dialog_close) { d, _ -> d.dismiss() }
                .create()
        if (dialog.window != null) {
            dialog.window?.setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute)
        }
        dialog.show()
    }

    @SuppressLint("DefaultLocale")
    private fun getVersionInfo(): String {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            return String.format("Version %s (Build %d)", info.versionName, androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
        } catch (e: PackageManager.NameNotFoundException) {
            return "Version Unknown"
        }
    }

    private fun getAppName(): String {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (info.applicationInfo != null) {
                return info.applicationInfo?.loadLabel(packageManager).toString()
            }
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        return "Moonlight V+"
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (ignored: Exception) {
        }
    }

    fun joinQQGroup(key: String) {
        try {
            val intent = Intent()
            intent.data = Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key")
            startActivity(intent)
        } catch (ignored: Exception) {
        }
    }

    // VPN Permission

    override fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
        } else {
            onActivityResult(VPN_PERMISSION_REQUEST_CODE, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Handle ZXing scan result
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            if (scanResult.contents != null) {
                handleQrPairResult(scanResult.contents.trim())
            }
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE && easyTierController != null) {
            easyTierController?.handleVpnPermissionResult(resultCode)
        } else if (requestCode == UpdateManager.INSTALL_PERMISSION_REQUEST_CODE) {
            UpdateManager.onInstallPermissionResult(this)
        }
    }

    // Utility

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Inner Classes

    class ComputerObject(var details: ComputerDetails) {
        init {
            requireNotNull(details) { "details must not be null" }
        }

        override fun toString(): String {
            return details.name!!
        }
    }
}
