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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Exports any number of devices at once through one exporter, the way a desktop
 * usbipd serves several devices from a single port. No permissions are requested
 * or devices claimed automatically. The owner must release each export on
 * detach/session end, and close when finished. Every tunnel authorizes the source
 * port it connects from, and the exporter accepts nothing else.
 */
public final class UsbIpBackend implements AutoCloseable {
    private static final String TAG = "MoonlightUsbIp";

    /** One live export. {@code handle} and {@code port} identify the exporter
     * that serves it; {@code busId} identifies the device inside it. */
    public static final class Export {
        public final long handle;
        public final String deviceName;
        public final String busId;
        public final int port;
        private Export(long handle, String deviceName, String busId, int port) {
            this.handle = handle; this.deviceName = deviceName; this.busId = busId; this.port = port;
        }
    }

    /** What closing an export needs from the device layer. The device handle
     *  itself cannot be built outside the framework, and this keeps the
     *  teardown paths reachable from a test. */
    interface Connection {
        void close();
    }

    private static final class Active {
        final Export export;
        final Connection connection;
        Active(Export export, Connection connection) {
            this.export = export; this.connection = connection;
        }
    }

    private final UsbManager usbManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "MoonlightUsbIp"));
    private boolean closed;
    private Future<?> closeFuture;
    // Access only on executor. The exporter is started by the first export and
    // stopped again once nothing is bound, so no listener outlives its devices.
    private long exporter;
    private int exporterPort;
    private final Map<String, Active> exports = new LinkedHashMap<>();

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
            if (exports.containsKey(device.getDeviceName()))
                throw new IllegalStateException("Device is already exported");
            if (usbManager == null || !usbManager.hasPermission(device))
                throw new SecurityException("USB permission has not been granted");
            if (!device.equals(usbManager.getDeviceList().get(device.getDeviceName())))
                throw new IOException("USB device was detached");
            NativeUsbIp.load();
            if (exporter == 0) {
                exporter = NativeUsbIp.start();
                exporterPort = NativeUsbIp.localPort(exporter);
                Log.i(TAG, "native exporter listening on 127.0.0.1:" + exporterPort);
            }
            UsbDeviceConnection connection = null;
            String busId = null;
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) throw new IOException("Unable to open USB device");
                Log.i(TAG, "USB device opened, fd=" + connection.getFileDescriptor());
                busId = NativeUsbIp.bind(exporter, connection.getFileDescriptor());
                Log.i(TAG, "native exporter bound busid=" + busId);
                Export export = new Export(exporter, device.getDeviceName(), busId, exporterPort);
                exports.put(device.getDeviceName(), new Active(export, connection::close));
                return export;
            } catch (Exception | Error error) {
                Log.e(TAG, "USB export failed", error);
                if (rollback(busId, connection)) {
                    // Nothing is bound here any more, so an exporter that this
                    // failed export started must not keep listening.
                    try {
                        stopIdleExporter();
                    } catch (Exception stopError) {
                        // It keeps running, and the next release or close will
                        // stop it; the export failure is what the caller needs.
                        Log.e(TAG, "Idle exporter did not stop", stopError);
                    }
                }
                throw error;
            }
        });
    }

    public synchronized Future<?> release(Export expected) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> releaseNow(expected));
    }

    public synchronized Future<?> deviceDetached(String deviceName) {
        if (closed) throw new IllegalStateException("Backend closed");
        return executor.submit(() -> {
            Active active = exports.get(deviceName);
            if (active != null) releaseNow(active.export);
        });
    }

    /** Runs on the executor. An older export never releases a replacement of the
     * same device: the identity check belongs to the caller's own handle. */
    private void releaseNow(Export expected) {
        Active active = exports.get(expected.deviceName);
        if (active == null || active.export != expected) {
            // Either a stale export, or the retry of a release whose exporter
            // stop failed: the export is gone, but an idle exporter may not be.
            stopIdleExporter();
            return;
        }
        // Unbinds before either FD owner is released. Native cleanup runs first so
        // that a failure keeps the device handle open and the export registered.
        NativeUsbIp.unbind(exporter, expected.busId);
        exports.remove(expected.deviceName);
        active.connection.close();
        stopIdleExporter();
    }

    /** Stops an exporter nothing is bound to. Only a successful stop clears it,
     * so a failed one is retried by the next release. */
    private void stopIdleExporter() {
        if (!exports.isEmpty() || exporter == 0) return;
        NativeUsbIp.stop(exporter);
        exporter = 0;
        exporterPort = 0;
    }

    /** Undoes a partially started export. The native side owns the FD until the
     * device is unbound, so the connection only closes once it is gone; a device
     * that cannot be unbound stays with the exporter, which closes it on stop.
     * @return whether no native owner is left holding the device handle. */
    private boolean rollback(String busId, UsbDeviceConnection connection) {
        if (busId != null) {
            try {
                NativeUsbIp.unbind(exporter, busId);
            } catch (Exception unbindError) {
                Log.e(TAG, "USB device did not unbind; the exporter keeps its handle", unbindError);
                return false;
            }
        }
        if (connection != null) connection.close();
        return true;
    }

    public synchronized Future<?> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        closeFuture = executor.submit((Callable<Void>) () -> {
            Throwable failure = null;
            for (String deviceName : new ArrayList<>(exports.keySet())) {
                try {
                    Active active = exports.get(deviceName);
                    if (active != null) releaseNow(active.export);
                } catch (Throwable error) {
                    // Keep going: the remaining devices still have to be released.
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (exporter != 0) {
                // Whatever a failed release left behind is torn down here. A
                // stopped exporter wraps no connection any more, so the devices
                // it kept are closed with it: nothing else can reach them once
                // this backend is closed.
                try {
                    NativeUsbIp.stop(exporter);
                    exporter = 0;
                    exporterPort = 0;
                    // Empty the map first: a throwing close must not leave an
                    // entry that nothing can reach again.
                    ArrayList<Active> abandoned = new ArrayList<>(exports.values());
                    exports.clear();
                    for (Active active : abandoned) active.connection.close();
                    // The fallback released what the failed releases could not,
                    // so there is nothing left to report: the caller treats a
                    // reported failure as devices it must keep reserved.
                    failure = null;
                } catch (Throwable error) {
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
