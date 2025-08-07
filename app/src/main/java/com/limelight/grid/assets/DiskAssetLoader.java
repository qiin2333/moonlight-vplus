package com.limelight.grid.assets;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DiskAssetLoader {
    // 20 MB
    private static final long MAX_ASSET_SIZE = 20 * 1024 * 1024;

    // Standard box art is 300x400
    private static final int STANDARD_ASSET_WIDTH = 300;
    private static final int STANDARD_ASSET_HEIGHT = 400;

    private final boolean isLowRamDevice;
    private final File cacheDir;

    public DiskAssetLoader(Context context) {
        this.cacheDir = context.getCacheDir();
        this.isLowRamDevice =
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).isLowRamDevice();
    }

    public boolean checkCacheExists(CachedAppAssetLoader.LoaderTuple tuple) {
        return CacheHelper.cacheFileExists(cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png");
    }

    // https://developer.android.com/topic/performance/graphics/load-bitmap.html
    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculates the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public ScaledBitmap loadBitmapFromCache(CachedAppAssetLoader.LoaderTuple tuple, int sampleSize) {
        File file = getFile(tuple.computer.uuid, tuple.app.getAppId());

        // Don't bother with anything if it doesn't exist
        if (!file.exists()) {
            return null;
        }

        // Make sure the cached asset doesn't exceed the maximum size
        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: "+tuple);
            file.delete();
            return null;
        }

        Bitmap bmp;

        // For OSes prior to P, we have to use the ugly BitmapFactory API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Lookup bounds of the downloaded image
            BitmapFactory.Options decodeOnlyOptions = new BitmapFactory.Options();
            decodeOnlyOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOnlyOptions);
            if (decodeOnlyOptions.outWidth <= 0 || decodeOnlyOptions.outHeight <= 0) {
                // Dimensions set to -1 on error. Return value always null.
                return null;
            }

            LimeLog.info("Tuple "+tuple+" has cached art of size: "+decodeOnlyOptions.outWidth+"x"+decodeOnlyOptions.outHeight);

            // Load the image scaled to the appropriate size
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(decodeOnlyOptions,
                    decodeOnlyOptions.outWidth / sampleSize,
                    decodeOnlyOptions.outHeight / sampleSize);
            if (isLowRamDevice) {
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inDither = true;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                options.inPreferredConfig = Bitmap.Config.HARDWARE;
            }

            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bmp != null) {
                LimeLog.info("Tuple "+tuple+" decoded from disk cache with sample size: "+options.inSampleSize);
                
                // 检查并压缩过大的Bitmap
                Bitmap compressedBmp = compressLargeBitmap(bmp);
                if (compressedBmp != bmp) {
                    bmp.recycle(); // 回收原始Bitmap
                    bmp = compressedBmp;
                }
                
                return new ScaledBitmap(decodeOnlyOptions.outWidth, decodeOnlyOptions.outHeight, bmp);
            }
        }
        else {
            // On P, we can get a bitmap back in one step with ImageDecoder
            final ScaledBitmap scaledBitmap = new ScaledBitmap();
            try {
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source) {
                        scaledBitmap.originalWidth = imageInfo.getSize().getWidth();
                        scaledBitmap.originalHeight = imageInfo.getSize().getHeight();

                        // imageDecoder.setTargetSize(STANDARD_ASSET_WIDTH, STANDARD_ASSET_HEIGHT);
                        if (isLowRamDevice) {
                            imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                        }
                    }
                });
                
                // 检查并压缩过大的Bitmap
                if (scaledBitmap.bitmap != null) {
                    Bitmap compressedBmp = compressLargeBitmap(scaledBitmap.bitmap);
                    if (compressedBmp != scaledBitmap.bitmap) {
                        scaledBitmap.bitmap.recycle(); // 回收原始Bitmap
                        scaledBitmap.bitmap = compressedBmp;
                    }
                }
                
                return scaledBitmap;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        return null;
    }
    
    /**
     * 压缩过大的Bitmap
     */
    private Bitmap compressLargeBitmap(Bitmap original) {
        if (original == null) return null;
        
        // 计算Bitmap的内存大小（字节）
        int byteCount = original.getByteCount();
        int maxSize = 1024 * 1024; // 1MB限制
        
        // 如果大小超过限制，进行压缩
        if (byteCount > maxSize) {
            try {
                // 计算压缩比例
                float scale = (float) Math.sqrt((double) maxSize / byteCount);
                int newWidth = Math.round(original.getWidth() * scale);
                int newHeight = Math.round(original.getHeight() * scale);
                
                // 确保最小尺寸
                newWidth = Math.max(newWidth, 300);
                newHeight = Math.max(newHeight, 400);
                
                // 创建压缩后的Bitmap
                Bitmap compressed = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
                
                LimeLog.info("DiskAssetLoader: Compressed bitmap from " + original.getWidth() + "x" + original.getHeight() + 
                           " to " + newWidth + "x" + newHeight + " (size: " + byteCount + " -> " + compressed.getByteCount() + " bytes)");
                
                return compressed;
            } catch (Exception e) {
                LimeLog.warning("DiskAssetLoader: Failed to compress bitmap: " + e.getMessage());
                return original;
            }
        }
        
        return original;
    }

    public File getFile(String computerUuid, int appId) {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, appId + ".png");
    }

    public void deleteAssetsForComputer(String computerUuid) {
        File dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    public void populateCacheWithStream(CachedAppAssetLoader.LoaderTuple tuple, InputStream input) {
        boolean success = false;
        try (final OutputStream out = CacheHelper.openCacheFileForOutput(
                cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png")
        ) {
            CacheHelper.writeInputStreamToOutputStream(input, out, MAX_ASSET_SIZE);
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (!success) {
                LimeLog.warning("Unable to populate cache with tuple: "+tuple);
                CacheHelper.deleteCacheFile(cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png");
            }
        }
    }
}
