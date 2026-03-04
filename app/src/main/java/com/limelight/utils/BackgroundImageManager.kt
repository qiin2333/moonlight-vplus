package com.limelight.utils

import android.content.Context
import android.graphics.Bitmap
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView

import com.limelight.R

/**
 * 背景图片管理器，用于处理AppView背景图片的平滑切换
 */
class BackgroundImageManager(
    private val context: Context,
    private val backgroundImageView: ImageView
) {
    var currentBackground: Bitmap? = null
        private set

    /**
     * 平滑地切换到新的背景图片
     * @param newBackground 新的背景图片
     */
    fun setBackgroundSmoothly(newBackground: Bitmap?) {
        if (newBackground == null) {
            return
        }

        // 如果当前没有背景图片，直接设置
        if (currentBackground == null) {
            currentBackground = newBackground
            backgroundImageView.setImageBitmap(newBackground)
            backgroundImageView.startAnimation(
                AnimationUtils.loadAnimation(context, R.anim.background_fadein)
            )
            return
        }

        // 如果背景图片相同，不需要切换
        if (currentBackground == newBackground) {
            return
        }

        // 执行平滑切换动画
        val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                // 淡出完成后，设置新图片并淡入
                currentBackground = newBackground
                backgroundImageView.setImageBitmap(newBackground)
                backgroundImageView.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.background_fadein)
                )
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        backgroundImageView.startAnimation(fadeOutAnimation)
    }

    /**
     * 清除背景图片
     */
    fun clearBackground() {
        if (currentBackground != null) {
            val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
            fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    backgroundImageView.setImageBitmap(null)
                    currentBackground = null
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })

            backgroundImageView.startAnimation(fadeOutAnimation)
        }
    }
}
