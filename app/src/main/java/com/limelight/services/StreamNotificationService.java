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

public class StreamNotificationService extends Service {

    private static final String CHANNEL_ID = "stream_keep_alive";
    private static final int NOTIFICATION_ID = 1001;

    // Intent 参数键名
    private static final String EXTRA_HOST_NAME = "extra_host_name";
    private static final String EXTRA_APP_NAME = "extra_app_name";

    public static void start(Context context, String hostName, String appName) {
        Intent intent = new Intent(context, StreamNotificationService.class);
        intent.putExtra(EXTRA_HOST_NAME, hostName);
        intent.putExtra(EXTRA_APP_NAME, appName);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止服务并移除通知
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, StreamNotificationService.class);
        intent.setAction("ACTION_STOP");

        try {
            context.startService(intent);
        } catch (Exception e) {
            // 如果服务本来就没跑，或者不允许后台启动，那正好不需要停了
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 构建默认通知 (防御性，防止 intent 为空)
        String hostName = "Unknown";
        String appName = "Moonlight";
        if (intent != null) {
            hostName = intent.getStringExtra(EXTRA_HOST_NAME);
            appName = intent.getStringExtra(EXTRA_APP_NAME);
        }
        Notification notification = buildNotification(hostName, appName);

        // =========================================================
        // 无论 intent 是否为空，无论是否要停止，
        // 只要进来了，必须先 startForeground，给系统一个交代！
        // =========================================================
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 如果连 startForeground 都挂了，那就没救了，直接停
            stopSelf();
            return START_NOT_STICKY;
        }


        if (intent != null && "ACTION_STOP".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 正常保活逻辑
        if (intent == null) {
            // 异常重启，没有数据，那就停止吧，反正 startForeground 已经交代过了
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

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

        String title = "Moonlight-V+";
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