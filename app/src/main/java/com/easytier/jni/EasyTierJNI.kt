package com.easytier.jni

/**
 * EasyTier JNI 接口类
 * 提供 Android 应用调用 EasyTier 网络功能的接口。
 * 这是连接 Java/Kotlin 代码与底层 Rust 库的桥梁。
 */
object EasyTierJNI {

    init {
        // 加载本地库 (libeasytier_android_jni.so)
        System.loadLibrary("easytier_android_jni")
    }

    /**
     * 设置 TUN 文件描述符。
     */
    external fun setTunFd(instanceName: String, fd: Int): Int

    /**
     * 解析配置字符串以进行验证。
     */
    external fun parseConfig(config: String): Int

    /**
     * 根据配置运行一个新的网络实例。
     */
    external fun runNetworkInstance(config: String): Int

    /**
     * 保留指定的网络实例，停止其他所有实例。
     * @param instanceNames 要保留的实例名称数组，传入 null 或空数组将停止所有实例
     */
    external fun retainNetworkInstance(instanceNames: Array<String>?): Int

    /**
     * 收集所有正在运行的网络实例的信息。
     * @param maxLength 最大返回条目数
     * @return 包含网络信息的 JSON 格式字符串
     */
    external fun collectNetworkInfos(maxLength: Int): String

    /**
     * 获取最后一次 JNI 调用发生的错误消息。
     * @return 错误消息字符串，如果没有错误则返回 null
     */
    external fun getLastError(): String?

    /**
     * 便利方法：停止所有网络实例。
     */
    fun stopAllInstances(): Int {
        return retainNetworkInstance(null)
    }
}
