package com.limelight.binding.audio

/**
 * 麦克风事件类
 * 定义麦克风相关的事件类型和数据结构
 */
class MicrophoneEvent @JvmOverloads constructor(
    val type: EventType,
    val message: String? = null,
    val error: Throwable? = null
) {
    val timestamp: Long = System.currentTimeMillis()

    /**
     * 麦克风事件类型
     */
    enum class EventType {
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

    override fun toString(): String {
        return "MicrophoneEvent{type=$type, message='$message', timestamp=$timestamp, error=$error}"
    }
}
