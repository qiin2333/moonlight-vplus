package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbForwardingFailureTest {
    @Test fun hostReasonsGetTheirOwnAdvice() {
        assertEquals(R.string.usb_forward_host_releasing,
            UsbForwardingFailure.messageOf("device already forwarded"))
        assertEquals(R.string.usb_forward_host_attach_failed,
            UsbForwardingFailure.messageOf("usbip attach failed"))
    }

    @Test fun unknownAndMissingReasonsKeepTheGenericMessage() {
        assertEquals(R.string.usb_forward_failed, UsbForwardingFailure.messageOf(null))
        assertEquals(R.string.usb_forward_failed, UsbForwardingFailure.messageOf(""))
        assertEquals(R.string.usb_forward_failed, UsbForwardingFailure.messageOf("device is busy"))
    }
}
