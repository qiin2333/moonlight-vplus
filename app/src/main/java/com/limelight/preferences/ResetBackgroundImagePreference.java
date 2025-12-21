package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.LimeLog;

import java.io.File;

/**
 * 重置背景图片偏好设置类
 * 清除所有背景图片相关配置，恢复到默认的API图片
 */
public class ResetBackgroundImagePreference extends Preference {
    
    // 自定义背景图片的文件名（用于删除文件）
    private static final String BACKGROUND_FILE_NAME = "custom_background_image.png";

    public ResetBackgroundImagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ResetBackgroundImagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onClick() {
        resetBackgroundImage();
    }

    /**
     * 重置背景图片配置
     */
    private void resetBackgroundImage() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        
        // 删除本地图片文件（如果存在）
        try {
            File localImageFile = new File(getContext().getFilesDir(), BACKGROUND_FILE_NAME);
            if (localImageFile.exists()) {
                boolean deleted = localImageFile.delete();
                if (deleted) {
                    LimeLog.info("Deleted local background image file");
                }
            }
        } catch (Exception e) {
            LimeLog.warning("Failed to delete local background image: " + e.getMessage());
        }
        
        // 清除所有背景图片相关配置
        prefs.edit()
            .putString("background_image_type", "default")
            .remove("background_image_url")
            .remove("background_image_local_path")
            .apply();
        
        Toast.makeText(getContext(), "已恢复默认背景图片", Toast.LENGTH_SHORT).show();
        LimeLog.info("Background image reset to default");
        
        // 发送广播通知 PcView 更新背景图片
        Intent broadcastIntent = new Intent("com.limelight.REFRESH_BACKGROUND_IMAGE");
        getContext().sendBroadcast(broadcastIntent);
    }
}

