package com.limelight.usbip;

/** JNI owns the duplicated FDs. One exporter (a handle) serves any number of
 * devices, each bound by its busid. All calls are serialized by UsbIpBackend,
 * and a handle is never reused after stop(). */
final class NativeUsbIp {
    private static boolean loaded;
    static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("moonlight_usbip");
            loaded = true;
        }
    }
    static native long start();
    static native int localPort(long handle);
    static native void authorizeLocalConnection(long handle, int sourcePort);
    static native void revokeLocalConnection(long handle, int sourcePort);
    static native String bind(long handle, int fd);
    static native void unbind(long handle, String busId);
    static native void stop(long handle);
    private NativeUsbIp() {}
}
