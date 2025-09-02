package com.limelight.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import android.app.AlertDialog;

import com.limelight.R;

public class AboutDialogPreference extends Preference {
    
    private static final String GITHUB_REPO_URL = "https://github.com/qiin2333/moonlight-android";
    private static final String GITHUB_STAR_URL = "https://github.com/qiin2333/moonlight-android/stargazers";

    public AboutDialogPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public AboutDialogPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AboutDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AboutDialogPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        showAboutDialog();
    }

    private void showAboutDialog() {
        Context context = getContext();
        
        // 创建自定义布局
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_about, null);
        
        // 设置版本信息
        TextView versionText = dialogView.findViewById(R.id.text_version);
        String versionInfo = getVersionInfo(context);
        versionText.setText(versionInfo);
        
        // 设置应用名称
        TextView appNameText = dialogView.findViewById(R.id.text_app_name);
        String appName = getAppName(context);
        appNameText.setText(appName);
        
        // 设置描述信息
        TextView descriptionText = dialogView.findViewById(R.id.text_description);
        descriptionText.setText(R.string.about_dialog_description);
        
        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        
        // 设置按钮
        builder.setPositiveButton(R.string.about_dialog_github, (dialog, which) -> {
            // 打开项目仓库
            openUrl(GITHUB_REPO_URL);
        });
        
        builder.setNeutralButton(R.string.about_dialog_star, (dialog, which) -> {
            // 打开Star页面
            openUrl(GITHUB_STAR_URL);
        });
        
        builder.setNegativeButton(R.string.about_dialog_close, (dialog, which) -> {
            dialog.dismiss();
        });
        
        // 显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    @SuppressLint("DefaultLocale")
    private String getVersionInfo(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return String.format("Version %s (Build %d)", 
                    packageInfo.versionName, 
                    packageInfo.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return "Version Unknown";
        }
    }
    
    private String getAppName(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.applicationInfo.loadLabel(context.getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "Moonlight V+";
        }
    }
    
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            // 如果无法打开链接，忽略错误
        }
    }
}
