package com.limelight.binding.input.driver.wireless.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

internal data class HciUsbEndpoints(
    val usbInterface: UsbInterface,
    val eventIn: UsbEndpoint,
    val aclIn: UsbEndpoint,
    val aclOut: UsbEndpoint
)

internal data class HciUsbDeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val profile: HciUsbAdapterProfile,
    val behavior: HciUsbAdapterBehavior,
    val endpoints: HciUsbEndpoints
)

/** Performs descriptor-only discovery. It never opens or claims the USB device. */
internal object HciUsbDeviceProbe {
    fun probe(device: UsbDevice): HciUsbDeviceDescriptor? {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            val endpointsByAddress = HashMap<Int, UsbEndpoint>()
            val endpointDescriptors = ArrayList<HciUsbEndpointDescriptor>(usbInterface.endpointCount)

            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                endpointsByAddress[endpoint.address] = endpoint
                endpointDescriptors.add(endpoint.toDescriptor())
            }

            val layout = HciUsbInterfaceMatcher.match(
                HciUsbInterfaceDescriptor(
                    interfaceId = usbInterface.id,
                    alternateSetting = usbInterface.alternateSetting,
                    interfaceClass = usbInterface.interfaceClass,
                    interfaceSubclass = usbInterface.interfaceSubclass,
                    interfaceProtocol = usbInterface.interfaceProtocol,
                    endpoints = endpointDescriptors
                )
            ) ?: continue

            val profile = HciUsbAdapterProfileResolver.resolve(device.vendorId, device.productId)
            val compositeDevice = device.deviceClass == UsbConstants.USB_CLASS_MISC ||
                usbInterface.id != 0
            return HciUsbDeviceDescriptor(
                vendorId = device.vendorId,
                productId = device.productId,
                profile = profile,
                behavior = HciUsbAdapterBehaviorResolver.resolve(profile, compositeDevice),
                endpoints = HciUsbEndpoints(
                    usbInterface = usbInterface,
                    eventIn = endpointsByAddress.getValue(layout.eventIn.address),
                    aclIn = endpointsByAddress.getValue(layout.aclIn.address),
                    aclOut = endpointsByAddress.getValue(layout.aclOut.address)
                )
            )
        }

        return null
    }

    private fun UsbEndpoint.toDescriptor(): HciUsbEndpointDescriptor {
        return HciUsbEndpointDescriptor(
            address = address,
            direction = if (direction == UsbConstants.USB_DIR_IN) {
                HciUsbEndpointDirection.IN
            } else {
                HciUsbEndpointDirection.OUT
            },
            transferType = when (type) {
                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> HciUsbTransferType.CONTROL
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> HciUsbTransferType.ISOCHRONOUS
                UsbConstants.USB_ENDPOINT_XFER_BULK -> HciUsbTransferType.BULK
                UsbConstants.USB_ENDPOINT_XFER_INT -> HciUsbTransferType.INTERRUPT
                else -> HciUsbTransferType.UNKNOWN
            },
            maxPacketSize = maxPacketSize,
            interval = interval
        )
    }
}
