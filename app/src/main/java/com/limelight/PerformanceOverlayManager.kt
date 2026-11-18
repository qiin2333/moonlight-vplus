package com.limelight

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager

import com.limelight.utils.AppSwitchStyle
import com.limelight.utils.AppSwitchView
import com.limelight.binding.video.PerformanceInfo
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.perfhud.LatencyChain
import com.limelight.perfhud.PerfHudDensity
import com.limelight.perfhud.PerfHudInfoSheets
import com.limelight.perfhud.PerfHudLayout
import com.limelight.perfhud.PerfHudLayoutPolicy
import com.limelight.perfhud.PerfHudPalette
import com.limelight.perfhud.PerfHudSnapshot
import com.limelight.perfhud.PerfHudStrings
import com.limelight.perfhud.PerfHudTarget
import com.limelight.perfhud.PerfHudView
import com.limelight.perfhud.PerfHudVisibility
import com.limelight.perfhud.PerfMood
import com.limelight.preferences.PerfOverlayDisplayItemsPreference
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.CenterAlignedImageSpan
import com.limelight.ui.StreamView
import com.limelight.utils.BandwidthMeter
import com.limelight.utils.MoonPhaseUtils
import com.limelight.utils.UiHelper
import com.limelight.utils.formatBandwidthSpeed

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PerformanceOverlayManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
    private val onJitterMonitorChanged: (Boolean) -> Unit = {},
    private val onOpenSettings: () -> Unit = {}
) {

    private var performanceOverlayView: LinearLayout? = null
    private var performanceClockView: TextView? = null
    private lateinit var streamView: StreamView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val infoSheets by lazy {
        PerfHudInfoSheets(
            activity,
            snapshot = { lastHudSnapshot },
            isHudStyle = ::isHudStyle,
            anchor = { performanceOverlayView },
            showFallback = ::showManagerTargetInfo,
            onOpenSettings = onOpenSettings,
            moodText = { buildHudStrings().mood(it) },
            fpsHeader = ::createJitterMonitorSwitchRow
        )
    }
    private var delayedShowPending = false
    private val delayedShowRunnable = Runnable {
        delayedShowPending = false
        val overlay = performanceOverlayView ?: return@Runnable
        if (activity.isFinishing || (activity as? Game)?.connected != true ||
            overlay.windowToken == null
        ) return@Runnable

        prefConfig.enablePerfOverlay = true
        configurePerformanceOverlay()
        applyOverlayState()
        prefConfig.writePreferences(activity)
    }
    private val clockUpdateRunnable = object : Runnable {
        override fun run() {
            val overlay = performanceOverlayView
            if (overlay == null || requestedPerformanceOverlayVisibility != View.VISIBLE ||
                overlay.visibility != View.VISIBLE || performanceClockView?.visibility != View.VISIBLE
            ) {
                return
            }
            updateClockText()
            uiHandler.postDelayed(this, clockUpdateDelayMs())
        }
    }

    private val overlayPrefs: SharedPreferences by lazy {
        activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE)
    }

    private var requestedPerformanceOverlayVisibility = View.GONE
    private var hasShownPerfOverlay = false

    // 性能覆盖层拖动相关
    private var isDraggingPerfOverlay = false
    private var perfOverlayStartX = 0f
    private var perfOverlayStartY = 0f
    private var perfOverlayDeltaX = 0f
    private var perfOverlayDeltaY = 0f
    private var perfOverlayOriginalLayout: FrameLayout.LayoutParams? = null
    private var perfOverlayDragWidth = 0
    private var perfOverlayDragHeight = 0
    private var perfOverlayMoved = false
    private var dragGuideX: SnapGuide? = null
    private var dragGuideY: SnapGuide? = null
    private var dragRelativeX: Float? = null
    private var dragRelativeY: Float? = null
    private var dragMaxLeft = 0
    private var dragMaxTop = 0
    private var snapGuidesView: PerfOverlaySnapGuides? = null

    private var classicAnchorOnly = false
    private var overlayTemporarilyHidden = false
    private var destroyed = false

    private enum class SnapGuide { START, CENTER, END }
    private data class GuidedPosition(val margin: Int, val guide: SnapGuide?)

    private val bandwidthMeter = BandwidthMeter()

    // 月相缓存
    private var currentMoonPhaseIcon = "🌙"
    private var currentIsFullMoon = false
    private var lastCalculatedDay = -1

    // 当前性能信息缓存
    private var currentPerformanceInfo: PerformanceInfo? = null

    // 实际设备刷新率（从 Game 传递）
    private var actualDisplayRefreshRate = 0.0f

    // 电量更新相关
    private var lastBatteryUpdateTime = 0L
    private var hasDisplayableBattery = false

    // 串流电量统计
    private var streamStartBatteryLevel = -1
    private var streamStartTime = -1L

    // 新版 HUD
    private var hudView: PerfHudView? = null
    private var classicOverlayPadding: IntArray? = null
    private var currentHudLayout: PerfHudLayout? = null
    private var currentDockTop = -1
    private var overlayStyleGeneration = 0
    private var hudBatteryLevel = -1
    private var hudCharging = false
    private var overlayTouchActive = false
    private var hudTouchX = 0f
    private var hudTouchY = 0f
    private val gestures = PerfOverlayGestureController(
        postDelayed = { task, delay -> uiHandler.postDelayed(task, delay) },
        removeCallbacks = { task -> uiHandler.removeCallbacks(task) },
        onSingleTap = { x, y ->
            if (isHudStyle()) {
                cycleHudDensity()
            } else {
                cancelOverlayInteraction()
                showClickedItemInfo(x, y)
            }
        },
        onDoubleTap = ::toggleLayoutOrientation,
        onLongPress = ::onHudLongPress,
        onPressHighlight = {
            hudView?.let { it.setPressedAt(hudTouchX - it.left, hudTouchY - it.top) }
        },
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    )

    val isPerfOverlayDocked: Boolean get() = currentHudLayout == PerfHudLayout.DOCK
    val perfOverlayHealthVisible: Boolean get() = overlayPrefs.getBoolean(KEY_HUD_HEALTH, true)
    val perfOverlaySingleTapEnabled: Boolean get() = overlayPrefs.getBoolean(KEY_HUD_SINGLE_TAP, false)
    val perfOverlayDoubleTapEnabled: Boolean get() = overlayPrefs.getBoolean(KEY_HUD_DOUBLE_TAP, false)
    val perfOverlayLongPressEnabled: Boolean get() = overlayPrefs.getBoolean(KEY_HUD_LONG_PRESS, true)

    fun setPerfOverlayHealthVisible(enabled: Boolean) {
        overlayPrefs.edit { putBoolean(KEY_HUD_HEALTH, enabled) }
        refreshPerformanceOverlayConfig()
    }

    fun setPerfOverlaySingleTapEnabled(enabled: Boolean) = setGestureEnabled(KEY_HUD_SINGLE_TAP, enabled)
    fun setPerfOverlayDoubleTapEnabled(enabled: Boolean) = setGestureEnabled(KEY_HUD_DOUBLE_TAP, enabled)
    fun setPerfOverlayLongPressEnabled(enabled: Boolean) = setGestureEnabled(KEY_HUD_LONG_PRESS, enabled)

    private fun setGestureEnabled(key: String, enabled: Boolean) {
        cancelOverlayInteraction()
        overlayPrefs.edit { putBoolean(key, enabled) }
    }

    fun setPerfOverlayDisplayItems(items: Set<String>) {
        PerfOverlayDisplayItemsPreference.setDisplayItems(activity, items.toSet())
        refreshPerformanceOverlayConfig()
    }
    private val hudAnchorListener = View.OnLayoutChangeListener     { v, l, t, r, b, oL, oT, oR, oB ->
            onHudOverlayResized(v, l, t, r, b, oL, oT, oR, oB)
    }
    private val overlayParentListener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if ((r - l != oldR - oldL || b - t != oldB - oldT) && hasRelativePosition()) {
            performanceOverlayView?.let { restoreFloatingPosition(it) }
        }
    }

    /**
     * 性能项目枚举 - 统一管理所有性能指标
     */
    private enum class PerformanceItem(val viewId: Int, val preferenceKey: String) {
        RESOLUTION(R.id.perfRes, "resolution"),
        DECODER(R.id.perfDecoder, "decoder"),
        RENDER_FPS(R.id.perfRenderFps, "render_fps"),
        PACKET_LOSS(R.id.perfPacketLoss, "packet_loss"),
        NETWORK_LATENCY(R.id.perfNetworkLatency, "network_latency"),
        DECODE_LATENCY(R.id.perfDecodeLatency, "decode_latency"),
        HOST_LATENCY(R.id.perfHostLatency, "host_latency"),
        BATTERY(R.id.perfBattery, "battery"),
        ONE_PERCENT_LOW(R.id.perfOnePercentLow, "one_percent_low"),
        CLOCK(R.id.perfClock, "clock")
    }

    /**
     * 性能项目信息类 - 包含View引用和相关信息
     */
    private class PerformanceItemInfo(
        val item: PerformanceItem,
        val view: TextView?,
        val infoMethod: Runnable
    ) {
        val isVisible: Boolean
            get() = view != null && view.visibility == View.VISIBLE
    }

    // 性能项目信息数组
    private lateinit var performanceItems: Array<PerformanceItemInfo>
    // 枚举 → 项信息 快查索引，避免频繁 O(n) 遍历
    private lateinit var performanceItemMap: EnumMap<PerformanceItem, PerformanceItemInfo>

    // 解码器类型信息类
    private class DecoderTypeInfo(val fullName: String, val shortName: String)

    private data class ClockFormat(val pattern: String, val includesSeconds: Boolean)

    /**
     * 初始化性能覆盖层
     */
    fun initialize() {
        performanceOverlayView = activity.findViewById(R.id.performanceOverlay)
        performanceClockView = activity.findViewById(R.id.perfClock)
        streamView = activity.findViewById(R.id.surfaceView)
        hudView = activity.findViewById<PerfHudView>(R.id.perfHud)?.also { it.setStrings(buildHudStrings()) }
        performanceOverlayView?.addOnLayoutChangeListener(hudAnchorListener)
        (performanceOverlayView?.parent as? View)?.addOnLayoutChangeListener(overlayParentListener)

        initializePerformanceItems()
        loadLayoutOrientation()

        if (prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.VISIBLE
            performanceOverlayView?.apply {
                visibility = View.GONE
                alpha = 0.0f
            }
        }
        configurePerformanceOverlay()
        setupPerformanceOverlayDragging()
        recordStreamStart()
    }

    private fun initializePerformanceItems() {
        performanceItems = Array(PerformanceItem.entries.size) { i ->
            val item = PerformanceItem.entries[i]
            val view: TextView? = activity.findViewById(item.viewId)
            val infoMethod = getInfoMethodForItem(item)
            PerformanceItemInfo(item, view, infoMethod)
        }
        performanceItemMap = EnumMap(PerformanceItem::class.java)
        for (info in performanceItems) {
            performanceItemMap[info.item] = info
        }
    }

    private fun getInfoMethodForItem(item: PerformanceItem): Runnable {
        return when (item) {
            PerformanceItem.RESOLUTION -> Runnable { showResolutionInfo() }
            PerformanceItem.DECODER -> Runnable { showDecoderInfo() }
            PerformanceItem.RENDER_FPS -> Runnable { infoSheets.showFps() }
            PerformanceItem.PACKET_LOSS -> Runnable { infoSheets.showMetric(PerfHudTarget.LOSS) }
            PerformanceItem.NETWORK_LATENCY -> Runnable { infoSheets.showMetric(PerfHudTarget.NETWORK) }
            PerformanceItem.DECODE_LATENCY -> Runnable { infoSheets.showMetric(PerfHudTarget.DECODE) }
            PerformanceItem.HOST_LATENCY -> Runnable { infoSheets.showMetric(PerfHudTarget.HOST) }
            PerformanceItem.BATTERY -> Runnable { showBatteryInfo() }
            PerformanceItem.ONE_PERCENT_LOW -> Runnable { infoSheets.showMetric(PerfHudTarget.ONE_PERCENT_LOW) }
            PerformanceItem.CLOCK -> Runnable { infoSheets.showMoonPhase() }
        }
    }

    fun hideOverlayImmediate() {
        overlayTemporarilyHidden = true
        ++overlayStyleGeneration
        cancelDelayedShow()
        stopClockUpdates()
        infoSheets.dismiss()
        cancelOverlayInteraction()
        performanceOverlayView?.clearAnimation()
        performanceOverlayView?.visibility = View.GONE
    }

    fun applyRequestedVisibility() {
        overlayTemporarilyHidden = false
        val overlay = performanceOverlayView ?: return
        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            refreshOverlayNow()
            overlay.clearAnimation()
            overlay.alpha = 1f
            hasShownPerfOverlay = true
            setupPerformanceOverlayDragging()
        }
        overlay.visibility = requestedPerformanceOverlayVisibility
        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            startClockUpdates()
        } else {
            stopClockUpdates()
        }
    }

    fun isPerfOverlayVisible(): Boolean {
        return requestedPerformanceOverlayVisibility == View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    fun togglePerformanceOverlay() {
        val overlay = performanceOverlayView ?: return

        cancelDelayedShow()

        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            requestedPerformanceOverlayVisibility = View.GONE
            hasShownPerfOverlay = false
            ++overlayStyleGeneration
            overlay.setOnTouchListener(null)
            stopClockUpdates()
            fadeOutAndHide(overlay)
        } else {
            requestedPerformanceOverlayVisibility = View.VISIBLE
            hasShownPerfOverlay = true
            refreshOverlayNow()
            if (overlayTemporarilyHidden) return
            overlay.clearAnimation()
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1.0f
            setupPerformanceOverlayDragging()
            startClockUpdates()
        }
    }

    fun showPerformanceOverlayDelayed() {
        if (performanceOverlayView == null) return
        if (requestedPerformanceOverlayVisibility == View.VISIBLE || delayedShowPending) return

        delayedShowPending = true
        uiHandler.postDelayed(delayedShowRunnable, DELAYED_SHOW_MS)
    }

    fun cancelPendingTasks() {
        destroyed = true
        ++overlayStyleGeneration
        cancelDelayedShow()
        stopClockUpdates()
        infoSheets.dismiss()
        cancelOverlayInteraction()
        performanceOverlayView?.clearAnimation()
        performanceOverlayView?.setOnTouchListener(null)
        performanceOverlayView?.removeOnLayoutChangeListener(hudAnchorListener)
        (performanceOverlayView?.parent as? View)?.removeOnLayoutChangeListener(overlayParentListener)
    }

    fun cancelOverlayInteraction() {
        gestures.cancel()
        overlayTouchActive = false
        hudView?.clearPressed()
        val overlay = performanceOverlayView
        if (isDraggingPerfOverlay && currentHudLayout != PerfHudLayout.DOCK && overlay != null) {
            cancelOverlayDrag(overlay)
        } else {
            isDraggingPerfOverlay = false
            hideSnapGuides()
        }
    }

    private fun cancelDelayedShow() {
        if (!delayedShowPending) return
        delayedShowPending = false
        uiHandler.removeCallbacks(delayedShowRunnable)
    }

    private fun startClockUpdates() {
        uiHandler.removeCallbacks(clockUpdateRunnable)
        if (performanceClockView?.visibility != View.VISIBLE) return
        updateClockText()
        uiHandler.post(clockUpdateRunnable)
    }

    private fun stopClockUpdates() {
        uiHandler.removeCallbacks(clockUpdateRunnable)
    }

    private fun updateClockText() {
        val clock = performanceClockView ?: return
        val format = clockFormat()
        clock.text = SimpleDateFormat(format.pattern, Locale.getDefault()).format(Date())
        performanceOverlayView?.let { if (it.isLayoutRequested && hasRelativePosition()) restoreFloatingPosition(it) }
    }

    private fun clockFormat(): ClockFormat {
        val systemIs24Hour = DateFormat.is24HourFormat(activity)
        return when (prefConfig.perfOverlayClockFormat) {
            "system_seconds" -> if (systemIs24Hour) {
                ClockFormat("HH:mm:ss", true)
            } else {
                ClockFormat("h:mm:ss a", true)
            }
            "24_minutes" -> ClockFormat("HH:mm", false)
            "24_seconds" -> ClockFormat("HH:mm:ss", true)
            "12_minutes" -> ClockFormat("h:mm a", false)
            "12_seconds" -> ClockFormat("h:mm:ss a", true)
            else -> if (systemIs24Hour) {
                ClockFormat("HH:mm", false)
            } else {
                ClockFormat("h:mm a", false)
            }
        }
    }

    private fun clockUpdateDelayMs(): Long {
        if (clockFormat().includesSeconds) return CLOCK_SECOND_UPDATE_MS
        val remainder = System.currentTimeMillis() % CLOCK_MINUTE_MS
        return (CLOCK_MINUTE_MS - remainder).coerceAtLeast(1L)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applyOverlayState() {
        cancelDelayedShow()
        val overlay = performanceOverlayView ?: return
        cancelOverlayInteraction()

        if (!prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.GONE
            hasShownPerfOverlay = false
            ++overlayStyleGeneration
            overlay.setOnTouchListener(null)
            stopClockUpdates()
            fadeOutAndHide(overlay)
            return
        }

        requestedPerformanceOverlayVisibility = View.VISIBLE

        refreshOverlayNow()
        if (overlayTemporarilyHidden) return
        overlay.clearAnimation()

        if (overlay.visibility != View.VISIBLE || overlay.alpha < 1.0f) {
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1.0f
            hasShownPerfOverlay = true
        }

        startClockUpdates()
        setupPerformanceOverlayDragging()
    }

    private fun fadeOutAndHide(overlay: View) {
        stopClockUpdates()
        cancelOverlayInteraction()
        overlay.clearAnimation()
        if (overlayTemporarilyHidden) {
            overlay.visibility = View.GONE
            overlay.alpha = 0f
            return
        }
        val anim = AnimationUtils.loadAnimation(activity, R.anim.perf_overlay_fadeout)
        overlay.startAnimation(anim)
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}
            override fun onAnimationEnd(animation: Animation) {
                if (requestedPerformanceOverlayVisibility != View.VISIBLE || overlayTemporarilyHidden) {
                    overlay.visibility = View.GONE
                    overlay.alpha = 0.0f
                }
            }
            override fun onAnimationRepeat(animation: Animation) {}
        })
    }

    fun refreshPerformanceOverlayConfig() {
        if (requestedPerformanceOverlayVisibility == View.VISIBLE && !overlayTemporarilyHidden) refreshOverlayNow()
    }

    fun onConfigurationChanged() {
        cancelOverlayInteraction()
        if (performanceOverlayView != null) {
            refreshOverlayNow()
        }
    }

    fun updatePerformanceInfo(performanceInfo: PerformanceInfo) {
        updateBandwidthInfo(performanceInfo)

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            currentPerformanceInfo = performanceInfo
            showOverlayIfNeeded()
            if (requestedPerformanceOverlayVisibility != View.VISIBLE || overlayTemporarilyHidden) return@runOnUiThread
            updateBatteryDisplayIfNeeded()
            if (isHudStyle()) {
                updateHud(performanceInfo)
            } else {
                updatePerformanceViewsWithStyledText(performanceInfo)
            }
        }
    }

    fun setActualDisplayRefreshRate(refreshRate: Float) {
        this.actualDisplayRefreshRate = refreshRate
    }

    fun recordStreamStart() {
        bandwidthMeter.reset()
        hasDisplayableBattery = UiHelper.hasDisplayableBattery(activity)
        streamStartBatteryLevel = if (hasDisplayableBattery) {
            UiHelper.getBatteryLevel(activity)
        } else {
            -1
        }
        streamStartTime = System.currentTimeMillis()
        lastBatteryUpdateTime = streamStartTime
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            currentPerformanceInfo = null
            lastHudSnapshot = null
            hudView?.clearHistory()
            updateBatteryViewIfVisible()
        }
    }

    private fun updateBatteryDisplayIfNeeded() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBatteryUpdateTime >= BATTERY_UPDATE_INTERVAL_MS) {
            lastBatteryUpdateTime = currentTime
            updateBatteryViewIfVisible()
        }
    }

    private fun updateBatteryViewIfVisible() {
        refreshHudBattery()
        val batteryView = getPerformanceItemView(PerformanceItem.BATTERY)
        if (batteryView != null && batteryView.visibility == View.VISIBLE) {
            updateBatteryText(batteryView)
        }
    }

    private fun updateBandwidthInfo(performanceInfo: PerformanceInfo) {
        val bandwidthMbps = bandwidthMeter.update(
            MoonBridge.getRtpVideoBytesReceived(),
            SystemClock.elapsedRealtimeNanos()
        )
        performanceInfo.bandWidth = bandwidthMbps?.let(::formatBandwidthSpeed) ?: "N/A"
    }

    private fun buildDecoderInfo(performanceInfo: PerformanceInfo): String {
        val decoderTypeInfo = getDecoderTypeInfo(performanceInfo.decoder)
        // NBSP (\u00A0) keeps the codec and dynamic-range format on one line.
        return "${decoderTypeInfo.shortName}\u00A0${performanceInfo.hdrFormat.displayName}"
    }

    private fun getCurrentMoonPhaseIcon(): String {
        val now = Calendar.getInstance(TimeZone.getDefault())
        val currentDay = now.get(Calendar.DAY_OF_YEAR)

        if (currentDay == lastCalculatedDay) {
            return currentMoonPhaseIcon
        }

        val phase = MoonPhaseUtils.getCurrentMoonPhase()
        currentMoonPhaseIcon = MoonPhaseUtils.getMoonPhaseIcon(phase)
        currentIsFullMoon = MoonPhaseUtils.isFullMoon(phase, FULL_MOON_TOLERANCE)
        lastCalculatedDay = currentDay

        return currentMoonPhaseIcon
    }

    private fun showOverlayIfNeeded() {
        if (prefConfig.enablePerfOverlay && !overlayTemporarilyHidden && !hasShownPerfOverlay && performanceOverlayView != null) {
            configurePerformanceOverlay()
            performanceOverlayView?.visibility = View.VISIBLE
            performanceOverlayView?.alpha = 1.0f
            hasShownPerfOverlay = true
            startClockUpdates()
            setupPerformanceOverlayDragging()
        }
    }

    /**
     * 构造带图标 + 数值 + 单位的样式化文本。
     *
     * 优先级：[iconRes] > [iconEmoji]。当指定 [iconRes] 时使用 Phosphor 矢量图标
     * （与 HarmonyOS 版本视觉一致），通过 ImageSpan 嵌入到行内；图标颜色默认跟随
     * [valueColor]（未提供时为白色）。
     */
    private fun createStyledText(
        iconRes: Int?,
        value: String?,
        unit: String?,
        valueColor: Int?,
        iconEmoji: String? = null,
        textSizePx: Float? = null
    ): SpannableString {
        val builder = SpannableStringBuilder()

        if (iconRes != null) {
            val drawable = ContextCompat.getDrawable(activity, iconRes)?.mutate()
            if (drawable != null) {
                // 图标尺寸跟随当前行字号；缺省时回退到 14sp
                val basePx = textSizePx ?: TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14f, activity.resources.displayMetrics
                )
                val sizePx = (basePx * 1.05f).toInt()
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.setTint(valueColor ?: Color.WHITE)
                val placeholder = builder.length
                builder.append("\u00A0")
                builder.setSpan(
                    CenterAlignedImageSpan(drawable),
                    placeholder, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append("\u00A0")
            }
        } else if (!iconEmoji.isNullOrEmpty()) {
            val iconStart = builder.length
            builder.append(iconEmoji)
            builder.setSpan(StyleSpan(Typeface.BOLD), iconStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(1.1f), iconStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append("\u00A0")
        }

        if (!value.isNullOrEmpty()) {
            val valueStart = builder.length
            builder.append(value)
            builder.setSpan(TypefaceSpan("sans-serif-medium"), valueStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(1.0f), valueStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (valueColor != null) {
                builder.setSpan(ForegroundColorSpan(valueColor), valueStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        if (!unit.isNullOrEmpty()) {
            builder.append("\u00A0")
            val unitStart = builder.length
            builder.append(unit)
            builder.setSpan(TypefaceSpan("sans-serif-light"), unitStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(0.9f), unitStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(0xCCFFFFFF.toInt()), unitStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return SpannableString(builder)
    }

    private fun updatePerformanceViewsWithStyledText(performanceInfo: PerformanceInfo) {
        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                if (classicAnchorOnly) updateClassicAnchor() else updatePerformanceItemText(itemInfo, performanceInfo)
            }
        }
        configureTextAlignment()
        performanceOverlayView?.let { if (it.isLayoutRequested && hasRelativePosition()) restoreFloatingPosition(it) }
    }

    private fun updatePerformanceItemText(itemInfo: PerformanceItemInfo, performanceInfo: PerformanceInfo) {
        when (itemInfo.item) {
            PerformanceItem.RESOLUTION -> updateResolutionText(itemInfo.view!!, performanceInfo)
            PerformanceItem.DECODER -> updateDecoderText(itemInfo.view!!, performanceInfo)
            PerformanceItem.RENDER_FPS -> updateRenderFpsText(itemInfo.view!!, performanceInfo)
            PerformanceItem.PACKET_LOSS -> updatePacketLossText(itemInfo.view!!, performanceInfo)
            PerformanceItem.NETWORK_LATENCY -> updateNetworkLatencyText(itemInfo.view!!, performanceInfo)
            PerformanceItem.DECODE_LATENCY -> updateDecodeLatencyText(itemInfo.view!!, performanceInfo)
            PerformanceItem.HOST_LATENCY -> updateHostLatencyText(itemInfo.view!!, performanceInfo)
            PerformanceItem.BATTERY -> Unit
            PerformanceItem.ONE_PERCENT_LOW -> updateOnePercentLowText(itemInfo.view!!, performanceInfo)
            PerformanceItem.CLOCK -> Unit
        }
    }

    private fun updateResolutionText(view: TextView, performanceInfo: PerformanceInfo) {
        val resValue = String.format(Locale.getDefault(), "%dx%d@%.0f",
            performanceInfo.initialWidth, performanceInfo.initialHeight, performanceInfo.totalFps)
        val moonIcon = getCurrentMoonPhaseIcon()
        // 月相位置保持与原版一致：用 emoji 作为前缀图标
        view.text = createStyledText(null, resValue, "", null, moonIcon, textSizePx = view.textSize)
    }

    private fun updateDecoderText(view: TextView, performanceInfo: PerformanceInfo) {
        val decoderInfo = buildDecoderInfo(performanceInfo)
        // 原版本解码器行不带图标
        view.text = createStyledText(null, decoderInfo, "", null, textSizePx = view.textSize)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun updateRenderFpsText(view: TextView, performanceInfo: PerformanceInfo) {
        // NBSP + Word Joiner 围绕 / 防止 TextView 在 "Rx 60 / Rd 60" 任意空格或斜杠处断行
        val fpsValue = if (performanceInfo.framegenFps > 0.5f) {
            String.format(Locale.getDefault(), "Rx\u00A0%.0f\u00A0\u2060/\u2060\u00A0Rd\u00A0%.0f\u00A0\u2060/\u2060\u00A0FG\u00A0%.0f",
                performanceInfo.receivedFps, performanceInfo.renderedFps, performanceInfo.framegenFps)
        } else {
            String.format(Locale.getDefault(), "Rx\u00A0%.0f\u00A0\u2060/\u2060\u00A0Rd\u00A0%.0f",
                performanceInfo.receivedFps, performanceInfo.renderedFps)
        }
        // 原版本本行无图标
        view.text = createStyledText(null, fpsValue, "FPS", 0xFF0DDAF4.toInt(), textSizePx = view.textSize)
    }

    private fun updatePacketLossText(view: TextView, performanceInfo: PerformanceInfo) {
        val lossValue = String.format(Locale.getDefault(), "%.2f", performanceInfo.lostFrameRate)
        val lossColor = if (performanceInfo.lostFrameRate < 5.0f) 0xFF7D9D7D.toInt() else 0xFFB57D7D.toInt()
        view.text = createStyledText(R.drawable.phc_perf_signal, lossValue, "%", lossColor, textSizePx = view.textSize)
    }

    private fun updateNetworkLatencyText(view: TextView, performanceInfo: PerformanceInfo) {
        // 带宽用 gauge 仪表盘图标更直观，始终显示
        val iconRes: Int = R.drawable.phc_perf_gauge
        val bandwidthAndLatency = String.format(Locale.getDefault(), "%s\u00A0\u00A0\u00A0%d\u00A0\u00B1\u00A0%d",
            performanceInfo.bandWidth,
            (performanceInfo.rttInfo shr 32).toInt(),
            performanceInfo.rttInfo.toInt())
        view.text = createStyledText(iconRes, bandwidthAndLatency, "ms", 0xFFBCEDD3.toInt(), textSizePx = view.textSize)
    }

    private fun updateDecodeLatencyText(view: TextView, performanceInfo: PerformanceInfo) {
        val isHot = performanceInfo.decodeTimeMs >= 15
        val iconRes: Int? = if (isHot) null else R.drawable.phc_perf_timer
        val iconEmoji: String? = if (isHot) "🥵" else null
        val latencyValue = if (performanceInfo.pyrowaveTimingFrames > 0) {
            String.format(
                Locale.getDefault(),
                "%.2f+%.2f",
                performanceInfo.pyrowaveDecodeTimeMs,
                performanceInfo.pyrowavePresentTimeMs,
            )
        } else {
            String.format(Locale.getDefault(), "%.2f", performanceInfo.decodeTimeMs)
        }
        view.text = createStyledText(iconRes, latencyValue, "ms", 0xFFD597E3.toInt(), iconEmoji, textSizePx = view.textSize)
    }

    private fun updateHostLatencyText(view: TextView, performanceInfo: PerformanceInfo) {
        if (performanceInfo.framesWithHostProcessingLatency > 0) {
            val latencyValue = String.format(Locale.getDefault(), "%.1f", performanceInfo.aveHostProcessingLatency)
            view.text = createStyledText(R.drawable.phc_perf_monitor, latencyValue, "ms", 0xFF009688.toInt(), textSizePx = view.textSize)
        } else {
            // 空置时保持原版本的 🧋 emoji
            view.text = createStyledText(null, "Ver.V+", "", 0xFF009688.toInt(), iconEmoji = "🧋", textSizePx = view.textSize)
        }
    }

    private fun updateBatteryText(view: TextView) {
        if (!hasDisplayableBattery) {
            view.visibility = View.GONE
            return
        }

        val batteryLevel = UiHelper.getBatteryLevel(activity)
        val isCharging = UiHelper.isCharging(activity)
        val batteryColor = when {
            batteryLevel > 50 -> 0xFF90EE90.toInt()
            batteryLevel > 20 -> 0xFFFFA500.toInt()
            else -> 0xFFFF6B6B.toInt()
        }
        // 与鸿蒙一致：根据充电/电量分档选择不同电池图标
        val iconRes = when {
            isCharging          -> R.drawable.phc_perf_charging
            batteryLevel > 80   -> R.drawable.phc_perf_battery_full
            batteryLevel > 50   -> R.drawable.phc_perf_battery_mid
            batteryLevel > 20   -> R.drawable.phc_perf_battery_low
            batteryLevel > 0    -> R.drawable.phc_perf_battery_empty
            else                -> R.drawable.phc_perf_battery
        }
        view.text = createStyledText(iconRes, batteryLevel.toString(), "%", batteryColor, textSizePx = view.textSize)
    }

    private fun updateOnePercentLowText(view: TextView, performanceInfo: PerformanceInfo) {
        val lowFps = performanceInfo.onePercentLowFps
        if (lowFps <= 0) {
            view.text = createStyledText(null, "1%Low\u00A0—", "FPS", 0xFFFF7043.toInt(), textSizePx = view.textSize)
            return
        }
        val value = String.format(Locale.getDefault(), "1%%Low\u00A0%.1f", lowFps)
        val color = when {
            lowFps >= performanceInfo.renderedFps * 0.9f -> 0xFF90EE90.toInt()
            lowFps >= performanceInfo.renderedFps * 0.7f -> 0xFFFFD740.toInt()
            else -> 0xFFFF7043.toInt()
        }
        view.text = createStyledText(null, value, "FPS", color, textSizePx = view.textSize)
    }

    private fun showBatteryInfo() {
        if (!hasDisplayableBattery) {
            return
        }

        val batteryLevel = UiHelper.getBatteryLevel(activity)
        val isCharging = UiHelper.isCharging(activity)
        val status = activity.getString(
            when {
                batteryLevel > 50 -> R.string.perf_battery_status_sufficient
                batteryLevel > 20 -> R.string.perf_battery_status_low
                else -> R.string.perf_battery_status_critical
            }
        )

        val info = StringBuilder()
        info.append(activity.getString(R.string.perf_battery_info_content, batteryLevel, status))

        val hasStreamData = streamStartBatteryLevel >= 0 && streamStartTime > 0
        val streamDurationSeconds = if (hasStreamData) (System.currentTimeMillis() - streamStartTime) / 1000 else 0L

        if (isCharging) {
            info.append("\n\n⚡ ").append(activity.getString(R.string.perf_battery_charging))
            if (hasStreamData) {
                info.append("\n")
                    .append(activity.getString(R.string.perf_stream_duration, formatDuration(streamDurationSeconds)))
            }
        } else if (hasStreamData) {
            val batteryConsumed = streamStartBatteryLevel - batteryLevel
            info.append("\n\n")
                .append(activity.getString(
                    R.string.perf_battery_consumed,
                    if (batteryConsumed > 0) "${batteryConsumed}%" else "0%"
                ))
                .append("\n")
                .append(activity.getString(R.string.perf_stream_duration, formatDuration(streamDurationSeconds)))

            if (batteryConsumed > 0 && streamDurationSeconds > 0) {
                val consumedPerMinute = batteryConsumed.toDouble() / (streamDurationSeconds / 60.0)
                if (consumedPerMinute > 0) {
                    val remainingMinutes = (batteryLevel / consumedPerMinute).toLong()
                    info.append("\n")
                        .append(activity.getString(
                            R.string.perf_battery_remaining_estimate,
                            formatDuration(remainingMinutes * 60)
                        ))
                }
            }
        }

        infoSheets.showText(activity.getString(R.string.perf_battery_info_title), info.toString(), PerfHudTarget.BATTERY)
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) {
            return activity.getString(R.string.perf_duration_seconds, seconds)
        }
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60

        return when {
            hours > 0 -> if (minutes > 0) {
                activity.getString(R.string.perf_duration_hours_minutes, hours, minutes)
            } else {
                activity.getString(R.string.perf_duration_hours, hours)
            }
            remainingSeconds > 0 -> activity.getString(R.string.perf_duration_minutes_seconds, minutes, remainingSeconds)
            else -> activity.getString(R.string.perf_duration_minutes, minutes)
        }
    }

    private fun getPerformanceItemView(item: PerformanceItem): TextView? {
        return performanceItemMap[item]?.view
    }

    private fun isEffectiveVerticalLayout(): Boolean {
        val isPortrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) return true
        return prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
    }

    @SuppressLint("RtlHardcoded")
    private fun configurePerformanceOverlay() {
        val overlay = performanceOverlayView ?: return
        if (isHudStyle()) {
            configureHudOverlay(overlay)
            return
        }
        restoreClassicContainer(overlay)

        val layoutParams = overlay.layoutParams as FrameLayout.LayoutParams

        val isPortrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val isVertical = isEffectiveVerticalLayout()
        if (isVertical) {
            overlay.orientation = LinearLayout.VERTICAL
        } else {
            overlay.orientation = LinearLayout.HORIZONTAL
        }
        // 同步每个性能 TextView 的宽度模式：
        // - 垂直布局保留 match_parent 让 gravity 右对齐生效
        // - 水平布局必须 wrap_content，否则 LinearLayout 二次测量会让子项保留之前的最大宽度，
        //   数字位数缩短后无法回退，导致 item 间距残留。
        val targetWidth = if (isVertical)
            LinearLayout.LayoutParams.MATCH_PARENT
        else
            LinearLayout.LayoutParams.WRAP_CONTENT
        for (itemInfo in performanceItems) {
            val view = itemInfo.view ?: continue
            val lp = view.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.width != targetWidth) {
                lp.width = targetWidth
                view.layoutParams = lp
            }
        }
        overlay.setBackgroundColor(getOverlayBackgroundColor())

        configureDisplayItems()

        applyFloatingPosition(layoutParams, isPortrait)

        overlay.layoutParams = layoutParams
        overlay.post { configureTextAlignment() }
        setupPerformanceOverlayDragging()
        restoreFloatingPosition(overlay)
    }

    @SuppressLint("RtlHardcoded")
    private fun applyFloatingPosition(layoutParams: FrameLayout.LayoutParams, isPortrait: Boolean) {
        val prefs = overlayPrefs
        val hasCustomPosition = prefs.getBoolean("has_custom_position", false)
        val hasRelativePosition = hasRelativePosition()

        // HUD 自带圆角背景，贴着屏幕边缘时圆角会被切掉，留一点安全边距；横屏顶栏则直接吸顶
        val inset = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
        val topInset = if (isStickyBar()) 0 else inset
        if (hasRelativePosition) {
            layoutParams.gravity = Gravity.NO_GRAVITY
            layoutParams.leftMargin = prefs.getInt("left_margin", 0)
            layoutParams.topMargin = prefs.getInt("top_margin", 0)
        } else if (isPortrait) {
            layoutParams.gravity = Gravity.RIGHT or Gravity.TOP
            layoutParams.leftMargin = inset
            layoutParams.topMargin = inset
        } else if (hasCustomPosition) {
            layoutParams.gravity = Gravity.NO_GRAVITY
            layoutParams.leftMargin = prefs.getInt("left_margin", 0)
            val savedTop = prefs.getInt("top_margin", 0)
            // 顶栏吸顶时存的是 0；换成面板等其他形态时仍留出安全边距
            layoutParams.topMargin = if (savedTop <= inset) topInset else savedTop
        } else {
            when (prefConfig.perfOverlayPosition) {
                PreferenceConfiguration.PerfOverlayPosition.TOP ->
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                PreferenceConfiguration.PerfOverlayPosition.BOTTOM ->
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                PreferenceConfiguration.PerfOverlayPosition.TOP_LEFT ->
                    layoutParams.gravity = Gravity.LEFT or Gravity.TOP
                PreferenceConfiguration.PerfOverlayPosition.TOP_RIGHT ->
                    layoutParams.gravity = Gravity.RIGHT or Gravity.TOP
                PreferenceConfiguration.PerfOverlayPosition.BOTTOM_LEFT ->
                    layoutParams.gravity = Gravity.LEFT or Gravity.BOTTOM
                PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT ->
                    layoutParams.gravity = Gravity.RIGHT or Gravity.BOTTOM
            }
            layoutParams.leftMargin = inset
            layoutParams.topMargin = topInset
        }
        layoutParams.rightMargin = if (hasRelativePosition) 0 else inset
        layoutParams.bottomMargin = if (hasRelativePosition) 0 else inset
    }

    /** 横屏 HUD 顶栏（标准档、横排）贴屏幕上沿显示。 */
    private fun isStickyBar(): Boolean = isHudStyle() && currentHudLayout == PerfHudLayout.BAR

    /** 顶栏位于屏幕最上沿时切换成吸顶外观。 */
    private fun refreshHudStick() {
        val hud = hudView ?: return
        val lp = performanceOverlayView?.layoutParams as? FrameLayout.LayoutParams ?: return
        val vertical = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK
        val atTop = lp.gravity == Gravity.NO_GRAVITY || vertical == Gravity.TOP
        hud.stickTop = isStickyBar() && atTop && lp.topMargin == 0
    }

    private fun configureDisplayItems() {
        val hud = isHudStyle()
        for (itemInfo in performanceItems) {
            if (itemInfo.view != null) {
                val isEnabled = !hud && PerfOverlayDisplayItemsPreference.isItemEnabled(activity, itemInfo.item.preferenceKey)
                itemInfo.view.visibility = if (isEnabled) View.VISIBLE else View.GONE
            }
        }
        classicAnchorOnly = !hud && performanceItems.none { it.isVisible }
        if (classicAnchorOnly) {
            getPerformanceItemView(PerformanceItem.RESOLUTION)?.visibility = View.VISIBLE
            updateClassicAnchor()
        } else {
            getPerformanceItemView(PerformanceItem.RESOLUTION)?.contentDescription = null
        }
        hudView?.let {
            it.visibility = if (hud) View.VISIBLE else View.GONE
            if (hud) it.setItemVisibility(readHudVisibility())
        }
    }

    private fun updateClassicAnchor() {
        getPerformanceItemView(PerformanceItem.RESOLUTION)?.apply {
            text = getCurrentMoonPhaseIcon()
            contentDescription = MoonPhaseUtils.getCurrentMoonPhaseInfo(activity).name
        }
    }

    private fun getOverlayBackgroundColor(): Int {
        val alpha = prefConfig.perfOverlayBgOpacity.coerceIn(0, 100) * 255 / 100
        return Color.argb(alpha, OVERLAY_BACKGROUND_RGB, OVERLAY_BACKGROUND_RGB, OVERLAY_BACKGROUND_RGB)
    }

    private fun configureTextAlignment() {

        val isVertical = isEffectiveVerticalLayout()
        val gravity = if (isVertical && determineRightSidePosition())
            Gravity.CENTER_VERTICAL or Gravity.END
        else
            Gravity.CENTER_VERTICAL or Gravity.START

        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                configureTextViewStyle(itemInfo.view!!, gravity, isVertical)
            }
        }
    }

    private fun determineRightSidePosition(): Boolean {
        val prefs = overlayPrefs
        val hasCustomPosition = prefs.getBoolean("has_custom_position", false)

        return if (hasCustomPosition || isDraggingPerfOverlay) {
            val overlay = performanceOverlayView ?: return false
            val parent = overlay.parent as? View
            val lp = overlay.layoutParams as? FrameLayout.LayoutParams
            val left = if (lp?.gravity == Gravity.NO_GRAVITY) lp.leftMargin + (parent?.paddingLeft ?: 0) else overlay.left
            val parentWidth = parent?.width?.takeIf { it > 0 } ?: streamView.width
            (left + getViewDimensions(overlay)[0]) > (parentWidth * 2 / 3)
        } else {
            prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.TOP_RIGHT ||
                prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT
        }
    }

    private fun configureTextViewStyle(textView: TextView, gravity: Int, isVertical: Boolean) {
        textView.gravity = gravity
        textView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textView.letterSpacing = 0.02f
        textView.includeFontPadding = false

        if (isVertical) {
            textView.setShadowLayer(2.5f, 1.0f, 1.0f, 0x80000000.toInt())
        } else {
            textView.setShadowLayer(1.5f, 0.5f, 0.5f, 0x60000000)
        }

        val titleSize = getAdaptiveTextSizePx(11f)
        val bodySize = getAdaptiveTextSizePx(10f)

        val viewId = textView.id
        when (viewId) {
            R.id.perfRes -> {
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
            }
            R.id.perfDecoder -> {
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            }
            R.id.perfRenderFps -> {
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            }
            R.id.perfPacketLoss, R.id.perfNetworkLatency, R.id.perfDecodeLatency,
            R.id.perfHostLatency, R.id.perfBattery, R.id.perfOnePercentLow -> {
                textView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            }
            R.id.perfClock -> {
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            }
        }
    }

    private fun getAdaptiveTextSizePx(baseSizeSp: Float): Float {
        val dm: DisplayMetrics = activity.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val shortSideDp = shortSide / dm.density
        val referenceDp = 411f
        val scaleFactor = sqrt((shortSideDp / referenceDp).toDouble()).toFloat()
        val targetPx = baseSizeSp * dm.density * scaleFactor
        return targetPx.coerceIn(8f, 40f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPerformanceOverlayDragging() {
        val overlay = performanceOverlayView ?: return

        if (prefConfig.perfOverlayLocked) {
            cancelOverlayInteraction()
            overlay.setOnTouchListener(null)
            overlay.isClickable = false
            overlay.isFocusable = false
            overlay.isLongClickable = false
        } else {
            overlay.isClickable = true
            overlay.isFocusable = true
            overlay.isLongClickable = true

            overlay.setOnTouchListener { v, event ->
                handleOverlayTouch(v, event)
            }
        }
    }

    private fun handleActionDown(v: View, event: MotionEvent): Boolean {
        ++overlayStyleGeneration
        hideSnapGuides()
        perfOverlayMoved = false
        isDraggingPerfOverlay = true
        perfOverlayStartX = event.rawX
        perfOverlayStartY = event.rawY

        val layoutParams = v.layoutParams as FrameLayout.LayoutParams
        perfOverlayOriginalLayout = FrameLayout.LayoutParams(layoutParams)
        val hasRelativePosition = hasRelativePosition()
        dragGuideX = if (hasRelativePosition) savedGuide(KEY_GUIDE_X) else when (layoutParams.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> SnapGuide.START
            Gravity.CENTER_HORIZONTAL -> SnapGuide.CENTER
            Gravity.RIGHT -> SnapGuide.END
            else -> null
        }
        dragGuideY = if (hasRelativePosition) savedGuide(KEY_GUIDE_Y) else when (layoutParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> SnapGuide.START
            Gravity.CENTER_VERTICAL -> SnapGuide.CENTER
            Gravity.BOTTOM -> SnapGuide.END
            else -> null
        }
        dragRelativeX = if (hasRelativePosition) overlayPrefs.getFloat(KEY_RELATIVE_X, 0.5f) else null
        dragRelativeY = if (hasRelativePosition) overlayPrefs.getFloat(KEY_RELATIVE_Y, 0.5f) else null

        if (layoutParams.gravity != Gravity.NO_GRAVITY) {
            convertGravityToMargins(v, layoutParams)
        }
        layoutParams.rightMargin = 0
        layoutParams.bottomMargin = 0
        v.layoutParams = layoutParams
        perfOverlayDragWidth = v.width
        perfOverlayDragHeight = v.height

        perfOverlayDeltaX = perfOverlayStartX - layoutParams.leftMargin
        perfOverlayDeltaY = perfOverlayStartY - layoutParams.topMargin

        applyDraggingVisualFeedback(v, true)
        return true
    }

    private fun handleActionMove(v: View, event: MotionEvent): Boolean {
        if (!isDraggingPerfOverlay) return false

        val parent = v.parent as View
        val viewWidth = maxOf(if (v.width == 0) 300 else v.width, perfOverlayDragWidth)
        val viewHeight = maxOf(if (v.height == 0) 50 else v.height, perfOverlayDragHeight)

        val layoutParams = v.layoutParams as FrameLayout.LayoutParams
        var newLeftMargin = (event.rawX - perfOverlayDeltaX).toInt()
        var newTopMargin = (event.rawY - perfOverlayDeltaY).toInt()

        val maxLeft = maxOf(0, parent.width - parent.paddingLeft - parent.paddingRight - viewWidth)
        val maxTop = maxOf(0, parent.height - parent.paddingTop - parent.paddingBottom - viewHeight)
        dragMaxLeft = maxLeft
        dragMaxTop = maxTop
        newLeftMargin = newLeftMargin.coerceIn(0, maxLeft)
        newTopMargin = newTopMargin.coerceIn(0, maxTop)
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        if (abs(event.rawX - perfOverlayStartX) > slop || abs(event.rawY - perfOverlayStartY) > slop) {
            perfOverlayMoved = true
        }
        if (perfOverlayMoved) {
            val sideInset = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
            val topInset = if (isStickyBar()) 0 else sideInset
            val x = guidedPosition(newLeftMargin, maxLeft, sideInset, sideInset)
            val y = guidedPosition(newTopMargin, maxTop, topInset, sideInset)
            newLeftMargin = x.margin
            newTopMargin = y.margin
            if (maxLeft > 0) {
                dragGuideX = x.guide
                dragRelativeX = newLeftMargin.toFloat() / maxLeft
            }
            if (maxTop > 0) {
                dragGuideY = y.guide
                dragRelativeY = newTopMargin.toFloat() / maxTop
            }
            updateSnapGuides(parent as? ViewGroup, newLeftMargin, newTopMargin, viewWidth, viewHeight, x.guide, y.guide)
        } else {
            return true
        }

        layoutParams.leftMargin = newLeftMargin
        layoutParams.topMargin = newTopMargin
        layoutParams.gravity = Gravity.NO_GRAVITY
        v.layoutParams = layoutParams

        configureTextAlignment()
        refreshHudStick()
        return true
    }

    private fun handleActionUp(v: View, event: MotionEvent): Boolean {
        if (!isDraggingPerfOverlay) return false

        handleActionMove(v, event)
        isDraggingPerfOverlay = false
        applyDraggingVisualFeedback(v, false)
        hideSnapGuides()

        if (!perfOverlayMoved) {
            restoreOverlayLayout(v)
        } else {
            saveDragPosition(v)
        }

        return true
    }

    private fun cancelOverlayDrag(v: View): Boolean {
        if (!isDraggingPerfOverlay) return false
        isDraggingPerfOverlay = false
        applyDraggingVisualFeedback(v, false)
        hideSnapGuides()
        restoreOverlayLayout(v)
        refreshHudStick()
        return true
    }

    private fun restoreOverlayLayout(v: View) {
        perfOverlayOriginalLayout?.let { v.layoutParams = it }
    }

    private fun convertGravityToMargins(v: View, layoutParams: FrameLayout.LayoutParams) {
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        v.getLocationInWindow(viewLocation)
        val parent = v.parent as View
        parent.getLocationInWindow(parentLocation)
        layoutParams.leftMargin = viewLocation[0] - parentLocation[0] - parent.paddingLeft
        layoutParams.topMargin = viewLocation[1] - parentLocation[1] - parent.paddingTop
        layoutParams.gravity = Gravity.NO_GRAVITY
        v.layoutParams = layoutParams
    }

    private fun applyDraggingVisualFeedback(v: View, isDragging: Boolean) {
        val targetAlpha = if (isDragging) 0.7f else 1.0f
        val targetScale = if (isDragging) 1.05f else 1.0f
        v.alpha = targetAlpha
        v.scaleX = targetScale
        v.scaleY = targetScale
    }

    private fun showClickedItemInfo(x: Float, y: Float) {
        if (classicAnchorOnly) {
            infoSheets.showMoonPhase()
            return
        }
        if (isEffectiveVerticalLayout()) {
            showClickedItemInfoVertical(y)
        } else {
            showClickedItemInfoHorizontal(x)
        }
    }

    private fun showClickedItemInfoVertical(y: Float) {
        val overlayHeight = performanceOverlayView?.height ?: return
        if (overlayHeight == 0) return

        val visibleItemCount = getVisibleItemCount()
        if (visibleItemCount == 0) {
            infoSheets.showMoonPhase()
            return
        }

        val itemHeight = (overlayHeight / visibleItemCount).coerceAtLeast(1)
        val clickedItemIndex = (y / itemHeight).toInt()

        showInfoByIndex(clickedItemIndex)
    }

    private fun showClickedItemInfoHorizontal(x: Float) {
        val overlayWidth = performanceOverlayView?.width ?: return
        if (overlayWidth == 0) return

        val visibleItemCount = getVisibleItemCount()
        if (visibleItemCount == 0) {
            infoSheets.showMoonPhase()
            return
        }

        val clickedItemIndex = findClickedItemByBoundaries(x)
        showInfoByIndex(clickedItemIndex)
    }

    private fun findClickedItemByBoundaries(x: Float): Int {
        var currentIndex = 0
        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                val viewLocation = IntArray(2)
                itemInfo.view?.getLocationInWindow(viewLocation)

                val overlayLocation = IntArray(2)
                performanceOverlayView?.getLocationInWindow(overlayLocation)

                val viewLeft = viewLocation[0] - overlayLocation[0]
                val viewRight = viewLeft + (itemInfo.view?.width ?: 0)

                if (x >= viewLeft && x <= viewRight) {
                    return currentIndex
                }
                currentIndex++
            }
        }
        return -1
    }

    private fun getVisibleItemCount(): Int = performanceItems.count { it.isVisible }

    private fun showInfoByIndex(index: Int) {
        var currentIndex = 0
        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                if (currentIndex == index) {
                    itemInfo.infoMethod.run()
                    return
                }
                currentIndex++
            }
        }
        infoSheets.showMoonPhase()
    }

    private fun showResolutionInfo() {
        val perfInfo = currentPerformanceInfo
        if (perfInfo == null) {
            infoSheets.showMoonPhase()
            return
        }

        val scalePercent = prefConfig.resolutionScale
        val scaleFactor = scalePercent / 100.0f
        val hostWidth = (perfInfo.initialWidth * scaleFactor).toInt()
        val hostHeight = (perfInfo.initialHeight * scaleFactor).toInt()

        val resolutionInfo = StringBuilder()
        resolutionInfo.append(activity.getString(R.string.perf_client_resolution)).append(perfInfo.initialWidth)
            .append(" × ").append(perfInfo.initialHeight).append("\n")
        resolutionInfo.append(activity.getString(R.string.perf_host_resolution)).append(hostWidth)
            .append(" × ").append(hostHeight).append("\n")
        resolutionInfo.append(activity.getString(R.string.perf_scale_factor)).append(String.format(Locale.getDefault(), "%.2f", scaleFactor))
            .append(" (").append(scalePercent).append("%)\n")

        val deviceRefreshRate = UiHelper.getDeviceRefreshRate(activity)
        resolutionInfo.append(activity.getString(R.string.perf_target_fps)).append(prefConfig.fps).append(" FPS\n")
        resolutionInfo.append(activity.getString(R.string.perf_current_fps)).append(String.format(Locale.getDefault(), "%.0f", perfInfo.totalFps)).append(" FPS\n")
        resolutionInfo.append(activity.getString(R.string.perf_device_refresh)).append(String.format(Locale.getDefault(), "%.0f", deviceRefreshRate)).append(" Hz\n")

        if (actualDisplayRefreshRate > 0) {
            resolutionInfo.append(activity.getString(R.string.perf_actual_refresh)).append(String.format(Locale.getDefault(), "%.2f", actualDisplayRefreshRate)).append(" Hz\n")
        }

        infoSheets.showText(activity.getString(R.string.perf_resolution_title), resolutionInfo.toString(), PerfHudTarget.RESOLUTION)
    }

    private fun showDecoderInfo() {
        val fullDecoderInfo = getCurrentDecoderInfo()
        infoSheets.showText(activity.getString(R.string.perf_decoder_title), fullDecoderInfo, PerfHudTarget.DECODER)
    }

    private fun getCurrentDecoderInfo(): String {
        val decoderInfo = StringBuilder()
        val perfInfo = currentPerformanceInfo
        if (perfInfo != null) {
            decoderInfo.append(activity.getString(R.string.perf_codec_label)).append(perfInfo.decoder).append("\n\n")
            val decoderTypeInfo = getDecoderTypeInfo(perfInfo.decoder)
            decoderInfo.append(activity.getString(R.string.perf_type_label)).append(decoderTypeInfo.fullName).append("\n")
            decoderInfo.append(activity.getString(R.string.perf_dynamic_range)).append(when (perfInfo.hdrFormat) {
                com.limelight.binding.video.StreamHdrFormat.HDR10_PLUS -> activity.getString(R.string.hdr_diagnostic_hdr10_plus)
                com.limelight.binding.video.StreamHdrFormat.DOLBY_VISION -> activity.getString(R.string.hdr_diagnostic_dolby)
                else -> perfInfo.hdrFormat.displayName
            }).append("\n")
        }
        decoderInfo.append(activity.getString(R.string.perf_decoder_info))
        return decoderInfo.toString()
    }

    private fun getDecoderTypeInfo(fullDecoderName: String?): DecoderTypeInfo {
        if (fullDecoderName == null) {
            return DecoderTypeInfo("Unknown", "Unknown")
        }

        val lowerName = fullDecoderName.lowercase()

        // The overlay shows the codec name only; the detailed renderer path
        // (Vulkan Surface vs CPU staging) stays in the decoder detail dialog.
        if (lowerName.contains("pyrowave")) {
            return DecoderTypeInfo(fullDecoderName, "PyroWave")
        }

        for ((key, value) in DECODER_TYPE_MAP) {
            if (lowerName.contains(key)) {
                return value
            }
        }

        val parts = fullDecoderName.split(".")
        if (parts.isNotEmpty()) {
            val extractedName = parts.last()
            return DecoderTypeInfo(fullDecoderName, extractedName.uppercase())
        }

        return DecoderTypeInfo(fullDecoderName, fullDecoderName)
    }

    private fun createJitterMonitorSwitchRow(): View {
        val jitterSwitch = AppSwitchView(activity).apply {
            isChecked = prefConfig.enableJitterMonitor
            text = ""
            minWidth = dp(48f)
            minHeight = dp(48f)
            background = null
            isClickable = false
            isFocusable = false
            AppSwitchStyle.apply(this)
            setOnCheckedChangeListener { _, isChecked ->
                setJitterMonitorEnabled(isChecked)
            }
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(58f)
            setPadding(0, dp(4f), 0, dp(6f))
            setOnClickListener { jitterSwitch.isChecked = !jitterSwitch.isChecked }

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.title_enable_jitter_monitor)
                    setTextColor(PerfHudPalette.TXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    includeFontPadding = false
                })
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.summary_enable_jitter_monitor)
                    setTextColor(PerfHudPalette.DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setLineSpacing(0f, 1.05f)
                    setPadding(0, dp(4f), dp(12f), 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(jitterSwitch, LinearLayout.LayoutParams(dp(56f), dp(48f)))
        }
    }

    private fun setJitterMonitorEnabled(enabled: Boolean) {
        if (prefConfig.enableJitterMonitor == enabled) return
        prefConfig.enableJitterMonitor = enabled
        prefConfig.writePreferences(activity)
        onJitterMonitorChanged(enabled)
    }

    private fun toggleLayoutOrientation() {
        val orientation = if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL
        } else {
            PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
        }
        setPerfOverlayOrientation(orientation)
    }

    fun setPerfOverlayOrientation(orientation: PreferenceConfiguration.PerfOverlayOrientation) {
        if (prefConfig.perfOverlayOrientation == orientation) return
        cancelOverlayInteraction()
        prefConfig.perfOverlayOrientation = orientation
        saveLayoutOrientation()
        if (requestedPerformanceOverlayVisibility == View.VISIBLE && !overlayTemporarilyHidden) refreshOverlayNow()
        when {
            currentHudLayout == PerfHudLayout.DOCK ->
                showHudToast(activity.getString(R.string.game_menu_perf_orientation_docked))
            isHudStyle() && prefConfig.perfOverlayStyle != PerfHudDensity.STANDARD ->
                showHudToast(activity.getString(R.string.game_menu_perf_orientation_fixed))
            !isHudStyle() && isPortrait() ->
                showHudToast(activity.getString(R.string.game_menu_perf_orientation_portrait))
            else -> {
                val label = activity.getString(
                    if (orientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL)
                        R.string.orientation_vertical else R.string.orientation_horizontal
                )
                showHudToast(activity.getString(R.string.perf_hud_style_changed, label))
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun saveLayoutOrientation(synchronous: Boolean = false): Boolean {
        val value = when (prefConfig.perfOverlayOrientation) {
            PreferenceConfiguration.PerfOverlayOrientation.VERTICAL -> "vertical"
            PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL -> "horizontal"
        }
        val editor = PreferenceManager.getDefaultSharedPreferences(activity).edit()
            .putString(KEY_DEFAULT_ORIENTATION, value)
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun loadLayoutOrientation() {
        val savedOrientation = overlayPrefs.getString(KEY_LEGACY_ORIENTATION, null)
        if (savedOrientation != null) {
            try {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.valueOf(savedOrientation)
            } catch (_: IllegalArgumentException) {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
            }
            // Commit the canonical value before removing the only copy of a legacy selection.
            if (saveLayoutOrientation(synchronous = true)) {
                overlayPrefs.edit { remove(KEY_LEGACY_ORIENTATION) }
            }
        }
    }

    private fun guidedPosition(position: Int, max: Int, startInset: Int, endInset: Int): GuidedPosition {
        // A full-size overlay cannot select an anchor on this axis.
        if (max == 0) return GuidedPosition(0, null)
        var margin = startInset.coerceIn(0, max)
        var guide = SnapGuide.START
        val center = max / 2
        val end = (max - endInset).coerceIn(0, max)
        if (abs(position - center) < abs(position - margin)) {
            margin = center
            guide = SnapGuide.CENTER
        }
        if (abs(position - end) < abs(position - margin)) {
            margin = end
            guide = SnapGuide.END
        }
        return if (abs(position - margin) <= dp(SNAP_GUIDE_DISTANCE_DP)) {
            GuidedPosition(margin, guide)
        } else {
            GuidedPosition(position, null)
        }
    }

    private fun updateSnapGuides(
        parent: ViewGroup?, left: Int, top: Int, width: Int, height: Int, guideX: SnapGuide?, guideY: SnapGuide?
    ) {
        if (parent == null) {
            hideSnapGuides()
            return
        }
        if (guideX == null && guideY == null) {
            snapGuidesView?.visibility = View.INVISIBLE
            return
        }
        val guides = snapGuidesView ?: PerfOverlaySnapGuides(activity, UiHelper.accentColor(activity)).also {
            it.tag = SNAP_GUIDES_TAG
            val index = parent.indexOfChild(performanceOverlayView).coerceAtLeast(0)
            parent.addView(it, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            snapGuidesView = it
        }
        guides.visibility = View.VISIBLE
        val x = when (guideX) {
            SnapGuide.START -> left
            SnapGuide.CENTER -> left + width / 2
            SnapGuide.END -> left + width
            null -> null
        }
        val y = when (guideY) {
            SnapGuide.START -> top
            SnapGuide.CENTER -> top + height / 2
            SnapGuide.END -> top + height
            null -> null
        }
        val overlay = performanceOverlayView
        val extraX = (width * ((overlay?.scaleX ?: 1f) - 1f) / 2).roundToInt().coerceAtLeast(0)
        val extraY = (height * ((overlay?.scaleY ?: 1f) - 1f) / 2).roundToInt().coerceAtLeast(0)
        guides.update(x, y, left - extraX, top - extraY, width + extraX * 2, height + extraY * 2)
    }

    private fun hideSnapGuides() {
        snapGuidesView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        snapGuidesView = null
    }

    private fun hasRelativePosition(): Boolean = overlayPrefs.getBoolean("has_custom_position", false) &&
        overlayPrefs.contains(KEY_RELATIVE_X) && overlayPrefs.contains(KEY_RELATIVE_Y)

    private fun measureOverlayInParent(view: View, parent: View, lp: FrameLayout.LayoutParams): Pair<Int, Int> {
        val width = maxOf(0, parent.width - parent.paddingLeft - parent.paddingRight - lp.rightMargin)
        val height = maxOf(0, parent.height - parent.paddingTop - parent.paddingBottom - lp.bottomMargin)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        )
        return width to height
    }

    private fun saveDragPosition(view: View) {
        val parent = view.parent as? View ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val (availableWidth, availableHeight) = measureOverlayInParent(view, parent, lp)
        val maxLeft = maxOf(0, availableWidth - view.measuredWidth)
        val maxTop = maxOf(0, availableHeight - view.measuredHeight)
        val sideInset = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
        val topInset = if (isStickyBar()) 0 else sideInset
        val left = if (dragMaxLeft == 0 || dragGuideX != null) {
            restoredMargin(dragRelativeX ?: 0.5f, dragGuideX, maxLeft, sideInset, sideInset)
        } else {
            lp.leftMargin.coerceIn(0, maxLeft)
        }
        val top = if (dragMaxTop == 0 || dragGuideY != null) {
            restoredMargin(dragRelativeY ?: 0.5f, dragGuideY, maxTop, topInset, sideInset)
        } else {
            lp.topMargin.coerceIn(0, maxTop)
        }
        lp.leftMargin = left
        lp.topMargin = top
        view.layoutParams = lp
        overlayPrefs.edit {
            putBoolean("has_custom_position", true)
            putInt("left_margin", left)
            putInt("top_margin", top)
            // Keep the last meaningful ratio when the new size fills the parent.
            putFloat(KEY_RELATIVE_X, if (maxLeft > 0) left.toFloat() / maxLeft else dragRelativeX ?: 0.5f)
            putFloat(KEY_RELATIVE_Y, if (maxTop > 0) top.toFloat() / maxTop else dragRelativeY ?: 0.5f)
            if (dragGuideX != null) putString(KEY_GUIDE_X, dragGuideX!!.name) else remove(KEY_GUIDE_X)
            if (dragGuideY != null) putString(KEY_GUIDE_Y, dragGuideY!!.name) else remove(KEY_GUIDE_Y)
        }
        configureTextAlignment()
        refreshHudStick()
    }

    private fun restoreFloatingPosition(overlay: View) {
        val relativePosition = hasRelativePosition()
        if (isDraggingPerfOverlay || !overlayPrefs.getBoolean("has_custom_position", false) ||
            (isPortrait() && !relativePosition) || currentHudLayout == PerfHudLayout.DOCK
        ) return
        repositionFloatingOverlay(overlay, Rect(overlay.left, overlay.top, overlay.right, overlay.bottom),
            ++overlayStyleGeneration, relativePosition)
    }

    private fun savedGuide(key: String): SnapGuide? {
        val name = overlayPrefs.getString(key, null)
        return SnapGuide.entries.firstOrNull { it.name == name }
    }

    private fun restoredMargin(relative: Float, guide: SnapGuide?, max: Int, startInset: Int, endInset: Int): Int {
        return when (guide) {
            SnapGuide.START -> startInset.coerceIn(0, max)
            SnapGuide.CENTER -> max / 2
            SnapGuide.END -> (max - endInset).coerceIn(0, max)
            null -> (relative.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        }
    }

    private fun savePerformanceOverlayPosition(x: Int, y: Int) {
        val prefs = overlayPrefs
        prefs.edit {
            putBoolean("has_custom_position", true)
                .putInt("left_margin", x)
                .putInt("top_margin", y)
        }
    }

    private fun getViewDimensions(view: View): IntArray {
        var width = view.width
        var height = view.height
        if (width == 0) width = 300
        if (height == 0) height = 50
        return intArrayOf(width, height)
    }

    // ================================================================ 新版 HUD

    private fun isHudStyle(): Boolean = prefConfig.perfOverlayStyle.isHud

    private fun isPortrait(): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** 游戏菜单里切换覆盖层样式。 */
    fun setPerfOverlayStyle(style: PerfHudDensity) = applyStyleChange(style, announce = false)

    private fun cycleHudDensity() = applyStyleChange(prefConfig.perfOverlayStyle.next(), announce = true)

    private fun applyStyleChange(style: PerfHudDensity, announce: Boolean) {
        if (prefConfig.perfOverlayStyle == style) return
        cancelOverlayInteraction()
        ++overlayStyleGeneration
        prefConfig.perfOverlayStyle = style
        prefConfig.writePreferences(activity)
        refreshOverlayNow()
        if (announce) {
            showHudToast(activity.getString(R.string.perf_hud_style_changed, styleName(style)))
        }
    }

    private fun styleName(style: PerfHudDensity): String = activity.getString(
        when (style) {
            PerfHudDensity.MINI -> R.string.perf_overlay_style_mini
            PerfHudDensity.STANDARD -> R.string.perf_overlay_style_standard
            PerfHudDensity.DETAILED -> R.string.perf_overlay_style_detailed
            PerfHudDensity.CLASSIC -> R.string.perf_overlay_style_classic
        }
    )

    /** 重新配置后立即用最近一帧数据刷新，避免切换样式后空白一秒。 */
    private fun refreshOverlayNow() {
        if (performanceOverlayView == null) return
        configurePerformanceOverlay()
        val info = currentPerformanceInfo
        if (isHudStyle()) {
            if (info != null) updateHud(info, recordHistory = false) else hudView?.invalidate()
        } else {
            if (info != null) updatePerformanceViewsWithStyledText(info)
            updateBatteryViewIfVisible()
            if (requestedPerformanceOverlayVisibility == View.VISIBLE) startClockUpdates()
        }
    }

    /** Measure without the old margins so a larger style is not clipped before it can be repositioned. */
    private fun repositionFloatingOverlay(overlay: View, oldBounds: Rect, generation: Int, relativePosition: Boolean) {
        overlay.post {
            if (generation != overlayStyleGeneration || isDraggingPerfOverlay ||
                currentHudLayout == PerfHudLayout.DOCK
            ) return@post
            val parent = overlay.parent as? View ?: return@post
            val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return@post
            if (lp.gravity != Gravity.NO_GRAVITY || parent.width <= 0 || parent.height <= 0) return@post

            val (availableWidth, availableHeight) = measureOverlayInParent(overlay, parent, lp)
            val maxLeft = maxOf(0, availableWidth - overlay.measuredWidth)
            val maxTop = maxOf(0, availableHeight - overlay.measuredHeight)
            val sideInset = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
            val topInset = if (isStickyBar()) 0 else sideInset
            val left = if (relativePosition) {
                restoredMargin(overlayPrefs.getFloat(KEY_RELATIVE_X, 0f), savedGuide(KEY_GUIDE_X),
                    maxLeft, sideInset, sideInset)
            } else {
                anchoredMargin(oldBounds.left, oldBounds.right, parent.width, parent.paddingLeft, parent.paddingRight,
                    availableWidth, overlay.measuredWidth, lp.leftMargin)
            }
            val top = if (relativePosition) {
                restoredMargin(overlayPrefs.getFloat(KEY_RELATIVE_Y, 0f), savedGuide(KEY_GUIDE_Y),
                    maxTop, topInset, sideInset)
            } else {
                anchoredMargin(oldBounds.top, oldBounds.bottom, parent.height, parent.paddingTop, parent.paddingBottom,
                    availableHeight, overlay.measuredHeight, lp.topMargin)
            }
            if (left != lp.leftMargin || top != lp.topMargin) {
                lp.leftMargin = left
                lp.topMargin = top
                overlay.layoutParams = lp
                if (!relativePosition) savePerformanceOverlayPosition(left, top)
                refreshHudStick()
            } else {
                // The explicit measurement must be followed by a normal parent layout.
                overlay.requestLayout()
            }
            if (!isHudStyle()) configureTextAlignment()
        }
    }

    /** Keep a centered or end-anchored edge stable as the overlay changes size. */
    private fun anchoredMargin(
        oldStart: Int, oldEnd: Int, parentSize: Int, paddingStart: Int, paddingEnd: Int,
        availableSize: Int, newSize: Int, currentMargin: Int
    ): Int {
        val oldCenterTwice = oldStart + oldEnd
        val contentCenterTwice = parentSize + paddingStart - paddingEnd
        val margin = when {
            oldEnd <= oldStart -> currentMargin
            abs(oldCenterTwice - contentCenterTwice) <= dp(2f) * 2 ->
                (parentSize - paddingStart - paddingEnd - newSize) / 2
            oldCenterTwice > contentCenterTwice -> oldEnd - paddingStart - newSize
            else -> currentMargin
        }
        return margin.coerceIn(0, maxOf(0, availableSize - newSize))
    }

    private fun restoreClassicContainer(overlay: LinearLayout) {
        hudView?.visibility = View.GONE
        currentHudLayout = null
        currentDockTop = -1
        classicOverlayPadding?.let { overlay.setPadding(it[0], it[1], it[2], it[3]) }
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    private fun configureHudOverlay(overlay: LinearLayout) {
        if (classicOverlayPadding == null) {
            classicOverlayPadding = intArrayOf(
                overlay.paddingLeft, overlay.paddingTop, overlay.paddingRight, overlay.paddingBottom
            )
        }
        overlay.setPadding(0, 0, 0, 0)
        overlay.background = null
        overlay.orientation = LinearLayout.VERTICAL
        configureDisplayItems()
        hudView?.apply {
            setBaseTextSizePx(getAdaptiveTextSizePx(HUD_BASE_TEXT_SP))
            setBackgroundOpacity(prefConfig.perfOverlayBgOpacity.coerceIn(0, 100) / 100f)
            accentColor = UiHelper.accentColor(activity)
        }
        applyHudPlacement(overlay, force = true)
        setupPerformanceOverlayDragging()
        restoreFloatingPosition(overlay)
    }

    /**
     * 决定 HUD 形态并摆放容器。竖屏且视频下方黑边放得下时停靠在视频下方（不可拖动），
     * 否则作为浮层沿用原有的位置/吸附逻辑。每次数据刷新都会以 force=false 调用，
     * 以便虚拟手柄显隐、旋转后视频尺寸变化时自动在停靠和浮动之间切换。
     */
    private fun applyHudPlacement(overlay: LinearLayout, force: Boolean) {
        val hud = hudView ?: return
        val density = prefConfig.perfOverlayStyle
        val portrait = isPortrait()
        val detailed = density == PerfHudDensity.DETAILED
        val dockTop = if (portrait && density != PerfHudDensity.MINI) computeDockTop(overlay, hud, detailed) else -1
        val layout = PerfHudLayoutPolicy.resolve(
            density,
            portrait,
            prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL,
            dockTop >= 0
        )
        if (!force && layout == currentHudLayout && (layout != PerfHudLayout.DOCK || dockTop == currentDockTop)) return

        val wasDock = currentHudLayout == PerfHudLayout.DOCK
        if (!wasDock && layout == PerfHudLayout.DOCK && isDraggingPerfOverlay) cancelOverlayDrag(overlay)
        currentHudLayout = layout
        currentDockTop = if (layout == PerfHudLayout.DOCK) dockTop else -1
        hud.setHudLayout(layout, detailed)

        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        val hudLp = hud.layoutParams
        if (layout == PerfHudLayout.DOCK) {
            val side = dp(DOCK_SIDE_MARGIN_DP)
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.TOP
            lp.leftMargin = side
            lp.rightMargin = side
            lp.topMargin = dockTop
            lp.bottomMargin = 0
            hudLp.width = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            hudLp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            if (force || wasDock) applyFloatingPosition(lp, portrait)
        }
        hud.layoutParams = hudLp
        overlay.layoutParams = lp
        refreshHudStick()
        if (wasDock && layout != PerfHudLayout.DOCK) restoreFloatingPosition(overlay)
    }

    /** @return 停靠卡片的 topMargin；放不下或被虚拟手柄占用时返回 -1。 */
    private fun computeDockTop(overlay: View, hud: PerfHudView, detailed: Boolean): Int {
        if ((activity as? Game)?.isVirtualControllerVisible() == true) return -1
        val parent = overlay.parent as? View ?: return -1
        if (parent.width == 0 || parent.height == 0 || streamView.height == 0) return -1

        val parentLoc = IntArray(2)
        val streamLoc = IntArray(2)
        parent.getLocationInWindow(parentLoc)
        streamView.getLocationInWindow(streamLoc)
        val videoBottom = streamLoc[1] - parentLoc[1] + streamView.height
        val gap = dp(DOCK_GAP_DP)
        val top = videoBottom + gap
        val width = parent.width - dp(DOCK_SIDE_MARGIN_DP) * 2
        if (width <= 0) return -1
        // 已经停靠且宽度、档位没变时，当前高度就是答案，省掉每秒两次额外测量
        val needed = if (currentHudLayout == PerfHudLayout.DOCK && hud.detailed == detailed && hud.width == width && hud.height > 0) {
            hud.height
        } else {
            hud.measureHeightFor(PerfHudLayout.DOCK, detailed, width)
        }
        return if (top + needed + gap <= parent.height) top else -1
    }

    /**
     * HUD 尺寸随样式或数值变化时：原来靠右/靠下的就保持右/下边缘不动，并始终夹在父容器内，
     * 避免切到详细面板后半个面板跑出屏幕。
     */
    private fun onHudOverlayResized(
        v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int
    ) {
        if (currentHudLayout == PerfHudLayout.DOCK || isDraggingPerfOverlay) return
        val newW = r - l
        val newH = b - t
        val oldW = oldR - oldL
        val oldH = oldB - oldT
        if (newW == oldW && newH == oldH) return
        if (hasRelativePosition()) {
            restoreFloatingPosition(v)
            return
        }
        if (oldW == 0 && oldH == 0 && overlayPrefs.getBoolean("has_custom_position", false)) {
            restoreFloatingPosition(v)
            return
        }
        if (!isHudStyle()) return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity != Gravity.NO_GRAVITY) return
        val parent = v.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return

        val availableWidth = maxOf(0, pw - parent.paddingLeft - parent.paddingRight - lp.rightMargin)
        val availableHeight = maxOf(0, ph - parent.paddingTop - parent.paddingBottom - lp.bottomMargin)
        // A placement change already chose the new position; don't re-anchor it to the old bounds.
        val left = if (l != oldL) lp.leftMargin.coerceIn(0, maxOf(0, availableWidth - newW)) else
            anchoredMargin(oldL, oldR, pw, parent.paddingLeft, parent.paddingRight, availableWidth, newW, lp.leftMargin)
        val top = if (t != oldT) lp.topMargin.coerceIn(0, maxOf(0, availableHeight - newH)) else
            anchoredMargin(oldT, oldB, ph, parent.paddingTop, parent.paddingBottom, availableHeight, newH, lp.topMargin)
        if (left != lp.leftMargin || top != lp.topMargin) {
            val generation = overlayStyleGeneration
            val previousLeft = lp.leftMargin
            val previousTop = lp.topMargin
            v.post {
                if (generation != overlayStyleGeneration || !isHudStyle() || isDraggingPerfOverlay ||
                    currentHudLayout == PerfHudLayout.DOCK || lp.gravity != Gravity.NO_GRAVITY ||
                    lp.leftMargin != previousLeft || lp.topMargin != previousTop
                ) return@post
                lp.leftMargin = left
                lp.topMargin = top
                v.layoutParams = lp
                // 让下次进串流时按当前尺寸恢复，而不是用旧尺寸的左上角
                if (overlayPrefs.getBoolean("has_custom_position", false)) {
                    savePerformanceOverlayPosition(left, top)
                }
            }
        }
    }

    private fun readHudVisibility(): PerfHudVisibility {
        fun enabled(key: String) = PerfOverlayDisplayItemsPreference.isItemEnabled(activity, key)
        return PerfHudVisibility(
            resolution = enabled(PerformanceItem.RESOLUTION.preferenceKey),
            decoder = enabled(PerformanceItem.DECODER.preferenceKey),
            fps = enabled(PerformanceItem.RENDER_FPS.preferenceKey),
            onePercentLow = enabled(PerformanceItem.ONE_PERCENT_LOW.preferenceKey),
            loss = enabled(PerformanceItem.PACKET_LOSS.preferenceKey),
            network = enabled(PerformanceItem.NETWORK_LATENCY.preferenceKey),
            decode = enabled(PerformanceItem.DECODE_LATENCY.preferenceKey),
            host = enabled(PerformanceItem.HOST_LATENCY.preferenceKey),
            battery = enabled(PerformanceItem.BATTERY.preferenceKey),
            clock = enabled(PerformanceItem.CLOCK.preferenceKey),
            health = perfOverlayHealthVisible
        )
    }

    private var lastHudSnapshot: PerfHudSnapshot? = null
    private var hudHintChecked = false

    private fun buildHudStrings(): PerfHudStrings {
        fun s(id: Int) = activity.getString(id)
        return PerfHudStrings(
            onePercentLow = s(R.string.perf_hud_label_one_percent_low),
            latency = s(R.string.perf_hud_label_latency),
            latencyChain = s(R.string.perf_hud_label_latency_chain),
            network = s(R.string.perf_hud_label_network),
            host = s(R.string.perf_hud_label_host),
            decode = s(R.string.perf_hud_label_decode),
            render = s(R.string.perf_hud_label_render),
            loss = s(R.string.perf_hud_label_loss),
            bandwidth = s(R.string.perf_hud_label_bandwidth),
            battery = s(R.string.perf_hud_label_battery),
            decoder = s(R.string.perf_hud_label_decoder),
            resolution = s(R.string.perf_hud_label_resolution),
            clock = s(R.string.perf_hud_label_clock),
            fpsHistory = s(R.string.perf_hud_label_fps_history),
            drainPerHour = s(R.string.perf_hud_drain_per_hour),
            moods = mapOf(
                PerfMood.WARMING_UP to s(R.string.perf_hud_mood_warming_up),
                PerfMood.SMOOTH to s(R.string.perf_hud_mood_smooth),
                PerfMood.FULL_MOON_SMOOTH to s(R.string.perf_hud_mood_full_moon),
                PerfMood.HOT_DECODE to s(R.string.perf_hud_mood_hot_decode),
                PerfMood.LOSSY to s(R.string.perf_hud_mood_lossy),
                PerfMood.FPS_DROP to s(R.string.perf_hud_mood_fps_drop),
                PerfMood.LAGGY_NETWORK to s(R.string.perf_hud_mood_laggy),
                PerfMood.LOW_BATTERY to s(R.string.perf_hud_mood_low_battery)
            )
        )
    }

    private fun refreshHudBattery() {
        if (!hasDisplayableBattery) {
            hudBatteryLevel = -1
            return
        }
        hudBatteryLevel = UiHelper.getBatteryLevel(activity)
        hudCharging = UiHelper.isCharging(activity)
    }

    private var hudClockFormat: SimpleDateFormat? = null

    private fun hudClockFormatter(): SimpleDateFormat {
        val pattern = clockFormat().pattern
        hudClockFormat?.takeIf { it.toPattern() == pattern }?.let { return it }
        return SimpleDateFormat(pattern, Locale.getDefault()).also { hudClockFormat = it }
    }

    private fun buildHudSnapshot(info: PerformanceInfo): PerfHudSnapshot {
        val rtt = (info.rttInfo shr 32).toInt()
        val rttVariance = info.rttInfo.toInt()
        val pyrowave = info.pyrowaveTimingFrames > 0
        val decodeMs = if (pyrowave) info.pyrowaveDecodeTimeMs else info.decodeTimeMs
        val renderMs = if (pyrowave) info.pyrowavePresentTimeMs else info.renderingLatencyMs
        val hostMs = if (info.framesWithHostProcessingLatency > 0) info.aveHostProcessingLatency else null
        val batteryLevel = if (hasDisplayableBattery) hudBatteryLevel else -1
        val drain = if (streamStartTime > 0) {
            PerfHudSnapshot.drainPercentPerHour(
                streamStartBatteryLevel, batteryLevel, System.currentTimeMillis() - streamStartTime, hudCharging
            )
        } else {
            null
        }
        val clock = hudClockFormatter().format(Date())
        val moonIcon = getCurrentMoonPhaseIcon()
        return PerfHudSnapshot(
            width = info.initialWidth,
            height = info.initialHeight,
            targetFps = prefConfig.fps,
            codec = getDecoderTypeInfo(info.decoder).shortName,
            hdrLabel = info.hdrFormat.takeIf { info.isHdrActive }?.displayName,
            decoderName = info.decoder ?: "",
            renderedFps = info.renderedFps,
            receivedFps = info.receivedFps,
            framegenFps = info.framegenFps,
            onePercentLowFps = info.onePercentLowFps,
            packetLossPercent = info.lostFrameRate,
            rttMs = rtt,
            rttVarianceMs = rttVariance,
            bandwidth = info.bandWidth ?: "N/A",
            latency = LatencyChain.estimate(rtt, hostMs, decodeMs, renderMs),
            batteryLevel = batteryLevel,
            charging = hudCharging,
            drainPercentPerHour = drain,
            clock = clock,
            moonIcon = moonIcon,
            fullMoon = currentIsFullMoon
        )
    }

    private fun updateHud(info: PerformanceInfo, recordHistory: Boolean = true) {
        val overlay = performanceOverlayView ?: return
        val hud = hudView ?: return
        val snapshot = buildHudSnapshot(info)
        lastHudSnapshot = snapshot
        hud.setSnapshot(snapshot, recordHistory)
        applyHudPlacement(overlay, force = false)
        if (hud.isLayoutRequested && hasRelativePosition()) restoreFloatingPosition(overlay)
        maybeShowHudHint()
    }

    /** 第一次看到新版 HUD 时用 💡 串流小课堂 提示交互方式，只提示一次。 */
    private fun maybeShowHudHint() {
        if (hudHintChecked || prefConfig.perfOverlayLocked) return
        if (performanceOverlayView?.visibility != View.VISIBLE) return
        hudHintChecked = true
        if (overlayPrefs.getBoolean(KEY_HUD_HINT_SHOWN, false)) return
        overlayPrefs.edit { putBoolean(KEY_HUD_HINT_SHOWN, true) }
        showHudToast(activity.getString(R.string.perf_hud_hint), long = true)
    }

    private fun showHudToast(text: String, long: Boolean = false) {
        Toast.makeText(activity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun handleOverlayTouch(v: View, event: MotionEvent): Boolean {
        val hud = isHudStyle()
        val docked = hud && currentHudLayout == PerfHudLayout.DOCK
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overlayTouchActive = true
                hudTouchX = event.x
                hudTouchY = event.y
                if (docked) {
                    isDraggingPerfOverlay = true
                    perfOverlayStartX = event.rawX
                    perfOverlayStartY = event.rawY
                } else {
                    handleActionDown(v, event)
                }
                val configuration = ViewConfiguration.get(activity)
                gestures.down(
                    event.eventTime, event.rawX, event.rawY, event.x, event.y, configuration.scaledDoubleTapSlop,
                    single = !hud || perfOverlaySingleTapEnabled,
                    double = perfOverlayDoubleTapEnabled,
                    longPress = hud && perfOverlayLongPressEnabled
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!overlayTouchActive) return true
                if (movedBeyondSlop(event)) {
                    gestures.moveBeyondSlop()
                    hudView?.clearPressed()
                }
                if (!docked && isDraggingPerfOverlay) handleActionMove(v, event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!overlayTouchActive) return true
                overlayTouchActive = false
                if (movedBeyondSlop(event)) {
                    gestures.moveBeyondSlop()
                }
                hudView?.clearPressed()
                if (docked) {
                    isDraggingPerfOverlay = false
                } else if (isDraggingPerfOverlay) {
                    handleActionUp(v, event)
                }
                gestures.up(event.eventTime)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelOverlayInteraction()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelOverlayInteraction()
                return true
            }
        }
        return false
    }

    private fun movedBeyondSlop(event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        return abs(event.rawX - perfOverlayStartX) > slop || abs(event.rawY - perfOverlayStartY) > slop
    }

    private fun onHudLongPress() {
        val overlay = performanceOverlayView ?: return
        val hud = hudView ?: return
        if (currentHudLayout != PerfHudLayout.DOCK && isDraggingPerfOverlay && !perfOverlayMoved) {
            restoreOverlayLayout(overlay)
        }
        isDraggingPerfOverlay = false
        hideSnapGuides()
        applyDraggingVisualFeedback(overlay, false)
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val target = hud.targetAt(hudTouchX - hud.left, hudTouchY - hud.top) ?: PerfHudTarget.DIAGNOSIS
        hud.clearPressed()
        overlayPrefs.edit { putBoolean(KEY_HUD_LONG_PRESS_USED, true) }
        showHudTargetInfo(target)
    }

    private fun showHudTargetInfo(target: PerfHudTarget) {
        if (infoSheets.showFor(target)) return
        showManagerTargetInfo(target)
    }

    private fun showManagerTargetInfo(target: PerfHudTarget) {
        when (target) {
            PerfHudTarget.RESOLUTION -> showResolutionInfo()
            PerfHudTarget.DECODER -> showDecoderInfo()
            PerfHudTarget.BATTERY -> showBatteryInfo()
            else -> Unit
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        activity.resources.displayMetrics
    ).toInt()

    companion object {
        private const val DELAYED_SHOW_MS = 1000L
        private const val CLOCK_SECOND_UPDATE_MS = 1000L
        private const val CLOCK_MINUTE_MS = 60_000L
        private const val BATTERY_UPDATE_INTERVAL_MS = 15000L
        private const val OVERLAY_BACKGROUND_RGB = 0x16
        private const val HUD_BASE_TEXT_SP = 11f
        private const val DOCK_SIDE_MARGIN_DP = 10f
        private const val DOCK_GAP_DP = 10f
        private const val HUD_EDGE_INSET_DP = 8f
        private const val SNAP_GUIDE_DISTANCE_DP = 16f
        private const val SNAP_GUIDES_TAG = "performance_overlay_snap_guides"
        private const val KEY_RELATIVE_X = "relative_x"
        private const val KEY_RELATIVE_Y = "relative_y"
        private const val KEY_GUIDE_X = "guide_x"
        private const val KEY_GUIDE_Y = "guide_y"
        private const val FULL_MOON_TOLERANCE = 1.0 / 29.53
        private const val KEY_HUD_HINT_SHOWN = "hud_hint_shown"
        private const val KEY_HUD_LONG_PRESS_USED = "hud_long_press_used"
        private const val KEY_HUD_HEALTH = "hud_health_enabled"
        private const val KEY_HUD_SINGLE_TAP = "hud_single_tap_style_enabled"
        private const val KEY_HUD_DOUBLE_TAP = "hud_double_tap_layout_enabled"
        private const val KEY_HUD_LONG_PRESS = "hud_long_press_info_enabled"
        private const val KEY_LEGACY_ORIENTATION = "layout_orientation"
        private const val KEY_DEFAULT_ORIENTATION = "list_perf_overlay_orientation"

        private val DECODER_TYPE_MAP = mapOf(
            "avc" to DecoderTypeInfo("H.264/AVC", "AVC"),
            "h264" to DecoderTypeInfo("H.264/AVC", "AVC"),
            "hevc" to DecoderTypeInfo("H.265/HEVC", "HEVC"),
            "h265" to DecoderTypeInfo("H.265/HEVC", "HEVC"),
            "av1" to DecoderTypeInfo("AV1", "AV1"),
            "vp9" to DecoderTypeInfo("VP9", "VP9"),
            "vp8" to DecoderTypeInfo("VP8", "VP8")
        )
    }
}

private class PerfOverlaySnapGuides(context: Context, color: Int) : View(context) {
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = 210
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private var guideX: Int? = null
    private var guideY: Int? = null
    private val overlayBounds = Rect()
    private val gap = (resources.displayMetrics.density * 4).roundToInt()

    fun update(x: Int?, y: Int?, left: Int, top: Int, width: Int, height: Int) {
        guideX = x
        guideY = y
        overlayBounds.set(left - gap, top - gap, left + width + gap, top + height + gap)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        guideX?.let {
            canvas.drawLine(it.toFloat(), 0f, it.toFloat(), overlayBounds.top.toFloat(), guidePaint)
            canvas.drawLine(it.toFloat(), overlayBounds.bottom.toFloat(), it.toFloat(), height.toFloat(), guidePaint)
        }
        guideY?.let {
            canvas.drawLine(0f, it.toFloat(), overlayBounds.left.toFloat(), it.toFloat(), guidePaint)
            canvas.drawLine(overlayBounds.right.toFloat(), it.toFloat(), width.toFloat(), it.toFloat(), guidePaint)
        }
    }
}
