package com.limelight.binding.input.driver.wireless.hci

import android.hardware.usb.UsbDeviceConnection

/** Builds a transport from the exact descriptor and behavior selected during descriptor probing. */
internal object HciUsbTransportFactory {
    fun create(
        connection: UsbDeviceConnection,
        descriptor: HciUsbDeviceDescriptor
    ): HciTransport {
        return GenericHciUsbTransport(
            io = AndroidHciUsbIo(connection, descriptor),
            profile = descriptor.profile,
            behavior = descriptor.behavior
        )
    }
}
