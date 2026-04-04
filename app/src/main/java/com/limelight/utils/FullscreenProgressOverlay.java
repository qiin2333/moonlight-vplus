package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.util.Random;

public class FullscreenProgressOverlay {
    private final Activity activity;
    private final View overlayView;
    private final TextView statusText;
    private final TextView progressText;
    private final TextView randomTip;
    private final ImageView appPosterBackgroundBlur;
    private final ImageView appPosterBackgroundClear;
    private final ProgressBar progressBar;
    private final ViewGroup rootView;
    private final String[] tips;
    private final Random random;
    private boolean isShowing = false;
    private ComputerDetails computer;
    private NvApp app;

    public FullscreenProgressOverlay(Activity activity, NvApp app) {
        this.activity = activity;
        this.app = app;

        this.random = new Random();
        
        // 初始化提示数组
        this.tips = new String[]{
            activity.getString(R.string.tip_esc_exit),
            activity.getString(R.string.tip_double_tap_mouse),
            activity.getString(R.string.tip_long_press_controller),
            activity.getString(R.string.tip_volume_keys),
            activity.getString(R.string.tip_wallpaper_change),
            activity.getString(R.string.tip_5ghz_wifi),
            activity.getString(R.string.tip_close_apps),
            activity.getString(R.string.tip_home_saves),
            activity.getString(R.string.tip_hdr_colors),
            activity.getString(R.string.tip_touch_modes),
            activity.getString(R.string.tip_custom_keys),
            activity.getString(R.string.tip_performance_overlay),
            activity.getString(R.string.tip_audio_config),
            activity.getString(R.string.tip_external_display),
            activity.getString(R.string.tip_virtual_display),
            activity.getString(R.string.tip_dynamic_bitrate),
            activity.getString(R.string.tip_cards_show)
        };

        // 获取根视图
        rootView = (ViewGroup) activity.findViewById(android.R.id.content);
        
        // 创建覆盖层视图
        LayoutInflater inflater = LayoutInflater.from(activity);
        overlayView = inflater.inflate(R.layout.fullscreen_progress_overlay, rootView, false);
        
        // 初始化视图组件
        statusText = overlayView.findViewById(R.id.statusText);
        progressText = overlayView.findViewById(R.id.progressText);
        randomTip = overlayView.findViewById(R.id.randomTip);
        appPosterBackgroundBlur = overlayView.findViewById(R.id.appPosterBackgroundBlur);
        appPosterBackgroundClear = overlayView.findViewById(R.id.appPosterBackgroundClear);
        progressBar = overlayView.findViewById(R.id.progressBar);
        
        // 设置初始状态
        overlayView.setVisibility(View.GONE);
    }

    public void show(String title, String message) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (!isShowing) {
                // 设置状态文字
                statusText.setText(title);
                progressText.setText(message);
                
                // 设置随机提示
                String tip = tips[random.nextInt(tips.length)];
                randomTip.setText(tip);
                
                // 添加到根视图
                if (overlayView.getParent() == null) {
                    rootView.addView(overlayView);
                }
                
                overlayView.setVisibility(View.VISIBLE);
                isShowing = true;
                
                loadAppImage();
            }
        });
    }

    public void setMessage(String message) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (isShowing) {
                progressText.setText(message);
            }
        });
    }

    public void setStatus(String status) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (isShowing) {
                statusText.setText(status);
            }
        });
    }

    public void setAppPoster(Bitmap poster) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (poster != null) {
                BackgroundImageManager.setBlurredBitmap(appPosterBackgroundBlur, poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA);
                appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA));
            } else {
                appPosterBackgroundBlur.setImageResource(R.drawable.no_app_image);
                appPosterBackgroundClear.setImageBitmap(null);
            }
        });
    }

    public void setAppPoster(Drawable poster) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (poster != null) {
                BackgroundImageManager.setBlurredDrawable(appPosterBackgroundBlur, poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA);
                if (poster instanceof android.graphics.drawable.BitmapDrawable) {
                    Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) poster).getBitmap();
                    if (bmp != null) {
                        appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(bmp, BackgroundImageManager.OVERLAY_IMAGE_ALPHA));
                    } else {
                        appPosterBackgroundClear.setImageDrawable(poster);
                    }
                } else {
                    appPosterBackgroundClear.setImageDrawable(poster);
                }
            } else {
                appPosterBackgroundBlur.setImageResource(R.drawable.no_app_image);
                appPosterBackgroundClear.setImageBitmap(null);
            }
        });
    }

    public void setProgress(int progress) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (isShowing) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(progress);
            }
        });
    }

    public void setIndeterminate(boolean indeterminate) {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (isShowing) {
                progressBar.setIndeterminate(indeterminate);
            }
        });
    }

    public void dismiss() {
        if (activity.isFinishing()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (isShowing) {
                overlayView.setVisibility(View.GONE);
                if (overlayView.getParent() != null) {
                    rootView.removeView(overlayView);
                }
                isShowing = false;
            }
        });
    }

    public boolean isShowing() {
        return isShowing;
    }
    
    /**
     * 设置computer信息
     */
    public void setComputer(ComputerDetails computer) {
        this.computer = computer;
    }
    


    private void loadAppImage() {
        if (app != null && computer != null) {
            Bitmap fullBitmap = AppIconCache.getInstance().getFullIcon(computer, app);
            if (fullBitmap != null) {
                appPosterBackgroundBlur.setVisibility(View.VISIBLE);
                appPosterBackgroundClear.setVisibility(View.VISIBLE);
                BackgroundImageManager.setBlurredBitmap(appPosterBackgroundBlur, fullBitmap, BackgroundImageManager.OVERLAY_IMAGE_ALPHA);
                appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(fullBitmap, BackgroundImageManager.OVERLAY_IMAGE_ALPHA));
            }
        }
    }
    

}
