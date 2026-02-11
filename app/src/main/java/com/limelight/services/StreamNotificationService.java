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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
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
    private static final String EXTRA_PC_NAME = "extra_pc_name";
    private static final String EXTRA_APP_NAME = "extra_app_name";

    // WakeLock + 心跳机制
    private PowerManager.WakeLock wakeLock;
    private Handler keepAliveHandler;
    private static final long HEART_BEAT_INTERVAL_MS = 8000; // 8秒心跳一次（更频繁以抵抗系统清理）
    private Runnable heartbeatRunnable;

    public static void start(Context context, String pcName, String appName) {
        Intent intent = new Intent(context, StreamNotificationService.class);
        intent.putExtra(EXTRA_PC_NAME, pcName);
        intent.putExtra(EXTRA_APP_NAME, appName);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            LimeLog.severe("Failed to start foreground service: " + e.getMessage());
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
        initWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 构建默认通知 (防御性，防止 intent 为空)
        String pcName = "Unknown";
        String appName = "Desktop";
        if (intent != null) {
            pcName = intent.getStringExtra(EXTRA_PC_NAME);
            appName = intent.getStringExtra(EXTRA_APP_NAME);
        }
        
        // 保存当前的 PC 和 App 名称，供心跳时使用
        getSharedPreferences("StreamState", Context.MODE_PRIVATE)
                .edit()
                .putString("last_pc_name", pcName)
                .putString("last_app_name", appName)
                .apply();
        
        Notification notification = buildNotification(pcName, appName);

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
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 正常保活逻辑
        if (intent == null) {
            // 异常重启，没有数据，那就停止吧，反正 startForeground 已经交代过了
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 启动心跳机制
        startHeartbeat();

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopHeartbeat();
        releaseWakeLock();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // 提升通知渠道的重要性（从 IMPORTANCE_LOW 改为 IMPORTANCE_HIGH）
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_HIGH // 更高的优先级
                );
                channel.setDescription(getString(R.string.notification_channel_desc));
                channel.setShowBadge(false);
                channel.enableVibration(false); // 不震动，避免消耗电量
                channel.setSound(null, null); // 无声，避免打扰
                channel.enableLights(false); // 不闪灯
                manager.createNotificationChannel(channel);
                LimeLog.info("StreamNotificationService: Notification channel created with HIGH importance");
            }
        }
    }

    private Notification buildNotification(String pcName, String appName) {
        // 点击通知跳转回 Game Activity
        Intent intent = new Intent(this, Game.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, flags);

        String title = "Moonlight-V+";
        String content = getString(R.string.notification_content_streaming,
                appName != null ? appName : "Desktop",
                pcName != null ? pcName : "Unknown");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH) // 提升优先级
                .setOngoing(true) // 禁止左滑删除
                .setContentIntent(contentIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setShowWhen(true) // 显示时间戳，增加系统识别度
                .setCategory(NotificationCompat.CATEGORY_STATUS);
        
        return builder.build();
    }

    // =========================================================
    // WakeLock + 心跳机制 (防止应用被系统杀死)
    // =========================================================

    private void initWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // 使用 PARTIAL_WAKE_LOCK 保持 CPU 运行
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Moonlight:StreamKeepAlive");
                wakeLock.setReferenceCounted(false);
                // 以 24 小时超时获取，确保持续持有
                wakeLock.acquire(24 * 60 * 60 * 1000L);
                LimeLog.info("StreamNotificationService: WakeLock acquired with 24h timeout");
            }
        } catch (Exception e) {
            LimeLog.warning("Failed to initialize WakeLock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                LimeLog.info("StreamNotificationService: WakeLock released");
            } catch (Exception e) {
                LimeLog.warning("Error releasing WakeLock: " + e.getMessage());
            }
        }
    }

    private void startHeartbeat() {
        if (keepAliveHandler == null) {
            keepAliveHandler = new Handler(Looper.getMainLooper());
        }
        // 移除之前的任务（如果有的话）
        keepAliveHandler.removeCallbacksAndMessages(null);
        
        // 初始化心跳 Runnable（延迟初始化以避免自引用问题）
        heartbeatRunnable = () -> {
            try {
                // 心跳策略1：验证 WakeLock 状态，如果被释放则重新获取
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(24 * 60 * 60 * 1000L);
                    LimeLog.info("StreamNotificationService: Re-acquired WakeLock during heartbeat");
                }

                // 心跳策略2：触发通知更新，强制系统刷新前台服务状态
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    String pcName = getSharedPreferences("StreamState", Context.MODE_PRIVATE)
                            .getString("last_pc_name", "Unknown");
                    String appName = getSharedPreferences("StreamState", Context.MODE_PRIVATE)
                            .getString("last_app_name", "Desktop");
                    nm.notify(NOTIFICATION_ID, buildNotification(pcName, appName));
                }

                LimeLog.warning("StreamNotificationService: Heartbeat pulse");
            } catch (Exception e) {
                LimeLog.warning("Heartbeat error: " + e.getMessage());
            }

            // 递归继续下一个心跳
            if (keepAliveHandler != null) {
                keepAliveHandler.postDelayed(heartbeatRunnable, HEART_BEAT_INTERVAL_MS);
            }
        };
        
        // 启动心跳
        keepAliveHandler.postDelayed(heartbeatRunnable, HEART_BEAT_INTERVAL_MS);
        LimeLog.info("StreamNotificationService: Heartbeat started with 8s interval");
    }

    private void stopHeartbeat() {
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacksAndMessages(null);
            LimeLog.info("StreamNotificationService: Heartbeat stopped");
        }
    }
}