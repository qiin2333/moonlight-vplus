package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Presentation;
import android.content.Context;

import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;
import com.limelight.LimeLog;
import com.limelight.utils.UiHelper;

/**
 * 外接显示器管理器
 * 负责管理外接显示器的检测、连接、断开和内容显示
 */
public class ExternalDisplayManager {
    
    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final NvConnection conn;
    private final MediaCodecDecoderRenderer decoderRenderer;
    private final String pcName;
    private final String appName;
    
    private DisplayManager displayManager;
    private Display externalDisplay;
    private boolean useExternalDisplay = false;
    private DisplayManager.DisplayListener displayListener;
    private ExternalDisplayPresentation externalPresentation;
    
    // 回调接口
    public interface ExternalDisplayCallback {
        void onExternalDisplayConnected(Display display);
        void onExternalDisplayDisconnected();
        void onStreamViewReady(StreamView streamView);
    }
    
    private ExternalDisplayCallback callback;
    
    public ExternalDisplayManager(Activity activity, PreferenceConfiguration prefConfig, 
                                 NvConnection conn, MediaCodecDecoderRenderer decoderRenderer,
                                 String pcName, String appName) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.conn = conn;
        this.decoderRenderer = decoderRenderer;
        this.pcName = pcName;
        this.appName = appName;
    }
    
    public void setCallback(ExternalDisplayCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 初始化外接显示器管理器
     */
    public void initialize() {
        // 初始化显示管理器
        displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        
        // 设置显示器监听器
        setupDisplayListener();
        
        // 检查是否有外接显示器
        checkForExternalDisplay();
        
        // 如果有外接显示器，启动外接显示器演示，并降低内建屏幕亮度到30%
        if (useExternalDisplay) {
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams layoutParams = window.getAttributes();
                layoutParams.screenBrightness = 0.3f;
                window.setAttributes(layoutParams);
            }
            startExternalDisplayPresentation();
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        // 清理外接显示器演示
        if (externalPresentation != null) {
            externalPresentation.dismiss();
            externalPresentation = null;
        }
        
        // 取消注册显示器监听器
        if (displayListener != null && displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
    }
    
    /**
     * 获取要使用的显示器
     */
    public Display getTargetDisplay() {
        if (useExternalDisplay && externalDisplay != null) {
            return externalDisplay;
        }
        return activity.getWindowManager().getDefaultDisplay();
    }
    
    /**
     * 检查是否正在使用外接显示器
     */
    public boolean isUsingExternalDisplay() {
        return useExternalDisplay && externalDisplay != null;
    }
    
    /**
     * 检查是否有外接显示器连接
     */
    public static boolean hasExternalDisplay(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                Display[] displays = displayManager.getDisplays();
                for (Display display : displays) {
                    if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * 设置显示器监听器
     */
    private void setupDisplayListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    LimeLog.info("Display added: " + displayId);
                    if (prefConfig.useExternalDisplay && displayId != Display.DEFAULT_DISPLAY) {
                        // 外接显示器已连接
                        checkForExternalDisplay();
                        if (useExternalDisplay) {
                            startExternalDisplayPresentation();
                        }
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    LimeLog.info("Display removed: " + displayId);
                    if (externalDisplay != null && displayId == externalDisplay.getDisplayId()) {
                        // 外接显示器已断开
                        if (externalPresentation != null) {
                            externalPresentation.dismiss();
                            externalPresentation = null;
                        }
                        externalDisplay = null;
                        useExternalDisplay = false;

                        // 显示主屏幕内容
                        View surfaceView = activity.findViewById(R.id.surfaceView);
                        if (surfaceView != null) {
                            surfaceView.setVisibility(View.VISIBLE);
                        }
                        Toast.makeText(activity, "外接显示器已断开，切换到主屏幕", Toast.LENGTH_SHORT).show();
                        
                        if (callback != null) {
                            callback.onExternalDisplayDisconnected();
                        }
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    LimeLog.info("Display changed: " + displayId);
                }
            };

            displayManager.registerDisplayListener(displayListener, null);
        }
    }

    /**
     * 检查并配置外接显示器
     */
    private void checkForExternalDisplay() {
        // 如果用户没有启用外接显示器选项，直接返回
        if (!prefConfig.useExternalDisplay) {
            LimeLog.info("External display disabled by user preference");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Display[] displays = displayManager.getDisplays();

            // 查找外接显示器（不是主显示器）
            for (Display display : displays) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    externalDisplay = display;
                    useExternalDisplay = true;
                    LimeLog.info("Found external display: " + display.getName() +
                            " (ID: " + display.getDisplayId() + ")");
                    
                    if (callback != null) {
                        callback.onExternalDisplayConnected(display);
                    }
                    break;
                }
            }

            if (!useExternalDisplay) {
                LimeLog.info("No external display found, using default display");
            }
        }
    }

    /**
     * 将Activity移动到外接显示器
     */
    private void moveToExternalDisplay() {
        if (useExternalDisplay && externalDisplay != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // 创建WindowManager.LayoutParams for external display
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.preferredDisplayModeId = externalDisplay.getMode().getModeId();
            activity.getWindow().setAttributes(params);

            // 或者使用Presentation来在外接显示器上显示
            // 这需要重新设计Activity结构
        }
    }

    /**
     * 外接显示器演示类
     */
    private class ExternalDisplayPresentation extends Presentation {

        public ExternalDisplayPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // 设置全屏
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);



            // 设置内容视图
            setContentView(R.layout.activity_game);

            // 初始化StreamView
            StreamView externalStreamView = findViewById(R.id.surfaceView);
            if (externalStreamView != null) {
                // 通知回调StreamView已准备就绪
                if (callback != null) {
                    callback.onStreamViewReady(externalStreamView);
                }
            }
        }

        @Override
        public void onDisplayRemoved() {
            super.onDisplayRemoved();
            // 外接显示器被移除时，关闭串流
            activity.finish();
        }
    }

    /**
     * 启动外接显示器演示
     */
    @SuppressLint({"ResourceAsColor", "SetTextI18n"})
    private void startExternalDisplayPresentation() {
        if (!(useExternalDisplay && externalDisplay != null && externalPresentation == null)) {
            return;
        }

        externalPresentation = new ExternalDisplayPresentation(activity, externalDisplay);
        externalPresentation.show();

        // 隐藏主Activity的内容
        View surfaceView = activity.findViewById(R.id.surfaceView);
        if (surfaceView != null) {
            surfaceView.setVisibility(View.GONE);
        }

        if (prefConfig.enablePerfOverlay) {
            // 创建电量显示TextView
            final TextView batteryTextView = new TextView(activity);
            batteryTextView.setGravity(Gravity.CENTER);
            batteryTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            batteryTextView.setTextColor(activity.getResources().getColor(R.color.scene_color_1));

            // 设置布局参数（居中显示）
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER;
            batteryTextView.setLayoutParams(params);

            // 添加到内建屏幕（主Activity）视图的中间
            FrameLayout rootView = activity.findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.addView(batteryTextView);
            }

            // 创建定时更新任务
            final Handler handler = new Handler();
            final Runnable updateBatteryTask = new Runnable() {
                private final int[] gravityOptions = {
                    Gravity.CENTER,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                    Gravity.CENTER_VERTICAL | Gravity.LEFT,
                    Gravity.CENTER_VERTICAL | Gravity.RIGHT,
                    Gravity.TOP | Gravity.LEFT,
                    Gravity.TOP | Gravity.RIGHT,
                    Gravity.BOTTOM | Gravity.LEFT,
                    Gravity.BOTTOM | Gravity.RIGHT
                };
                
                @Override
                public void run() {
                    // 更新电量显示
                    batteryTextView.setText(String.format("🔋 %d%%", UiHelper.getBatteryLevel(activity)));
                    
                    // 随机选择位置和参数以避免烧屏
                    int randomGravity = gravityOptions[(int) (Math.random() * gravityOptions.length)];
                    
                    // 随机生成边距参数（-200到200像素之间）
                    int randomMarginLeft = (int) (Math.random() * 401) - 200;
                    int randomMarginTop = (int) (Math.random() * 401) - 200;
                    int randomMarginRight = (int) (Math.random() * 401) - 200;
                    int randomMarginBottom = (int) (Math.random() * 401) - 200;
                    
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) batteryTextView.getLayoutParams();
                    params.gravity = randomGravity;
                    params.setMargins(randomMarginLeft, randomMarginTop, randomMarginRight, randomMarginBottom);
                    batteryTextView.setLayoutParams(params);
                    
                    // 每分钟更新一次
                    handler.postDelayed(this, 60000);
                }
            };

            // 立即执行首次更新并启动定时器
            updateBatteryTask.run();
        }

        Toast.makeText(activity, "串流已切换到外接显示器, 若某些外接设备不能正常横屏显示，请翻滚主机。", Toast.LENGTH_LONG).show();
    }
}
