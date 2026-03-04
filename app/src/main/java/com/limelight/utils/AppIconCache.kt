package com.limelight.utils

import android.graphics.Bitmap
import android.util.LruCache
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp

/**
 * 全局App Icon缓存管理器
 */
class AppIconCache private constructor() {
    private val iconCache: LruCache<String, Bitmap>

    init {
        // 获取应用可用内存的1/8作为缓存大小
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8

        iconCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                // 返回bitmap占用的内存大小（KB）
                return bitmap.byteCount / 1024
            }
        }
    }

    /**
     * 生成缓存键
     */
    private fun generateKey(computer: ComputerDetails, app: NvApp): String {
        return "${computer.uuid}_${app.appId}"
    }

    /**
     * 存储app icon
     */
    fun putIcon(computer: ComputerDetails?, app: NvApp?, icon: Bitmap?) {
        if (computer != null && app != null && icon != null) {
            val key = generateKey(computer, app)
            iconCache.put(key, icon)
        }
    }

    /**
     * 获取app icon
     */
    fun getIcon(computer: ComputerDetails?, app: NvApp?): Bitmap? {
        if (computer != null && app != null) {
            val key = generateKey(computer, app)
            return iconCache.get(key)
        }
        return null
    }

    /**
     * 清除缓存
     */
    fun clear() {
        iconCache.evictAll()
    }

    /**
     * 清除特定电脑的缓存
     */
    fun clearForComputer(computerUuid: String?) {
        // 由于LruCache没有提供按前缀删除的方法，我们只能清除所有缓存
        clear()
    }

    companion object {
        @JvmStatic
        val instance: AppIconCache by lazy { AppIconCache() }
    }
}
