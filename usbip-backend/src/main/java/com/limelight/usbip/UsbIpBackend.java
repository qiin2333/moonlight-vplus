package com.limelight.usbip;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Single-device prototype. No permissions are requested or devices claimed automatically.
 * The owner must release on detach/session end, and close when finished.
 * The loopback listener accepts only the source port reserved by the owning tunnel.
 */
public final class UsbIpBackend implements AutoCloseable {
    private static final String TAG = "MoonlightUsbIp";
    public static final class Export {
        public final String deviceName;
        public final String busId;
        public final int port;
        private Export(String deviceName, String busId, int port) {
            this.deviceName = deviceName; this.busId = busId; this.port = port;
        }
    }

    private static UsbIpBackend nativeOwner;
    private final UsbManager usbManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "MoonlightUsbIp"));
    private boolean closed;
    private Future<?> closeFuture;
    // Access only on executor, except nativeOwner which uses the class monitor.
    private UsbDeviceConnection connection;
    private Export active;
    private boolean started;

    public UsbIpBackend(Context context) {
        usbManager = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
    }

    public static boolean isSupported() {
        // First validated ABI/API range; do not load the library on other devices.
        return Build.VERSION.SDK_INT >= 28 && android.os.Process.is64Bit() && Build.SUPPORTED_ABIS.length > 0
                && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
    }

    public synchronized Future<Export> export(UsbDevice device) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> {
            synchronized (this) {
                if (closed) throw new IllegalStateException("Backend closed");
            }
            Log.i(TAG, "export requested for " + device.getDeviceName());
            if (!isSupported()) throw new UnsupportedOperationException("USB/IP requires Android 9+ ARM64");
            if (active != null) throw new IllegalStateException("Release the current device first");
            if (usbManager == null || !usbManager.hasPermission(device))
                throw new SecurityException("USB permission has not been granted");
            if (!device.equals(usbManager.getDeviceList().get(device.getDeviceName())))
                throw new IOException("USB device was detached");
            acquireNative();
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) throw new IOException("Unable to open USB device");
                Log.i(TAG, "USB device opened, fd=" + connection.getFileDescriptor());
                NativeUsbIp.load();
                int port = NativeUsbIp.start();
                started = true;
                Log.i(TAG, "native exporter listening on 127.0.0.1:" + port);
                String busId = NativeUsbIp.bind(connection.getFileDescriptor());
                Log.i(TAG, "native exporter bound busid=" + busId);
                active = new Export(device.getDeviceName(), busId, port);
                return active;
            } catch (Exception | Error error) {
                Log.e(TAG, "USB export failed", error);
                cleanup();
                throw error;
            }
        });
    }

    public synchronized Future<?> release(Export expected) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> {
            // An old operation must not stop a replacement export, even with the same bus ID.
            if (active == expected) cleanup();
        });
    }

    public synchronized Future<?> deviceDetached(String deviceName) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> {
            if (active != null && active.deviceName.equals(deviceName)) cleanup();
        });
    }

    private void acquireNative() {
        synchronized (UsbIpBackend.class) {
            if (nativeOwner != null && nativeOwner != this)
                throw new IllegalStateException("Another backend owns USB/IP");
            nativeOwner = this;
        }
    }

    private void cleanup() {
        // Stop waits for URB callbacks before either FD owner is released.
        // Do not close the connection or relinquish ownership if native stop fails.
        if (started) NativeUsbIp.stop();
        started = false;
        active = null;
        if (connection != null) { connection.close(); connection = null; }
        synchronized (UsbIpBackend.class) {
            if (nativeOwner == this) nativeOwner = null;
        }
    }

    public synchronized Future<?> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        closeFuture = executor.submit(this::cleanup);
        executor.shutdown();
        return closeFuture;
    }

    @Override public void close() {
        closeAsync();
    }
}
