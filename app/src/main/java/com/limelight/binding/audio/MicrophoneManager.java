package com.limelight.binding.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * 麦克风管理器
 * 负责管理麦克风的权限、状态和UI更新
 */
public class MicrophoneManager {
    private static final String TAG = "MicrophoneManager";
    
    private final Context context;
    private final NvConnection connection;
    private MicrophoneStream microphoneStream;
    private ImageButton micButton;
    private boolean enableMic;
    
    public interface MicrophoneStateListener {
        void onMicrophoneStateChanged(boolean isActive);
        void onPermissionRequested();
    }
    
    private MicrophoneStateListener stateListener;
    
    public MicrophoneManager(Context context, NvConnection connection, boolean enableMic) {
        this.context = context;
        this.connection = connection;
        this.enableMic = enableMic;
    }
    
    /**
     * 设置状态监听器
     */
    public void setStateListener(MicrophoneStateListener listener) {
        this.stateListener = listener;
    }
    
    /**
     * 设置麦克风按钮
     */
    public void setMicrophoneButton(ImageButton button) {
        this.micButton = button;
        setupMicrophoneButton();
    }
    
    /**
     * 初始化麦克风流
     */
    public boolean initializeMicrophoneStream() {
        if (!enableMic) {
            LimeLog.info("麦克风功能已禁用");
            return false;
        }
        
        if (microphoneStream != null) {
            LimeLog.info("麦克风流已存在");
            return true;
        }

        if (!hasMicrophonePermission()) {
            showMessage(context.getString(R.string.mic_permission_required));
            return false;
        }
        
        try {
            MicrophoneConfig.updateBitrateFromConfig(context);
            microphoneStream = new MicrophoneStream(connection);

            if (!microphoneStream.start()) {
                showMessage("无法启动麦克风流");
                return false;
            }

            LimeLog.info("麦克风流启动成功");
            
            if (!microphoneStream.isMicrophoneAvailable()) {
                showMessage("主机不支持麦克风功能");
            }

            // 初始化后默认暂停麦克风
            if (microphoneStream.isRunning()) {
                microphoneStream.pause();
                LimeLog.info("麦克风流已初始化，默认状态为关闭");
            }
            
            return true;
        } catch (Exception e) {
            LimeLog.warning("初始化麦克风流失败: " + e.getMessage());
            showMessage("初始化麦克风流失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 切换麦克风状态
     */
    public void toggleMicrophone() {
        if (!checkMicrophonePermission()) return;
        
        if (microphoneStream != null) {
            if (microphoneStream.isRunning()) {
                pauseMicrophone();
            } else {
                resumeMicrophone();
            }
        } else if (connection != null) {
            if (initializeMicrophoneStream()) {
                updateMicrophoneButtonState();
                showMessage(context.getString(R.string.mic_disabled));
            } else {
                showMessage("麦克风状态切换: 初始化失败");
            }
        } else {
            showMessage("麦克风状态切换: 连接不存在");
        }
        
        updateMicrophoneButtonState();
    }
    
    /**
     * 暂停麦克风
     */
    public void pauseMicrophone() {
        if (microphoneStream != null && microphoneStream.isRunning()) {
            microphoneStream.pause();
            showMessage(context.getString(R.string.mic_disabled));
            notifyStateChange(false);
        }
    }
    
    /**
     * 恢复麦克风
     */
    public void resumeMicrophone() {
        if (!checkMicrophonePermission()) return;
        
        if (microphoneStream != null && !microphoneStream.isRunning()) {
            if (microphoneStream.resume()) {
                showMessage(context.getString(R.string.mic_enabled));
                notifyStateChange(true);
            } else {
                restartMicrophoneStream();
            }
        }
    }

    private void restartMicrophoneStream() {
        LimeLog.warning("麦克风恢复失败，尝试重新初始化");
        microphoneStream.stop();
        MicrophoneConfig.updateBitrateFromConfig(context);

        microphoneStream = new MicrophoneStream(connection);
        if (microphoneStream.start()) {
            showMessage(context.getString(R.string.mic_enabled));
            notifyStateChange(true);
        } else {
            showMessage("麦克风恢复失败: 重新初始化失败");
        }
    }

    private void notifyStateChange(boolean isActive) {
        if (stateListener != null) {
            stateListener.onMicrophoneStateChanged(isActive);
        }
    }
    
    /**
     * 获取麦克风当前状态
     */
    public boolean isMicrophoneActive() {
        return microphoneStream != null && microphoneStream.isRunning();
    }
    
    /**
     * 检查麦克风是否可用
     */
    public boolean isMicrophoneAvailable() {
        return microphoneStream != null && microphoneStream.isMicrophoneAvailable();
    }
    
    /**
     * 检查麦克风是否真正可用
     */
    public boolean isMicrophoneTrulyAvailable() {
        return hasMicrophonePermission() &&
                microphoneStream != null &&
                microphoneStream.isMicrophoneAvailable();
    }
    
    /**
     * 检查麦克风权限
     */
    public boolean checkMicrophonePermission() {
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission();
            return false;
        }
        return true;
    }
    
    /**
     * 检查是否有麦克风权限
     */
    public boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * 请求麦克风权限
     */
    public void requestMicrophonePermission() {
        if (context instanceof android.app.Activity) {
            ActivityCompat.requestPermissions((android.app.Activity) context, 
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 
                    MicrophoneConfig.PERMISSION_REQUEST_MICROPHONE);
            if (stateListener != null) {
                stateListener.onPermissionRequested();
            }
        }
    }
    
    /**
     * 处理权限请求结果
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == MicrophoneConfig.PERMISSION_REQUEST_MICROPHONE &&
                grantResults.length > 0) {

            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                new android.os.Handler().postDelayed(() -> {
                    if (hasMicrophonePermission()) {
                        toggleMicrophone();
                    } else {
                        showPermissionError();
                    }
                }, MicrophoneConfig.PERMISSION_DELAY_MS);
            } else {
                showPermissionError();
            }
        }
    }
    
    /**
     * 设置麦克风按钮
     */
    private void setupMicrophoneButton() {
        if (micButton == null) return;

        micButton.setVisibility(enableMic ? android.view.View.VISIBLE : android.view.View.GONE);
        if (enableMic) {
            updateMicrophoneButtonState();
            micButton.setOnClickListener(v -> {
                if (checkMicrophonePermission()) {
                    toggleMicrophone();
                }
            });
        }
    }
    
    /**
     * 更新麦克风按钮状态
     */
    public void updateMicrophoneButtonState() {
        if (micButton == null) return;

        boolean isActive = isMicrophoneActive();
        micButton.setSelected(isActive);

        // 根据配置获取图标资源
        int iconResource = getMicIconResource(isActive);
        micButton.setImageResource(iconResource);

        micButton.setContentDescription(context.getString(
                isActive ? R.string.mic_enabled : R.string.mic_disabled));
        micButton.setEnabled(true);
    }

    /**
     * 根据配置获取麦克风图标资源ID
     * 使用资源映射表，代码更简洁
     */
    private int getMicIconResource(boolean isActive) {
        // 读取配置
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(context);
        String colorScheme = prefConfig.micIconColor != null ? prefConfig.micIconColor : "solid_white";
        
        // 资源映射表：简化代码
        String resourceName = isActive ? 
                getEnabledIconResourceName(colorScheme) : 
                getDisabledIconResourceName(colorScheme);
        
        return context.getResources().getIdentifier(
                resourceName, "drawable", context.getPackageName());
    }
    
    /**
     * 获取启用状态的图标资源名称
     */
    private String getEnabledIconResourceName(String colorScheme) {
        switch (colorScheme) {
            case "gradient_blue": return "ic_btn_mic_gradient_blue";
            case "gradient_purple": return "ic_btn_mic_gradient_purple";
            case "gradient_green": return "ic_btn_mic_gradient_green";
            case "gradient_orange": return "ic_btn_mic_gradient_orange";
            case "gradient_red": return "ic_btn_mic_gradient_red";
            default: return "ic_btn_mic";
        }
    }
    
    /**
     * 获取禁用状态的图标资源名称
     */
    private String getDisabledIconResourceName(String colorScheme) {
        switch (colorScheme) {
            case "gradient_blue": return "ic_btn_mic_gradient_blue_disabled";
            case "gradient_purple": return "ic_btn_mic_gradient_purple_disabled";
            case "gradient_green": return "ic_btn_mic_gradient_green_disabled";
            case "gradient_orange": return "ic_btn_mic_gradient_orange_disabled";
            case "gradient_red": return "ic_btn_mic_gradient_red_disabled";
            default: return "ic_btn_mic_disabled";
        }
    }

    /**
     * 停止麦克风流
     */
    public void stopMicrophoneStream() {
        if (microphoneStream != null) {
            microphoneStream.stop();
            microphoneStream = null;
        }
    }
    
    private void showPermissionError() {
        showMessage(context.getString(R.string.mic_permission_required));
    }
    
    private void showMessage(String message) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });
        } else {
            // 如果不是 Activity，使用 Handler 确保在主线程中执行
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    /**
     * 测试麦克风状态
     */
    public void testMicrophoneStatus() {
        String status = String.format("权限: %s, 流存在: %s, 可用: %s, 激活: %s",
                hasMicrophonePermission(),
                microphoneStream != null,
                isMicrophoneAvailable(),
                isMicrophoneActive());
        
        LimeLog.info("麦克风状态: " + status);
        Toast.makeText(context, status, Toast.LENGTH_LONG).show();
    }
    
    /**
     * 设置麦克风启用状态
     */
    public void setEnableMic(boolean enable) {
        this.enableMic = enable;
        
        if (enable) {
            MicrophoneConfig.updateBitrateFromConfig(context);
        }
        
        if (micButton != null) {
            setupMicrophoneButton();
        }
    }
    
    /**
     * 获取麦克风流实例
     */
    public MicrophoneStream getMicrophoneStream() {
        return microphoneStream;
    }
    
    /**
     * 设置麦克风默认状态为关闭
     */
    public void setDefaultStateOff() {
        if (microphoneStream != null && microphoneStream.isRunning()) {
            microphoneStream.pause();
            LimeLog.info("麦克风已设置为默认关闭状态");
        }
        updateMicrophoneButtonState();
    }
} 