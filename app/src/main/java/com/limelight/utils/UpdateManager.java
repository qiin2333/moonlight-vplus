package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import com.limelight.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateManager {
	private static final String TAG = "UpdateManager";
	private static final String GITHUB_API_URL = "https://api.github.com/repos/qiin2333/moonlight-vplus/releases/latest";
	private static final String GITHUB_RELEASE_PAGE = "https://github.com/qiin2333/moonlight-vplus/releases/latest";
	private static final long UPDATE_CHECK_INTERVAL = 4 * 60 * 60 * 1000;

	// 代理发现地址
	private static final String PROXY_DISCOVERY_URL = "https://ghproxy.link/js/src_views_home_HomeView_vue.js";

	// API与下载的代理前缀（按优先级尝试）- 将在运行时动态更新
	private static volatile String[] PROXY_PREFIXES = new String[]{};

	private static final AtomicBoolean isChecking = new AtomicBoolean(false);
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();

	// 代理缓存相关
	private static final long PROXY_CACHE_DURATION = 24 * 60 * 60 * 1000; // 24小时
	private static final String PREF_LAST_PROXY_UPDATE_TIME = "last_proxy_update_time";

	// SharedPreferences 中存储下载信息的 key
	private static final String PREF_DOWNLOAD_ID = "update_download_id";
	private static final String PREF_DOWNLOAD_APK_NAME = "update_download_apk_name";

	// 安装权限请求码（供 Activity 的 onActivityResult 使用）
	public static final int INSTALL_PERMISSION_REQUEST_CODE = 9527;

	// 等待安装权限授予后再执行下载的暂存信息
	private static volatile UpdateInfo pendingUpdateInfo;

	// 下载进度轮询间隔
	private static final long PROGRESS_POLL_INTERVAL_MS = 300;

	// 当前进度对话框与 handler（用于在 Activity 销毁时清理）
	private static AlertDialog currentProgressDialog;
	private static Handler progressHandler;
	private static Runnable progressRunnable;

	// ------------------------------------------------------------------
	// 公开 API
	// ------------------------------------------------------------------

	public static void checkForUpdates(Context context, boolean showToast) {
		if (isChecking.getAndSet(true)) {
			return;
		}
		executor.execute(new UpdateCheckTask(context, showToast));
	}

	public static void checkForUpdatesOnStartup(Context context) {
		long lastCheckTime = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
				.getLong("last_check_time", 0);
		long currentTime = System.currentTimeMillis();

		if (currentTime - lastCheckTime > UPDATE_CHECK_INTERVAL) {
			checkForUpdates(context, false);
		}
	}

	/**
	 * 当用户在系统设置中授予安装权限后由 Activity.onActivityResult 调用。
	 * 如果之前有暂存的更新信息且权限已授予，则自动开始下载。
	 */
	public static void onInstallPermissionResult(Context context) {
		if (pendingUpdateInfo != null && canInstallApk(context)) {
			UpdateInfo info = pendingUpdateInfo;
			pendingUpdateInfo = null;
			startDirectDownload(context, info);
		} else if (pendingUpdateInfo != null) {
			pendingUpdateInfo = null;
			Toast.makeText(context, "未获得安装权限，下载已取消", Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * 由 {@link UpdateDownloadReceiver} 在下载完成时调用。
	 * 也会由应用内进度轮询在检测到完成时调用。
	 */
	public static void onDownloadComplete(Context context, long completedDownloadId) {
		SharedPreferences prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
		long savedDownloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1);

		if (savedDownloadId == -1 || savedDownloadId != completedDownloadId) {
			return; // 不是我们的下载
		}

		String apkName = prefs.getString(PREF_DOWNLOAD_APK_NAME, "update.apk");

		// 清除已保存的下载信息
		prefs.edit()
				.remove(PREF_DOWNLOAD_ID)
				.remove(PREF_DOWNLOAD_APK_NAME)
				.apply();

		DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
		if (dm == null) return;

		// 检查下载是否成功
		DownloadManager.Query query = new DownloadManager.Query();
		query.setFilterById(completedDownloadId);
		try (Cursor cursor = dm.query(query)) {
			if (cursor != null && cursor.moveToFirst()) {
				int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
				if (statusIndex >= 0) {
					int status = cursor.getInt(statusIndex);
					if (status == DownloadManager.STATUS_SUCCESSFUL) {
						Log.d(TAG, "下载成功，准备安装: " + apkName);
						installApk(context, completedDownloadId);
						return;
					}
				}
			}
		}

		Log.w(TAG, "下载可能失败，downloadId=" + completedDownloadId);
	}

	/**
	 * 清理进度对话框资源（在 Activity 销毁时调用）
	 */
	public static void cleanup() {
		dismissProgressDialog();
		if (progressHandler != null && progressRunnable != null) {
			progressHandler.removeCallbacks(progressRunnable);
		}
	}

	// ------------------------------------------------------------------
	// 更新检查
	// ------------------------------------------------------------------

	private static class UpdateCheckTask implements Runnable {
		private final Context context;
		private final boolean showToast;

		public UpdateCheckTask(Context context, boolean showToast) {
			this.context = context;
			this.showToast = showToast;
		}

		@Override
		public void run() {
			if (shouldUpdateProxyList(context)) {
				updateProxyList(context);
			}

			UpdateInfo updateInfo = null;
			try {
				String json = httpGetWithProxies(GITHUB_API_URL);
				if (json != null) {
					JSONObject jsonResponse = new JSONObject(json);
					String latestVersion = jsonResponse.optString("tag_name", "");
					String releaseNotes = jsonResponse.optString("body", "");

					// 解析资产，优先选择APK
					String apkUrl = null;
					String apkName = null;
					JSONArray assets = jsonResponse.optJSONArray("assets");
					if (assets != null) {
						List<JSONObject> apkAssets = new ArrayList<>();
						for (int i = 0; i < assets.length(); i++) {
							JSONObject a = assets.optJSONObject(i);
							if (a != null) {
								String name = a.optString("name", "");
								String url = a.optString("browser_download_url", "");
								if (name.endsWith(".apk") && url.startsWith("http")) {
									apkAssets.add(a);
								}
							}
						}
						// 优先匹配root/nonRoot
						for (JSONObject a : apkAssets) {
							String name = a.optString("name", "");
							boolean isRootApk = name.toLowerCase().contains("root");
							if (isRootApk == BuildConfig.ROOT_BUILD) {
								apkName = name;
								apkUrl = a.optString("browser_download_url", null);
								break;
							}
						}
						// 若没匹配到，退而求其次取第一个APK
						if (apkUrl == null && !apkAssets.isEmpty()) {
							JSONObject a = apkAssets.get(0);
							apkName = a.optString("name", null);
							apkUrl = a.optString("browser_download_url", null);
						}
					}

					updateInfo = new UpdateInfo(latestVersion, releaseNotes, apkName, apkUrl);
				}
			} catch (Exception e) {
				Log.e(TAG, "检查更新失败", e);
			}

			final UpdateInfo finalUpdateInfo = updateInfo;

			if (context instanceof Activity) {
				((Activity) context).runOnUiThread(() -> handleUpdateResult(finalUpdateInfo));
			}
		}

		private void handleUpdateResult(UpdateInfo updateInfo) {
			isChecking.set(false);

			context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
					.edit()
					.putLong("last_check_time", System.currentTimeMillis())
					.apply();

			if (updateInfo == null) {
				if (showToast) {
					Toast.makeText(context, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show();
				}
				return;
			}

			String currentVersion = getCurrentVersion(context);
			if (isNewVersionAvailable(currentVersion, updateInfo.version)) {
				showUpdateDialog(context, updateInfo);
			} else if (showToast) {
				showLatestVersionDialog(context, currentVersion, updateInfo.releaseNotes);
			}
		}
	}

	// ------------------------------------------------------------------
	// 对话框
	// ------------------------------------------------------------------

	private static void showLatestVersionDialog(Context context, String currentVersion, String releaseNotes) {
		if (!(context instanceof Activity)) {
			Toast.makeText(context, "已是最新版本 v" + currentVersion, Toast.LENGTH_SHORT).show();
			return;
		}

		Activity activity = (Activity) context;
		activity.runOnUiThread(() -> {
			AlertDialog.Builder builder = new AlertDialog.Builder(activity);
			builder.setTitle("✨ 已是最新版本 v" + currentVersion);

			String message;
			if (releaseNotes != null && !releaseNotes.trim().isEmpty()) {
				message = "当前版本更新内容：\n\n" + releaseNotes;
			} else {
				message = "当前版本暂无更新日志";
			}

			builder.setMessage(message);
			builder.setPositiveButton("知道了", null);
			builder.setCancelable(true);

			AlertDialog dialog = builder.create();
			dialog.show();
		});
	}

	private static void showUpdateDialog(Context context, UpdateInfo updateInfo) {
		if (!(context instanceof Activity)) {
			return;
		}

		Activity activity = (Activity) context;
		activity.runOnUiThread(() -> {
			AlertDialog.Builder builder = new AlertDialog.Builder(activity);
			builder.setTitle("发现新版本: " + updateInfo.version);

			String message = "有新版本可用！\n\n";
			if (updateInfo.releaseNotes != null && !updateInfo.releaseNotes.isEmpty()) {
				String notes = updateInfo.releaseNotes;
				if (notes.length() > 300) {
					notes = notes.substring(0, 300) + "...";
				}
				message += "更新内容：\n" + notes + "\n\n";
			}
			if (updateInfo.apkName != null) {
				message += "文件: " + updateInfo.apkName + "\n\n";
			}
			message += "请选择下载方式";

			builder.setMessage(message);

			builder.setPositiveButton("打开浏览器更新", (dialog, which) -> {
				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASE_PAGE));
				context.startActivity(intent);
			});
			if (updateInfo.apkDownloadUrl != null) {
				builder.setNeutralButton("直接下载", (dialog, which) -> {
					if (!canInstallApk(context)) {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							showInstallPermissionDialog(context, updateInfo);
						} else {
							Toast.makeText(context, "无法获得安装权限", Toast.LENGTH_SHORT).show();
						}
					} else {
						startDirectDownload(context, updateInfo);
					}
				});
			}
			builder.setNegativeButton("稍后", null);
			builder.setCancelable(true);

			AlertDialog dialog = builder.create();
			dialog.show();
		});
	}

	// ------------------------------------------------------------------
	// 安装权限
	// ------------------------------------------------------------------

	private static boolean canInstallApk(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return context.getPackageManager().canRequestPackageInstalls();
		}
		return true;
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private static void showInstallPermissionDialog(Context context, UpdateInfo info) {
		// 暂存更新信息，等权限授予后恢复
		pendingUpdateInfo = info;

		if (!(context instanceof Activity)) {
			Toast.makeText(context, "需要安装权限才能自动安装更新", Toast.LENGTH_LONG).show();
			return;
		}

		Activity activity = (Activity) context;
		AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setTitle("需要安装权限");
		builder.setMessage("为了自动安装更新，需要授予应用安装权限。\n\n点击确定前往设置页面开启权限，返回后将自动开始下载。");
		builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
				intent.setData(Uri.parse("package:" + context.getPackageName()));
				activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST_CODE);
			} catch (Exception e) {
				try {
					Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
					activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST_CODE);
				} catch (Exception e2) {
					pendingUpdateInfo = null;
					Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show();
				}
			}
		});
		builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> pendingUpdateInfo = null);
		builder.setCancelable(false);
		builder.show();
	}

	// ------------------------------------------------------------------
	// 下载 APK
	// ------------------------------------------------------------------

	private static void startDirectDownload(Context context, UpdateInfo info) {
		try {
			String src = info.apkDownloadUrl;
			String fileName = info.apkName != null ? info.apkName : ("moonlight-" + info.version + ".apk");

			// 构造候选 URL 列表（代理优先，最后直连）
			List<String> candidates = new ArrayList<>();
			for (String p : PROXY_PREFIXES) {
				candidates.add(p + src);
			}
			candidates.add(src); // 直连兜底

			// 使用第一个候选 URL 开始下载
			String primaryUrl = candidates.get(0);

			DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
			if (dm == null) {
				Toast.makeText(context, "系统下载服务不可用", Toast.LENGTH_SHORT).show();
				return;
			}

			DownloadManager.Request req = new DownloadManager.Request(Uri.parse(primaryUrl));
			req.setTitle("Moonlight V+ 更新下载");
			req.setDescription(fileName);
			req.setMimeType("application/vnd.android.package-archive");
			req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
			req.setVisibleInDownloadsUi(true);
			req.setAllowedOverMetered(true);
			req.setAllowedOverRoaming(true);
			req.addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
			req.addRequestHeader("Accept", "*/*");
			req.addRequestHeader("Referer", "https://github.com/");
			req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

			long downloadId = dm.enqueue(req);
			Log.d(TAG, "已启动下载，ID: " + downloadId + ", URL: " + primaryUrl);

			// 保存下载信息到 SharedPreferences（供 BroadcastReceiver 和代理重试使用）
			SharedPreferences prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
			prefs.edit()
					.putLong(PREF_DOWNLOAD_ID, downloadId)
					.putString(PREF_DOWNLOAD_APK_NAME, fileName)
					// 保存完整候选 URL 列表用于重试
					.putString("update_download_candidates", joinStrings(candidates))
					.putInt("update_download_candidate_index", 0)
					.apply();

			// 如果当前在 Activity 中，显示应用内进度对话框
			if (context instanceof Activity) {
				showDownloadProgressDialog((Activity) context, downloadId, dm, candidates, fileName);
			} else {
				Toast.makeText(context, "已开始下载，下载完成后点击通知栏即可安装", Toast.LENGTH_LONG).show();
			}
		} catch (Exception e) {
			Log.e(TAG, "下载失败", e);
			Toast.makeText(context, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	// ------------------------------------------------------------------
	// 应用内下载进度对话框
	// ------------------------------------------------------------------

	private static void showDownloadProgressDialog(Activity activity, long downloadId,
												   DownloadManager dm,
												   List<String> candidates, String fileName) {
		// 构建进度视图
		LinearLayout layout = new LinearLayout(activity);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = dpToPx(activity, 24);
		layout.setPadding(pad, dpToPx(activity, 16), pad, dpToPx(activity, 8));

		ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setProgress(0);
		progressBar.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		layout.addView(progressBar);

		TextView progressText = new TextView(activity);
		progressText.setText("准备下载...");
		progressText.setPadding(0, dpToPx(activity, 8), 0, 0);
		progressText.setGravity(Gravity.CENTER);
		layout.addView(progressText);

		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle("正在下载更新")
				.setView(layout)
				.setNegativeButton("后台下载", (d, w) -> {
					Toast.makeText(activity, "下载将在后台继续，完成后会通知您", Toast.LENGTH_SHORT).show();
				})
				.setCancelable(false)
				.create();
		dialog.show();
		currentProgressDialog = dialog;

		// 使用 Handler 轮询下载进度
		progressHandler = new Handler(Looper.getMainLooper());
		final long[] currentDownloadId = {downloadId};
		final int[] currentCandidateIndex = {0};

		progressRunnable = new Runnable() {
			@Override
			public void run() {
				if (activity.isFinishing() || activity.isDestroyed()) {
					dismissProgressDialog();
					return;
				}

				DownloadManager.Query query = new DownloadManager.Query();
				query.setFilterById(currentDownloadId[0]);

				try (Cursor cursor = dm.query(query)) {
					if (cursor == null || !cursor.moveToFirst()) {
						// 下载可能已被取消
						dismissProgressDialog();
						return;
					}

					int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
					int bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
					int bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
					int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

					if (statusIndex < 0) {
						dismissProgressDialog();
						return;
					}

					int status = cursor.getInt(statusIndex);
					long bytesDownloaded = bytesDownloadedIndex >= 0 ? cursor.getLong(bytesDownloadedIndex) : 0;
					long bytesTotal = bytesTotalIndex >= 0 ? cursor.getLong(bytesTotalIndex) : -1;

					switch (status) {
						case DownloadManager.STATUS_RUNNING:
							if (bytesTotal > 0) {
								int progress = (int) (bytesDownloaded * 100 / bytesTotal);
								progressBar.setIndeterminate(false);
								progressBar.setProgress(progress);
								String downloadedMB = String.format("%.1f", bytesDownloaded / 1048576.0);
								String totalMB = String.format("%.1f", bytesTotal / 1048576.0);
								progressText.setText(downloadedMB + " MB / " + totalMB + " MB (" + progress + "%)");
							} else {
								progressBar.setIndeterminate(true);
								String downloadedMB = String.format("%.1f", bytesDownloaded / 1048576.0);
								progressText.setText("已下载 " + downloadedMB + " MB");
							}
							progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
							break;

						case DownloadManager.STATUS_PENDING:
							progressBar.setIndeterminate(true);
							progressText.setText("等待下载...");
							progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
							break;

						case DownloadManager.STATUS_PAUSED:
							progressText.setText("下载已暂停");
							progressHandler.postDelayed(this, 1000);
							break;

						case DownloadManager.STATUS_SUCCESSFUL:
							progressBar.setProgress(100);
							progressText.setText("下载完成，正在准备安装...");
							dismissProgressDialog();
							// 触发安装
							onDownloadComplete(activity, currentDownloadId[0]);
							break;

						case DownloadManager.STATUS_FAILED:
							int reason = reasonIndex >= 0 ? cursor.getInt(reasonIndex) : -1;
							Log.w(TAG, "下载失败, reason=" + reason + ", candidateIndex=" + currentCandidateIndex[0]);

							// 尝试下一个代理
							currentCandidateIndex[0]++;
							if (currentCandidateIndex[0] < candidates.size()) {
								dm.remove(currentDownloadId[0]);
								String nextUrl = candidates.get(currentCandidateIndex[0]);
								Log.d(TAG, "尝试备用下载链接: " + nextUrl);
								progressText.setText("正在切换下载源...");

								try {
									DownloadManager.Request retryReq = new DownloadManager.Request(Uri.parse(nextUrl));
									retryReq.setTitle("Moonlight V+ 更新下载");
									retryReq.setDescription(fileName);
									retryReq.setMimeType("application/vnd.android.package-archive");
									retryReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
									retryReq.setVisibleInDownloadsUi(true);
									retryReq.setAllowedOverMetered(true);
									retryReq.setAllowedOverRoaming(true);
									retryReq.addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
									retryReq.addRequestHeader("Accept", "*/*");
									retryReq.addRequestHeader("Referer", "https://github.com/");
									retryReq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

									long newDownloadId = dm.enqueue(retryReq);
									currentDownloadId[0] = newDownloadId;

									// 更新 SharedPreferences 中的下载 ID
									SharedPreferences prefs = activity.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
									prefs.edit()
											.putLong(PREF_DOWNLOAD_ID, newDownloadId)
											.putInt("update_download_candidate_index", currentCandidateIndex[0])
											.apply();

									progressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);
								} catch (Exception e) {
									Log.e(TAG, "备用下载也失败", e);
									dismissProgressDialog();
									Toast.makeText(activity, "所有下载源均失败，请稍后重试", Toast.LENGTH_LONG).show();
								}
							} else {
								dismissProgressDialog();
								Toast.makeText(activity, "下载失败，请稍后重试或使用浏览器下载", Toast.LENGTH_LONG).show();
							}
							break;
					}
				} catch (Exception e) {
					Log.e(TAG, "查询下载进度失败", e);
					progressHandler.postDelayed(this, 1000);
				}
			}
		};

		progressHandler.post(progressRunnable);
	}

	private static void dismissProgressDialog() {
		if (currentProgressDialog != null && currentProgressDialog.isShowing()) {
			try {
				currentProgressDialog.dismiss();
			} catch (Exception ignored) {
			}
		}
		currentProgressDialog = null;
	}

	// ------------------------------------------------------------------
	// APK 安装
	// ------------------------------------------------------------------

	private static void installApk(Context context, long downloadId) {
		try {
			DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
			if (dm == null) return;

			Uri downloadedUri = dm.getUriForDownloadedFile(downloadId);
			if (downloadedUri == null) {
				Log.w(TAG, "无法获取下载文件 URI");
				Toast.makeText(context, "无法获取下载文件，请在下载目录中手动安装", Toast.LENGTH_LONG).show();
				return;
			}

			Intent installIntent = new Intent(Intent.ACTION_VIEW);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				// Android 7.0+ 使用 content:// URI
				// DownloadManager.getUriForDownloadedFile 在 N+ 已经返回 content:// URI
				installIntent.setDataAndType(downloadedUri, "application/vnd.android.package-archive");
				installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			} else {
				installIntent.setDataAndType(downloadedUri, "application/vnd.android.package-archive");
			}

			installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(installIntent);

			Log.d(TAG, "已启动安装界面");
		} catch (Exception e) {
			Log.e(TAG, "启动安装失败", e);
			Toast.makeText(context, "启动安装失败，请在下载目录中手动安装", Toast.LENGTH_LONG).show();
		}
	}

	// ------------------------------------------------------------------
	// 版本比较
	// ------------------------------------------------------------------

	private static String getCurrentVersion(Context context) {
		try {
			PackageInfo packageInfo = context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0);
			return packageInfo.versionName;
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(TAG, "获取当前版本失败", e);
			return "0.0.0";
		}
	}

	private static boolean isNewVersionAvailable(String currentVersion, String latestVersion) {
		try {
			currentVersion = currentVersion.replaceAll("^[Vv]", "");
			latestVersion = latestVersion.replaceAll("^[Vv]", "");

			String[] currentParts = currentVersion.split("\\.");
			String[] latestParts = latestVersion.split("\\.");

			int maxLength = Math.max(currentParts.length, latestParts.length);

			for (int i = 0; i < maxLength; i++) {
				int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
				int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;

				if (latestPart > currentPart) {
					return true;
				} else if (latestPart < currentPart) {
					return false;
				}
			}
			return false;
		} catch (NumberFormatException e) {
			Log.e(TAG, "版本号格式错误: current=" + currentVersion + ", latest=" + latestVersion, e);
			return false;
		}
	}

	// ------------------------------------------------------------------
	// 代理相关（保持原有逻辑）
	// ------------------------------------------------------------------

	private static boolean shouldUpdateProxyList(Context context) {
		long currentTime = System.currentTimeMillis();
		long lastUpdateTime = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
				.getLong(PREF_LAST_PROXY_UPDATE_TIME, 0);
		return (currentTime - lastUpdateTime) > PROXY_CACHE_DURATION || PROXY_PREFIXES.length == 0;
	}

	private static void updateProxyList(Context context) {
		try {
			Log.d(TAG, "开始更新代理列表...");
			String scriptContent = fetchScriptContent();
			if (scriptContent != null) {
				String[] newProxies = extractProxiesFromScript(scriptContent);
				if (newProxies.length > 0) {
					Set<String> allProxies = new HashSet<>(Arrays.asList(PROXY_PREFIXES));
					allProxies.addAll(Arrays.asList(newProxies));

					PROXY_PREFIXES = allProxies.toArray(new String[0]);

					context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
							.edit()
							.putLong(PREF_LAST_PROXY_UPDATE_TIME, System.currentTimeMillis())
							.apply();

					Log.d(TAG, "代理列表已更新，共 " + PROXY_PREFIXES.length + " 个代理：" + Arrays.toString(PROXY_PREFIXES));
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "更新代理列表失败: " + e.getMessage());
		}
	}

	private static String fetchScriptContent() {
		try {
			URL url = new URL(PROXY_DISCOVERY_URL);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);

			int responseCode = conn.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				StringBuilder content = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					content.append(line).append("\n");
				}
				reader.close();
				return content.toString();
			}
		} catch (Exception e) {
			Log.w(TAG, "获取代理发现脚本失败: " + e.getMessage());
		}
		return null;
	}

	private static String[] extractProxiesFromScript(String scriptContent) {
		List<String> proxies = new ArrayList<>();

		try {
			String[] patterns = {
					"[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
					"baseUrl\\s*=\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
					"url:\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
					"[\"']https://(?:gh|mirror|proxy|cdn)[\\w.-]*\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']"
			};

			for (String patternStr : patterns) {
				Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(scriptContent);

				while (matcher.find()) {
					String match = matcher.group();
					String urlStr = match.replaceAll("[\"']", "");

					if (urlStr.startsWith("https://")) {
						if (!urlStr.endsWith("/")) {
							urlStr = urlStr + "/";
						}
						if (isValidProxyUrl(urlStr)) {
							proxies.add(urlStr);
							Log.d(TAG, "发现代理地址: " + urlStr);
						}
					}
				}
			}

			Pattern domainPattern = Pattern.compile("(?:proxy|mirror|gh|cdn)[\\w.-]*\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)", Pattern.CASE_INSENSITIVE);
			Matcher domainMatcher = domainPattern.matcher(scriptContent);

			while (domainMatcher.find()) {
				String domain = domainMatcher.group();
				String proxyUrl = "https://" + domain + "/";
				if (isValidProxyUrl(proxyUrl)) {
					proxies.add(proxyUrl);
					Log.d(TAG, "发现域名代理: " + proxyUrl);
				}
			}

		} catch (Exception e) {
			Log.w(TAG, "解析代理地址失败: " + e.getMessage());
		}

		Set<String> uniqueProxies = new HashSet<>(proxies);
		return uniqueProxies.toArray(new String[0]);
	}

	private static boolean isValidProxyUrl(String url) {
		if (url == null || url.length() < 15 || url.length() > 100) {
			return false;
		}

		String[] blacklist = {
				"github.com", "googleapis.com", "gstatic.com",
				"jquery.com", "bootstrap.com", "cdnjs.com",
				"unpkg.com", "jsdelivr.net", "ghproxy.link"
		};

		for (String blocked : blacklist) {
			if (url.contains(blocked)) {
				return false;
			}
		}

		if (detectRedirectToGhproxyLink(url)) {
			return false;
		}

		return true;
	}

	private static boolean detectRedirectToGhproxyLink(String proxyUrl) {
		HttpURLConnection conn = null;
		try {
			String testUrl = proxyUrl + "https://api.github.com/zen";
			conn = (HttpURLConnection) new URL(testUrl).openConnection();
			conn.setRequestMethod("HEAD");
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);

			int responseCode = conn.getResponseCode();

			if (responseCode >= 300 && responseCode < 400) {
				String location = conn.getHeaderField("Location");
				if (location != null && location.contains("ghproxy.link")) {
					Log.d(TAG, "代理重定向回 ghproxy.link，排除: " + proxyUrl);
					return true;
				}
			}

			if (conn.getURL().toString().contains("ghproxy.link")) {
				Log.d(TAG, "代理最终URL包含 ghproxy.link，排除: " + proxyUrl);
				return true;
			}

			String server = conn.getHeaderField("Server");
			if (server != null && server.toLowerCase().contains("ghproxy")) {
				Log.d(TAG, "代理服务器信息包含 ghproxy，排除: " + proxyUrl);
				return true;
			}

			Log.d(TAG, "代理检测通过: " + proxyUrl + " (响应码: " + responseCode + ")");
			return false;

		} catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
			Log.w(TAG, "代理连接失败，排除: " + proxyUrl + " - " + e.getMessage());
			return true;
		} catch (Exception e) {
			Log.d(TAG, "代理检测异常但不排除: " + proxyUrl + " - " + e.getMessage());
			return false;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String httpGetWithProxies(String url) {
		List<String> tries = buildProxiedUrls(url);

		int maxTries = Math.min(tries.size(), 3);
		for (int i = 0; i < maxTries; i++) {
			String u = tries.get(i);
			try {
				HttpURLConnection connection = (HttpURLConnection) new URL(u).openConnection();
				connection.setRequestMethod("GET");
				connection.setRequestProperty("User-Agent", "Moonlight-Android");
				connection.setConnectTimeout(3000);
				connection.setReadTimeout(3000);
				int responseCode = connection.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_OK) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
					StringBuilder response = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						response.append(line);
					}
					reader.close();
					return response.toString();
				}
			} catch (Exception e) {
				Log.w(TAG, "Request failed, trying next: " + u + " - " + e.getMessage());
			}
		}
		return null;
	}

	/**
	 * Build a list of candidate URLs: original first, then proxied variants.
	 */
	public static List<String> buildProxiedUrls(String url) {
		List<String> tries = new ArrayList<>();
		tries.add(url);
		for (String p : PROXY_PREFIXES) {
			tries.add(p + url);
		}
		return tries;
	}

	public static void ensureProxyListUpdated(Context context) {
		if (shouldUpdateProxyList(context)) {
			updateProxyList(context);
		}
	}

	// ------------------------------------------------------------------
	// 工具方法
	// ------------------------------------------------------------------

	private static int dpToPx(Context context, int dp) {
		return (int) (dp * context.getResources().getDisplayMetrics().density);
	}

	private static String joinStrings(List<String> list) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) sb.append("|||");
			sb.append(list.get(i));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// 数据类
	// ------------------------------------------------------------------

	private static class UpdateInfo {
		final String version;
		final String releaseNotes;
		final String apkName;
		final String apkDownloadUrl;

		UpdateInfo(String version, String releaseNotes, String apkName, String apkDownloadUrl) {
			this.version = version;
			this.releaseNotes = releaseNotes;
			this.apkName = apkName;
			this.apkDownloadUrl = apkDownloadUrl;
		}
	}
}
