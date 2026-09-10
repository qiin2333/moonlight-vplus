@file:Suppress("DEPRECATION")
package com.limelight.grid.assets

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build

import com.limelight.LimeLog
import com.limelight.utils.CacheHelper
import com.limelight.utils.HostCacheKey

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

class DiskAssetLoader(context: Context) {

    private val isLowRamDevice: Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val cacheDir: File = context.cacheDir

    fun checkCacheExists(tuple: CachedAppAssetLoader.LoaderTuple): Boolean {
        val cacheKey = HostCacheKey.fromUuid(tuple.computer.uuid) ?: return false
        return CacheHelper.cacheFileExists(cacheDir, "boxart", cacheKey, "${tuple.app.appId}.png")
    }

    fun loadBitmapFromCache(tuple: CachedAppAssetLoader.LoaderTuple, sampleSize: Int): ScaledBitmap? {
        val computerUuid = tuple.computer.uuid ?: return null
        val file = getFile(computerUuid, tuple.app.appId) ?: return null

        if (!file.exists()) {
            return null
        }

        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: $tuple")
            file.delete()
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val decodeOnlyOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOnlyOptions)
            if (decodeOnlyOptions.outWidth <= 0 || decodeOnlyOptions.outHeight <= 0) {
                return null
            }

            LimeLog.info("Tuple $tuple has cached art of size: ${decodeOnlyOptions.outWidth}x${decodeOnlyOptions.outHeight}")

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    decodeOnlyOptions,
                    decodeOnlyOptions.outWidth / sampleSize,
                    decodeOnlyOptions.outHeight / sampleSize
                )
                if (isLowRamDevice) {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
            }

            var bmp = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bmp != null) {
                LimeLog.info("Tuple $tuple decoded from disk cache with sample size: ${options.inSampleSize}")

                val compressedBmp = compressLargeBitmap(bmp)
                if (compressedBmp !== bmp) {
                    bmp.recycle()
                    bmp = compressedBmp!!
                }

                return ScaledBitmap(decodeOnlyOptions.outWidth, decodeOnlyOptions.outHeight, bmp)
            }
        } else {
            val scaledBitmap = ScaledBitmap()
            try {
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(file)
                ) { imageDecoder, imageInfo, _ ->
                    scaledBitmap.originalWidth = imageInfo.size.width
                    scaledBitmap.originalHeight = imageInfo.size.height

                    if (isLowRamDevice) {
                        imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                    }
                }

                if (scaledBitmap.bitmap != null) {
                    val compressedBmp = compressLargeBitmap(scaledBitmap.bitmap)
                    if (compressedBmp !== scaledBitmap.bitmap) {
                        scaledBitmap.bitmap?.recycle()
                        scaledBitmap.bitmap = compressedBmp
                    }
                }

                return scaledBitmap
            } catch (e: IOException) {
                e.printStackTrace()
                return null
            }
        }

        return null
    }

    /**
     * 从磁盘缓存加载Bitmap用于背景图显示。
     * @param maxDimension 目标最大边长（像素）。0 或负数表示不下采样。
     *                     超大原图会被 inSampleSize / setTargetSize 缩到 <= maxDimension，
     *                     避免低内存设备 OOM（典型 4K TV/低端机 SDK 23）。
     */
    fun loadFullBitmapFromCache(computerUuid: String, appId: Int, maxDimension: Int = 0): Bitmap? {
        val file = getFile(computerUuid, appId) ?: return null
        if (!file.exists() || file.length() > MAX_ASSET_SIZE) {
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(file)
                ) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (maxDimension > 0) {
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = max(w, h)
                        if (longSide > maxDimension) {
                            val scale = maxDimension.toFloat() / longSide
                            decoder.setTargetSize(
                                max(1, (w * scale).toInt()),
                                max(1, (h * scale).toInt())
                            )
                        }
                    }
                }
            } else {
                // 两遍解码：先读 bounds 算 inSampleSize
                val sampleSize = if (maxDimension > 0) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val longSide = max(bounds.outWidth, bounds.outHeight)
                    var s = 1
                    while (longSide > 0 && (longSide / s) > maxDimension) s *= 2
                    s
                } else 1
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = if (isLowRamDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 压缩过大的Bitmap
     */
    private fun compressLargeBitmap(original: Bitmap?): Bitmap? {
        if (original == null) return null

        val byteCount = original.byteCount
        val maxSize = 1024 * 1024 // 1MB限制

        if (byteCount > maxSize) {
            try {
                val scale = sqrt(maxSize.toDouble() / byteCount).toFloat()
                var newWidth = round(original.width * scale).toInt()
                var newHeight = round(original.height * scale).toInt()

                newWidth = max(newWidth, 300)
                newHeight = max(newHeight, 400)

                val compressed = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)

                LimeLog.info(
                    "DiskAssetLoader: Compressed bitmap from ${original.width}x${original.height}" +
                    " to ${newWidth}x${newHeight} (size: $byteCount -> ${compressed.byteCount} bytes)"
                )

                return compressed
            } catch (e: Exception) {
                LimeLog.warning("DiskAssetLoader: Failed to compress bitmap: ${e.message}")
                return original
            }
        }

        return original
    }

    fun getFile(computerUuid: String, appId: Int): File? {
        val cacheKey = HostCacheKey.fromUuid(computerUuid) ?: return null
        return CacheHelper.openPath(false, cacheDir, "boxart", cacheKey, "$appId.png")
    }

    fun deleteAssetsForComputer(computerUuid: String) {
        val cacheKey = HostCacheKey.fromUuid(computerUuid) ?: return
        val dir = CacheHelper.openPath(false, cacheDir, "boxart", cacheKey)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun populateCacheWithStream(tuple: CachedAppAssetLoader.LoaderTuple, input: InputStream) {
        val cacheKey = HostCacheKey.fromUuid(tuple.computer.uuid)
        if (cacheKey == null) {
            LimeLog.warning("Skipping box art cache for host without an identifier")
            return
        }
        var success = false
        try {
            CacheHelper.openCacheFileForOutput(
                cacheDir, "boxart", cacheKey, "${tuple.app.appId}.png"
            ).use { out ->
                CacheHelper.writeInputStreamToOutputStream(input, out, MAX_ASSET_SIZE)
                success = true
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (!success) {
                LimeLog.warning("Unable to populate cache with tuple: $tuple")
                CacheHelper.deleteCacheFile(cacheDir, "boxart", cacheKey, "${tuple.app.appId}.png")
            }
        }
    }

    companion object {
        private const val MAX_ASSET_SIZE = 20L * 1024 * 1024 // 20 MB

        private const val STANDARD_ASSET_WIDTH = 300
        private const val STANDARD_ASSET_HEIGHT = 400

        // https://developer.android.com/topic/performance/graphics/load-bitmap.html
        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            return inSampleSize
        }
    }
}
