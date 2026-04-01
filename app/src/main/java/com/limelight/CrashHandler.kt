package com.limelight

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局崩溃处理器，捕获未处理的异常并保存到文件
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    
    companion object {
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val MAX_LOG_FILES = 10
        
        @Volatile
        private var instance: CrashHandler? = null
        
        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = CrashHandler(context.applicationContext)
                        Thread.setDefaultUncaughtExceptionHandler(instance)
                    }
                }
            }
        }
        
        fun getCrashLogDir(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), CRASH_LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
        
        fun getCrashLogFiles(context: Context): List<File> {
            val dir = getCrashLogDir(context)
            return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
        
        /**
         * 手动记录异常到崩溃日志（用于捕获的异常）
         */
        fun logException(context: Context, throwable: Throwable, tag: String = "Exception") {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName = "error_${tag}_$timestamp.log"
                
                val logDir = getCrashLogDir(context)
                val logFile = File(logDir, fileName)
                
                val errorInfo = buildString {
                    appendLine("=== Error Report ($tag) ===")
                    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("Thread: ${Thread.currentThread().name}")
                    appendLine("Type: Caught Exception (Non-Fatal)")
                    appendLine()
                    appendLine("=== Device Info ===")
                    appendLine("Brand: ${Build.BRAND}")
                    appendLine("Model: ${Build.MODEL}")
                    appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Manufacturer: ${Build.MANUFACTURER}")
                    appendLine()
                    appendLine("=== Exception ===")
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    appendLine(sw.toString())
                }
                
                logFile.writeText(errorInfo)
                LimeLog.severe("Error log saved: ${logFile.absolutePath}")
                
                // 清理旧日志
                val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
                if (files.size > MAX_LOG_FILES) {
                    files.drop(MAX_LOG_FILES).forEach { it.delete() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                LimeLog.severe("Failed to save error log: ${e.message}")
            }
        }
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(thread, throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "crash_$timestamp.log"
        
        val logDir = getCrashLogDir(context)
        val logFile = File(logDir, fileName)
        
        val crashInfo = buildString {
            appendLine("=== Crash Report ===")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== Device Info ===")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine()
            appendLine("=== Exception ===")
            appendLine(getStackTraceString(throwable))
        }
        
        logFile.writeText(crashInfo)
        LimeLog.severe("Crash log saved: ${logFile.absolutePath}")
        
        cleanOldLogs(logDir)
    }
    
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }
    
    private fun cleanOldLogs(logDir: File) {
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { it.delete() }
        }
    }
}
