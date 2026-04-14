@file:Suppress("DEPRECATION")
package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

import com.limelight.BuildConfig
import com.limelight.R

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/qiin2333/moonlight-vplus/releases/latest"
    private const val GITHUB_RELEASE_PAGE = "https://github.com/qiin2333/moonlight-vplus/releases/latest"
    private const val UPDATE_CHECK_INTERVAL = 4 * 60 * 60 * 1000L

    // 代理发现地址
    private const val PROXY_DISCOVERY_URL = "https://ghproxy.link/js/src_views_home_HomeView_vue.js"

    // API与下载的代理前缀（按优先级尝试）- 将在运行时动态更新
    @Volatile
    private var PROXY_PREFIXES: Array<String> = emptyArray()

    private val isChecking = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // 代理缓存相关
    private const val PROXY_CACHE_DURATION = 24 * 60 * 60 * 1000L // 24小时
    private const val PREF_LAST_PROXY_UPDATE_TIME = "last_proxy_update_time"

    // SharedPreferences 中存储下载信息的 key
    private const val PREF_DOWNLOAD_ID = "update_download_id"
    private const val PREF_DOWNLOAD_APK_NAME = "update_download_apk_name"

    // 安装权限请求码（供 Activity 的 onActivityResult 使用）
    const val INSTALL_PERMISSION_REQUEST_CODE = 9527

    // 等待安装权限授予后再执行下载的暂存信息
    @Volatile
    private var pendingUpdateInfo: UpdateInfo? = null

    // 下载进度轮询间隔
    private const val PROGRESS_POLL_INTERVAL_MS = 300L

    // 当前进度对话框与 handler（用于在 Activity 销毁时清理）
    private var currentProgressDialog: AlertDialog? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    fun checkForUpdates(context: Context, showToast: Boolean) {
        if (isChecking.getAndSet(true)) {
            return
        }
        executor.execute(UpdateCheckTask(context, showToast))
    }

    fun checkForUpdatesOnStartup(context: Context) {
        val lastCheckTime = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                .getLong("last_check_time", 0)
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastCheckTime > UPDATE_CHECK_INTERVAL) {
            checkForUpdates(context, false)
        }
    }

    /**
     * 当用户在系统设置中授予安装权限后由 Activity.onActivityResult 调用。
     * 如果之前有暂存的更新信息且权限已授予，则自动开始下载。
     */
    fun onInstallPermissionResult(context: Context) {
        if (pendingUpdateInfo != null && canInstallApk(context)) {
            val info = pendingUpdateInfo
            pendingUpdateInfo = null
            startDirectDownload(context, info!!)
        } else if (pendingUpdateInfo != null) {
            pendingUpdateInfo = null
            Toast.makeText(context, context.getString(R.string.toast_install_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 由 [UpdateDownloadReceiver] 在下载完成时调用。
     * 也会由应用内进度轮询在检测到完成时调用。
     */
    fun onDownloadComplete(context: Context, completedDownloadId: Long) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val savedDownloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1)

        if (savedDownloadId == -1L || savedDownloadId != completedDownloadId) {
            return // 不是我们的下载
        }

        val apkName = prefs.getString(PREF_DOWNLOAD_APK_NAME, "update.apk")

        // 清除已保存的下载信息
        prefs.edit()
                .remove(PREF_DOWNLOAD_ID)
                .remove(PREF_DOWNLOAD_APK_NAME)
                .apply()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return

        // 检查下载是否成功
        val query = DownloadManager.Query()
        query.setFilterById(completedDownloadId)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0) {
                    val status = cursor.getInt(statusIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Log.d(TAG, "下载成功，准备安装: $apkName")
                        installApk(context, completedDownloadId)
                        return
                    }
                }
            }
        }

        Log.w(TAG, "下载可能失败，downloadId=$completedDownloadId")
    }

    /**
     * 清理进度对话框资源（在 Activity 销毁时调用）
     */
    fun cleanup() {
        dismissProgressDialog()
        if (progressHandler != null && progressRunnable != null) {
            progressHandler!!.removeCallbacks(progressRunnable!!)
        }
    }

    // ------------------------------------------------------------------
    // 更新检查
    // ------------------------------------------------------------------

    private class UpdateCheckTask(private val context: Context, private val showToast: Boolean) : Runnable {

        override fun run() {
            if (shouldUpdateProxyList(context)) {
                updateProxyList(context)
            }

            var updateInfo: UpdateInfo? = null
            try {
                val json = httpGetWithProxies(GITHUB_API_URL)
                if (json != null) {
                    val jsonResponse = JSONObject(json)
                    val latestVersion = jsonResponse.optString("tag_name", "")
                    val releaseNotes = jsonResponse.optString("body", "")

                    // 解析资产，优先选择APK
                    var apkUrl: String? = null
                    var apkName: String? = null
                    val assets = jsonResponse.optJSONArray("assets")
                    if (assets != null) {
                        val apkAssets = ArrayList<JSONObject>()
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i)
                            if (a != null) {
                                val name = a.optString("name", "")
                                val url = a.optString("browser_download_url", "")
                                if (name.endsWith(".apk") && url.startsWith("http")) {
                                    apkAssets.add(a)
                                }
                            }
                        }
                        // 优先匹配root/nonRoot
                        for (a in apkAssets) {
                            val name = a.optString("name", "")
                            val isRootApk = name.lowercase().contains("root")
                            if (isRootApk == BuildConfig.ROOT_BUILD) {
                                apkName = name
                                apkUrl = a.opt("browser_download_url") as? String
                                break
                            }
                        }
                        // 若没匹配到，退而求其次取第一个APK
                        if (apkUrl == null && apkAssets.isNotEmpty()) {
                            val a = apkAssets[0]
                            apkName = a.opt("name") as? String
                            apkUrl = a.opt("browser_download_url") as? String
                        }
                    }

                    updateInfo = UpdateInfo(latestVersion, releaseNotes, apkName, apkUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
            }

            val finalUpdateInfo = updateInfo

            if (context is Activity) {
                context.runOnUiThread { handleUpdateResult(finalUpdateInfo) }
            }
        }

        private fun handleUpdateResult(updateInfo: UpdateInfo?) {
            isChecking.set(false)

            context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_check_time", System.currentTimeMillis())
                    .apply()

            if (updateInfo == null) {
                if (showToast) {
                    Toast.makeText(context, context.getString(R.string.toast_check_update_failed), Toast.LENGTH_SHORT).show()
                }
                return
            }

            val currentVersion = getCurrentVersion(context)
            if (isNewVersionAvailable(currentVersion, updateInfo.version)) {
                showUpdateDialog(context, updateInfo)
            } else if (showToast) {
                showLatestVersionDialog(context, currentVersion, updateInfo.releaseNotes)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对话框
    // ------------------------------------------------------------------

    private fun showLatestVersionDialog(context: Context, currentVersion: String, releaseNotes: String?) {
        if (context !is Activity) {
            Toast.makeText(context, context.getString(R.string.toast_already_latest_version, currentVersion), Toast.LENGTH_SHORT).show()
            return
        }

        val activity = context
        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity, R.style.AppDialogStyle)
            builder.setTitle(context.getString(R.string.update_already_latest_title))

            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

            val version = view.findViewById<TextView>(R.id.update_version)
            version.text = "v$currentVersion"

            if (releaseNotes != null && releaseNotes.trim().isNotEmpty()) {
                val accentColor = ContextCompat.getColor(activity, R.color.theme_pink_primary)
                val notesScroll = view.findViewById<ScrollView>(R.id.update_notes_scroll)
                notesScroll.visibility = View.VISIBLE
                val notes = view.findViewById<TextView>(R.id.update_notes)
                notes.text = SimpleMarkdownRenderer.render(releaseNotes, accentColor)
            }

            builder.setView(view)
            builder.setPositiveButton(context.getString(R.string.update_btn_got_it), null)
            builder.setCancelable(true)
            builder.show()
        }
    }

    private fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        if (context !is Activity) {
            return
        }

        val activity = context
        activity.runOnUiThread {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

            val curVer = getCurrentVersion(context)
            val version = view.findViewById<TextView>(R.id.update_version)
            version.text = "v$curVer → v${updateInfo.version}"

            if (updateInfo.releaseNotes != null && updateInfo.releaseNotes.isNotEmpty()) {
                val accentColor = ContextCompat.getColor(activity, R.color.theme_pink_primary)
                val notesScroll = view.findViewById<ScrollView>(R.id.update_notes_scroll)
                notesScroll.visibility = View.VISIBLE
                val notesView = view.findViewById<TextView>(R.id.update_notes)
                notesView.text = SimpleMarkdownRenderer.render(updateInfo.releaseNotes, accentColor)
            }

            if (updateInfo.apkName != null) {
                val fileName = view.findViewById<TextView>(R.id.update_file_name)
                fileName.text = updateInfo.apkName
                fileName.visibility = View.VISIBLE
            }

            val builder = AlertDialog.Builder(activity, R.style.AppDialogStyle)
            builder.setTitle(context.getString(R.string.update_new_version_title))
            builder.setView(view)

            builder.setPositiveButton(context.getString(R.string.update_btn_browser_download)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASE_PAGE))
                context.startActivity(intent)
            }

            if (updateInfo.apkDownloadUrl != null) {
                builder.setNeutralButton(context.getString(R.string.update_btn_direct_download)) { _, _ ->
                    if (!canInstallApk(context)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            showInstallPermissionDialog(context, updateInfo)
                        } else {
                            Toast.makeText(context, context.getString(R.string.toast_cannot_get_install_permission), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        startDirectDownload(context, updateInfo)
                    }
                }
            }

            builder.setNegativeButton(context.getString(R.string.update_btn_later), null)
            builder.setCancelable(true)
            builder.show()
        }
    }

    // ------------------------------------------------------------------
    // 安装权限
    // ------------------------------------------------------------------

    private fun canInstallApk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.packageManager.canRequestPackageInstalls()
        }
        return true
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun showInstallPermissionDialog(context: Context, info: UpdateInfo) {
        // 暂存更新信息，等权限授予后恢复
        pendingUpdateInfo = info

        if (context !is Activity) {
            Toast.makeText(context, context.getString(R.string.toast_need_install_permission), Toast.LENGTH_LONG).show()
            return
        }

        val activity = context
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(context.getString(R.string.update_install_permission_title))
        builder.setMessage(context.getString(R.string.update_install_permission_msg))
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:" + context.getPackageName())
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST_CODE)
                } catch (e2: Exception) {
                    pendingUpdateInfo = null
                    Toast.makeText(context, context.getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ -> pendingUpdateInfo = null }
        builder.setCancelable(false)
        builder.show()
    }

    // ------------------------------------------------------------------
    // 下载 APK
    // ------------------------------------------------------------------

    private fun startDirectDownload(context: Context, info: UpdateInfo) {
        try {
            val src = info.apkDownloadUrl!!
            val fileName = info.apkName ?: ("moonlight-" + info.version + ".apk")

            // 构造候选 URL 列表（代理优先，最后直连）
            val candidates = ArrayList<String>()
            for (p in PROXY_PREFIXES) {
                candidates.add(p + src)
            }
            candidates.add(src) // 直连兜底

            // 使用第一个候选 URL 开始下载
            val primaryUrl = candidates[0]

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                Toast.makeText(context, context.getString(R.string.toast_download_service_unavailable), Toast.LENGTH_SHORT).show()
                return
            }

            val req = DownloadManager.Request(Uri.parse(primaryUrl))
            req.setTitle(context.getString(R.string.update_download_notification_title))
            req.setDescription(fileName)
            req.setMimeType("application/vnd.android.package-archive")
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setVisibleInDownloadsUi(true)
            req.setAllowedOverMetered(true)
            req.setAllowedOverRoaming(true)
            req.addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)")
            req.addRequestHeader("Accept", "*/*")
            req.addRequestHeader("Referer", "https://github.com/")
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadId = dm.enqueue(req)
            Log.d(TAG, "已启动下载，ID: $downloadId, URL: $primaryUrl")

            // 保存下载信息到 SharedPreferences（供 BroadcastReceiver 和代理重试使用）
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                    .putLong(PREF_DOWNLOAD_ID, downloadId)
                    .putString(PREF_DOWNLOAD_APK_NAME, fileName)
                    // 保存完整候选 URL 列表用于重试
                    .putString("update_download_candidates", joinStrings(candidates))
                    .putInt("update_download_candidate_index", 0)
                    .apply()

            // 如果当前在 Activity 中，显示应用内进度对话框
            if (context is Activity) {
                showDownloadProgressDialog(context, downloadId, dm, candidates, fileName)
            } else {
                Toast.makeText(context, context.getString(R.string.toast_download_started), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            Toast.makeText(context, context.getString(R.string.toast_download_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // 应用内下载进度对话框
    // ------------------------------------------------------------------

    private fun showDownloadProgressDialog(activity: Activity, downloadId: Long,
                                           dm: DownloadManager,
                                           candidates: List<String>, fileName: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)

        val progressBar = view.findViewById<ProgressBar>(R.id.download_progress_bar)
        val progressText = view.findViewById<TextView>(R.id.download_progress_text)

        val dialog = AlertDialog.Builder(activity, R.style.AppDialogStyle)
                .setTitle(activity.getString(R.string.update_downloading_title))
                .setView(view)
                .setNegativeButton(activity.getString(R.string.update_btn_background)) { _, _ ->
                    Toast.makeText(activity, activity.getString(R.string.toast_download_continue_bg), Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .create()

        dialog.show()
        currentProgressDialog = dialog

        // 使用 Handler 轮询下载进度
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        val currentDownloadId = longArrayOf(downloadId)
        val currentCandidateIndex = intArrayOf(0)

        val runnable = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    dismissProgressDialog()
                    return
                }

                val query = DownloadManager.Query()
                query.setFilterById(currentDownloadId[0])

                try {
                    dm.query(query)?.use { cursor ->
                        if (!cursor.moveToFirst()) {
                            // 下载可能已被取消
                            dismissProgressDialog()
                            return
                        }

                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                        if (statusIndex < 0) {
                            dismissProgressDialog()
                            return
                        }

                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = if (bytesDownloadedIndex >= 0) cursor.getLong(bytesDownloadedIndex) else 0L
                        val bytesTotal = if (bytesTotalIndex >= 0) cursor.getLong(bytesTotalIndex) else -1L

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                if (bytesTotal > 0) {
                                    val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = progress
                                    val downloadedMB = String.format("%.1f", bytesDownloaded / 1048576.0)
                                    val totalMB = String.format("%.1f", bytesTotal / 1048576.0)
                                    progressText.text = "$downloadedMB MB / $totalMB MB ($progress%)"
                                } else {
                                    progressBar.isIndeterminate = true
                                    val downloadedMB = String.format("%.1f", bytesDownloaded / 1048576.0)
                                    progressText.text = activity.getString(R.string.update_progress_downloaded, downloadedMB)
                                }
                                handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                            }

                            DownloadManager.STATUS_PENDING -> {
                                progressBar.isIndeterminate = true
                                progressText.text = activity.getString(R.string.update_progress_waiting)
                                handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                            }

                            DownloadManager.STATUS_PAUSED -> {
                                progressText.text = activity.getString(R.string.update_progress_paused)
                                handler.postDelayed(this, 1000)
                            }

                            DownloadManager.STATUS_SUCCESSFUL -> {
                                progressBar.progress = 100
                                progressText.text = activity.getString(R.string.update_progress_installing)
                                dismissProgressDialog()
                                // 触发安装
                                onDownloadComplete(activity, currentDownloadId[0])
                            }

                            DownloadManager.STATUS_FAILED -> {
                                val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                Log.w(TAG, "下载失败, reason=$reason, candidateIndex=${currentCandidateIndex[0]}")

                                // 尝试下一个代理
                                currentCandidateIndex[0]++
                                if (currentCandidateIndex[0] < candidates.size) {
                                    dm.remove(currentDownloadId[0])
                                    val nextUrl = candidates[currentCandidateIndex[0]]
                                    Log.d(TAG, "尝试备用下载链接: $nextUrl")
                                    progressText.text = activity.getString(R.string.update_progress_switching_source)

                                    try {
                                        val retryReq = DownloadManager.Request(Uri.parse(nextUrl))
                                        retryReq.setTitle(activity.getString(R.string.update_download_notification_title))
                                        retryReq.setDescription(fileName)
                                        retryReq.setMimeType("application/vnd.android.package-archive")
                                        retryReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        retryReq.setVisibleInDownloadsUi(true)
                                        retryReq.setAllowedOverMetered(true)
                                        retryReq.setAllowedOverRoaming(true)
                                        retryReq.addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)")
                                        retryReq.addRequestHeader("Accept", "*/*")
                                        retryReq.addRequestHeader("Referer", "https://github.com/")
                                        retryReq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                                        val newDownloadId = dm.enqueue(retryReq)
                                        currentDownloadId[0] = newDownloadId

                                        // 更新 SharedPreferences 中的下载 ID
                                        val prefs = activity.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                                        prefs.edit()
                                                .putLong(PREF_DOWNLOAD_ID, newDownloadId)
                                                .putInt("update_download_candidate_index", currentCandidateIndex[0])
                                                .apply()

                                        handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "备用下载也失败", e)
                                        dismissProgressDialog()
                                        Toast.makeText(activity, activity.getString(R.string.toast_all_sources_failed), Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    dismissProgressDialog()
                                    Toast.makeText(activity, activity.getString(R.string.toast_download_failed_try_browser), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } ?: run {
                        // cursor is null
                        dismissProgressDialog()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "查询下载进度失败", e)
                    handler.postDelayed(this, 1000)
                }
            }
        }

        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun dismissProgressDialog() {
        if (currentProgressDialog != null && currentProgressDialog!!.isShowing) {
            try {
                currentProgressDialog!!.dismiss()
            } catch (ignored: Exception) {
            }
        }
        currentProgressDialog = null
    }

    // ------------------------------------------------------------------
    // APK 安装
    // ------------------------------------------------------------------

    private fun installApk(context: Context, downloadId: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return

            val downloadedUri = dm.getUriForDownloadedFile(downloadId)
            if (downloadedUri == null) {
                Log.w(TAG, "无法获取下载文件 URI")
                Toast.makeText(context, context.getString(R.string.toast_cannot_get_download_file), Toast.LENGTH_LONG).show()
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用 content:// URI
                // DownloadManager.getUriForDownloadedFile 在 N+ 已经返回 content:// URI
                installIntent.setDataAndType(downloadedUri, "application/vnd.android.package-archive")
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                installIntent.setDataAndType(downloadedUri, "application/vnd.android.package-archive")
            }

            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(installIntent)

            Log.d(TAG, "已启动安装界面")
        } catch (e: Exception) {
            Log.e(TAG, "启动安装失败", e)
            Toast.makeText(context, context.getString(R.string.toast_install_launch_failed), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // 版本比较
    // ------------------------------------------------------------------

    private fun getCurrentVersion(context: Context): String {
        try {
            val packageInfo = context.packageManager
                    .getPackageInfo(context.packageName, 0)
            return packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "获取当前版本失败", e)
            return "0.0.0"
        }
    }

    private fun isNewVersionAvailable(currentVersion: String, latestVersion: String): Boolean {
        try {
            val current = currentVersion.replaceFirst("^[Vv]".toRegex(), "")
            val latest = latestVersion.replaceFirst("^[Vv]".toRegex(), "")

            val currentParts = current.split(".")
            val latestParts = latest.split(".")

            val maxLength = Math.max(currentParts.size, latestParts.size)

            for (i in 0 until maxLength) {
                val currentPart = if (i < currentParts.size) currentParts[i].toInt() else 0
                val latestPart = if (i < latestParts.size) latestParts[i].toInt() else 0

                if (latestPart > currentPart) {
                    return true
                } else if (latestPart < currentPart) {
                    return false
                }
            }
            return false
        } catch (e: NumberFormatException) {
            Log.e(TAG, "版本号格式错误: current=$currentVersion, latest=$latestVersion", e)
            return false
        }
    }

    // ------------------------------------------------------------------
    // 代理相关（保持原有逻辑）
    // ------------------------------------------------------------------

    private fun shouldUpdateProxyList(context: Context): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                .getLong(PREF_LAST_PROXY_UPDATE_TIME, 0)
        return (currentTime - lastUpdateTime) > PROXY_CACHE_DURATION || PROXY_PREFIXES.isEmpty()
    }

    private fun updateProxyList(context: Context) {
        try {
            Log.d(TAG, "开始更新代理列表...")
            val scriptContent = fetchScriptContent()
            if (scriptContent != null) {
                val newProxies = extractProxiesFromScript(scriptContent)
                if (newProxies.isNotEmpty()) {
                    val allProxies = HashSet(PROXY_PREFIXES.toList())
                    allProxies.addAll(newProxies)

                    PROXY_PREFIXES = allProxies.toTypedArray()

                    context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putLong(PREF_LAST_PROXY_UPDATE_TIME, System.currentTimeMillis())
                            .apply()

                    Log.d(TAG, "代理列表已更新，共 ${PROXY_PREFIXES.size} 个代理：${PROXY_PREFIXES.contentToString()}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新代理列表失败: ${e.message}")
        }
    }

    private fun fetchScriptContent(): String? {
        try {
            val url = URL(PROXY_DISCOVERY_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)")
            conn.connectTimeout = 2000
            conn.readTimeout = 2000

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val content = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    content.append(line).append("\n")
                }
                reader.close()
                return content.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取代理发现脚本失败: ${e.message}")
        }
        return null
    }

    private fun extractProxiesFromScript(scriptContent: String): Array<String> {
        val proxies = ArrayList<String>()

        try {
            val patterns = arrayOf(
                    "[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
                    "baseUrl\\s*=\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
                    "url:\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
                    "[\"']https://(?:gh|mirror|proxy|cdn)[\\w.-]*\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']"
            )

            for (patternStr in patterns) {
                val pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(scriptContent)

                while (matcher.find()) {
                    val match = matcher.group()
                    var urlStr = match.replace("[\"']".toRegex(), "")

                    if (urlStr.startsWith("https://")) {
                        if (!urlStr.endsWith("/")) {
                            urlStr = "$urlStr/"
                        }
                        if (isValidProxyUrl(urlStr)) {
                            proxies.add(urlStr)
                            Log.d(TAG, "发现代理地址: $urlStr")
                        }
                    }
                }
            }

            val domainPattern = Pattern.compile("(?:proxy|mirror|gh|cdn)[\\w.-]*\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)", Pattern.CASE_INSENSITIVE)
            val domainMatcher = domainPattern.matcher(scriptContent)

            while (domainMatcher.find()) {
                val domain = domainMatcher.group()
                val proxyUrl = "https://$domain/"
                if (isValidProxyUrl(proxyUrl)) {
                    proxies.add(proxyUrl)
                    Log.d(TAG, "发现域名代理: $proxyUrl")
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "解析代理地址失败: ${e.message}")
        }

        val uniqueProxies = HashSet(proxies)
        return uniqueProxies.toTypedArray()
    }

    private fun isValidProxyUrl(url: String?): Boolean {
        if (url == null || url.length < 15 || url.length > 100) {
            return false
        }

        val blacklist = arrayOf(
                "github.com", "googleapis.com", "gstatic.com",
                "jquery.com", "bootstrap.com", "cdnjs.com",
                "unpkg.com", "jsdelivr.net", "ghproxy.link"
        )

        for (blocked in blacklist) {
            if (url.contains(blocked)) {
                return false
            }
        }

        if (detectRedirectToGhproxyLink(url)) {
            return false
        }

        return true
    }

    private fun detectRedirectToGhproxyLink(proxyUrl: String): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val testUrl = proxyUrl + "https://api.github.com/zen"
            conn = URL(testUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)")
            conn.connectTimeout = 2000
            conn.readTimeout = 2000

            val responseCode = conn.responseCode

            if (responseCode in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location != null && location.contains("ghproxy.link")) {
                    Log.d(TAG, "代理重定向回 ghproxy.link，排除: $proxyUrl")
                    return true
                }
            }

            if (conn.url.toString().contains("ghproxy.link")) {
                Log.d(TAG, "代理最终URL包含 ghproxy.link，排除: $proxyUrl")
                return true
            }

            val server = conn.getHeaderField("Server")
            if (server != null && server.lowercase().contains("ghproxy")) {
                Log.d(TAG, "代理服务器信息包含 ghproxy，排除: $proxyUrl")
                return true
            }

            Log.d(TAG, "代理检测通过: $proxyUrl (响应码: $responseCode)")
            return false

        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "代理连接失败，排除: $proxyUrl - ${e.message}")
            return true
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "代理连接失败，排除: $proxyUrl - ${e.message}")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "代理检测异常但不排除: $proxyUrl - ${e.message}")
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGetWithProxies(url: String): String? {
        val tries = buildProxiedUrls(url)

        val maxTries = Math.min(tries.size, 3)
        for (i in 0 until maxTries) {
            val u = tries[i]
            try {
                val connection = URL(u).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Moonlight-Android")
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    return response.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Request failed, trying next: $u - ${e.message}")
            }
        }
        return null
    }

    /**
     * Build a list of candidate URLs: original first, then proxied variants.
     */
    fun buildProxiedUrls(url: String): List<String> {
        val tries = ArrayList<String>()
        tries.add(url)
        for (p in PROXY_PREFIXES) {
            tries.add(p + url)
        }
        return tries
    }

    fun ensureProxyListUpdated(context: Context) {
        if (shouldUpdateProxyList(context)) {
            updateProxyList(context)
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun joinStrings(list: List<String>): String {
        val sb = StringBuilder()
        for (i in list.indices) {
            if (i > 0) sb.append("|||")
            sb.append(list[i])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 数据类
    // ------------------------------------------------------------------

    private class UpdateInfo(
            val version: String,
            val releaseNotes: String?,
            val apkName: String?,
            val apkDownloadUrl: String?
    )
}
