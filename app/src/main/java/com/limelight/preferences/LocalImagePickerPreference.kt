@file:Suppress("DEPRECATION")
package com.limelight.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.Toast

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

import com.limelight.LimeLog

import java.io.File
import java.io.FileOutputStream

/**
 * 本地图片选择器偏好设置类
 * 提供一个按钮来选择本地图片
 */
class LocalImagePickerPreference : Preference {

    private var activity: StreamSettings? = null
    private var fragment: PreferenceFragmentCompat? = null

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        if (context is StreamSettings) {
            this.activity = context
        }
        instance = this
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        if (context is StreamSettings) {
            this.activity = context
        }
        instance = this
    }

    fun setFragment(fragment: PreferenceFragmentCompat) {
        this.fragment = fragment
    }

    override fun onClick() {
        openImagePicker()
    }

    /**
     * 打开图片选择器
     * 使用 ACTION_GET_CONTENT 可以让用户自己选择是用"相册"选还是用"文件管理器"选
     */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            val chooserIntent = Intent.createChooser(intent, "选择背景图片")
            if (fragment != null) {
                fragment!!.startActivityForResult(chooserIntent, PICK_IMAGE_REQUEST)
            } else if (activity != null) {
                activity!!.startActivityForResult(chooserIntent, PICK_IMAGE_REQUEST)
            }
        } catch (e: Exception) {
            LimeLog.warning("Failed to open image picker: ${e.message}")
            Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理图片选择结果
     */
    fun handleImagePickerResult(data: Intent?) {
        if (data?.data != null) {
            val imageUri = data.data!!

            val internalPath = copyImageToInternalStorage(context, imageUri)
            if (internalPath != null) {
                // Persist the path, then let BackgroundSource flip the active
                // source and broadcast the refresh. Using
                // setActivePreservingExtras so the newly saved path isn't wiped.
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(BackgroundSource.KEY_LOCAL_PATH, internalPath)
                    .apply()
                BackgroundSource.setActivePreservingExtras(context, BackgroundSource.Local)

                Toast.makeText(context, "背景图片设置成功", Toast.LENGTH_SHORT).show()
                LimeLog.info("Image saved to internal storage: $internalPath")
            } else {
                Toast.makeText(context, "图片保存失败，请重试", Toast.LENGTH_SHORT).show()
                LimeLog.warning("Failed to copy image to internal storage")
            }
        } else {
            Toast.makeText(context, "图片选择已取消", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 将选中的图片复制到应用的内部存储空间
     */
    private fun copyImageToInternalStorage(context: Context, sourceUri: Uri): String? {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
            inputStream.use { input ->
                val destFile = File(context.filesDir, BACKGROUND_FILE_NAME)
                if (destFile.exists()) {
                    destFile.delete()
                }
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while (input.read(buffer).also { length = it } > 0) {
                        output.write(buffer, 0, length)
                    }
                    output.flush()
                }
                return destFile.absolutePath
            }
        } catch (e: Exception) {
            LimeLog.warning("Error copying image: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    companion object {
        const val PICK_IMAGE_REQUEST = 1001
        private const val BACKGROUND_FILE_NAME = "custom_background_image.png"

        var instance: LocalImagePickerPreference? = null
            private set
    }
}
