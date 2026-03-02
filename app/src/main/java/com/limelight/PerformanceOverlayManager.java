package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import com.limelight.binding.video.PerformanceInfo;
import com.limelight.preferences.PerfOverlayDisplayItemsPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;
import com.limelight.utils.NetHelper;
import com.limelight.utils.MoonPhaseUtils;
import com.limelight.utils.UiHelper;

public class PerformanceOverlayManager {

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;

    private LinearLayout performanceOverlayView;
    private StreamView streamView;

    private TextView perfResView;
    private TextView perfDecoderView;
    private TextView perfRenderFpsView;
    private TextView networkLatencyView;
    private TextView decodeLatencyView;
    private TextView hostLatencyView;
    private TextView packetLossView;

    private int requestedPerformanceOverlayVisibility = View.GONE;
    private boolean hasShownPerfOverlay = false; // 跟踪性能覆盖层是否已经显示过

    // 性能覆盖层拖动相关
    private boolean isDraggingPerfOverlay = false;
    private float perfOverlayStartX, perfOverlayStartY;
    private float perfOverlayDeltaX, perfOverlayDeltaY;
    private static final int SNAP_THRESHOLD = 100; // 吸附阈值（像素）

    // 点击检测相关
    private static final int CLICK_THRESHOLD = 10; // 点击阈值（像素）
    private static final int DOUBLE_CLICK_TIMEOUT = 300; // 双击超时时间（毫秒）
    private long clickStartTime = 0;
    private float clickStartX, clickStartY; // 记录点击开始位置
    private long lastClickTime = 0; // 上次点击时间
    private boolean isDoubleClickHandled = false; // 标记双击是否已被处理

    // 8个吸附位置的枚举
    private enum SnapPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    // 计算带宽用
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;
    private String lastValidBandwidth = "N/A";

    // 月相缓存
    private String currentMoonPhaseIcon = "🌙";
    private int lastCalculatedDay = -1;

    // 当前性能信息缓存
    private PerformanceInfo currentPerformanceInfo;

    // 实际设备刷新率（从 Game 传递）
    private float actualDisplayRefreshRate = 0.0f;

    // 电量更新相关
    private static final long BATTERY_UPDATE_INTERVAL_MS = 15000; // 15秒更新一次
    private long lastBatteryUpdateTime = 0;

    // 串流电量统计
    private int streamStartBatteryLevel = -1; // 串流开始时的电量
    private long streamStartTime = -1; // 串流开始时间

    /**
     * 性能项目枚举 - 统一管理所有性能指标
     */
    private enum PerformanceItem {
        RESOLUTION(R.id.perfRes, "resolution", "perfResView"),
        DECODER(R.id.perfDecoder, "decoder", "perfDecoderView"),
        RENDER_FPS(R.id.perfRenderFps, "render_fps", "perfRenderFpsView"),
        PACKET_LOSS(R.id.perfPacketLoss, "packet_loss", "packetLossView"),
        NETWORK_LATENCY(R.id.perfNetworkLatency, "network_latency", "networkLatencyView"),
        DECODE_LATENCY(R.id.perfDecodeLatency, "decode_latency", "decodeLatencyView"),
        HOST_LATENCY(R.id.perfHostLatency, "host_latency", "hostLatencyView"),
        BATTERY(R.id.perfBattery, "battery", "perfBatteryView");

        final int viewId;
        final String preferenceKey;
        final String fieldName;

        PerformanceItem(int viewId, String preferenceKey, String fieldName) {
            this.viewId = viewId;
            this.preferenceKey = preferenceKey;
            this.fieldName = fieldName;
        }
    }

    /**
     * 性能项目信息类 - 包含View引用和相关信息
     */
    private static class PerformanceItemInfo {
        final PerformanceItem item;
        final TextView view;
        final Runnable infoMethod;

        PerformanceItemInfo(PerformanceItem item, TextView view, Runnable infoMethod) {
            this.item = item;
            this.view = view;
            this.infoMethod = infoMethod;
        }

        boolean isVisible() {
            return view != null && view.getVisibility() == View.VISIBLE;
        }
    }

    // 性能项目信息数组
    private PerformanceItemInfo[] performanceItems;

    // 解码器类型映射表
    private static final Map<String, DecoderTypeInfo> DECODER_TYPE_MAP = new HashMap<>();

    static {
        // 初始化解码器类型映射
        DECODER_TYPE_MAP.put("avc", new DecoderTypeInfo("H.264/AVC", "AVC"));
        DECODER_TYPE_MAP.put("h264", new DecoderTypeInfo("H.264/AVC", "AVC"));
        DECODER_TYPE_MAP.put("hevc", new DecoderTypeInfo("H.265/HEVC", "HEVC"));
        DECODER_TYPE_MAP.put("h265", new DecoderTypeInfo("H.265/HEVC", "HEVC"));
        DECODER_TYPE_MAP.put("av1", new DecoderTypeInfo("AV1", "AV1"));
        DECODER_TYPE_MAP.put("vp9", new DecoderTypeInfo("VP9", "VP9"));
        DECODER_TYPE_MAP.put("vp8", new DecoderTypeInfo("VP8", "VP8"));
    }

    // 解码器类型信息类
    private static class DecoderTypeInfo {
        final String fullName;
        final String shortName;

        DecoderTypeInfo(String fullName, String shortName) {
            this.fullName = fullName;
            this.shortName = shortName;
        }
    }

    public PerformanceOverlayManager(Activity activity, PreferenceConfiguration prefConfig) {
        this.activity = activity;
        this.prefConfig = prefConfig;
    }

    /**
     * 初始化性能覆盖层
     */
    public void initialize() {
        performanceOverlayView = activity.findViewById(R.id.performanceOverlay);
        streamView = activity.findViewById(R.id.surfaceView);

        // 初始化性能项目信息
        initializePerformanceItems();

        // 加载保存的布局方向设置
        loadLayoutOrientation();

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            // 初始状态下设置为不可见，等待性能数据更新时再显示
            if (performanceOverlayView != null) {
                performanceOverlayView.setVisibility(View.GONE);
                performanceOverlayView.setAlpha(0.0f);
            }
        }
        // 配置性能覆盖层的方向和位置
        configurePerformanceOverlay();

        // 强制应用一次当前的配置（为了处理初始就是 锁定 的情况）
        setupPerformanceOverlayDragging();

