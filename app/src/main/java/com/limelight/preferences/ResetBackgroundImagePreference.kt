package com.limelight.preferences

import android.content.Context
import androidx.preference.Preference
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

        // BackgroundSource.setActive() is the single writer: it flips the active
        // source to Auto, clears per-source extras (URL / local path), drops the
        // legacy key, and broadcasts a refresh.
        BackgroundSource.setActive(context, BackgroundSource.Auto)

        Toast.makeText(context, "已恢复默认背景图片", Toast.LENGTH_SHORT).show()
        LimeLog.info("Background image reset to default")
    }

    companion object {
        // 自定义背景图片的文件名（用于删除文件）
        private const val BACKGROUND_FILE_NAME = "custom_background_image.png"
    }
}
