package com.limelight.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;


/**
 * 这是一个 "All-in-One" 的通知服务类。
 * 既负责构建通知 UI，也负责前台服务的生命周期（保活）。
 */
public class StreamNotificationService extends Service {

    private static final String CHANNEL_ID = "stream_keep_alive";
    private static final int NOTIFICATION_ID = 1001;

    // Intent 参数键名
    private static final String EXTRA_HOST_NAME = "extra_host_name";
    private static final String EXTRA_APP_NAME = "extra_app_name";

    // ==========================================
    // 静态辅助方法 (供 Game.java 调用)
    // ==========================================

    /**
     * 启动保活服务并显示通知
     */
    public static void start(Context context, String hostName, String appName) {
        Intent intent = new Intent(context, StreamNotificationService.class);
        intent.putExtra(EXTRA_HOST_NAME, hostName);
        intent.putExtra(EXTRA_APP_NAME, appName);

        // 使用 ContextCompat 自动处理 Android 8.0+ 的启动差异
        try {
            ContextCompat.startForegroundService(context, intent);
            LimeLog.info("StreamNotificationService: 启动请求已发送");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止服务并移除通知
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, StreamNotificationService.class);
        context.stopService(intent);
        LimeLog.info("StreamNotificationService: 停止请求已发送");
    }

    // ==========================================
    // Service 生命周期方法
    // ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 如果系统重启了服务但没传 Intent，直接停止，防止空指针
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String hostName = intent.getStringExtra(EXTRA_HOST_NAME);
        String appName = intent.getStringExtra(EXTRA_APP_NAME);

        // 1. 构建通知对象
        Notification notification = buildNotification(hostName, appName);

        // 2. 核心保活代码：提升为前台服务
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14 强制要求指定类型，这里用 dataSync 表示数据同步/传输
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            LimeLog.warning("启动前台服务失败: " + e.getMessage());
            // 如果前台启动失败，尝试做普通服务运行（虽然大概率会被杀）
        }

        return START_STICKY; // 如果被杀，尝试重启
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不需要绑定
    }

    // ==========================================
    // 内部私有方法 (通知构建逻辑)
    // ==========================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(getString(R.string.notification_channel_desc));
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String hostName, String appName) {
        // 点击通知跳转回 Game Activity
        Intent intent = new Intent(this, Game.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, flags);

        String title = "Moonlight-V+ 正在运行";
        String content = String.format("正在串流: %s (%s)",
                appName != null ? appName : "Desktop",
                hostName != null ? hostName : "Unknown");

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play) // 确保图标资源存在
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content)) // 支持长文本
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 禁止左滑删除
                .setContentIntent(contentIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // 立即显示，防止延迟
                .build();
    }
}