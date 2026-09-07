package com.limelight.usbip;

/** JNI owns only the duplicated FD. All calls are serialized by UsbIpBackend. */
final class NativeUsbIp {
    private static boolean loaded;
    static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("moonlight_usbip");
            loaded = true;
        }
    }
    static native int start();
    static native void authorizeLocalConnection(int sourcePort);
    static native String bind(int fd);
    static native void stop();
    private NativeUsbIp() {}
}
