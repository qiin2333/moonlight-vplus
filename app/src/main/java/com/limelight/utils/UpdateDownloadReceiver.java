package com.limelight.utils;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 监听系统下载管理器的下载完成事件，
 * 当用户将下载切换到后台后仍能在下载完成时自动触发安装。
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {
	private static final String TAG = "UpdateDownloadReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
			return;
		}

		long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
		if (downloadId == -1) {
			return;
		}

		Log.d(TAG, "收到下载完成广播, downloadId=" + downloadId);
		UpdateManager.onDownloadComplete(context, downloadId);
	}
}
