package com.limelight

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.ContextCompat
import androidx.core.content.edit

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
import kotlin.math.pow
import kotlin.math.sqrt

class PerformanceOverlayManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
    private val onJitterMonitorChanged: (Boolean) -> Unit = {}
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

    // 点击检测相关
    private var clickStartTime = 0L
    private var clickStartX = 0f
    private var clickStartY = 0f
    private var lastClickTime = 0L
    private var isDoubleClickHandled = false

    // 8个吸附位置的枚举
    private enum class SnapPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

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
    private var hudBatteryLevel = -1
    private var hudCharging = false
    private var hudLongPressFired = false
    private var hudTouchX = 0f
    private var hudTouchY = 0f
    private val hudLongPressRunnable = Runnable { onHudLongPress() }
    /** 按住片刻后高亮手指下的指标，提示继续按住就能看说明。 */
    private val hudPressRunnable = Runnable {
        val hud = hudView ?: return@Runnable
        hud.setPressedAt(hudTouchX - hud.left, hudTouchY - hud.top)
    }
    private val hudAnchorListener = View.OnLayoutChangeListener     { v, l, t, r, b, oL, oT, oR, oB ->
            onHudOverlayResized(v, l, t, r, b, oL, oT, oR, oB)
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
        cancelDelayedShow()
        stopClockUpdates()
        infoSheets.dismiss()
        performanceOverlayView?.visibility = View.GONE
    }

    fun applyRequestedVisibility() {
        val overlay = performanceOverlayView ?: return
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

    fun togglePerformanceOverlay() {
        val overlay = performanceOverlayView ?: return

        cancelDelayedShow()

        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            requestedPerformanceOverlayVisibility = View.GONE
            hasShownPerfOverlay = false
            stopClockUpdates()
            fadeOutAndHide(overlay)
        } else {
            requestedPerformanceOverlayVisibility = View.VISIBLE
            hasShownPerfOverlay = true
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1.0f
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
        cancelDelayedShow()
        stopClockUpdates()
        infoSheets.dismiss()
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

    fun applyOverlayState() {
        cancelDelayedShow()
        val overlay = performanceOverlayView ?: return

        if (!prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.GONE
            hasShownPerfOverlay = false
            stopClockUpdates()
            fadeOutAndHide(overlay)
            return
        }

        requestedPerformanceOverlayVisibility = View.VISIBLE

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
        val anim = AnimationUtils.loadAnimation(activity, R.anim.perf_overlay_fadeout)
        overlay.startAnimation(anim)
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}
            override fun onAnimationEnd(animation: Animation) {
                overlay.visibility = View.GONE
                overlay.alpha = 0.0f
            }
            override fun onAnimationRepeat(animation: Animation) {}
        })
    }

    fun refreshPerformanceOverlayConfig() {
        if (performanceOverlayView != null && requestedPerformanceOverlayVisibility == View.VISIBLE) {
            configureDisplayItems()
            configureTextAlignment()
        }
    }

    fun onConfigurationChanged() {
        if (performanceOverlayView != null) {
            configurePerformanceOverlay()
        }
    }

    fun updatePerformanceInfo(performanceInfo: PerformanceInfo) {
        currentPerformanceInfo = performanceInfo
        updateBandwidthInfo(performanceInfo)

        activity.runOnUiThread {
            showOverlayIfNeeded()
            updateBatteryDisplayIfNeeded()
            // 副屏等外部观察者始终拿到最新快照，即使主屏没有开启 HUD。
            val snapshot = buildHudSnapshot(performanceInfo)
            lastHudSnapshot = snapshot
            snapshotListener?.invoke(snapshot)
            if (isHudStyle()) {
                updateHud(performanceInfo)
            } else {
                updatePerformanceViewsWithStyledText(performanceInfo)
            }
        }
    }

    /** 副屏监控面板订阅性能快照；回调在主线程。传 null 取消订阅。 */
    fun setSnapshotListener(listener: ((PerfHudSnapshot) -> Unit)?) {
        snapshotListener = listener
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
        if (prefConfig.enablePerfOverlay && !hasShownPerfOverlay && performanceOverlayView != null) {
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
                updatePerformanceItemText(itemInfo, performanceInfo)
            }
        }
        configureTextAlignment()
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
    }

    @SuppressLint("RtlHardcoded")
    private fun applyFloatingPosition(layoutParams: FrameLayout.LayoutParams, isPortrait: Boolean) {
        val prefs = overlayPrefs
        val hasCustomPosition = prefs.getBoolean("has_custom_position", false)

        // HUD 自带圆角背景，贴着屏幕边缘时圆角会被切掉，留一点安全边距；横屏顶栏则直接吸顶
        val inset = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
        val topInset = if (isStickyBar()) 0 else inset
        if (isPortrait) {
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
        layoutParams.rightMargin = inset
        layoutParams.bottomMargin = inset
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
        hudView?.let {
            it.visibility = if (hud) View.VISIBLE else View.GONE
            if (hud) it.setItemVisibility(readHudVisibility())
        }
    }

    private fun getOverlayBackgroundColor(): Int {
        val alpha = prefConfig.perfOverlayBgOpacity.coerceIn(0, 100) * 255 / 100
        return Color.argb(alpha, OVERLAY_BACKGROUND_RGB, OVERLAY_BACKGROUND_RGB, OVERLAY_BACKGROUND_RGB)
    }

    private fun configureTextAlignment() {

        val isVertical = isEffectiveVerticalLayout()
        val isRightSide = determineRightSidePosition(isVertical)

        val gravity = if (isVertical && isRightSide)
            Gravity.CENTER_VERTICAL or Gravity.END
        else
            Gravity.CENTER_VERTICAL or Gravity.START

        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                configureTextViewStyle(itemInfo.view!!, gravity, isVertical)
            }
        }
    }

    private fun determineRightSidePosition(isVertical: Boolean): Boolean {
        val prefs = overlayPrefs
        val hasCustomPosition = prefs.getBoolean("has_custom_position", false)

        return if (hasCustomPosition) {
            val viewDimensions = getViewDimensions(performanceOverlayView!!)
            val viewWidth = viewDimensions[0]
            val leftMargin = prefs.getInt("left_margin", 0)
            (leftMargin + viewWidth) > (streamView.width * 2 / 3)
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
            overlay.setOnTouchListener(null)
            overlay.isClickable = false
            overlay.isFocusable = false
            overlay.isLongClickable = false
        } else {
            overlay.isClickable = true
            overlay.isFocusable = true
            overlay.isLongClickable = true

            overlay.setOnTouchListener { v, event ->
                if (isHudStyle()) return@setOnTouchListener handleHudTouch(v, event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> handleActionDown(v, event)
                    MotionEvent.ACTION_MOVE -> handleActionMove(v, event)
                    MotionEvent.ACTION_UP -> handleActionUp(v, event)
                    else -> false
                }
            }
        }
    }

    private fun handleActionDown(v: View, event: MotionEvent): Boolean {
        isDraggingPerfOverlay = true
        perfOverlayStartX = event.rawX
        perfOverlayStartY = event.rawY
        clickStartTime = System.currentTimeMillis()
        clickStartX = event.x
        clickStartY = event.y
        isDoubleClickHandled = false

        val layoutParams = v.layoutParams as FrameLayout.LayoutParams

        if (layoutParams.gravity != Gravity.NO_GRAVITY) {
            convertGravityToMargins(v, layoutParams)
        }

        perfOverlayDeltaX = perfOverlayStartX - layoutParams.leftMargin
        perfOverlayDeltaY = perfOverlayStartY - layoutParams.topMargin

        applyDraggingVisualFeedback(v, true)
        return true
    }

    private fun handleActionMove(v: View, event: MotionEvent): Boolean {
        if (!isDraggingPerfOverlay) return false

        val parentDimensions = getParentDimensions(v)
        val viewDimensions = getViewDimensions(v)
        val parentWidth = parentDimensions[0]
        val parentHeight = parentDimensions[1]
        val viewWidth = viewDimensions[0]
        val viewHeight = viewDimensions[1]

        val layoutParams = v.layoutParams as FrameLayout.LayoutParams
        var newLeftMargin = (event.rawX - perfOverlayDeltaX).toInt()
        var newTopMargin = (event.rawY - perfOverlayDeltaY).toInt()

        newLeftMargin = newLeftMargin.coerceIn(0, parentWidth - viewWidth)
        newTopMargin = newTopMargin.coerceIn(0, parentHeight - viewHeight)

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

        isDraggingPerfOverlay = false
        applyDraggingVisualFeedback(v, false)

        if (isClick(event)) {
            handleClickEvent()
        } else {
            snapToNearestPosition(v)
        }

        return true
    }

    private fun convertGravityToMargins(v: View, layoutParams: FrameLayout.LayoutParams) {
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        v.getLocationInWindow(viewLocation)
        (v.parent as View).getLocationInWindow(parentLocation)

        layoutParams.leftMargin = viewLocation[0] - parentLocation[0]
        layoutParams.topMargin = viewLocation[1] - parentLocation[1]
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

    private fun handleClickEvent() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastClick = currentTime - lastClickTime

        if (timeSinceLastClick < DOUBLE_CLICK_TIMEOUT && lastClickTime > 0L) {
            toggleLayoutOrientation()
            lastClickTime = 0
            isDoubleClickHandled = true
        } else {
            lastClickTime = currentTime
            isDoubleClickHandled = false
            performanceOverlayView?.postDelayed({
                if (!isDoubleClickHandled && lastClickTime > 0L) {
                    lastClickTime = 0
                    if (isHudStyle()) cycleHudDensity() else showClickedItemInfo()
                }
            }, DOUBLE_CLICK_TIMEOUT.toLong())
        }
    }

    private fun showClickedItemInfo() {
        if (isEffectiveVerticalLayout()) {
            showClickedItemInfoVertical()
        } else {
            showClickedItemInfoHorizontal()
        }
    }

    private fun showClickedItemInfoVertical() {
        val overlayHeight = performanceOverlayView?.height ?: return
        if (overlayHeight == 0) return

        val visibleItemCount = getVisibleItemCount()
        if (visibleItemCount == 0) {
            infoSheets.showMoonPhase()
            return
        }

        val itemHeight = overlayHeight / visibleItemCount
        val clickedItemIndex = (clickStartY / itemHeight).toInt()

        showInfoByIndex(clickedItemIndex)
    }

    private fun showClickedItemInfoHorizontal() {
        val overlayWidth = performanceOverlayView?.width ?: return
        if (overlayWidth == 0) return

        val visibleItemCount = getVisibleItemCount()
        if (visibleItemCount == 0) {
            infoSheets.showMoonPhase()
            return
        }

        val clickedItemIndex = findClickedItemByBoundaries()
        showInfoByIndex(clickedItemIndex)
    }

    private fun findClickedItemByBoundaries(): Int {
        var currentIndex = 0
        for (itemInfo in performanceItems) {
            if (itemInfo.isVisible) {
                val viewLocation = IntArray(2)
                itemInfo.view?.getLocationInWindow(viewLocation)

                val overlayLocation = IntArray(2)
                performanceOverlayView?.getLocationInWindow(overlayLocation)

                val viewLeft = viewLocation[0] - overlayLocation[0]
                val viewRight = viewLeft + (itemInfo.view?.width ?: 0)

                if (clickStartX >= viewLeft && clickStartX <= viewRight) {
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
        if (isHudStyle() && currentHudLayout == PerfHudLayout.DOCK) {
            // 停靠卡片没有横竖之分，别偷偷改偏好，告诉用户什么时候生效
            showHudToast(activity.getString(R.string.perf_hud_dock_orientation_hint))
            return
        }
        prefConfig.perfOverlayOrientation = if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL
        } else {
            PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
        }
        saveLayoutOrientation()
        if (isHudStyle()) {
            // 双击总要有可见变化：迷你/详细下横竖排不生效，所以顺带回到标准档
            val styleForced = prefConfig.perfOverlayStyle != PerfHudDensity.STANDARD
            if (styleForced) {
                prefConfig.perfOverlayStyle = PerfHudDensity.STANDARD
                prefConfig.writePreferences(activity)
            }
            val orientationName = activity.getString(
                if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL)
                    R.string.orientation_vertical else R.string.orientation_horizontal
            )
            val label = if (styleForced) "${styleName(PerfHudDensity.STANDARD)} / $orientationName" else orientationName
            refreshOverlayNow()
            showHudToast(activity.getString(R.string.perf_hud_style_changed, label))
            return
        }
        configurePerformanceOverlay()
    }

    private fun saveLayoutOrientation() {
        val prefs = overlayPrefs
        prefs.edit {
            putString("layout_orientation", prefConfig.perfOverlayOrientation.name)
        }
    }

    private fun loadLayoutOrientation() {
        val prefs = overlayPrefs
        val savedOrientation = prefs.getString("layout_orientation", null)

        if (savedOrientation != null) {
            try {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.valueOf(savedOrientation)
            } catch (_: IllegalArgumentException) {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
            }
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        val deltaX = abs(event.rawX - perfOverlayStartX)
        val deltaY = abs(event.rawY - perfOverlayStartY)
        val deltaTime = System.currentTimeMillis() - clickStartTime
        return deltaX < CLICK_THRESHOLD && deltaY < CLICK_THRESHOLD && deltaTime < 500
    }

    private fun snapToNearestPosition(view: View) {
        val parentDimensions = getParentDimensions(view)
        val viewDimensions = getViewDimensions(view)
        val screenWidth = parentDimensions[0]
        val screenHeight = parentDimensions[1]
        val viewWidth = viewDimensions[0]
        val viewHeight = viewDimensions[1]

        val layoutParams = view.layoutParams as FrameLayout.LayoutParams
        val currentX = layoutParams.leftMargin + viewWidth / 2
        val currentY = layoutParams.topMargin + viewHeight / 2

        val snapPositions = arrayOf(
            intArrayOf(viewWidth / 2, viewHeight / 2),
            intArrayOf(screenWidth / 2, viewHeight / 2),
            intArrayOf(screenWidth - viewWidth / 2, viewHeight / 2),
            intArrayOf(viewWidth / 2, screenHeight / 2),
            intArrayOf(screenWidth - viewWidth / 2, screenHeight / 2),
            intArrayOf(viewWidth / 2, screenHeight - viewHeight / 2),
            intArrayOf(screenWidth / 2, screenHeight - viewHeight / 2),
            intArrayOf(screenWidth - viewWidth / 2, screenHeight - viewHeight / 2)
        )

        val positions = SnapPosition.entries.toTypedArray()
        var nearestPosition = SnapPosition.TOP_CENTER
        var minDistance = Double.MAX_VALUE

        for (i in snapPositions.indices) {
            val distance = sqrt(
                (currentX - snapPositions[i][0]).toDouble().pow(2.0) +
                        (currentY - snapPositions[i][1]).toDouble().pow(2.0)
            )
            if (distance < minDistance) {
                minDistance = distance
                nearestPosition = positions[i]
            }
        }

        animateToSnapPosition(view, nearestPosition, screenWidth, screenHeight)
    }

    private fun animateToSnapPosition(view: View, position: SnapPosition, screenWidth: Int, screenHeight: Int) {
        val layoutParams = view.layoutParams as FrameLayout.LayoutParams
        val viewDimensions = getViewDimensions(view)
        val viewWidth = viewDimensions[0]
        val viewHeight = viewDimensions[1]

        val i = if (isHudStyle()) dp(HUD_EDGE_INSET_DP) else 0
        val t = if (isStickyBar()) 0 else i
        val (targetX, targetY) = when (position) {
            SnapPosition.TOP_LEFT -> i to t
            SnapPosition.TOP_CENTER -> (screenWidth - viewWidth) / 2 to t
            SnapPosition.TOP_RIGHT -> screenWidth - viewWidth - i to t
            SnapPosition.CENTER_LEFT -> i to (screenHeight - viewHeight) / 2
            SnapPosition.CENTER_RIGHT -> screenWidth - viewWidth - i to (screenHeight - viewHeight) / 2
            SnapPosition.BOTTOM_LEFT -> i to screenHeight - viewHeight - i
            SnapPosition.BOTTOM_CENTER -> (screenWidth - viewWidth) / 2 to screenHeight - viewHeight - i
            SnapPosition.BOTTOM_RIGHT -> screenWidth - viewWidth - i to screenHeight - viewHeight - i
        }

        view.animate()
            .translationX((targetX - layoutParams.leftMargin).toFloat())
            .translationY((targetY - layoutParams.topMargin).toFloat())
            .setDuration(200)
            .withEndAction {
                layoutParams.leftMargin = targetX
                layoutParams.topMargin = targetY
                view.translationX = 0f
                view.translationY = 0f
                view.layoutParams = layoutParams
                savePerformanceOverlayPosition(targetX, targetY)
                configureTextAlignment()
                refreshHudStick()
            }
            .start()
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

    private fun getParentDimensions(view: View): IntArray {
        val parent = view.parent as View
        return intArrayOf(parent.width, parent.height)
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
        prefConfig.perfOverlayStyle = style
        prefConfig.writePreferences(activity)
        refreshOverlayNow()
        if (announce) {
            var text = activity.getString(R.string.perf_hud_style_changed, styleName(style))
            if (!overlayPrefs.getBoolean(KEY_HUD_LONG_PRESS_USED, false)) {
                text += "\n" + activity.getString(R.string.perf_hud_long_press_hint)
            }
            showHudToast(text)
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
            if (info != null) updateHud(info)
        } else {
            if (info != null) updatePerformanceViewsWithStyledText(info)
            updateBatteryViewIfVisible()
            if (requestedPerformanceOverlayVisibility == View.VISIBLE) startClockUpdates()
        }
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
        if (!isHudStyle() || currentHudLayout == PerfHudLayout.DOCK || isDraggingPerfOverlay) return
        val newW = r - l
        val newH = b - t
        val oldW = oldR - oldL
        val oldH = oldB - oldT
        if (newW == oldW && newH == oldH) return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity != Gravity.NO_GRAVITY) return
        val parent = v.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return

        var left = lp.leftMargin
        var top = lp.topMargin
        if (oldW > 0 && oldL + oldW / 2 > pw / 2) left = oldR - newW
        if (oldH > 0 && oldT + oldH / 2 > ph / 2) top = oldB - newH
        left = left.coerceIn(0, maxOf(0, pw - newW))
        top = top.coerceIn(0, maxOf(0, ph - newH))
        if (left != lp.leftMargin || top != lp.topMargin) {
            v.post {
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
            clock = enabled(PerformanceItem.CLOCK.preferenceKey)
        ).also { hudVisibility = it }
    }

    private var hudVisibility = PerfHudVisibility()
    private var lastHudSnapshot: PerfHudSnapshot? = null
    private var snapshotListener: ((PerfHudSnapshot) -> Unit)? = null
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
        val clock = if (hudVisibility.clock) hudClockFormatter().format(Date()) else null
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

    private fun updateHud(info: PerformanceInfo) {
        val overlay = performanceOverlayView ?: return
        val hud = hudView ?: return
        val snapshot = buildHudSnapshot(info)
        lastHudSnapshot = snapshot
        hud.setSnapshot(snapshot)
        applyHudPlacement(overlay, force = false)
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

    /**
     * HUD 触摸：单击切换信息密度、双击切换横竖排、长按查看指标说明、拖动挪位置（停靠时不可拖）。
     * 单击/双击复用经典样式的 [handleClickEvent]，拖动和吸附复用原有逻辑。
     */
    private fun handleHudTouch(v: View, event: MotionEvent): Boolean {
        val docked = currentHudLayout == PerfHudLayout.DOCK
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hudLongPressFired = false
                hudTouchX = event.x
                hudTouchY = event.y
                if (docked) {
                    isDraggingPerfOverlay = true
                    perfOverlayStartX = event.rawX
                    perfOverlayStartY = event.rawY
                    clickStartTime = System.currentTimeMillis()
                } else {
                    handleActionDown(v, event)
                }
                val longPress = ViewConfiguration.getLongPressTimeout().toLong()
                uiHandler.postDelayed(hudPressRunnable, longPress / 3)
                uiHandler.postDelayed(hudLongPressRunnable, longPress)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hudLongPressFired) return true
                val slop = ViewConfiguration.get(activity).scaledTouchSlop
                if (abs(event.rawX - perfOverlayStartX) > slop || abs(event.rawY - perfOverlayStartY) > slop) {
                    uiHandler.removeCallbacks(hudLongPressRunnable)
                    clearHudPress()
                }
                return if (docked) true else handleActionMove(v, event)
            }
            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(hudLongPressRunnable)
                clearHudPress()
                if (hudLongPressFired) {
                    isDraggingPerfOverlay = false
                    return true
                }
                if (docked) {
                    isDraggingPerfOverlay = false
                    if (isClick(event)) handleClickEvent()
                    return true
                }
                return handleActionUp(v, event)
            }
            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(hudLongPressRunnable)
                clearHudPress()
                val wasDragging = isDraggingPerfOverlay
                isDraggingPerfOverlay = false
                applyDraggingVisualFeedback(v, false)
                if (wasDragging && !docked && !hudLongPressFired) snapToNearestPosition(v)
                return true
            }
        }
        return false
    }

    private fun clearHudPress() {
        uiHandler.removeCallbacks(hudPressRunnable)
        hudView?.clearPressed()
    }

    private fun onHudLongPress() {
        val overlay = performanceOverlayView ?: return
        val hud = hudView ?: return
        hudLongPressFired = true
        isDraggingPerfOverlay = false
        lastClickTime = 0
        applyDraggingVisualFeedback(overlay, false)
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val target = hud.targetAt(hudTouchX - hud.left, hudTouchY - hud.top) ?: PerfHudTarget.DIAGNOSIS
        clearHudPress()
        overlayPrefs.edit { putBoolean(KEY_HUD_LONG_PRESS_USED, true) }
        showHudTargetInfo(target)
    }

    private fun showHudTargetInfo(target: PerfHudTarget) {
        if (infoSheets.showFor(target)) return
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
        private const val CLICK_THRESHOLD = 10
        private const val DOUBLE_CLICK_TIMEOUT = 300
        private const val DELAYED_SHOW_MS = 1000L
        private const val CLOCK_SECOND_UPDATE_MS = 1000L
        private const val CLOCK_MINUTE_MS = 60_000L
        private const val BATTERY_UPDATE_INTERVAL_MS = 15000L
        private const val OVERLAY_BACKGROUND_RGB = 0x16
        private const val HUD_BASE_TEXT_SP = 11f
        private const val DOCK_SIDE_MARGIN_DP = 10f
        private const val DOCK_GAP_DP = 10f
        private const val HUD_EDGE_INSET_DP = 8f
        private const val FULL_MOON_TOLERANCE = 1.0 / 29.53
        private const val KEY_HUD_HINT_SHOWN = "hud_hint_shown"
        private const val KEY_HUD_LONG_PRESS_USED = "hud_long_press_used"

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
