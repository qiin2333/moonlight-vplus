package com.limelight.binding.input.driver.wireless.hci

import java.io.Closeable

internal enum class HciUsbAdapterProfile {
    GENERIC,
    CSR
}

internal enum class HciUsbCommandRecipient {
    DEVICE,
    INTERFACE
}

internal data class HciUsbAdapterBehavior(
    val commandRecipients: List<HciUsbCommandRecipient>,
    val reassembleEventTransfers: Boolean,
    val eventInputUsesEndpointPacketSize: Boolean,
    val aclInputRequestCount: Int
)

/** Keeps observed adapter quirks out of the generic packet and lifecycle code. */
internal object HciUsbAdapterBehaviorResolver {
    fun resolve(
        profile: HciUsbAdapterProfile,
        compositeDevice: Boolean
    ): HciUsbAdapterBehavior {
        return when (profile) {
            HciUsbAdapterProfile.CSR -> HciUsbAdapterBehavior(
                // CSR8510 clones have been observed accepting either form depending on firmware.
                commandRecipients = listOf(
                    HciUsbCommandRecipient.INTERFACE,
                    HciUsbCommandRecipient.DEVICE
                ),
                reassembleEventTransfers = true,
                // Fake CSR8510 firmware may not terminate a short interrupt transfer. Submit
                // one endpoint-sized request at a time and let the event decoder reassemble it.
                eventInputUsesEndpointPacketSize = true,
                aclInputRequestCount = DEFAULT_ACL_INPUT_REQUEST_COUNT
            )
            HciUsbAdapterProfile.GENERIC -> HciUsbAdapterBehavior(
                commandRecipients = if (compositeDevice) {
                    listOf(
                        HciUsbCommandRecipient.INTERFACE,
                        HciUsbCommandRecipient.DEVICE
                    )
                } else {
                    listOf(HciUsbCommandRecipient.DEVICE)
                },
                reassembleEventTransfers = false,
                eventInputUsesEndpointPacketSize = false,
                aclInputRequestCount = DEFAULT_ACL_INPUT_REQUEST_COUNT
            )
        }
    }

    private const val DEFAULT_ACL_INPUT_REQUEST_COUNT = 4
}

/** Profile selection only applies after the standard HCI interface layout has matched. */
internal object HciUsbAdapterProfileResolver {
    fun resolve(vendorId: Int, productId: Int): HciUsbAdapterProfile {
        return if (vendorId == CSR_VENDOR_ID && productId == CSR8510_A10_PRODUCT_ID) {
            HciUsbAdapterProfile.CSR
        } else {
            HciUsbAdapterProfile.GENERIC
        }
    }

    private const val CSR_VENDOR_ID = 0x0a12
    private const val CSR8510_A10_PRODUCT_ID = 0x0001
}

internal enum class HciTransportState {
    CLOSED,
    OPENING,
    OPEN,
    CLOSING,
    FAILED
}

internal enum class HciTransportErrorCode {
    DEVICE_DETACHED,
    INTERFACE_CLAIM_FAILED,
    CONTROL_TRANSFER_FAILED,
    EVENT_TRANSFER_FAILED,
    ACL_TRANSFER_FAILED,
    MALFORMED_EVENT,
    MALFORMED_ACL
}

internal data class HciTransportFailure(
    val code: HciTransportErrorCode,
    val message: String? = null,
    val cause: Throwable? = null
)

/** Receives already-framed HCI packets. Callbacks are serialized by the transport. */
internal interface HciPacketListener {
    fun onEvent(packet: HciEventPacket)
    fun onAcl(packet: HciAclPacket)
    fun onTransportFailure(failure: HciTransportFailure)
}

/**
 * Asynchronous boundary between the Bluetooth host state machine and a concrete USB adapter.
 * Command completion remains an HCI event and is correlated by the protocol layer, not here.
 */
internal interface HciTransport : Closeable {
    val state: HciTransportState
    val profile: HciUsbAdapterProfile

    fun setListener(listener: HciPacketListener?)
    fun open(): Boolean
    fun configureAclOutput(maxPayloadLength: Int, packetCredits: Int): Boolean
    fun sendCommand(packet: HciCommandPacket): Boolean
    fun sendAcl(packet: HciAclPacket): Boolean
    fun flushAcl(timeoutMs: Long): Boolean = true
    override fun close()
}
