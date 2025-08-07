package com.limelight.utils;

import android.graphics.Bitmap;
import android.util.LruCache;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

/**
 * 全局App Icon缓存管理器
 */
public class AppIconCache {
    private static AppIconCache instance;
    private final LruCache<String, Bitmap> iconCache;
    
    private AppIconCache() {
        // 获取应用可用内存的1/8作为缓存大小
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        
        iconCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // 返回bitmap占用的内存大小（KB）
                return bitmap.getByteCount() / 1024;
            }
        };
    }
    
    public static AppIconCache getInstance() {
        if (instance == null) {
            instance = new AppIconCache();
        }
        return instance;
    }
    
    /**
     * 生成缓存键
     */
    private String generateKey(ComputerDetails computer, NvApp app) {
        return computer.uuid + "_" + app.getAppId();
    }
    
    /**
     * 存储app icon
     */
    public void putIcon(ComputerDetails computer, NvApp app, Bitmap icon) {
        if (computer != null && app != null && icon != null) {
            String key = generateKey(computer, app);
            iconCache.put(key, icon);
        }
    }
    
    /**
     * 获取app icon
     */
    public Bitmap getIcon(ComputerDetails computer, NvApp app) {
        if (computer != null && app != null) {
            String key = generateKey(computer, app);
            return iconCache.get(key);
        }
        return null;
    }
    
    /**
     * 清除缓存
     */
    public void clear() {
        iconCache.evictAll();
    }
    
    /**
     * 清除特定电脑的缓存
     */
    public void clearForComputer(String computerUuid) {
        // 由于LruCache没有提供按前缀删除的方法，我们只能清除所有缓存
        // 在实际应用中，可以考虑使用更复杂的缓存实现
        clear();
    }
}