        // 记录串流开始时的电量信息
        recordStreamStart();
    }

    /**
     * 初始化性能项目信息数组
     */
    private void initializePerformanceItems() {
        performanceItems = new PerformanceItemInfo[PerformanceItem.values().length];

        for (int i = 0; i < PerformanceItem.values().length; i++) {
            PerformanceItem item = PerformanceItem.values()[i];
            TextView view = activity.findViewById(item.viewId);
            Runnable infoMethod = getInfoMethodForItem(item);

            performanceItems[i] = new PerformanceItemInfo(item, view, infoMethod);
        }
    }

    /**
     * 根据性能项目获取对应的信息显示方法
     */
    private Runnable getInfoMethodForItem(PerformanceItem item) {
        switch (item) {
            case RESOLUTION: return this::showResolutionInfo;
            case DECODER: return this::showDecoderInfo;
            case RENDER_FPS: return this::showFpsInfo;
            case PACKET_LOSS: return this::showPacketLossInfo;
            case NETWORK_LATENCY: return this::showNetworkLatencyInfo;
            case DECODE_LATENCY: return this::showDecodeLatencyInfo;
            case HOST_LATENCY: return this::showHostLatencyInfo;
            case BATTERY: return this::showBatteryInfo;
            default: return this::showMoonPhaseInfo;
        }
    }

    /** 隐藏覆盖层（立即） */
    public void hideOverlayImmediate() {
        if (performanceOverlayView != null) {
            performanceOverlayView.setVisibility(View.GONE);
        }
    }

    /** 应用当前请求的可见性到视图 */
    public void applyRequestedVisibility() {
        if (performanceOverlayView != null) {
            performanceOverlayView.setVisibility(requestedPerformanceOverlayVisibility);
        }
    }

    /** 覆盖层是否可见 */
    public boolean isPerfOverlayVisible() {
        return requestedPerformanceOverlayVisibility == View.VISIBLE;
    }

    /** 切换覆盖层显示/隐藏 */
    public void togglePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }

        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            // 隐藏性能覆盖层 - 使用淡出动画
            requestedPerformanceOverlayVisibility = View.GONE;
            hasShownPerfOverlay = false; // 重置显示状态
            Animation fadeOutAnimation = AnimationUtils.loadAnimation(activity, R.anim.perf_overlay_fadeout);
            performanceOverlayView.startAnimation(fadeOutAnimation);
            fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    performanceOverlayView.setVisibility(View.GONE);
                    performanceOverlayView.setAlpha(0.0f);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
        } else {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            hasShownPerfOverlay = true; // 标记为已显示，避免重复动画
            performanceOverlayView.setVisibility(View.VISIBLE);
            performanceOverlayView.setAlpha(1.0f);
        }
    }

    public void applyOverlayState() {
        if (performanceOverlayView == null) return;

        // ------------------------------------------------
        // 情况 A: 需要隐藏 (enablePerfOverlay == false)
        // ------------------------------------------------
        if (!prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.GONE;
            hasShownPerfOverlay = false;

            // 执行淡出动画
            Animation fadeOutAnimation = AnimationUtils.loadAnimation(activity, R.anim.perf_overlay_fadeout);
            performanceOverlayView.startAnimation(fadeOutAnimation);
            fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}
                @Override
                public void onAnimationEnd(Animation animation) {
                    performanceOverlayView.setVisibility(View.GONE);
                    performanceOverlayView.setAlpha(0.0f);
                }
                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            return;
        }

        // ------------------------------------------------
        // 情况 B: 需要显示 (enablePerfOverlay == true)
        // ------------------------------------------------
        requestedPerformanceOverlayVisibility = View.VISIBLE;

        // 如果之前是隐藏的，现在显示出来
        if (performanceOverlayView.getVisibility() != View.VISIBLE || performanceOverlayView.getAlpha() < 1.0f) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            performanceOverlayView.setAlpha(1.0f);
            hasShownPerfOverlay = true;
        }

        setupPerformanceOverlayDragging();
    }

    /** 刷新覆盖层配置（显示项与对齐） */
    public void refreshPerformanceOverlayConfig() {
        if (performanceOverlayView != null && requestedPerformanceOverlayVisibility == View.VISIBLE) {
            configureDisplayItems();
            configureTextAlignment();
        }
    }

    /**
     * 更新性能信息（带宽、丢包、延迟等）并刷新文案
     */
    public void updatePerformanceInfo(final PerformanceInfo performanceInfo) {
        // 保存当前性能信息，用于弹窗显示
        currentPerformanceInfo = performanceInfo;

        // 计算带宽信息
        updateBandwidthInfo(performanceInfo);

        // 在UI线程中更新显示
        activity.runOnUiThread(() -> {
            showOverlayIfNeeded();
            updatePerformanceViewsWithStyledText(performanceInfo);
            // 检查是否需要更新电量（每15秒更新一次）
            updateBatteryDisplayIfNeeded();
        });
    }

    /**
     * 设置实际设备刷新率
     * @param refreshRate 实际设备刷新率（Hz）
     */
    public void setActualDisplayRefreshRate(float refreshRate) {
        this.actualDisplayRefreshRate = refreshRate;
    }

    /**
     * 记录串流开始时的电量和时间
     */
    public void recordStreamStart() {
        streamStartBatteryLevel = UiHelper.getBatteryLevel(activity);
        streamStartTime = System.currentTimeMillis();
        lastBatteryUpdateTime = streamStartTime;
        // 立即更新一次电量显示
        activity.runOnUiThread(this::updateBatteryViewIfVisible);
    }

    /**
     * 检查并更新电量显示（如果距离上次更新超过15秒）
     */
    private void updateBatteryDisplayIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBatteryUpdateTime >= BATTERY_UPDATE_INTERVAL_MS) {
            lastBatteryUpdateTime = currentTime;
            updateBatteryViewIfVisible();
        }
    }

    private void updateBatteryViewIfVisible() {
        TextView batteryView = getPerformanceItemView(PerformanceItem.BATTERY);
        if (batteryView != null && batteryView.getVisibility() == View.VISIBLE) {
            updateBatteryText(batteryView);
        }
    }

    /**
     * 更新带宽信息
     */
    private void updateBandwidthInfo(PerformanceInfo performanceInfo) {
        long currentRxBytes = TrafficStats.getTotalRxBytes();
        long timeMillis = System.currentTimeMillis();
        long timeMillisInterval = timeMillis - previousTimeMillis;

        String calculatedBandwidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);

        // 如果时间间隔过长，使用上次有效带宽
        if (timeMillisInterval > 5000) {
            performanceInfo.bandWidth = lastValidBandwidth != null ? lastValidBandwidth : "N/A";
            previousTimeMillis = timeMillis;
            previousRxBytes = currentRxBytes;
            return;
        }

        // 检查计算出的带宽是否可靠
        if (!calculatedBandwidth.equals("0 K/s")) {
            performanceInfo.bandWidth = calculatedBandwidth;
            lastValidBandwidth = calculatedBandwidth;
            // 只有带宽数据可靠时才更新时间戳
            previousTimeMillis = timeMillis;
        } else {
            // 带宽数据不可靠，使用上次有效值
            performanceInfo.bandWidth = lastValidBandwidth != null ? lastValidBandwidth : "N/A";
        }

        // 无论带宽数据是否可靠，都要更新 previousRxBytes 用于下次计算
        previousRxBytes = currentRxBytes;
    }

    /**
     * 构建解码器信息字符串
     */
    private String buildDecoderInfo(PerformanceInfo performanceInfo) {
        DecoderTypeInfo decoderTypeInfo = getDecoderTypeInfo(performanceInfo.decoder);
        String decoderInfo = decoderTypeInfo.shortName;

        // 基于实际HDR激活状态而不是配置
        if (performanceInfo.isHdrActive) {
            decoderInfo += " HDR";
        }
        return decoderInfo;
    }

    /**
     * 获取当前月相图标
     * 基于真实的天文月相计算，带缓存优化
     */
    private String getCurrentMoonPhaseIcon() {
        Calendar now = Calendar.getInstance(TimeZone.getDefault());
        int currentDay = now.get(Calendar.DAY_OF_YEAR);

        // 如果是同一天，使用缓存的图标
        if (currentDay == lastCalculatedDay) {
            return currentMoonPhaseIcon;
        }

        // 计算月相
        currentMoonPhaseIcon = MoonPhaseUtils.getMoonPhaseIcon(MoonPhaseUtils.getCurrentMoonPhase());
        lastCalculatedDay = currentDay;

        return currentMoonPhaseIcon;
    }


    /**
     * 如果需要则显示覆盖层
     */
    private void showOverlayIfNeeded() {
        // 只有当 enabled 为 true 且还没显示过时才执行
        if (prefConfig.enablePerfOverlay && !hasShownPerfOverlay && performanceOverlayView != null) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            performanceOverlayView.setAlpha(1.0f);
            hasShownPerfOverlay = true;
            // 确保由于自动显示时，触摸状态是正确的
            setupPerformanceOverlayDragging();
        }
    }

    private SpannableString createStyledText(String icon, String value, String unit, Integer valueColor) {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        // 添加图标（使用标题样式）
        if (icon != null && !icon.isEmpty()) {
            int iconStart = builder.length();
            builder.append(icon);
            builder.setSpan(new StyleSpan(Typeface.BOLD), iconStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.1f), iconStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append(" ");
        }

        // 添加数值（使用中等粗细样式）
        if (value != null && !value.isEmpty()) {
            int valueStart = builder.length();
            builder.append(value);
            builder.setSpan(new TypefaceSpan("sans-serif-medium"), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.0f), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (valueColor != null) {
                builder.setSpan(new ForegroundColorSpan(valueColor), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // 添加单位（使用细体样式）
        if (unit != null && !unit.isEmpty()) {
            builder.append(" ");
            int unitStart = builder.length();
            builder.append(unit);
            builder.setSpan(new TypefaceSpan("sans-serif-light"), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(0.9f), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(0xCCFFFFFF), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return new SpannableString(builder);
    }

    private void updatePerformanceViewsWithStyledText(PerformanceInfo performanceInfo) {
        // 更新所有可见的性能项目
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.isVisible()) {
                updatePerformanceItemText(itemInfo, performanceInfo);
            }
        }

        configureTextAlignment();
    }

    /**
     * 更新单个性能项目的文本
     */
    private void updatePerformanceItemText(PerformanceItemInfo itemInfo, PerformanceInfo performanceInfo) {
        switch (itemInfo.item) {
            case RESOLUTION:
                updateResolutionText(itemInfo.view, performanceInfo);
                break;
            case DECODER:
                updateDecoderText(itemInfo.view, performanceInfo);
                break;
            case RENDER_FPS:
                updateRenderFpsText(itemInfo.view, performanceInfo);
                break;
            case PACKET_LOSS:
                updatePacketLossText(itemInfo.view, performanceInfo);
                break;
            case NETWORK_LATENCY:
                updateNetworkLatencyText(itemInfo.view, performanceInfo);
                break;
            case DECODE_LATENCY:
                updateDecodeLatencyText(itemInfo.view, performanceInfo);
                break;
            case HOST_LATENCY:
                updateHostLatencyText(itemInfo.view, performanceInfo);
                break;
            case BATTERY:
                updateBatteryText(itemInfo.view);
                break;
        }
    }

    private void updateResolutionText(TextView view, PerformanceInfo performanceInfo) {
        @SuppressLint("DefaultLocale") String resValue = String.format("%dx%d@%.0f",
            performanceInfo.initialWidth, performanceInfo.initialHeight, performanceInfo.totalFps);
        String moonIcon = getCurrentMoonPhaseIcon();
        view.setText(createStyledText(moonIcon, resValue, "", null));
    }

    private void updateDecoderText(TextView view, PerformanceInfo performanceInfo) {
        String decoderInfo = buildDecoderInfo(performanceInfo);
        view.setText(createStyledText("", decoderInfo, "", null));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    private void updateRenderFpsText(TextView view, PerformanceInfo performanceInfo) {
        @SuppressLint("DefaultLocale") String fpsValue = String.format("Rx %.0f / Rd %.0f",
            performanceInfo.receivedFps, performanceInfo.renderedFps);
        view.setText(createStyledText("", fpsValue, "FPS", 0xFF0DDAF4));
    }

    private void updatePacketLossText(TextView view, PerformanceInfo performanceInfo) {
        @SuppressLint("DefaultLocale") String lossValue = String.format("%.2f", performanceInfo.lostFrameRate);
        int lossColor = performanceInfo.lostFrameRate < 5.0f ? 0xFF7D9D7D : 0xFFB57D7D;
        view.setText(createStyledText("📶", lossValue, "%", lossColor));
    }

    private void updateNetworkLatencyText(TextView view, PerformanceInfo performanceInfo) {
        boolean showPacketLoss = getPerformanceItemView(PerformanceItem.PACKET_LOSS) != null &&
                                getPerformanceItemView(PerformanceItem.PACKET_LOSS).getVisibility() == View.VISIBLE;
        String icon = showPacketLoss ? "" : "🌐";
        @SuppressLint("DefaultLocale") String bandwidthAndLatency = String.format("%s   %d ± %d",
            performanceInfo.bandWidth,
            (int) (performanceInfo.rttInfo >> 32),
            (int) performanceInfo.rttInfo);
        view.setText(createStyledText(icon, bandwidthAndLatency, "ms", 0xFFBCEDD3));
    }

    private void updateDecodeLatencyText(TextView view, PerformanceInfo performanceInfo) {
        String icon = performanceInfo.decodeTimeMs < 15 ? "⏱️" : "🥵";
        @SuppressLint("DefaultLocale") String latencyValue = String.format("%.2f", performanceInfo.decodeTimeMs);
        view.setText(createStyledText(icon, latencyValue, "ms", 0xFFD597E3));
    }

    private void updateHostLatencyText(TextView view, PerformanceInfo performanceInfo) {
        if (performanceInfo.framesWithHostProcessingLatency > 0) {
            @SuppressLint("DefaultLocale") String latencyValue = String.format("%.1f", performanceInfo.aveHostProcessingLatency);
            view.setText(createStyledText("🖥", latencyValue, "ms", 0xFF009688));
        } else {
            view.setText(createStyledText("🧋", "Ver.V+", "", 0xFF009688));
        }
    }

    private void updateBatteryText(TextView view) {
        int batteryLevel = UiHelper.getBatteryLevel(activity);
        String batteryText;
        int batteryColor;

        if (batteryLevel > 50) {
            batteryText = String.valueOf(batteryLevel);
            batteryColor = 0xFF90EE90; // 浅绿色 - 电量充足
        } else if (batteryLevel > 20) {
            batteryText = String.valueOf(batteryLevel);
            batteryColor = 0xFFFFA500; // 橙色 - 电量偏低
        } else {
            batteryText = String.valueOf(batteryLevel);
            batteryColor = 0xFFFF6B6B; // 红色 - 电量严重不足
        }

        view.setText(createStyledText("🔋", batteryText, "%", batteryColor));
    }

    /**
     * 显示电池信息对话框
     */
    private void showBatteryInfo() {
        int batteryLevel = UiHelper.getBatteryLevel(activity);
        boolean isCharging = UiHelper.isCharging(activity);
        String status = activity.getString(batteryLevel > 50 ? R.string.perf_battery_status_sufficient
                : batteryLevel > 20 ? R.string.perf_battery_status_low
                : R.string.perf_battery_status_critical);

        StringBuilder info = new StringBuilder();
        info.append(activity.getString(R.string.perf_battery_info_content, batteryLevel, status));

        boolean hasStreamData = streamStartBatteryLevel >= 0 && streamStartTime > 0;
        long streamDurationSeconds = hasStreamData ? (System.currentTimeMillis() - streamStartTime) / 1000 : 0;

        if (isCharging) {
            info.append("\n\n⚡ 设备正在充电中");
            if (hasStreamData) {
                info.append("\n串流时长: ").append(formatDuration(streamDurationSeconds));
            }
        } else if (hasStreamData) {
            int batteryConsumed = streamStartBatteryLevel - batteryLevel;
            info.append("\n\n本次串流已消耗电量: ")
                .append(batteryConsumed > 0 ? batteryConsumed + "%" : "0%")
                .append("\n串流时长: ")
                .append(formatDuration(streamDurationSeconds));

            if (batteryConsumed > 0 && streamDurationSeconds > 0) {
                double consumedPerMinute = (double) batteryConsumed / (streamDurationSeconds / 60.0);
                if (consumedPerMinute > 0) {
                    long remainingMinutes = (long) (batteryLevel / consumedPerMinute);
                    info.append("\n预计还可续航: ").append(formatDuration(remainingMinutes * 60));
                }
            }
        }

        showInfoDialog(activity.getString(R.string.perf_battery_info_title), info.toString());
    }

    /**
     * 格式化时长（秒）为可读字符串
     */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        }

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        if (hours > 0) {
            return minutes > 0 ? hours + "小时" + minutes + "分钟" : hours + "小时";
        }
        return remainingSeconds > 0 ? minutes + "分" + remainingSeconds + "秒" : minutes + "分钟";
    }

    /**
     * 获取指定性能项目的View
     */
    private TextView getPerformanceItemView(PerformanceItem item) {
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.item == item) {
                return itemInfo.view;
            }
        }
        return null;
    }

    /**
     * 判断当前是否应使用垂直布局。
     * 竖屏时强制垂直布局，横屏时遵循用户设置。
     */
    private boolean isEffectiveVerticalLayout() {
        boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (isPortrait) {
            return true;
        }
        return prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
    }

    private void configurePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) performanceOverlayView.getLayoutParams();

        // 设置方向：竖屏时强制垂直布局
        boolean isPortrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (isEffectiveVerticalLayout()) {
            performanceOverlayView.setOrientation(LinearLayout.VERTICAL);
            performanceOverlayView.setBackgroundColor(activity.getResources().getColor(R.color.overlay_background_vertical));
        } else {
            performanceOverlayView.setOrientation(LinearLayout.HORIZONTAL);
            performanceOverlayView.setBackgroundColor(activity.getResources().getColor(R.color.overlay_background_horizontal));
        }

        // 根据用户配置显示/隐藏特定的性能指标
        configureDisplayItems();

        // 从SharedPreferences读取保存的位置
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        boolean hasCustomPosition = prefs.getBoolean("has_custom_position", false);

        if (isPortrait) {
            // 竖屏时强制右上角
            layoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
            layoutParams.leftMargin = 0;
            layoutParams.topMargin = 0;
        } else if (hasCustomPosition) {
            // 使用自定义位置
            layoutParams.gravity = Gravity.NO_GRAVITY;
            layoutParams.leftMargin = prefs.getInt("left_margin", 0);
            layoutParams.topMargin = prefs.getInt("top_margin", 0);
        } else {
            // 使用预设位置
            switch (prefConfig.perfOverlayPosition) {
                case TOP:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    break;
                case BOTTOM:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    break;
                case TOP_LEFT:
                    layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
                    break;
                case TOP_RIGHT:
                    layoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
                    break;
                case BOTTOM_LEFT:
                    layoutParams.gravity = Gravity.LEFT | Gravity.BOTTOM;
                    break;
                case BOTTOM_RIGHT:
                    layoutParams.gravity = Gravity.RIGHT | Gravity.BOTTOM;
                    break;
                default:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    break;
            }
            // 清除自定义边距
            layoutParams.leftMargin = 0;
            layoutParams.topMargin = 0;
        }
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;

        performanceOverlayView.setLayoutParams(layoutParams);

        // 根据位置和方向调整文字对齐（延迟执行确保View已测量）
        performanceOverlayView.post(this::configureTextAlignment);

        // 设置拖动监听器
        setupPerformanceOverlayDragging();
    }

    private void configureDisplayItems() {
        // 根据用户配置显示/隐藏特定的性能指标
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.view != null) {
                boolean isEnabled = PerfOverlayDisplayItemsPreference.isItemEnabled(activity, itemInfo.item.preferenceKey);
                itemInfo.view.setVisibility(isEnabled ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void configureTextAlignment() {
        if (performanceOverlayView == null) {
            return;
        }

        boolean isVertical = isEffectiveVerticalLayout();
        boolean isRightSide = determineRightSidePosition(isVertical);

        // 只在垂直布局且位置在右侧时，将文字设置为右对齐
        // 注意：需要保持 center_vertical 以确保文字垂直居中
        int gravity = (isVertical && isRightSide) ?
            (android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END) :
            (android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);

        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.isVisible()) {
                configureTextViewStyle(itemInfo.view, gravity, isVertical);
            }
        }
    }

    /**
     * 判断性能覆盖层是否位于右侧
     */
    private boolean determineRightSidePosition(boolean isVertical) {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        boolean hasCustomPosition = prefs.getBoolean("has_custom_position", false);

        if (hasCustomPosition) {
            // 自定义位置：检查是否接近右侧
            int[] viewDimensions = getViewDimensions(performanceOverlayView);
            int viewWidth = viewDimensions[0];
            int leftMargin = prefs.getInt("left_margin", 0);

            // 如果距离右边缘小于屏幕宽度的1/3，认为是右侧
            return (leftMargin + viewWidth) > (streamView.getWidth() * 2 / 3);
        } else {
            // 预设位置：检查是否为右侧位置
            return prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.TOP_RIGHT ||
                   prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT;
        }
    }

    private void configureTextViewStyle(TextView textView, int gravity, boolean isVertical) {
        // 设置文字对齐方式
        textView.setGravity(gravity);

        // 设置基础字体属性
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textView.setLetterSpacing(0.02f);
        textView.setIncludeFontPadding(false);

        // 根据布局方向设置阴影效果
        if (isVertical) {
            // 竖屏时添加字体阴影，提高可读性
            textView.setShadowLayer(2.5f, 1.0f, 1.0f, 0x80000000);
        } else {
            // 横屏时使用较轻的阴影
            textView.setShadowLayer(1.5f, 0.5f, 0.5f, 0x60000000);
        }

        // 计算 PPI 自适应字体大小
        float titleSize = getAdaptiveTextSizePx(11f);
        float bodySize = getAdaptiveTextSizePx(10f);

        // 根据TextView的ID设置特定的字体样式
        int viewId = textView.getId();
        if (viewId == R.id.perfRes) {
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize);
        } else if (viewId == R.id.perfDecoder) {
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfRenderFps) {
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfPacketLoss) {
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfNetworkLatency) {
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfDecodeLatency) {
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfHostLatency) {
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        } else if (viewId == R.id.perfBattery) {
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize);
        }
    }

    /**
     * 根据屏幕 PPI 计算自适应字体大小（像素）。
     * 使用屏幕短边像素数作为参考，使覆盖层在不同 PPI 设备上占据一致的屏幕比例。
     * 参考基准: 1080p 屏幕，10sp ≈ 屏幕短边的 ~1.5%
     */
    private float getAdaptiveTextSizePx(float baseSizeSp) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);

        // 参考基准：1080px 短边，10sp → 10 * 2.625(xxhdpi) ≈ 26px
        // 公式：targetPx = shortSide * (baseSizeSp / 10) * (26 / 1080)
        // 简化：targetPx = shortSide * baseSizeSp * 0.0024f
        float targetPx = shortSide * baseSizeSp * 0.0024f;

        // 限制范围：最小 8px，最大 40px
        return Math.max(8f, Math.min(targetPx, 40f));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPerformanceOverlayDragging() {
        if (performanceOverlayView == null) {
            return;
        }

        // 检查新的参数：是否锁定？
        if (prefConfig.perfOverlayLocked) {
            // =========================
            // 模式：固定 (Locked)
            // =========================
            // 1. 移除监听器
            performanceOverlayView.setOnTouchListener(null);

            // 2. 设置不可点击，让事件穿透到底层游戏画面
            performanceOverlayView.setClickable(false);
            performanceOverlayView.setFocusable(false);
            performanceOverlayView.setLongClickable(false);

        } else {
            // =========================
            // 模式：悬浮 (Floating)
            // =========================
            // 1. 恢复可点击
            performanceOverlayView.setClickable(true);
            performanceOverlayView.setFocusable(true);
            performanceOverlayView.setLongClickable(true);

            // 2. 绑定正常的拖动/点击逻辑
            performanceOverlayView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        return handleActionDown(v, event);
                    case MotionEvent.ACTION_MOVE:
                        return handleActionMove(v, event);
                    case MotionEvent.ACTION_UP:
                        return handleActionUp(v, event);
                }
                return false;
            });
        }
    }
    /**
     * 处理触摸按下事件
     */
    private boolean handleActionDown(View v, MotionEvent event) {
        isDraggingPerfOverlay = true;
        perfOverlayStartX = event.getRawX();
        perfOverlayStartY = event.getRawY();
        clickStartTime = System.currentTimeMillis();
        clickStartX = event.getX();
        clickStartY = event.getY();
        isDoubleClickHandled = false;

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();

        // 如果使用预设位置（gravity不为NO_GRAVITY），需要转换为实际坐标
        if (layoutParams.gravity != Gravity.NO_GRAVITY) {
            convertGravityToMargins(v, layoutParams);
        }

        perfOverlayDeltaX = perfOverlayStartX - layoutParams.leftMargin;
        perfOverlayDeltaY = perfOverlayStartY - layoutParams.topMargin;

        // 添加视觉反馈：降低透明度表示正在拖动
        applyDraggingVisualFeedback(v, true);
        return true;
    }

    /**
     * 处理触摸移动事件
     */
    private boolean handleActionMove(View v, MotionEvent event) {
        if (!isDraggingPerfOverlay) {
            return false;
        }

        // 获取父容器和View的尺寸
        int[] parentDimensions = getParentDimensions(v);
        int[] viewDimensions = getViewDimensions(v);
        int parentWidth = parentDimensions[0];
        int parentHeight = parentDimensions[1];
        int viewWidth = viewDimensions[0];
        int viewHeight = viewDimensions[1];

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();
        int newLeftMargin = (int) (event.getRawX() - perfOverlayDeltaX);
        int newTopMargin = (int) (event.getRawY() - perfOverlayDeltaY);

        // 边界检查，防止移出屏幕
        newLeftMargin = Math.max(0, Math.min(newLeftMargin, parentWidth - viewWidth));
        newTopMargin = Math.max(0, Math.min(newTopMargin, parentHeight - viewHeight));

        layoutParams.leftMargin = newLeftMargin;
        layoutParams.topMargin = newTopMargin;
        layoutParams.gravity = Gravity.NO_GRAVITY;
        v.setLayoutParams(layoutParams);

        // 拖动过程中实时更新文字对齐
        configureTextAlignment();
        return true;
    }

    /**
     * 处理触摸抬起事件
     */
    private boolean handleActionUp(View v, MotionEvent event) {
        if (!isDraggingPerfOverlay) {
            return false;
        }

        isDraggingPerfOverlay = false;
        applyDraggingVisualFeedback(v, false);

        // 检测是否为点击事件
        if (isClick(event)) {
            handleClickEvent();
        } else {
            snapToNearestPosition(v);
        }

        return true;
    }

    /**
     * 将预设位置转换为实际边距
     */
    private void convertGravityToMargins(View v, FrameLayout.LayoutParams layoutParams) {
        int[] viewLocation = new int[2];
        int[] parentLocation = new int[2];
        v.getLocationInWindow(viewLocation);
        ((View) v.getParent()).getLocationInWindow(parentLocation);

        layoutParams.leftMargin = viewLocation[0] - parentLocation[0];
        layoutParams.topMargin = viewLocation[1] - parentLocation[1];
        layoutParams.gravity = Gravity.NO_GRAVITY;
        v.setLayoutParams(layoutParams);
    }

    /**
     * 应用拖动视觉反馈效果
     */
    private void applyDraggingVisualFeedback(View v, boolean isDragging) {
        if (isDragging) {
            v.setAlpha(0.7f);
            v.setScaleX(1.05f);
            v.setScaleY(1.05f);
        } else {
            v.setAlpha(1.0f);
            v.setScaleX(1.0f);
            v.setScaleY(1.0f);
        }
    }

    /**
     * 处理点击事件（单击和双击）
     */
    private void handleClickEvent() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastClick = currentTime - lastClickTime;

        if (timeSinceLastClick < DOUBLE_CLICK_TIMEOUT && lastClickTime > 0) {
            // 双击：切换布局
            toggleLayoutOrientation();
            lastClickTime = 0;
            isDoubleClickHandled = true;
        } else {
            // 单击：延迟显示项目信息，等待确认不是双击
            lastClickTime = currentTime;
            isDoubleClickHandled = false;
            performanceOverlayView.postDelayed(() -> {
                if (!isDoubleClickHandled && lastClickTime > 0) {
                    showClickedItemInfo();
                }
            }, DOUBLE_CLICK_TIMEOUT);
        }
    }

    /**
     * 根据点击位置显示对应项目的信息
     */
    private void showClickedItemInfo() {
        if (isEffectiveVerticalLayout()) {
            showClickedItemInfoVertical();
        } else {
            showClickedItemInfoHorizontal();
        }
    }

    /**
     * 垂直布局的点击检测
     */
    private void showClickedItemInfoVertical() {
        // 获取覆盖层高度和可见项目数量
        int overlayHeight = performanceOverlayView.getHeight();
        if (overlayHeight == 0) return;

        // 计算每个项目的平均高度
        int visibleItemCount = getVisibleItemCount();
        if (visibleItemCount == 0) {
            showMoonPhaseInfo(); // 默认显示月相信息
            return;
        }

        int itemHeight = overlayHeight / visibleItemCount;
        int clickedItemIndex = (int) (clickStartY / itemHeight);

        // 根据索引显示对应信息
        showInfoByIndex(clickedItemIndex);
    }

    /**
     * 水平布局的点击检测
     */
    private void showClickedItemInfoHorizontal() {
        // 获取覆盖层宽度
        int overlayWidth = performanceOverlayView.getWidth();
        if (overlayWidth == 0) return;

        // 获取可见项目数量
        int visibleItemCount = getVisibleItemCount();
        if (visibleItemCount == 0) {
            showMoonPhaseInfo(); // 默认显示月相信息
            return;
        }

        // 使用实际View边界进行点击检测
        int clickedItemIndex = findClickedItemByBoundaries();

        // 根据索引显示对应信息
        showInfoByIndex(clickedItemIndex);
    }

    /**
     * 基于实际View边界查找被点击的项目
     */
    private int findClickedItemByBoundaries() {
        int currentIndex = 0;
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.isVisible()) {
                // 获取View在父容器中的位置
                int[] viewLocation = new int[2];
                itemInfo.view.getLocationInWindow(viewLocation);

                // 获取覆盖层在父容器中的位置
                int[] overlayLocation = new int[2];
                performanceOverlayView.getLocationInWindow(overlayLocation);

                // 计算View相对于覆盖层的边界
                int viewLeft = viewLocation[0] - overlayLocation[0];
                int viewRight = viewLeft + itemInfo.view.getWidth();

                // 检查点击位置是否在此View的边界内
                if (clickStartX >= viewLeft && clickStartX <= viewRight) {
                    return currentIndex;
                }

                currentIndex++;
            }
        }

        return -1;
    }

    /**
     * 获取可见项目的数量
     */
    private int getVisibleItemCount() {
        int count = 0;
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.isVisible()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 根据项目索引显示对应信息
     */
    private void showInfoByIndex(int index) {
        int currentIndex = 0;
        for (PerformanceItemInfo itemInfo : performanceItems) {
            if (itemInfo.isVisible()) {
                if (currentIndex == index) {
                    itemInfo.infoMethod.run();
                    return;
                }
                currentIndex++;
            }
        }

        showMoonPhaseInfo();
    }

    /**
     * 显示月相信息对话框
     */
    private void showMoonPhaseInfo() {
        MoonPhaseUtils.MoonPhaseInfo moonPhaseInfo = MoonPhaseUtils.getCurrentMoonPhaseInfo();
        double moonPhase = MoonPhaseUtils.getCurrentMoonPhase();
        double phasePercentage = MoonPhaseUtils.getMoonPhasePercentage(moonPhase);
        int daysInCycle = MoonPhaseUtils.getDaysInMoonCycle(moonPhase);

        // 格式化日期
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault());
        String currentDate = dateFormat.format(Calendar.getInstance(TimeZone.getDefault()).getTime());

        // 创建月相信息文本
        String moonInfo = String.format(
                activity.getString(R.string.perf_moon_phase_info),
                moonPhaseInfo.icon, moonPhaseInfo.name, phasePercentage, daysInCycle, currentDate, moonPhaseInfo.description
        );

        showMoonPhaseDialog(moonPhaseInfo.poeticTitle, moonInfo);
    }

    /**
     * 显示月相信息对话框
     */
    private void showMoonPhaseDialog(String title, String message) {
        new AlertDialog.Builder(activity, R.style.AppDialogStyle)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Ok", null)
                .setCancelable(true)
                .show();
    }


    /**
     * 显示分辨率信息
     */
    private void showResolutionInfo() {
        if (currentPerformanceInfo == null) {
            showMoonPhaseInfo(); // 如果没有性能信息，显示月相信息
            return;
        }

        // 计算主机端分辨率（客户端分辨率 * 缩放比例）
        // 从设置中获取缩放比例，默认为100（即1.0）
        int scalePercent = prefConfig.resolutionScale;
        float scaleFactor = scalePercent / 100.0f;
        int hostWidth = (int) (currentPerformanceInfo.initialWidth * scaleFactor);
        int hostHeight = (int) (currentPerformanceInfo.initialHeight * scaleFactor);

        // 创建分辨率信息文本
        StringBuilder resolutionInfo = new StringBuilder();
        resolutionInfo.append("Client Resolution: ").append(currentPerformanceInfo.initialWidth)
                     .append(" × ").append(currentPerformanceInfo.initialHeight).append("\n");
        resolutionInfo.append("Host Resolution: ").append(hostWidth)
                     .append(" × ").append(hostHeight).append("\n");
        resolutionInfo.append("Scale Factor: ").append(String.format("%.2f", scaleFactor)).append(" (").append(scalePercent).append("%)\n");
        // 获取设备支持的刷新率
        float deviceRefreshRate = UiHelper.getDeviceRefreshRate(activity);

        resolutionInfo.append("Target FPS: ").append(prefConfig.fps).append(" FPS\n");
        resolutionInfo.append("Current FPS: ").append(String.format("%.0f", currentPerformanceInfo.totalFps)).append(" FPS\n");
        resolutionInfo.append("Device Refresh Rate: ").append(String.format("%.0f", deviceRefreshRate)).append(" Hz\n");

        if (actualDisplayRefreshRate > 0) {
            resolutionInfo.append("Actual Display Refresh Rate: ").append(String.format("%.2f", actualDisplayRefreshRate)).append(" Hz\n");
        }

        showInfoDialog(
                "📱 Resolution Information",
                resolutionInfo.toString()
        );
    }

    /**
     * 显示解码器信息
     */
    private void showDecoderInfo() {
        // 获取当前性能信息中的完整解码器信息
        String fullDecoderInfo = getCurrentDecoderInfo();

        showInfoDialog(
                activity.getString(R.string.perf_decoder_title),
                fullDecoderInfo
        );
    }

    /**
     * 获取当前完整的解码器信息
     */
    private String getCurrentDecoderInfo() {
        StringBuilder decoderInfo = new StringBuilder();
        // 这里需要获取当前的PerformanceInfo对象
        // 由于PerformanceInfo是在updatePerformanceInfo方法中传入的，
        // 我们需要保存最新的PerformanceInfo对象
        if (currentPerformanceInfo != null) {
            // 添加完整解码器名称
            decoderInfo.append("Codec: ").append(currentPerformanceInfo.decoder).append("\n\n");

            // 添加解码器类型
            DecoderTypeInfo decoderTypeInfo = getDecoderTypeInfo(currentPerformanceInfo.decoder);
            decoderInfo.append("Type: ").append(decoderTypeInfo.fullName).append("\n");

            // 添加HDR状态
            if (currentPerformanceInfo.isHdrActive) {
                decoderInfo.append("HDR: Enabled\n");
            } else {
                decoderInfo.append("HDR: Disabled\n");
            }
        }

        decoderInfo.append(activity.getString(R.string.perf_decoder_info));
        return decoderInfo.toString();
    }

    /**
     * 统一的解码器类型识别方法
     * 返回包含完整名称和简短名称的DecoderTypeInfo对象
     */
    private DecoderTypeInfo getDecoderTypeInfo(String fullDecoderName) {
        if (fullDecoderName == null) {
            return new DecoderTypeInfo("Unknown", "Unknown");
        }

        String lowerName = fullDecoderName.toLowerCase();

        // 在映射表中查找匹配的解码器类型
        for (Map.Entry<String, DecoderTypeInfo> entry : DECODER_TYPE_MAP.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 如果没有找到匹配的类型，尝试提取最后一个点后面的部分
        String[] parts = fullDecoderName.split("\\.");
        if (parts.length > 0) {
            String extractedName = parts[parts.length - 1];
            return new DecoderTypeInfo(fullDecoderName, extractedName.toUpperCase());
        }

        return new DecoderTypeInfo(fullDecoderName, fullDecoderName);
    }

    private void showPerformanceInfo(int titleResId, int infoResId) {
        showInfoDialog(
                activity.getString(titleResId),
                activity.getString(infoResId)
        );
    }


    private void showFpsInfo() {
        showPerformanceInfo(R.string.perf_fps_title, R.string.perf_fps_info);
    }

    private void showPacketLossInfo() {
        showPerformanceInfo(R.string.perf_packet_loss_title, R.string.perf_packet_loss_info);
    }

    private void showNetworkLatencyInfo() {
        showPerformanceInfo(R.string.perf_network_latency_title, R.string.perf_network_latency_info);
    }

    private void showDecodeLatencyInfo() {
        showPerformanceInfo(R.string.perf_decode_latency_title, R.string.perf_decode_latency_info);
    }

    private void showHostLatencyInfo() {
        showPerformanceInfo(R.string.perf_host_latency_title, R.string.perf_host_latency_info);
    }

    private void showInfoDialog(String title, String message) {
        new AlertDialog.Builder(activity, R.style.AppDialogStyle)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(activity.getString(R.string.yes), null)
                .setCancelable(true)
                .show();
    }

    private void toggleLayoutOrientation() {
        // 切换布局方向
        if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL;
        } else {
            prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
        }

        // 保存设置到SharedPreferences
        saveLayoutOrientation();

        // 重新配置性能覆盖层
        configurePerformanceOverlay();
    }

    private void saveLayoutOrientation() {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        prefs.edit()
                .putString("layout_orientation", prefConfig.perfOverlayOrientation.name())
                .apply();
    }

    private void loadLayoutOrientation() {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        String savedOrientation = prefs.getString("layout_orientation", null);

        if (savedOrientation != null) {
            try {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.valueOf(savedOrientation);
            } catch (IllegalArgumentException e) {
                // 如果保存的值无效，使用默认值
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
            }
        }
    }

    /**
     * 检测是否为点击事件（而非拖动）
     */
    private boolean isClick(MotionEvent event) {
        float deltaX = Math.abs(event.getRawX() - perfOverlayStartX);
        float deltaY = Math.abs(event.getRawY() - perfOverlayStartY);
        long deltaTime = System.currentTimeMillis() - clickStartTime;

        // 点击条件：移动距离小且时间短
        return deltaX < CLICK_THRESHOLD && deltaY < CLICK_THRESHOLD && deltaTime < 500;
    }

    private void snapToNearestPosition(View view) {
        // 获取父容器和View的尺寸
        int[] parentDimensions = getParentDimensions(view);
        int[] viewDimensions = getViewDimensions(view);
        int screenWidth = parentDimensions[0];
        int screenHeight = parentDimensions[1];
        int viewWidth = viewDimensions[0];
        int viewHeight = viewDimensions[1];

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) view.getLayoutParams();
        int currentX = layoutParams.leftMargin + viewWidth / 2;
        int currentY = layoutParams.topMargin + viewHeight / 2;

        // 计算到各个吸附位置的距离
        SnapPosition nearestPosition = SnapPosition.TOP_CENTER;
        double minDistance = Double.MAX_VALUE;

        // 定义8个吸附位置
        int[][] snapPositions = {
            {viewWidth / 2, viewHeight / 2}, // TOP_LEFT
            {screenWidth / 2, viewHeight / 2}, // TOP_CENTER
            {screenWidth - viewWidth / 2, viewHeight / 2}, // TOP_RIGHT
            {viewWidth / 2, screenHeight / 2}, // CENTER_LEFT
            {screenWidth - viewWidth / 2, screenHeight / 2}, // CENTER_RIGHT
            {viewWidth / 2, screenHeight - viewHeight / 2}, // BOTTOM_LEFT
            {screenWidth / 2, screenHeight - viewHeight / 2}, // BOTTOM_CENTER
            {screenWidth - viewWidth / 2, screenHeight - viewHeight / 2} // BOTTOM_RIGHT
        };

        SnapPosition[] positions = SnapPosition.values();

        // 找到最近的吸附位置
        for (int i = 0; i < snapPositions.length; i++) {
            double distance = Math.sqrt(
                Math.pow(currentX - snapPositions[i][0], 2) +
                Math.pow(currentY - snapPositions[i][1], 2)
            );

            if (distance < minDistance) {
                minDistance = distance;
                nearestPosition = positions[i];
            }
        }

        // 吸过来
        animateToSnapPosition(view, nearestPosition, screenWidth, screenHeight);
    }

    private void animateToSnapPosition(View view, SnapPosition position, int screenWidth, int screenHeight) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) view.getLayoutParams();
        int[] viewDimensions = getViewDimensions(view);
        int viewWidth = viewDimensions[0];
        int viewHeight = viewDimensions[1];

        int targetX, targetY;

        switch (position) {
            case TOP_LEFT:
                targetX = 0;
                targetY = 0;
                break;
            case TOP_CENTER:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = 0;
                break;
            case TOP_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = 0;
                break;
            case CENTER_LEFT:
                targetX = 0;
                targetY = (screenHeight - viewHeight) / 2;
                break;
            case CENTER_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = (screenHeight - viewHeight) / 2;
                break;
            case BOTTOM_LEFT:
                targetX = 0;
                targetY = screenHeight - viewHeight;
                break;
            case BOTTOM_CENTER:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = screenHeight - viewHeight;
                break;
            case BOTTOM_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = screenHeight - viewHeight;
                break;
            default:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = 0;
                break;
        }

        // 使用动画平滑移动到目标位置
        view.animate()
            .translationX(targetX - layoutParams.leftMargin)
            .translationY(targetY - layoutParams.topMargin)
            .setDuration(200)
            .withEndAction(() -> {
                // 动画结束后更新实际的布局参数
                layoutParams.leftMargin = targetX;
                layoutParams.topMargin = targetY;
                view.setTranslationX(0);
                view.setTranslationY(0);
                view.setLayoutParams(layoutParams);

                // 保存位置到SharedPreferences
                savePerformanceOverlayPosition(targetX, targetY);

                // 重新配置文字对齐
                configureTextAlignment();
            })
            .start();
    }

    private void savePerformanceOverlayPosition(int x, int y) {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        prefs.edit()
            .putBoolean("has_custom_position", true)
            .putInt("left_margin", x)
            .putInt("top_margin", y)
            .apply();
    }

    /**
     * 获取View的实际尺寸，如果未测量则使用估计值
     */
    private int[] getViewDimensions(View view) {
        int width = view.getWidth();
        int height = view.getHeight();

        // 如果View尺寸为0（还未测量），使用估计值
        if (width == 0) {
            width = 300; // 估计宽度
        }
        if (height == 0) {
            height = 50; // 估计高度
        }

        return new int[]{width, height};
    }

    /**
     * 获取父容器的尺寸
     */
    private int[] getParentDimensions(View view) {
        View parent = (View) view.getParent();
        return new int[]{parent.getWidth(), parent.getHeight()};
    }

}
