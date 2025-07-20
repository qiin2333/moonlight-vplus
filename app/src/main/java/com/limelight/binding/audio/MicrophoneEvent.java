package com.limelight.binding.audio;

/**
 * 麦克风事件类
 * 定义麦克风相关的事件类型和数据结构
 */
public class MicrophoneEvent {
    
    /**
     * 麦克风事件类型
     */
    public enum EventType {
        PERMISSION_GRANTED,      // 权限授予
        PERMISSION_DENIED,       // 权限拒绝
        STARTED,                 // 麦克风启动
        STOPPED,                 // 麦克风停止
        PAUSED,                  // 麦克风暂停
        RESUMED,                 // 麦克风恢复
        ERROR,                   // 错误
        HOST_REQUESTED,          // 主机请求麦克风
        HOST_STOPPED_REQUEST     // 主机停止请求麦克风
    }
    
    private final EventType type;
    private final String message;
    private final long timestamp;
    private final Throwable error;
    
    public MicrophoneEvent(EventType type) {
        this(type, null, null);
    }
    
    public MicrophoneEvent(EventType type, String message) {
        this(type, message, null);
    }
    
    public MicrophoneEvent(EventType type, String message, Throwable error) {
        this.type = type;
        this.message = message;
        this.error = error;
        this.timestamp = System.currentTimeMillis();
    }
    
    public EventType getType() {
        return type;
    }
    
    public String getMessage() {
        return message;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public Throwable getError() {
        return error;
    }
    
    @Override
    public String toString() {
        return "MicrophoneEvent{" +
                "type=" + type +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                ", error=" + error +
                '}';
    }
} 