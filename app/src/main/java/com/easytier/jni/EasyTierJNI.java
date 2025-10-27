package com.easytier.jni;

/**
 * EasyTier JNI 接口类
 * 提供 Android 应用调用 EasyTier 网络功能的接口。
 * 这是连接 Java/Kotlin 代码与底层 Rust 库的桥梁。
 */
public final class EasyTierJNI {

    // 私有构造函数，防止该类被实例化
    private EasyTierJNI() {}

    static {
        // 加载本地库 (libeasytier_android_jni.so)
        System.loadLibrary("easytier_android_jni");
    }

    /**
     * 设置 TUN 文件描述符。
     * @param instanceName 实例名称
     * @param fd TUN 文件描述符
     * @return 0 表示成功，-1 表示失败
     */
    public static native int setTunFd(String instanceName, int fd);

    /**
     * 解析配置字符串以进行验证。
     * @param config TOML 格式的配置字符串
     * @return 0 表示成功，-1 表示失败
     */
    public static native int parseConfig(String config);

    /**
     * 根据配置运行一个新的网络实例。
     * @param config TOML 格式的配置字符串
     * @return 0 表示成功，-1 表示失败
     */
    public static native int runNetworkInstance(String config);

    /**
     * 保留指定的网络实例，停止其他所有实例。
     * @param instanceNames 要保留的实例名称数组，传入 null 或空数组将停止所有实例
     * @return 0 表示成功，-1 表示失败
     */
    public static native int retainNetworkInstance(String[] instanceNames);

    /**
     * 收集所有正在运行的网络实例的信息。
     * @param maxLength 最大返回条目数
     * @return 包含网络信息的 JSON 格式字符串
     */
    public static native String collectNetworkInfos(int maxLength);

    /**
     * 获取最后一次 JNI 调用发生的错误消息。
     * @return 错误消息字符串，如果没有错误则返回 null
     */
    public static native String getLastError();

    /**
     * 便利方法：停止所有网络实例。
     * @return 0 表示成功，-1 表示失败
     */
    public static int stopAllInstances() {
        return retainNetworkInstance(null);
    }
}