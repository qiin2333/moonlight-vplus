package com.limelight.usbip;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsbIpBackendSupportTest {
    @Test public void supportsArm64AndExperimentalArmv7OnAndroid9() {
        assertTrue(UsbIpBackend.isSupported(28, true, new String[]{"arm64-v8a"}));
        assertTrue(UsbIpBackend.isSupported(28, false, new String[]{"armeabi-v7a", "armeabi"}));
    }

    @Test public void requiresAndroid9ForBothProcessWidths() {
        assertFalse(UsbIpBackend.isSupported(27, true, new String[]{"arm64-v8a"}));
        assertFalse(UsbIpBackend.isSupported(27, false, new String[]{"armeabi-v7a"}));
    }

    @Test public void matchesProcessWidthRatherThanDevicePrimaryAbi() {
        String[] deviceAbis = {"arm64-v8a", "armeabi-v7a"};
        assertTrue(UsbIpBackend.isSupported(28, false, deviceAbis));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"arm64-v8a"}));
        assertFalse(UsbIpBackend.isSupported(28, true, new String[]{"armeabi-v7a"}));
    }

    @Test public void rejectsNonArmAndMissingAbis() {
        assertFalse(UsbIpBackend.isSupported(28, true, new String[]{"x86_64"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"x86"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{"armeabi"}));
        assertFalse(UsbIpBackend.isSupported(28, false, new String[]{}));
        assertFalse(UsbIpBackend.isSupported(28, true, null));
    }
}
