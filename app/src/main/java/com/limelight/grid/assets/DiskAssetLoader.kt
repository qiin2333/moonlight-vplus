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
                inSampleSize = capInSampleSize(
                    calculateInSampleSize(
                        decodeOnlyOptions,
                        decodeOnlyOptions.outWidth / sampleSize,
                        decodeOnlyOptions.outHeight / sampleSize
                    ),
                    decodeOnlyOptions.outWidth,
                    decodeOnlyOptions.outHeight,
                    MAX_ICON_DECODE_DIMENSION
                )
                if (isLowRamDevice) {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
            }

            val bmp: Bitmap? = try {
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                if (decoded != null) {
                    // createScaledBitmap 会在解码结果仍存活时再分配一份，同样可能 OOM，
                    // 压缩与解码共用同一个 OOM 兜底
                    val compressedBmp = compressLargeBitmap(decoded)
                    if (compressedBmp !== decoded) {
                        decoded.recycle()
                    }
                    compressedBmp
                } else null
            } catch (e: OutOfMemoryError) {
                LimeLog.warning("OOM while decoding tuple $tuple; using placeholder")
                null
            }
            if (bmp != null) {
                LimeLog.info("Tuple $tuple decoded from disk cache with sample size: ${options.inSampleSize}")
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

                    // imageInfo.size 始终是原图尺寸；setTargetSize 只影响解码输出
                    val longSide = max(imageInfo.size.width, imageInfo.size.height)
                    if (longSide > MAX_ICON_DECODE_DIMENSION) {
                        val scale = MAX_ICON_DECODE_DIMENSION.toFloat() / longSide
                        imageDecoder.setTargetSize(
                            max(1, (imageInfo.size.width * scale).toInt()),
                            max(1, (imageInfo.size.height * scale).toInt())
                        )
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
            } catch (e: OutOfMemoryError) {
                LimeLog.warning("OOM while decoding tuple $tuple; using placeholder")
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
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        // 头信息读不出来时按原尺寸盲解码可能直接 OOM
                        LimeLog.warning("Skipping unreadable cached art for app $appId")
                        return null
                    }
                    capInSampleSize(1, bounds.outWidth, bounds.outHeight, maxDimension)
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
        } catch (e: OutOfMemoryError) {
            LimeLog.warning("OOM while decoding full bitmap for app $appId")
            null
        }
    }

    /**
     * 压缩过大的Bitmap：等比缩放到 1MB 内。不设最小宽高钳制——
     * 两维独立钳制会破坏宽高比（横版封面被竖向拉伸）。
     */
    private fun compressLargeBitmap(original: Bitmap?): Bitmap? {
        if (original == null) return null

        val byteCount = original.byteCount
        val maxSize = 1024 * 1024 // 1MB限制

        if (byteCount > maxSize) {
            try {
                val (newWidth, newHeight) = compressedDimensions(original.width, original.height, byteCount, maxSize)

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

        // 图标解码长边硬上限（像素）。图标最终会被 compressLargeBitmap 压到
        // 1MB 以内，超过该尺寸的解码只是白占内存并可能 OOM。
        private const val MAX_ICON_DECODE_DIMENSION = 1024

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

        /**
         * 在按比例采样（calculateInSampleSize）之后追加长边硬上限。
         * scalingDivisor 收敛为 1 时按比例采样不会生效，超大自定义封面
         * （如 4900×4900、文件却不足 20MB）会被原尺寸解码直接 OOM。
         * 只会放大 inSampleSize，不会缩小；maxDimension <= 0 时不生效。
         */
        fun capInSampleSize(baseSampleSize: Int, srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
            if (maxDimension <= 0) return baseSampleSize
            // inSampleSize 必须是 2 的幂（非 2 幂会被解码器向下取整，如 3→2），
            // 基准先归一到不超过它的最大 2 的幂，保证返回值即实际生效的采样
            var sampleSize = Integer.highestOneBit(max(baseSampleSize, 1)).toLong()
            val longSide = max(srcWidth, srcHeight).toLong()
            // 判断用向上取整：解码器对非整除采样按向上取整取尺寸（4097/4 → 1025）
            while (longSide > 0 && (longSide + sampleSize - 1L) / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            return sampleSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * 等比压缩维度：byteCount 超过 maxBytes 时按面积比缩放，宽高比不变。
         * 不设最小显示尺寸；维度下限 1，防止极端比例 round 到 0 使
         * createScaledBitmap 抛异常。
         */
        fun compressedDimensions(width: Int, height: Int, byteCount: Int, maxBytes: Int): Pair<Int, Int> {
            if (byteCount <= maxBytes) return width to height
            val scale = sqrt(maxBytes.toDouble() / byteCount).toFloat()
            return max(1, round(width * scale).toInt()) to max(1, round(height * scale).toInt())
        }
    }
}
