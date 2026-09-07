package com.limelight.usbip.diagnostics;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.limelight.usbip.UsbIpBackend;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manual, foreground-only backend probe. Never starts a stream or takes over local input. */
public final class MainActivity extends Activity {
    private final ExecutorService waiter = Executors.newSingleThreadExecutor();
    private UsbManager manager;
    private UsbIpBackend backend;
    private LinearLayout layout;
    private TextView status;
    private UsbDevice pending;
    private UsbIpBackend.Export active;
    private boolean busy;
    private boolean destroyed;
    private long generation;
    private String permissionAction;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && !destroyed && permissionAction.equals(intent.getAction()) && pending != null
                    && intent.getLongExtra("generation", -1) == generation
                    && pending.equals(device)) {
                pending = null;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && manager.hasPermission(device)) startExport(device);
                else { busy = false; render("未获得 USB 授权"); }
            }
        }
    };

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || destroyed) return;
            if (pending != null && pending.equals(device)) {
                generation++; pending = null; busy = false;
            }
            backend.deviceDetached(device.getDeviceName());
            if (active != null && active.deviceName.equals(device.getDeviceName())) active = null;
            render("设备已拔出");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        permissionAction = getPackageName() + ".USB_PERMISSION";
        manager = (UsbManager) getSystemService(USB_SERVICE);
        backend = new UsbIpBackend(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);
        setContentView(layout);
        ContextCompat.registerReceiver(this, permissionReceiver, new IntentFilter(permissionAction),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        registerReceiver(detachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
        render("仅用于开发验证：导出端口只监听本机，尚未接入串流隧道。\n请先关闭其他程序的 USB 驱动，再选择 OTG 设备。");
    }

    private void render(String message) {
        if (destroyed) return;
        layout.removeAllViews();
        status = new TextView(this);
        status.setText(message);
        layout.addView(status);
        if (!UsbIpBackend.isSupported()) { status.append("\n当前阶段仅支持 Android 9+ ARM64"); return; }
        if (active != null) {
            Button release = new Button(this);
            release.setText("释放 " + active.busId);
            release.setEnabled(!busy);
            release.setOnClickListener(v -> releaseActive());
            layout.addView(release);
        } else if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                Button button = new Button(this);
                button.setText("导出 " + device.getDeviceName() + " (" + device.getVendorId() + ":" + device.getProductId() + ")");
                button.setEnabled(!busy);
                button.setOnClickListener(v -> request(device));
                layout.addView(button);
            }
            if (manager.getDeviceList().isEmpty()) status.append("\n当前没有 OTG 外设");
        }
        Button refresh = new Button(this);
        refresh.setText("刷新设备");
        refresh.setEnabled(!busy);
        refresh.setOnClickListener(v -> render("设备列表已刷新"));
        layout.addView(refresh);
    }

    private void request(UsbDevice device) {
        busy = true;
        generation++;
        render("等待 USB 授权");
        if (manager.hasPermission(device)) { startExport(device); return; }
        pending = device;
        // Mutable so UsbManager can fill its result; restricted to our package.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        manager.requestPermission(device, PendingIntent.getBroadcast(this, (int) generation,
                new Intent(permissionAction).setPackage(getPackageName()).putExtra("generation", generation), flags));
    }

    private void startExport(UsbDevice device) {
        long operation = generation;
        render("正在导出");
        waiter.execute(() -> {
            try {
                UsbIpBackend.Export result = backend.export(device).get();
                runOnUiThread(() -> {
                    if (destroyed) return; // Backend close is already queued.
                    if (operation != generation) { backend.release(result); return; }
                    active = result; busy = false;
                    render("已导出，尚未连接主机\nbusid=" + result.busId + "\n127.0.0.1:" + result.port);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!destroyed && operation == generation) {
                        busy = false; render("导出失败：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void releaseActive() {
        busy = true;
        UsbIpBackend.Export expected = active;
        render("正在释放");
        waiter.execute(() -> {
            try {
                backend.release(expected).get();
                runOnUiThread(() -> { active = null; busy = false; render("设备已释放"); });
            } catch (Exception error) {
                runOnUiThread(() -> { busy = false; render("释放失败：" + error.getMessage()); });
            }
        });
    }

    @Override protected void onStop() {
        super.onStop();
        // This diagnostic has no background service and must not export while hidden.
        generation++;
        pending = null;
        busy = false;
        if (active != null) releaseActive();
    }

    @Override protected void onDestroy() {
        destroyed = true; generation++;
        unregisterReceiver(permissionReceiver);
        unregisterReceiver(detachReceiver);
        backend.close();
        waiter.shutdown();
        super.onDestroy();
    }
}
