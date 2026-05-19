# 保留 JNI 入口与原生方法声明，避免 R8 把 native 方法所在类裁掉导致 UnsatisfiedLinkError。
-keep class com.limelight.framegen.FramegenInterceptor { *; }
-keepclasseswithmembernames class com.limelight.framegen.** {
    native <methods>;
}
