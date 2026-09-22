package com.limelight.usbip;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Exports any number of devices at once, one independent exporter each. No
 * permissions are requested or devices claimed automatically. The owner must
 * release each export on detach/session end, and close when finished.
 * Every exporter's loopback listener accepts only the source port reserved by
 * the tunnel that owns it.
 */
public final class UsbIpBackend implements AutoCloseable {
    private static final String TAG = "MoonlightUsbIp";

    /** One live exporter. {@code handle} is a native instance, not a process-wide slot. */
    public static final class Export {
        public final long handle;
        public final String deviceName;
        public final String busId;
        public final int port;
        private Export(long handle, String deviceName, String busId, int port) {
            this.handle = handle; this.deviceName = deviceName; this.busId = busId; this.port = port;
        }
    }

    private static final class Active {
        final Export export;
        final UsbDeviceConnection connection;
        Active(Export export, UsbDeviceConnection connection) {
            this.export = export; this.connection = connection;
        }
    }

    private final UsbManager usbManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "MoonlightUsbIp"));
    private boolean closed;
    private Future<?> closeFuture;
    // Access only on executor.
    private final Map<Long, Active> exports = new LinkedHashMap<>();

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
            for (Active active : exports.values()) {
                if (active.export.deviceName.equals(device.getDeviceName()))
                    throw new IllegalStateException("Device is already exported");
            }
            if (usbManager == null || !usbManager.hasPermission(device))
                throw new SecurityException("USB permission has not been granted");
            if (!device.equals(usbManager.getDeviceList().get(device.getDeviceName())))
                throw new IOException("USB device was detached");
            NativeUsbIp.load();
            long handle = NativeUsbIp.start();
            UsbDeviceConnection connection = null;
            try {
                int port = NativeUsbIp.localPort(handle);
                connection = usbManager.openDevice(device);
                if (connection == null) throw new IOException("Unable to open USB device");
                Log.i(TAG, "USB device opened, fd=" + connection.getFileDescriptor());
                String busId = NativeUsbIp.bind(handle, connection.getFileDescriptor());
                Log.i(TAG, "native exporter listening on 127.0.0.1:" + port + " busid=" + busId);
                Export export = new Export(handle, device.getDeviceName(), busId, port);
                exports.put(handle, new Active(export, connection));
                return export;
            } catch (Exception | Error error) {
                Log.e(TAG, "USB export failed", error);
                rollback(handle, connection);
                throw error;
            }
        });
    }

    public synchronized Future<?> release(Export expected) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> releaseNow(expected.handle));
    }

    public synchronized Future<?> deviceDetached(String deviceName) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> {
            for (Long handle : handlesFor(deviceName)) releaseNow(handle);
        });
    }

    /** Runs on the executor. An older export never stops a replacement: handles
     * are unique per exporter and never reused. */
    private void releaseNow(long handle) {
        Active active = exports.get(handle);
        if (active == null) return;
        // Stops before either FD owner is released. Native cleanup runs first so
        // that a failure keeps the device handle open and the export registered.
        NativeUsbIp.stop(handle);
        exports.remove(handle);
        active.connection.close();
    }

    private List<Long> handlesFor(String deviceName) {
        List<Long> handles = new ArrayList<>();
        for (Active active : exports.values()) {
            if (active.export.deviceName.equals(deviceName)) handles.add(active.export.handle);
        }
        return handles;
    }

    /** Undoes a partially started export. The native side owns the duplicated FD
     * until it stops, so the connection outlives a failed stop. */
    private static void rollback(long handle, UsbDeviceConnection connection) {
        try {
            NativeUsbIp.stop(handle);
        } catch (Exception stopError) {
            Log.e(TAG, "USB/IP exporter did not stop; keeping the device handle open", stopError);
            return;
        }
        if (connection != null) connection.close();
    }

    public synchronized Future<?> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        closeFuture = executor.submit((Callable<Void>) () -> {
            Throwable failure = null;
            for (Long handle : new ArrayList<>(exports.keySet())) {
                try {
                    releaseNow(handle);
                } catch (Throwable error) {
                    // Keep going: the remaining devices still have to be released.
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure != null) throw new RuntimeException(failure);
            return null;
        });
        executor.shutdown();
        return closeFuture;
    }

    @Override public void close() {
        closeAsync();
    }
}
