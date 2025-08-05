package com.limelight.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import com.limelight.R;

/**
 * 背景图片管理器，用于处理AppView背景图片的平滑切换
 */
public class BackgroundImageManager {
    private final Context context;
    private final ImageView backgroundImageView;
    private Bitmap currentBackground;

    public BackgroundImageManager(Context context, ImageView backgroundImageView) {
        this.context = context;
        this.backgroundImageView = backgroundImageView;
    }

    /**
     * 平滑地切换到新的背景图片
     * @param newBackground 新的背景图片
     */
    public void setBackgroundSmoothly(Bitmap newBackground) {
        if (newBackground == null) {
            return;
        }

        // 如果当前没有背景图片，直接设置
        if (currentBackground == null) {
            currentBackground = newBackground;
            backgroundImageView.setImageBitmap(newBackground);
            backgroundImageView.startAnimation(
                AnimationUtils.loadAnimation(context, R.anim.background_fadein)
            );
            return;
        }

        // 如果背景图片相同，不需要切换
        if (currentBackground.equals(newBackground)) {
            return;
        }

        // 执行平滑切换动画
        Animation fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout);
        fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                // 淡出完成后，设置新图片并淡入
                currentBackground = newBackground;
                backgroundImageView.setImageBitmap(newBackground);
                backgroundImageView.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.background_fadein)
                );
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        backgroundImageView.startAnimation(fadeOutAnimation);
    }

    /**
     * 清除背景图片
     */
    public void clearBackground() {
        if (currentBackground != null) {
            Animation fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout);
            fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    backgroundImageView.setImageBitmap(null);
                    currentBackground = null;
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });

            backgroundImageView.startAnimation(fadeOutAnimation);
        }
    }

    /**
     * 获取当前背景图片
     */
    public Bitmap getCurrentBackground() {
        return currentBackground;
    }
} 