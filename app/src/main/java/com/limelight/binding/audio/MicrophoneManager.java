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

/**
 * 麦克风管理器
 * 负责管理麦克风的权限、状态和UI更新
 */
public class MicrophoneManager {
    
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
        
        try {
            // 更新比特率配置
            MicrophoneConfig.updateBitrateFromConfig(context);
            
            microphoneStream = new MicrophoneStream(connection);
            if (!microphoneStream.start()) {
                LimeLog.warning("无法启动麦克风流");
                return false;
            } else {
                LimeLog.info("麦克风流启动成功");
            }
            
            // 检查麦克风是否真的可用
            if (!microphoneStream.isMicrophoneAvailable()) {
                LimeLog.warning("主机不支持麦克风功能");
            }
            
            // 初始化后默认暂停麦克风，等待用户主动开启
            if (microphoneStream.isRunning()) {
                microphoneStream.pause();
                LimeLog.info("麦克风流已初始化，默认状态为关闭");
            }
            
            return true;
        } catch (Exception e) {
            LimeLog.severe("初始化麦克风流失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 切换麦克风状态
     */
    public void toggleMicrophone() {
        // 首先检查权限
        if (!checkMicrophonePermission()) {
            return;
        }
        
        if (microphoneStream != null) {
            if (microphoneStream.isRunning()) {
                pauseMicrophone();
            } else {
                resumeMicrophone();
            }
        } else {
            // 如果麦克风流不存在，尝试创建并启动
            if (connection != null) {
                if (initializeMicrophoneStream()) {
                    // 默认不自动开启麦克风，需要用户再次点击
                    updateMicrophoneButtonState();
                    showMessage(context.getString(R.string.mic_disabled));
                } else {
                    showPermissionError();
                }
            } else {
                showPermissionError();
            }
        }
        
        // 更新按钮状态
        updateMicrophoneButtonState();
    }
    
    /**
     * 暂停麦克风
     */
    public void pauseMicrophone() {
        if (microphoneStream != null && microphoneStream.isRunning()) {
            microphoneStream.pause();
            showMessage(context.getString(R.string.mic_disabled));
            if (stateListener != null) {
                stateListener.onMicrophoneStateChanged(false);
            }
        }
    }
    
    /**
     * 恢复麦克风
     */
    public void resumeMicrophone() {
        if (microphoneStream != null && !microphoneStream.isRunning()) {
            // 尝试恢复麦克风，如果失败则重新初始化
            if (microphoneStream.resume()) {
                showMessage(context.getString(R.string.mic_enabled));
                if (stateListener != null) {
                    stateListener.onMicrophoneStateChanged(true);
                }
            } else {
                // 如果恢复失败，尝试重新启动整个麦克风流
                LimeLog.warning("麦克风恢复失败，尝试重新初始化");
                microphoneStream.stop();
                
                // 更新比特率配置
                MicrophoneConfig.updateBitrateFromConfig(context);
                
                microphoneStream = new MicrophoneStream(connection);
                if (microphoneStream.start()) {
                    showMessage(context.getString(R.string.mic_enabled));
                    if (stateListener != null) {
                        stateListener.onMicrophoneStateChanged(true);
                    }
                } else {
                    showPermissionError();
                }
            }
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
     * 检查麦克风是否真正可用（包括权限和设备状态）
     */
    public boolean isMicrophoneTrulyAvailable() {
        // 检查权限
        if (!hasMicrophonePermission()) {
            return false;
        }
        
        // 检查麦克风流是否可用
        if (microphoneStream == null) {
            return false;
        }
        
        // 检查主机是否请求麦克风
        if (!microphoneStream.isMicrophoneAvailable()) {
            return false;
        }
        
        return true;
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
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) 
                == PackageManager.PERMISSION_GRANTED;
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
        if (requestCode == MicrophoneConfig.PERMISSION_REQUEST_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，延迟一下再切换麦克风，确保系统权限状态已更新
                new android.os.Handler().postDelayed(() -> {
                    // 再次检查权限状态
                    if (hasMicrophonePermission()) {
                        // 权限确实已授予，现在可以安全地切换麦克风
                        toggleMicrophone();
                    } else {
                        showPermissionError();
                    }
                }, MicrophoneConfig.PERMISSION_DELAY_MS); // 延迟时间
            } else {
                // 权限被拒绝
                showPermissionError();
            }
        }
    }
    
    /**
     * 设置麦克风按钮
     */
    private void setupMicrophoneButton() {
        if (micButton == null) return;
        
        // 只有在启用了麦克风重定向时才显示按钮
        if (enableMic) {
            micButton.setVisibility(android.view.View.VISIBLE);
            updateMicrophoneButtonState();
            
            micButton.setOnClickListener(v -> {
                if (checkMicrophonePermission()) {
                    toggleMicrophone();
                }
            });
        } else {
            micButton.setVisibility(android.view.View.GONE);
        }
    }
    
    /**
     * 更新麦克风按钮状态
     */
    public void updateMicrophoneButtonState() {
        if (micButton == null) return;
        
        // 检查麦克风是否真正可用
        boolean micAvailable = isMicrophoneTrulyAvailable();
        
        if (isMicrophoneActive()) {
            micButton.setSelected(true);
            micButton.setImageResource(R.drawable.ic_btn_mic);
            micButton.setContentDescription(context.getString(R.string.mic_enabled));
        } else {
            micButton.setSelected(false);
            micButton.setImageResource(R.drawable.ic_btn_mic_disabled);
            micButton.setContentDescription(context.getString(R.string.mic_disabled));
        }
        
        // 默认启用按钮，让用户可以点击开启麦克风
        // 只有在完全没有权限或主机不支持时才禁用
        micButton.setEnabled(true);
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
    
    /**
     * 显示权限错误消息
     */
    private void showPermissionError() {
        showMessage(context.getString(R.string.mic_permission_required));
    }
    
    /**
     * 显示消息
     */
    private void showMessage(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 测试麦克风状态（用于调试）
     */
    public void testMicrophoneStatus() {
        boolean hasPermission = hasMicrophonePermission();
        boolean micStreamExists = microphoneStream != null;
        boolean micAvailable = micStreamExists && microphoneStream.isMicrophoneAvailable();
        boolean micActive = isMicrophoneActive();
        
        String status = String.format("权限: %s, 流存在: %s, 可用: %s, 激活: %s", 
                hasPermission, micStreamExists, micAvailable, micActive);
        
        LimeLog.info("麦克风状态: " + status);
        Toast.makeText(context, status, Toast.LENGTH_LONG).show();
    }
    
    /**
     * 设置麦克风启用状态
     */
    public void setEnableMic(boolean enable) {
        this.enableMic = enable;
        
        // 如果启用麦克风，更新比特率配置
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