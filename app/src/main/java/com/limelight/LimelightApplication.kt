package com.limelight

import android.app.Application

class LimelightApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化崩溃日志处理器
        CrashHandler.init(this)
    }
}
