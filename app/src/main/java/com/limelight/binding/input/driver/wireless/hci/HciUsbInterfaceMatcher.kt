package com.limelight.binding.input.driver.wireless.hci

internal enum class HciUsbEndpointDirection {
    IN,
    OUT
}

internal enum class HciUsbTransferType {
    CONTROL,
    ISOCHRONOUS,
    BULK,
    INTERRUPT,
    UNKNOWN
}

internal data class HciUsbEndpointDescriptor(
    val address: Int,
    val direction: HciUsbEndpointDirection,
    val transferType: HciUsbTransferType,
    val maxPacketSize: Int,
    val interval: Int
)

internal data class HciUsbInterfaceDescriptor(
    val interfaceId: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<HciUsbEndpointDescriptor>
)

internal data class HciUsbInterfaceLayout(
    val interfaceId: Int,
    val alternateSetting: Int,
    val eventIn: HciUsbEndpointDescriptor,
    val aclIn: HciUsbEndpointDescriptor,
    val aclOut: HciUsbEndpointDescriptor
)

/** Recognizes the Bluetooth USB transport by its standard interface and endpoint layout. */
internal object HciUsbInterfaceMatcher {
    const val WIRELESS_CONTROLLER_CLASS = 0xe0
    const val BLUETOOTH_SUBCLASS = 0x01
    const val BLUETOOTH_PROTOCOL = 0x01

    fun match(descriptor: HciUsbInterfaceDescriptor): HciUsbInterfaceLayout? {
        if (descriptor.interfaceClass != WIRELESS_CONTROLLER_CLASS ||
            descriptor.interfaceSubclass != BLUETOOTH_SUBCLASS ||
            descriptor.interfaceProtocol != BLUETOOTH_PROTOCOL
        ) {
            return null
        }

        val eventIn = descriptor.endpoints.firstOrNull {
            it.direction == HciUsbEndpointDirection.IN &&
                it.transferType == HciUsbTransferType.INTERRUPT
        } ?: return null
        val aclIn = descriptor.endpoints.firstOrNull {
            it.direction == HciUsbEndpointDirection.IN &&
                it.transferType == HciUsbTransferType.BULK
        } ?: return null
        val aclOut = descriptor.endpoints.firstOrNull {
            it.direction == HciUsbEndpointDirection.OUT &&
                it.transferType == HciUsbTransferType.BULK
        } ?: return null

        return HciUsbInterfaceLayout(
            interfaceId = descriptor.interfaceId,
            alternateSetting = descriptor.alternateSetting,
            eventIn = eventIn,
            aclIn = aclIn,
            aclOut = aclOut
        )
    }
}
