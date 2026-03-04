package com.limelight

import android.app.Activity
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.FrameLayout

import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView

/**
 * 屏幕位置管理器
 * 负责根据用户偏好设置（位置与偏移）调整串流视图的位置与边距。
 * 保留原有注释与行为。
 */
class DisplayPositionManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
    private val streamView: StreamView
) {

    fun setupDisplayPosition() {
        // 获取当前偏好设置
        val config = PreferenceConfiguration.readPreferences(activity)

        // 获取视图容器
        val layoutParams = streamView.layoutParams
        if (layoutParams is FrameLayout.LayoutParams) {
            // 根据屏幕位置设置重力属性
            when (config.screenPosition) {
                PreferenceConfiguration.ScreenPosition.TOP_LEFT ->
                    layoutParams.gravity = Gravity.TOP or Gravity.LEFT
                PreferenceConfiguration.ScreenPosition.TOP_CENTER ->
                    layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                PreferenceConfiguration.ScreenPosition.TOP_RIGHT ->
                    layoutParams.gravity = Gravity.TOP or Gravity.RIGHT
                PreferenceConfiguration.ScreenPosition.CENTER_LEFT ->
                    layoutParams.gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                PreferenceConfiguration.ScreenPosition.CENTER ->
                    layoutParams.gravity = Gravity.CENTER
                PreferenceConfiguration.ScreenPosition.CENTER_RIGHT ->
                    layoutParams.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                PreferenceConfiguration.ScreenPosition.BOTTOM_LEFT ->
                    layoutParams.gravity = Gravity.BOTTOM or Gravity.LEFT
                PreferenceConfiguration.ScreenPosition.BOTTOM_CENTER ->
                    layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                PreferenceConfiguration.ScreenPosition.BOTTOM_RIGHT ->
                    layoutParams.gravity = Gravity.BOTTOM or Gravity.RIGHT
                else -> {}
            }

            // 计算偏移量的像素值
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getMetrics(metrics)

            val streamWidth = prefConfig.width
            val streamHeight = prefConfig.height

            // 将0-100的偏移百分比转换为实际像素值
            val xOffset = streamWidth * config.screenOffsetX / 100
            val yOffset = streamHeight * config.screenOffsetY / 100

            // 应用偏移量
            if (layoutParams.gravity == Gravity.TOP ||
                layoutParams.gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL) ||
                layoutParams.gravity == (Gravity.TOP or Gravity.RIGHT)
            ) {
                layoutParams.topMargin = yOffset
            } else if (layoutParams.gravity == Gravity.BOTTOM ||
                layoutParams.gravity == (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) ||
                layoutParams.gravity == (Gravity.BOTTOM or Gravity.LEFT)
            ) {
                layoutParams.bottomMargin = yOffset
            }

            if (layoutParams.gravity == Gravity.LEFT ||
                layoutParams.gravity == (Gravity.CENTER_VERTICAL or Gravity.LEFT) ||
                layoutParams.gravity == (Gravity.BOTTOM or Gravity.LEFT)
            ) {
                layoutParams.leftMargin = xOffset
            } else if (layoutParams.gravity == Gravity.RIGHT ||
                layoutParams.gravity == (Gravity.CENTER_VERTICAL or Gravity.RIGHT) ||
                layoutParams.gravity == (Gravity.TOP or Gravity.RIGHT)
            ) {
                layoutParams.rightMargin = xOffset
            }

            // 应用更新后的布局参数
            streamView.layoutParams = layoutParams
        }
    }

    // 更新刷新显示位置方法
    fun refreshDisplayPosition(surfaceCreated: Boolean) {
        if (surfaceCreated) {
            setupDisplayPosition()
        }
    }
}
