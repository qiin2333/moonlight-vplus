package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.binding.video.PerformanceInfo;
import com.limelight.preferences.PerfOverlayDisplayItemsPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;
import com.limelight.utils.NetHelper;

/**
 * 性能覆盖层管理器
 * 负责性能覆盖层的显示、隐藏、配置、拖动和位置管理。
 * 保留了原有注释与行为。
 */
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

    // 8个吸附位置的枚举
    private enum SnapPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    // 计算带宽用
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;

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

        perfResView = activity.findViewById(R.id.perfRes);
        perfDecoderView = activity.findViewById(R.id.perfDecoder);
        perfRenderFpsView = activity.findViewById(R.id.perfRenderFps);
        networkLatencyView = activity.findViewById(R.id.perfNetworkLatency);
        decodeLatencyView = activity.findViewById(R.id.perfDecodeLatency);
        hostLatencyView = activity.findViewById(R.id.perfHostLatency);
        packetLossView = activity.findViewById(R.id.perfPacketLoss);

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            // 初始状态下设置为不可见，等待性能数据更新时再显示
            if (performanceOverlayView != null) {
                performanceOverlayView.setVisibility(View.GONE);
                performanceOverlayView.setAlpha(0.0f);
            }
            // 配置性能覆盖层的方向和位置
            configurePerformanceOverlay();
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
        long currentRxBytes = TrafficStats.getTotalRxBytes();
        long timeMillis = System.currentTimeMillis();
        long timeMillisInterval = timeMillis - previousTimeMillis;

        // 计算并更新带宽信息
        performanceInfo.bandWidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);
        previousTimeMillis = timeMillis;
        previousRxBytes = currentRxBytes;

        // 准备性能信息显示
        final String resInfo = String.format("🎬 %dx%d@%.0f",
            performanceInfo.initialWidth, performanceInfo.initialHeight, performanceInfo.totalFps);

        String decoderInfo = performanceInfo.decoder.replaceFirst(".*\\.(avc|hevc|av1).*", "$1").toUpperCase();
        decoderInfo += prefConfig.enableHdr ? " HDR" : "";

        final String renderFpsInfo = String.format("Rx %.0f / Rd %.0f FPS",
            performanceInfo.receivedFps, performanceInfo.renderedFps);

        final String packetLossInfo = String.format("📶 %.2f%%", performanceInfo.lostFrameRate);

        final String networkLatencyInfo = String.format("%s   %d ± %d ms",
            performanceInfo.bandWidth,
            (int) (performanceInfo.rttInfo >> 32),
            (int) performanceInfo.rttInfo);

        final String decodeLatencyInfo = String.format(performanceInfo.decodeTimeMs < 15 ?
            "⏱️ %.2f ms" : "🥵 %.2f ms", performanceInfo.decodeTimeMs);

        final String hostLatencyInfo = performanceInfo.framesWithHostProcessingLatency > 0 ?
            String.format("🖥 %.1f ms", performanceInfo.aveHostProcessingLatency) : "🧋 Ver.V+";

        final String finalDecoderInfo = decoderInfo;

        activity.runOnUiThread(() -> {
            // 如果是第一次收到性能数据且性能覆盖层已启用，则显示覆盖层
            if (!hasShownPerfOverlay && requestedPerformanceOverlayVisibility == View.VISIBLE && performanceOverlayView != null) {
                performanceOverlayView.setVisibility(View.VISIBLE);
                performanceOverlayView.setAlpha(1.0f);
            }

            // 只更新可见的性能指标
            if (perfResView != null && perfResView.getVisibility() == View.VISIBLE) {
                perfResView.setText(resInfo);
            }
            if (perfDecoderView != null && perfDecoderView.getVisibility() == View.VISIBLE) {
                perfDecoderView.setText(finalDecoderInfo);
            }
            if (perfRenderFpsView != null && perfRenderFpsView.getVisibility() == View.VISIBLE) {
                perfRenderFpsView.setText(renderFpsInfo);
            }
            if (packetLossView != null && packetLossView.getVisibility() == View.VISIBLE) {
                packetLossView.setText(packetLossInfo);
                // 根据丢包率设置颜色：小于5%为绿色，否则为红色
                packetLossView.setTextColor(performanceInfo.lostFrameRate < 5.0f ? 0xFF7D9D7D : 0xFFB57D7D);
            }
            if (networkLatencyView != null && networkLatencyView.getVisibility() == View.VISIBLE) {
                // 当丢包率不显示时，在网络延迟前添加信号图标
                boolean showPacketLoss = packetLossView != null && packetLossView.getVisibility() == View.VISIBLE;
                String displayText = showPacketLoss ? networkLatencyInfo : "🌐 " + networkLatencyInfo;
                networkLatencyView.setText(displayText);
            }
            if (decodeLatencyView != null && decodeLatencyView.getVisibility() == View.VISIBLE) {
                decodeLatencyView.setText(decodeLatencyInfo);
            }
            if (hostLatencyView != null && hostLatencyView.getVisibility() == View.VISIBLE) {
                hostLatencyView.setText(hostLatencyInfo);
            }
        });
    }

    private void configurePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) performanceOverlayView.getLayoutParams();

        // 设置方向
        if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
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

        if (hasCustomPosition) {
            // 使用自定义位置
            layoutParams.gravity = Gravity.NO_GRAVITY;
            layoutParams.leftMargin = prefs.getInt("left_margin", 0);
            layoutParams.topMargin = prefs.getInt("top_margin", 0);
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;
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
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;
        }

        performanceOverlayView.setLayoutParams(layoutParams);

        // 根据位置和方向调整文字对齐（延迟执行确保View已测量）
        performanceOverlayView.post(new Runnable() {
            @Override
            public void run() {
                configureTextAlignment();
            }
        });

        // 设置拖动监听器
        setupPerformanceOverlayDragging();
    }

    private void configureDisplayItems() {
        // 根据用户配置显示/隐藏特定的性能指标
        if (perfResView != null) {
            perfResView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "resolution") ?
                View.VISIBLE : View.GONE);
        }
        if (perfDecoderView != null) {
            perfDecoderView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "decoder") ?
                View.VISIBLE : View.GONE);
        }
        if (perfRenderFpsView != null) {
            perfRenderFpsView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "render_fps") ?
                View.VISIBLE : View.GONE);
        }
        if (networkLatencyView != null) {
            networkLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "network_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (decodeLatencyView != null) {
            decodeLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "decode_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (hostLatencyView != null) {
            hostLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "host_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (packetLossView != null) {
            packetLossView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "packet_loss") ?
                    View.VISIBLE : View.GONE);
        }
    }

    private void configureTextAlignment() {
        if (performanceOverlayView == null) {
            return;
        }

        boolean isVertical = prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
        boolean isRightSide = determineRightSidePosition(isVertical);

        // 只在垂直布局且位置在右侧时，将文字设置为右对齐
        int gravity = (isVertical && isRightSide) ? android.view.Gravity.END : android.view.Gravity.START;

        // 批量设置所有性能信息文本的对齐方式和阴影效果
        TextView[] perfViews = {
            perfResView, perfDecoderView, perfRenderFpsView,
                networkLatencyView, decodeLatencyView, hostLatencyView, packetLossView
        };

        for (TextView textView : perfViews) {
            if (textView != null && textView.getVisibility() == View.VISIBLE) {
                configureTextViewStyle(textView, gravity, isVertical);
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

    /**
     * 配置单个TextView的样式（对齐方式和阴影效果）
     */
    private void configureTextViewStyle(TextView textView, int gravity, boolean isVertical) {
        // 设置文字对齐方式
        textView.setGravity(gravity);

        // 根据布局方向设置阴影效果
        if (isVertical) {
            // 竖屏时添加字体阴影，提高可读性
            textView.setShadowLayer(2.0f, 1.0f, 1.0f, 0x80000000);
        } else {
            // 横屏时移除阴影
            textView.setShadowLayer(0, 0, 0, 0);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPerformanceOverlayDragging() {
        if (performanceOverlayView == null) {
            return;
        }

        performanceOverlayView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isDraggingPerfOverlay = true;
                        perfOverlayStartX = event.getRawX();
                        perfOverlayStartY = event.getRawY();
                        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();

                        // 如果使用预设位置（gravity不为NO_GRAVITY），需要转换为实际坐标
                        if (layoutParams.gravity != Gravity.NO_GRAVITY) {
                            int[] viewLocation = new int[2];
                            int[] parentLocation = new int[2];
                            v.getLocationInWindow(viewLocation);
                            ((View)v.getParent()).getLocationInWindow(parentLocation);

                            // 将预设位置转换为相对于父容器的leftMargin和topMargin
                            layoutParams.leftMargin = viewLocation[0] - parentLocation[0];
                            layoutParams.topMargin = viewLocation[1] - parentLocation[1];
                            layoutParams.gravity = Gravity.NO_GRAVITY;
                            v.setLayoutParams(layoutParams);
                        }

                        perfOverlayDeltaX = perfOverlayStartX - layoutParams.leftMargin;
                        perfOverlayDeltaY = perfOverlayStartY - layoutParams.topMargin;

                        // 添加视觉反馈：降低透明度表示正在拖动
                        v.setAlpha(0.7f);
                        v.setScaleX(1.05f);
                        v.setScaleY(1.05f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (isDraggingPerfOverlay) {
                            // 获取父容器和View的尺寸
                            int[] parentDimensions = getParentDimensions(v);
                            int[] viewDimensions = getViewDimensions(v);
                            int parentWidth = parentDimensions[0];
                            int parentHeight = parentDimensions[1];
                            int viewWidth = viewDimensions[0];
                            int viewHeight = viewDimensions[1];

                            layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();
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
                        break;

                    case MotionEvent.ACTION_UP:
                        if (isDraggingPerfOverlay) {
                            isDraggingPerfOverlay = false;

                            // 恢复视觉效果
                            v.setAlpha(1.0f);
                            v.setScaleX(1.0f);
                            v.setScaleY(1.0f);

                            snapToNearestPosition(v);

                            return true;
                        }
                        break;
                }
                return false;
            }
        });
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
