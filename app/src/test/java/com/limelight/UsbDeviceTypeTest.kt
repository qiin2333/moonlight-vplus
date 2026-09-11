package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDeviceTypeTest {
    @Test fun bootInputProtocolsStayDistinct() {
        assertEquals(UsbDeviceType.KEYBOARD, UsbDeviceType.classify(3, 1, 1))
        assertEquals(UsbDeviceType.MOUSE, UsbDeviceType.classify(3, 1, 2))
        assertEquals(UsbDeviceType.INPUT, UsbDeviceType.classify(3, 0, 0))
    }
    @Test fun serialAndVendorSpecificDevicesAreNotAssumedToBeNetworkAdapters() {
        assertEquals(UsbDeviceType.NETWORK, UsbDeviceType.classify(2, 6, 0))
        assertEquals(UsbDeviceType.NETWORK, UsbDeviceType.classify(2, 13, 0))
        assertEquals(UsbDeviceType.GENERIC, UsbDeviceType.classify(2, 2, 0))
        assertEquals(UsbDeviceType.GENERIC, UsbDeviceType.classify(255, 0, 0))
    }
    @Test fun standardDeviceClassesHaveDistinctIcons() {
        assertEquals(UsbDeviceType.AUDIO, UsbDeviceType.classify(1, 0, 0))
        assertEquals(UsbDeviceType.STORAGE, UsbDeviceType.classify(8, 6, 80))
        assertEquals(UsbDeviceType.CAMERA, UsbDeviceType.classify(14, 1, 0))
        assertEquals(UsbDeviceType.PRINTER, UsbDeviceType.classify(7, 1, 2))
        assertEquals(UsbDeviceType.HUB, UsbDeviceType.classify(9, 0, 0))
    }
}
