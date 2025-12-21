package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.preferences.StreamSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 本地图片选择器偏好设置类
 * 提供一个按钮来选择本地图片
 */
public class LocalImagePickerPreference extends Preference {
    public static final int PICK_IMAGE_REQUEST = 1001;
    private StreamSettings activity;
    private PreferenceFragment fragment;
    private static LocalImagePickerPreference instance;

    // 自定义背景图片的文件名
    private static final String BACKGROUND_FILE_NAME = "custom_background_image.png";

    public LocalImagePickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (context instanceof StreamSettings) {
            this.activity = (StreamSettings) context;
        }
        instance = this;
    }

    public LocalImagePickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (context instanceof StreamSettings) {
            this.activity = (StreamSettings) context;
        }
        instance = this;
    }

    public static LocalImagePickerPreference getInstance() {
        return instance;
    }

    public void setFragment(PreferenceFragment fragment) {
        this.fragment = fragment;
    }

    @Override
    protected void onClick() {
        openImagePicker();
    }

    /**
     * 打开图片选择器
     * 使用 ACTION_GET_CONTENT 可以让用户自己选择是用“相册”选还是用“文件管理器”选
     */
    private void openImagePicker() {
        // 创建 Intent，动作为“获取内容”
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        // 限制类型为图片
        intent.setType("image/*");
        // 确保返回的文件是可以打开读取的
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            Intent chooserIntent = Intent.createChooser(intent, "选择背景图片");

            // 启动选择
            if (fragment != null) {
                fragment.startActivityForResult(chooserIntent, PICK_IMAGE_REQUEST);
            } else if (activity != null) {
                activity.startActivityForResult(chooserIntent, PICK_IMAGE_REQUEST);
            }
        } catch (Exception e) {
            LimeLog.warning("Failed to open image picker: " + e.getMessage());
            Toast.makeText(getContext(),
                    "无法打开图片选择器",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 处理图片选择结果
     */
    public void handleImagePickerResult(Intent data) {
        if (data != null && data.getData() != null) {
            Uri imageUri = data.getData();

            // 将图片复制到应用私有目录
            String internalPath = copyImageToInternalStorage(getContext(), imageUri);

            if (internalPath != null) {
                // 保存私有文件的路径到偏好设置，并设置类型为本地文件
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
                prefs.edit()
                        .putString("background_image_type", "local")
                        .putString("background_image_local_path", internalPath)
                        // 清除API URL配置，避免冲突
                        .remove("background_image_url")
                        .apply();

                Toast.makeText(getContext(), "背景图片设置成功", Toast.LENGTH_SHORT).show();
                LimeLog.info("Image saved to internal storage: " + internalPath);

                // 发送广播通知 PcView 更新背景图片
                Intent broadcastIntent = new Intent("com.limelight.REFRESH_BACKGROUND_IMAGE");
                getContext().sendBroadcast(broadcastIntent);
            } else {
                Toast.makeText(getContext(), "图片保存失败，请重试", Toast.LENGTH_SHORT).show();
                LimeLog.warning("Failed to copy image to internal storage");
            }
        } else {
            Toast.makeText(getContext(), "图片选择已取消", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 将选中的图片复制到应用的内部存储空间
     * 这样无论应用重启还是权限变更，只要应用不被卸载，图片都能访问
     */
    private String copyImageToInternalStorage(Context context, Uri sourceUri) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            // 打开输入流
            inputStream = context.getContentResolver().openInputStream(sourceUri);
            if (inputStream == null) return null;

            // 创建内部存储的目标文件
            File destFile = new File(context.getFilesDir(), BACKGROUND_FILE_NAME);

            // 如果文件已存在，先删除（覆盖）
            if (destFile.exists()) {
                destFile.delete();
            }

            // 打开输出流
            outputStream = new FileOutputStream(destFile);

            // 执行复制
            byte[] buffer = new byte[4096];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.flush();

            // 返回该文件的绝对路径
            return destFile.getAbsolutePath();

        } catch (Exception e) {
            LimeLog.warning("Error copying image: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}