package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetHelperTest {
    @Test
    public void privateAddressDetectsIpv4PrivateRanges() {
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("10.0.0.1"));
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("172.16.0.1"));
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("172.31.255.255"));
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("192.168.1.10"));
    }

    @Test
    public void privateAddressRejectsPublicIpv4AndHostnames() {
        assertFalse(NetHelper.INSTANCE.isPrivateAddress("8.8.8.8"));
        assertFalse(NetHelper.INSTANCE.isPrivateAddress("172.32.0.1"));
        assertFalse(NetHelper.INSTANCE.isPrivateAddress("example.com"));
        assertFalse(NetHelper.INSTANCE.isPrivateAddress((String) null));
    }

    @Test
    public void privateAddressDetectsLocalIpv6Ranges() {
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("::1"));
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("[fe80::1]"));
        assertTrue(NetHelper.INSTANCE.isPrivateAddress("fd00::1"));
    }

    @Test
    public void bandwidthReturnsUnavailableForInvalidSamples() {
        assertEquals("N/A", NetHelper.INSTANCE.calculateBandwidth(100, 50, 0));
        assertEquals("N/A", NetHelper.INSTANCE.calculateBandwidth(50, 100, 1000));
        assertEquals("N/A", NetHelper.INSTANCE.calculateBandwidth(-1, 0, 1000));
    }
}
