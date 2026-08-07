package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetHelperTest {
    @Test
    public void hostnameIsNotResolvedForLanClassification() {
        // "localhost" used to resolve to loopback and was therefore classified as LAN.
        // Hostnames must remain unknown here so polling cannot trigger DNS as a side effect.
        assertFalse(NetHelper.INSTANCE.isLanAddress("localhost"));
        assertFalse(NetHelper.INSTANCE.isLanAddress("sunshine.example.com"));
    }

    @Test
    public void ipLiteralsKeepLanClassification() {
        assertTrue(NetHelper.INSTANCE.isLanAddress("127.0.0.1"));
        assertTrue(NetHelper.INSTANCE.isLanAddress("192.168.1.10"));
        assertTrue(NetHelper.INSTANCE.isLanAddress("::1"));
        assertFalse(NetHelper.INSTANCE.isLanAddress("8.8.8.8"));
    }
}
