package com.limelight.binding.input.driver.wireless.dualsense

import android.view.InputDevice
import com.limelight.binding.input.driver.DualSenseAdaptiveTriggerEffect
import java.io.Closeable

/** Per-InputDevice DualSense output state backed by Android's system Bluetooth connection. */
internal class DirectDualSenseBluetoothOutput(
    inputDevice: InputDevice,
    private val transport: AndroidBluetoothHidHostTransport
) : Closeable {
    private val bluetoothAddress = extractBluetoothAddress(inputDevice)
    private val writer = DualSenseBluetoothOutputWriter(
        sendReport = { transport.send(bluetoothAddress, it) }
    )

    init {
        writer.initializeLightbar()
    }

    fun onTransportReady() {
        writer.initializeLightbar()
        writer.retryPending()
    }

    fun updateRumble(lowFrequency: Short, highFrequency: Short): Boolean {
        val ready = prepareTransport()
        writer.updateRumble(lowFrequency, highFrequency)
        return ready
    }

    fun updateAdaptiveTriggers(
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ): Boolean {
        val ready = prepareTransport()
        writer.updateAdaptiveTriggers(eventFlags, typeLeft, typeRight, left, right)
        return ready
    }

    fun updateTriggerRumble(left: Short, right: Short): Boolean {
        val (leftType, leftPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble(left)
        val (rightType, rightPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble(right)
        return updateAdaptiveTriggers(
            DualSenseAdaptiveTriggerEffect.BOTH_FLAGS.toByte(),
            leftType,
            rightType,
            leftPayload,
            rightPayload
        )
    }

    fun updatePlayerLeds(mask: Int): Boolean {
        val ready = prepareTransport()
        writer.updatePlayerLeds(mask)
        return ready
    }

    fun updateLightbar(red: Byte, green: Byte, blue: Byte): Boolean {
        val ready = prepareTransport()
        writer.updateLightbar(red, green, blue)
        return ready
    }

    private fun prepareTransport(): Boolean {
        val ready = transport.isReady(bluetoothAddress)
        if (ready) writer.initializeLightbar() else transport.start()
        return ready
    }

    override fun close() {
        writer.close(sendNeutral = transport.isReady(bluetoothAddress))
    }

    companion object {
        private val BLUETOOTH_ADDRESS = Regex("bluetoothAddress=([0-9A-Fa-f:]{17})")

        fun supports(inputDevice: InputDevice): Boolean = supports(
            inputDevice.vendorId,
            inputDevice.productId,
            inputDevice.descriptor,
            inputDevice.name,
            runCatching { inputDevice.toString() }.getOrDefault("")
        )

        internal fun supports(
            vendorId: Int,
            productId: Int,
            descriptor: String,
            name: String,
            description: String
        ): Boolean {
            if (!isDualSenseProduct(vendorId, productId)) return false
            if (descriptor.contains("usb", ignoreCase = true) ||
                description.contains("bus=usb", ignoreCase = true)
            ) return false
            return BLUETOOTH_ADDRESS.containsMatchIn(description) ||
                descriptor.contains("bluetooth", ignoreCase = true) ||
                name.contains("Wireless Controller", ignoreCase = true)
        }

        internal fun isDualSenseProduct(vendorId: Int, productId: Int): Boolean =
            vendorId == SONY_VENDOR_ID && productId in DUALSENSE_PRODUCT_IDS

        private fun extractBluetoothAddress(inputDevice: InputDevice): String? =
            BLUETOOTH_ADDRESS.find(runCatching { inputDevice.toString() }.getOrDefault(""))
                ?.groupValues?.getOrNull(1)

        private const val SONY_VENDOR_ID = 0x054C
        private val DUALSENSE_PRODUCT_IDS = setOf(0x0CE6, 0x0DF2)
    }
}
