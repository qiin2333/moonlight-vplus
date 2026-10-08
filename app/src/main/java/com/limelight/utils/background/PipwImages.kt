package com.limelight.utils.background

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.limelight.preferences.BackgroundSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

class PipwImages private constructor(private val directory: File, private val assets: AssetManager) {
    private val downloader by lazy {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Pipw temporary directory unavailable")
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("pipw-") }?.forEach { it.delete() }
        val index = assets.open("backgrounds/pipw_review_index.json").bufferedReader().use {
            PipwReviewIndex.parse(it.readText())
        }
        PipwDownloader(PipwDownloader.client(), index, directory, ::isDecodable)
    }
    private val store = PipwImageStore({ pool, url, operation -> downloader.load(pool, url, operation) })

    fun acquire(resolved: BackgroundSource.ResolvedTarget, orientation: Int): PipwImageStore.Lease {
        val target = resolved.target ?: throw IOException("Missing Pipw source")
        val url = PipwUrlPolicy.api(target.toHttpUrlOrNull() ?: throw IOException("Invalid Pipw source"), orientation)
        val pool = if (url.queryParameter("phone") == "true") PipwPool.PHONE else PipwPool.PC
        return store.acquire(resolved.cacheKey, pool, url.toString())
    }

    private fun isDecodable(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    companion object {
        @Volatile private var instance: PipwImages? = null

        fun get(context: Context): PipwImages = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                PipwImages(File(app.cacheDir, "vplus-backgrounds"), app.assets).also { instance = it }
            }
        }

        fun handles(resolved: BackgroundSource.ResolvedTarget): Boolean =
            resolved.source === BackgroundSource.Pipw || resolved.target?.toHttpUrlOrNull()?.host == PipwUrlPolicy.API_HOST

        fun imageExtension(file: File): String {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return when (options.outMimeType) {
                "image/webp" -> "webp"
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/avif" -> "avif"
                "image/bmp", "image/x-ms-bmp" -> "bmp"
                else -> throw IOException("Unsupported Pipw export format")
            }
        }
    }
}
