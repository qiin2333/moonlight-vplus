package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.limelight.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdateManager {
	private static final String TAG = "UpdateManager";
	private static final String GITHUB_API_URL = "https://api.github.com/repos/qiin2333/moonlight-android/releases/latest";
	private static final String GITHUB_RELEASE_PAGE = "https://github.com/qiin2333/moonlight-android/releases/latest";
	private static final long UPDATE_CHECK_INTERVAL = 4 * 60 * 60 * 1000;

	// API与下载的代理前缀（按优先级尝试）
	private static final String[] PROXY_PREFIXES = new String[] {
		"https://mirror.ghproxy.com/",
		"https://ghp.ci/"
	};

	private static final AtomicBoolean isChecking = new AtomicBoolean(false);
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();

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

	private static class UpdateCheckTask implements Runnable {
		private final Context context;
		private final boolean showToast;

		public UpdateCheckTask(Context context, boolean showToast) {
			this.context = context;
			this.showToast = showToast;
		}

		@Override
		public void run() {
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
						// 根据是否root版本尽量挑选合适APK
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
				Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show();
			}
		}
	}

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

	private static void showUpdateDialog(Context context, UpdateInfo updateInfo) {
		if (!(context instanceof Activity)) {
			return;
		}

		Activity activity = (Activity) context;
		activity.runOnUiThread(() -> {
			AlertDialog.Builder builder = new AlertDialog.Builder(activity);
			builder.setTitle("发现新版本: " + updateInfo.version);

			String message = "New version available!\n\n";
			if (updateInfo.releaseNotes != null && !updateInfo.releaseNotes.isEmpty()) {
				String notes = updateInfo.releaseNotes;
				if (notes.length() > 500) {
					notes = notes.substring(0, 500) + "...";
				}
				message += "What's changed:\n" + notes + "\n\n";
			}
			if (updateInfo.apkName != null) {
				message += "Will download: " + updateInfo.apkName + "\n\n";
			}
			message += "Please choose the download method.";

			builder.setMessage(message);
			builder.setPositiveButton("Open in browser", (dialog, which) -> {
				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASE_PAGE));
				context.startActivity(intent);
			});
			if (updateInfo.apkDownloadUrl != null) {
				builder.setNeutralButton("Download directly", (dialog, which) -> startDirectDownload(context, updateInfo));
			}
			builder.setNegativeButton("稍后", null);
			builder.setCancelable(true);

			AlertDialog dialog = builder.create();
			dialog.show();
		});
	}

	private static void startDirectDownload(Context context, UpdateInfo info) {
		try {
			String src = info.apkDownloadUrl;
			String fileName = info.apkName != null ? info.apkName : ("moonlight-" + info.version + ".apk");

			// 优先使用代理加速
			List<String> candidates = new ArrayList<>();
			for (String p : PROXY_PREFIXES) {
				candidates.add(p + src);
			}
			// 最后尝试原始地址
			candidates.add(src);

			String chosen = candidates.get(0);

			DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
			DownloadManager.Request req = new DownloadManager.Request(Uri.parse(chosen));
			req.setTitle("Moonlight V+ Updating");
			req.setDescription(fileName);
			req.setMimeType("application/vnd.android.package-archive");
			req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
			} else {
				req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
			}
			dm.enqueue(req);
			Toast.makeText(context, "Downloading started, please check the notification bar for progress", Toast.LENGTH_LONG).show();
		} catch (Exception e) {
			Toast.makeText(context, "Downloading failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	private static String httpGetWithProxies(String url) {
		List<String> tries = new ArrayList<>();
		tries.add(url); // 先尝试直连
		for (String p : PROXY_PREFIXES) {
			tries.add(p + url);
		}
		for (String u : tries) {
			try {
				HttpURLConnection connection = (HttpURLConnection) new URL(u).openConnection();
				connection.setRequestMethod("GET");
				connection.setRequestProperty("User-Agent", "Moonlight-Android");
				connection.setConnectTimeout(10000);
				connection.setReadTimeout(10000);
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
				Log.w(TAG, "Request failed, trying next: " + u);
			}
		}
		return null;
	}

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
