package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdateManager {
    private static final String TAG = "UpdateManager";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/qiin2333/moonlight-android/releases/latest";
    private static final String GITHUB_DOWNLOAD_URL = "https://github.com/qiin2333/moonlight-android/releases/latest";
    private static final long UPDATE_CHECK_INTERVAL = 4 * 60 * 60 * 1000;
    
    private static final AtomicBoolean isChecking = new AtomicBoolean(false);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public static void checkForUpdates(Context context, boolean showToast) {
        if (isChecking.getAndSet(true)) {
            return;
        }
        
        executor.execute(new UpdateCheckTask(context, showToast));
    }
    
    public static void checkForUpdatesOnStartup(Context context) {
        // 检查是否在24小时内已经检查过更新
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
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Moonlight-Android");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String latestVersion = jsonResponse.getString("tag_name");
                    String releaseNotes = jsonResponse.getString("body");
                    
                    updateInfo = new UpdateInfo(latestVersion, releaseNotes);
                }
            } catch (Exception e) {
                Log.e(TAG, "检查更新失败", e);
            }
            
            final UpdateInfo finalUpdateInfo = updateInfo;
            
            // 在主线程中处理结果
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> handleUpdateResult(finalUpdateInfo));
            }
        }
        
        private void handleUpdateResult(UpdateInfo updateInfo) {
            isChecking.set(false);
            
            // 记录检查时间
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
            // 移除版本号中的V前缀（如果存在）
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
            
            String message = "发现新版本可用！\n\n";
            if (updateInfo.releaseNotes != null && !updateInfo.releaseNotes.isEmpty()) {
                // 限制显示长度，避免对话框过大
                String notes = updateInfo.releaseNotes;
                if (notes.length() > 500) {
                    notes = notes.substring(0, 500) + "...";
                }
                message += "更新内容：\n" + notes + "\n\n";
            }
            message += "是否前往下载？";
            
            builder.setMessage(message);
            builder.setPositiveButton("下载", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_DOWNLOAD_URL));
                context.startActivity(intent);
            });
            builder.setNegativeButton("稍后", null);
            builder.setCancelable(true);
            
            AlertDialog dialog = builder.create();
            dialog.show();
        });
    }
    
    private static class UpdateInfo {
        final String version;
        final String releaseNotes;
        
        UpdateInfo(String version, String releaseNotes) {
            this.version = version;
            this.releaseNotes = releaseNotes;
        }
    }
}
