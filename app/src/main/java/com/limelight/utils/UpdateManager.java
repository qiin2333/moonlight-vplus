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
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.limelight.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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
	private static final String GITHUB_API_URL = "https://api.github.com/repos/qiin2333/moonlight-android/releases/latest";
	private static final String GITHUB_RELEASE_PAGE = "https://github.com/qiin2333/moonlight-android/releases/latest";
	private static final long UPDATE_CHECK_INTERVAL = 4 * 60 * 60 * 1000;

	// 代理发现地址
	private static final String PROXY_DISCOVERY_URL = "https://ghproxy.link/js/src_views_home_HomeView_vue.js";
	
	// API与下载的代理前缀（按优先级尝试）- 将在运行时动态更新
	private static volatile String[] PROXY_PREFIXES = new String[] {};

	private static final AtomicBoolean isChecking = new AtomicBoolean(false);
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	
	// 代理缓存相关
	private static final long PROXY_CACHE_DURATION = 24 * 60 * 60 * 1000; // 24小时
	private static final String PREF_LAST_PROXY_UPDATE_TIME = "last_proxy_update_time";

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
				if (notes.length() > 300) {
					notes = notes.substring(0, 300) + "...";
				}
				message += "What's changed:\n" + notes + "\n\n";
			}
			if (updateInfo.apkName != null) {
				message += "File: " + updateInfo.apkName + "\n\n";
			}
			message += "Please choose the download method";

			builder.setMessage(message);

			builder.setPositiveButton("打开浏览器更新", (dialog, which) -> {
				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASE_PAGE));
				context.startActivity(intent);
			});
			if (updateInfo.apkDownloadUrl != null) {
				builder.setNeutralButton("直接下载", (dialog, which) -> startDirectDownload(context, updateInfo));
			}
			builder.setNegativeButton("稍后", null);
			builder.setCancelable(true);

			AlertDialog dialog = builder.create();
			dialog.show();
		});
	}

	private static void startDirectDownload(Context context, UpdateInfo info) {
		try {
			// 检查安装权限
			if (!canInstallApk(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    showInstallPermissionDialog(context);
                }
                return;
			}
			
			String src = info.apkDownloadUrl;
			String fileName = info.apkName != null ? info.apkName : ("moonlight-" + info.version + ".apk");

			// 构造候选列表（代理优先，最后直连）
			List<String> candidates = new ArrayList<>();
			for (String p : PROXY_PREFIXES) {
				candidates.add(p + src);
			}
			candidates.add(src);

			// 优先使用代理链接，提供备选方案
			String primaryUrl = candidates.get(0);
			Toast.makeText(context, "开始下载: " + fileName, Toast.LENGTH_SHORT).show();

			DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
			DownloadManager.Request req = new DownloadManager.Request(Uri.parse(primaryUrl));
			req.setTitle("Moonlight V+ 更新下载");
			req.setDescription(fileName + " (下载完成后点击通知即可安装)");
			req.setMimeType("application/vnd.android.package-archive");
			req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
			req.setVisibleInDownloadsUi(true);
			req.setAllowedOverMetered(true);
			req.setAllowedOverRoaming(true);
			req.addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
			req.addRequestHeader("Accept", "*/*");
			req.addRequestHeader("Referer", "https://github.com/");

			// 设置下载路径
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
			} else {
				req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
			}
			
			long downloadId = dm.enqueue(req);
			Log.d(TAG, "已启动下载，ID: " + downloadId + ", URL: " + primaryUrl);
			Toast.makeText(context, "已开始下载，下载完成后点击通知栏即可安装", Toast.LENGTH_LONG).show();
		} catch (Exception e) {
			Toast.makeText(context, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	// 检查是否可以安装APK
	private static boolean canInstallApk(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return context.getPackageManager().canRequestPackageInstalls();
		}
		return true; // Android 8.0以下不需要此权限
	}

	// 显示安装权限请求对话框
	@RequiresApi(api = Build.VERSION_CODES.O)
    private static void showInstallPermissionDialog(Context context) {
		if (!(context instanceof Activity)) {
			Toast.makeText(context, "需要安装权限才能自动安装更新", Toast.LENGTH_LONG).show();
			return;
		}

		Activity activity = (Activity) context;
		AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setTitle("需要安装权限");
		builder.setMessage("为了自动安装更新，需要授予应用安装权限。\n\n点击确定前往设置页面开启权限。");
		builder.setPositiveButton(activity.getResources().getText(android.R.string.ok), (dialog, which) -> {
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
				intent.setData(Uri.parse("package:" + context.getPackageName()));
				activity.startActivity(intent);
			} catch (Exception e) {
				// 如果无法打开特定包名的设置，则打开通用设置
				Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
				activity.startActivity(intent);
			}
		});
		builder.setNegativeButton(activity.getResources().getText(android.R.string.cancel), null);
		builder.setCancelable(true);
		builder.show();
	}
	
	// 检查是否需要更新代理列表
	private static boolean shouldUpdateProxyList(Context context) {
		long currentTime = System.currentTimeMillis();
		long lastUpdateTime = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
				.getLong(PREF_LAST_PROXY_UPDATE_TIME, 0);
		return (currentTime - lastUpdateTime) > PROXY_CACHE_DURATION || PROXY_PREFIXES.length == 0;
	}
	
	// 从 ghproxy.link 脚本中自动发现并更新代理地址
	private static void updateProxyList(Context context) {
		try {
			Log.d(TAG, "开始更新代理列表...");
			String scriptContent = fetchScriptContent();
			if (scriptContent != null) {
				String[] newProxies = extractProxiesFromScript(scriptContent);
				if (newProxies.length > 0) {
					// 合并新代理和现有代理，去重
					Set<String> allProxies = new HashSet<>(Arrays.asList(PROXY_PREFIXES));
					allProxies.addAll(Arrays.asList(newProxies));
					
					PROXY_PREFIXES = allProxies.toArray(new String[0]);
					
					// 保存代理更新时间到SharedPreferences
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
	
	// 获取代理发现脚本内容
	private static String fetchScriptContent() {
		try {
			// 直接访问脚本，不使用代理避免循环依赖
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
	
	// 从脚本内容中提取代理地址
	private static String[] extractProxiesFromScript(String scriptContent) {
		List<String> proxies = new ArrayList<>();
		
		try {
			// 匹配 JavaScript 脚本中的 URL 模式 - 扩展域名后缀
			String[] patterns = {
				// 匹配引号中的完整URL: "https://xxx.com/"
				"[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
				// 匹配变量赋值: baseUrl = "https://xxx.com/"
				"baseUrl\\s*=\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
				// 匹配配置对象中的URL
				"url:\\s*[\"']https://[\\w.-]+\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']",
				// 匹配GitHub代理特征的域名
				"[\"']https://(?:gh|mirror|proxy|cdn)[\\w.-]*\\.(?:com|net|org|cn|top|cc|io|me|cf|tk|ml|ga|gg|xyz|site|online|tech|info|biz|work|space|shop|club|pro|dev|app|link|run|art|fun|live|store|world|today|design|cloud)/[\"']"
			};
			
			for (String patternStr : patterns) {
				Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(scriptContent);
				
				while (matcher.find()) {
					String match = matcher.group();
					// 移除引号获取纯URL
					String url = match.replaceAll("[\"']", "");
					
					// 确保URL格式正确
					if (url.startsWith("https://") && url.endsWith("/")) {
						// 过滤掉明显不是代理的地址
						if (isValidProxyUrl(url)) {
							proxies.add(url);
							Log.d(TAG, "发现代理地址: " + url);
						}
					}
				}
			}
			
			// 额外查找脚本中的域名配置 - 扩展域名后缀
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
		
		// 去重并返回
		Set<String> uniqueProxies = new HashSet<>(proxies);
		return uniqueProxies.toArray(new String[0]);
	}
	
	// 验证代理URL是否有效
	private static boolean isValidProxyUrl(String url) {
		if (url == null || url.length() < 15 || url.length() > 100) {
			return false;
		}
		
		// 排除明显不是代理的地址
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
		
		// 检测是否会重定向回 ghproxy.link（失效代理的常见行为）
		if (detectRedirectToGhproxyLink(url)) {
			return false;
		}
		
		return true;
	}
	
	// 检测代理是否会重定向回 ghproxy.link
	private static boolean detectRedirectToGhproxyLink(String proxyUrl) {
		HttpURLConnection conn = null;
		try {
			// 测试访问一个简单的GitHub API
			String testUrl = proxyUrl + "https://api.github.com/zen";
			URL url = new URL(testUrl);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("HEAD");
			conn.setInstanceFollowRedirects(false); // 不自动跟随重定向
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:40.0)");
			conn.setConnectTimeout(1000); // 进一步缩短超时时间
			conn.setReadTimeout(1000);
			
			int responseCode = conn.getResponseCode();
			
			// 检查重定向
			if (responseCode >= 300 && responseCode < 400) {
				String location = conn.getHeaderField("Location");
				if (location != null && location.contains("ghproxy.link")) {
					return true; // 重定向回 ghproxy.link，说明代理失效
				}
			}
			
			// 检查最终URL（即使没有重定向状态码，有些服务器可能直接返回ghproxy.link内容）
			String finalUrl = conn.getURL().toString();
			if (finalUrl.contains("ghproxy.link")) {
				return true;
			}
			
			// 检查响应头中的服务器信息
			String server = conn.getHeaderField("Server");
			if (server != null && server.toLowerCase().contains("ghproxy")) {
				return true;
			}
			
			// 代理检测成功，没有重定向问题
			return false;
			
		} catch (Exception e) {
			// 网络错误或异常情况，认为代理不可靠，排除
			Log.w(TAG, "代理检测异常，排除不可靠代理: " + proxyUrl + " - " + e.getMessage());
			return true; // 返回true表示有问题，应该排除
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String httpGetWithProxies(String url) {
		List<String> tries = new ArrayList<>();
		tries.add(url); // 先尝试直连
		for (String p : PROXY_PREFIXES) {
			tries.add(p + url);
		}
		
		// 限制最大尝试次数，避免等待过久
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
