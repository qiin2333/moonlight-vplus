package com.limelight;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;

/**
 * 屏幕位置管理器
 * 负责根据用户偏好设置（位置与偏移）调整串流视图的位置与边距。
 * 保留原有注释与行为。
 */
public class DisplayPositionManager {

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final StreamView streamView;

    public DisplayPositionManager(Activity activity, PreferenceConfiguration prefConfig, StreamView streamView) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.streamView = streamView;
    }

    public void setupDisplayPosition() {
        // 获取当前偏好设置
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(activity);

        // 获取视图容器
        ViewGroup.LayoutParams layoutParams = streamView.getLayoutParams();
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layoutParams;

            // 根据屏幕位置设置重力属性
            switch (config.screenPosition) {
                case TOP_LEFT:
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    break;
                case TOP_CENTER:
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    break;
                case TOP_RIGHT:
                    params.gravity = Gravity.TOP | Gravity.RIGHT;
                    break;
                case CENTER_LEFT:
                    params.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
                    break;
                case CENTER:
                    params.gravity = Gravity.CENTER;
                    break;
                case CENTER_RIGHT:
                    params.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
                    break;
                case BOTTOM_LEFT:
                    params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    break;
                case BOTTOM_CENTER:
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    break;
                case BOTTOM_RIGHT:
                    params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                    break;
            }

            // 计算偏移量的像素值
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);

            int streamWidth = prefConfig.width;
            int streamHeight = prefConfig.height;

            // 将0-100的偏移百分比转换为实际像素值
            int xOffset = (streamWidth * config.screenOffsetX) / 100;
            int yOffset = (streamHeight * config.screenOffsetY) / 100;

            // 应用偏移量
            if (params.gravity == Gravity.TOP ||
                params.gravity == (Gravity.TOP | Gravity.CENTER_HORIZONTAL) ||
                params.gravity == (Gravity.TOP | Gravity.RIGHT)) {
                params.topMargin = yOffset;
            } else if (params.gravity == Gravity.BOTTOM ||
                      params.gravity == (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) ||
                      params.gravity == (Gravity.BOTTOM | Gravity.LEFT)) {
                params.bottomMargin = yOffset;
            }

            if (params.gravity == Gravity.LEFT ||
                params.gravity == (Gravity.CENTER_VERTICAL | Gravity.LEFT) ||
                params.gravity == (Gravity.BOTTOM | Gravity.LEFT)) {
                params.leftMargin = xOffset;
            } else if (params.gravity == Gravity.RIGHT ||
                      params.gravity == (Gravity.CENTER_VERTICAL | Gravity.RIGHT) ||
                      params.gravity == (Gravity.TOP | Gravity.RIGHT)) {
                params.rightMargin = xOffset;
            }

            // 应用更新后的布局参数
            streamView.setLayoutParams(params);
        }
    }

    // 更新刷新显示位置方法
    public void refreshDisplayPosition(boolean surfaceCreated) {
        if (surfaceCreated) {
            setupDisplayPosition();
        }
    }
}


