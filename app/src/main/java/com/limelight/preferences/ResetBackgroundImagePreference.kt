package com.limelight.preferences

import android.content.Context
import android.content.Intent
import android.preference.Preference
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.widget.Toast

import com.limelight.LimeLog

import java.io.File

/**
 * 重置背景图片偏好设置类
 * 清除所有背景图片相关配置，恢复到默认的API图片
 */
class ResetBackgroundImagePreference : Preference {

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onClick() {
        resetBackgroundImage()
    }

    /**
     * 重置背景图片配置
     */
    private fun resetBackgroundImage() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // 删除本地图片文件（如果存在）
        try {
            val localImageFile = File(context.filesDir, BACKGROUND_FILE_NAME)
            if (localImageFile.exists()) {
                val deleted = localImageFile.delete()
                if (deleted) {
                    LimeLog.info("Deleted local background image file")
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Failed to delete local background image: ${e.message}")
        }

        // 清除所有背景图片相关配置
        prefs.edit()
            .putString("background_image_type", "default")
            .remove("background_image_url")
            .remove("background_image_local_path")
            .apply()

        Toast.makeText(context, "已恢复默认背景图片", Toast.LENGTH_SHORT).show()
        LimeLog.info("Background image reset to default")

        // 发送广播通知 PcView 更新背景图片
        val broadcastIntent = Intent("com.limelight.REFRESH_BACKGROUND_IMAGE")
        context.sendBroadcast(broadcastIntent)
    }

    companion object {
        // 自定义背景图片的文件名（用于删除文件）
        private const val BACKGROUND_FILE_NAME = "custom_background_image.png"
    }
}
