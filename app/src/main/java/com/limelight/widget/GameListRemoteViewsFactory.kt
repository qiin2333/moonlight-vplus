package com.limelight.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService

import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.AppCacheManager
import com.limelight.utils.CacheHelper

import java.io.StringReader

class GameListRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra("appWidgetId", 0)
    private var appList: MutableList<NvApp> = mutableListOf()
    private var computerUuid: String? = null
    private var computerName: String? = null

    override fun onCreate() {
        // Data loading is done in onDataSetChanged()
    }

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        computerUuid = prefs.getString("widget_${appWidgetId}_uuid", null)
        computerName = prefs.getString("widget_${appWidgetId}_name", null)

        if (computerUuid == null) {
            appList.clear()
            return
        }

        val uuid = computerUuid ?: return
        try {
            val rawAppList = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(context.cacheDir, "applist", uuid)
            )

            if (rawAppList.isNotEmpty()) {
                appList = NvHTTP.getAppListByReader(StringReader(rawAppList))
                appList.sortWith(Comparator { lhs, rhs ->
                    lhs.appName.compareTo(rhs.appName, ignoreCase = true)
                })
            } else {
                appList.clear()
            }
        } catch (e: Exception) {
            LimeLog.warning("Failed to read app list for widget: ${e.message}")
            appList.clear()
        }
    }

    override fun onDestroy() {
        appList.clear()
    }

    override fun getCount(): Int = appList.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position >= appList.size) return null

        val app = appList[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_item_layout)

        rv.setTextViewText(R.id.widget_item_text, app.appName)

        // Load Box Art
        val bmp = loadBoxArt(app.appId)
        if (bmp != null) {
            rv.setImageViewBitmap(R.id.widget_item_image, bmp)
        } else {
            rv.setImageViewResource(R.id.widget_item_image, R.drawable.no_app_image)
        }

        // Fill-in Intent for click
        val extras = Bundle().apply {
            putString("UUID", computerUuid)
            putString("AppId", app.appId.toString())
            putString("AppName", app.appName)
            putBoolean("HDR", app.isHdrSupported())
        }

        // 保存完整的应用信息到缓存中，以便 ShortcutTrampoline 可以恢复
        try {
            val cacheManager = AppCacheManager(context)
            cacheManager.saveAppInfo(computerUuid, app)
        } catch (e: Exception) {
            LimeLog.warning("Failed to save app info to cache: ${e.message}")
        }

        val fillInIntent = Intent().apply { putExtras(extras) }
        rv.setOnClickFillInIntent(R.id.widget_item_image, fillInIntent)
        rv.setOnClickFillInIntent(R.id.widget_item_text, fillInIntent)

        return rv
    }

    private fun loadBoxArt(appId: Int): Bitmap? {
        val uuid = computerUuid ?: return null
        val file = CacheHelper.openPath(false, context.cacheDir, "boxart", uuid, "$appId.png")
        if (!file.exists()) return null

        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(options, 200, 266)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
