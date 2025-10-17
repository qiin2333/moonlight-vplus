package com.limelight;

import static android.view.RoundedCorner.*;

import android.app.Activity;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.RoundedCorner;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * 屏幕边距管理器
 * 调整边距避免圆角和刘海遮挡
 */
public class DisplayMarginManager {
    private final Activity activity;
    private final FrameLayout streamFrame;

    public DisplayMarginManager(Activity activity, FrameLayout streamFrame) {
        this.activity = activity;
        this.streamFrame = streamFrame;
    }

    public void setupDisplayMargin() {
        // 获取当前偏好设置
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(activity);

        // 获取视图容器
        ViewGroup.LayoutParams layoutParams = streamFrame.getLayoutParams();
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layoutParams;
            int left = 0, top = 0, right = 0, bottom = 0;
            Display display = activity.getWindowManager().getDefaultDisplay();
            // 获取屏幕尺寸
            if (config.cutoutSupport && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DisplayCutout cutout = display.getCutout();
                if (cutout != null) {
                    left = cutout.getSafeInsetLeft();
                    top = cutout.getSafeInsetTop();
                    right = cutout.getSafeInsetRight();
                    bottom = cutout.getSafeInsetBottom();
                }
            }
            if (config.roundCornerSupport && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
                WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
                int topLeftRadius = 0, topRightRadius = 0, bottomLeftRadius = 0, bottomRightRadius = 0;
                RoundedCorner corner = display.getRoundedCorner(POSITION_TOP_LEFT);
                if (corner != null)
                    topLeftRadius = corner.getRadius();
                corner = display.getRoundedCorner(POSITION_TOP_RIGHT);
                if (corner != null)
                    topRightRadius = corner.getRadius();
                corner = display.getRoundedCorner(POSITION_BOTTOM_LEFT);
                if (corner != null)
                    bottomLeftRadius = corner.getRadius();
                corner = display.getRoundedCorner(POSITION_BOTTOM_RIGHT);
                if (corner != null)
                    bottomRightRadius = corner.getRadius();
                if (1. * metrics.getBounds().height() / metrics.getBounds().width()
                        < 1. * config.height / config.width) {
                    left = Math.max(left, Math.max(topLeftRadius, bottomLeftRadius));
                    right = Math.max(right, Math.max(topRightRadius, bottomRightRadius));
                } else {
                    top = Math.max(top, Math.max(topLeftRadius, topRightRadius));
                    bottom = Math.max(bottom, Math.max(bottomLeftRadius, bottomRightRadius));
                }
            }
            params.setMargins(left, top, right, bottom);
            streamFrame.setLayoutParams(params);
        }
    }

    // 更新刷新显示位置方法
    public void refreshDisplayMargin(boolean surfaceCreated) {
        if (surfaceCreated) {
            setupDisplayMargin();
        }
    }
}


