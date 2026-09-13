package com.limelight

import android.hardware.usb.UsbDevice
import com.limelight.binding.input.driver.UsbDriverService

/** Conservative display classification; this never changes forwarding eligibility. */
internal enum class UsbDeviceType(val icon: Int, val label: Int) {
    KEYBOARD(R.drawable.ic_usb_type_keyboard, R.string.usb_type_keyboard),
    MOUSE(R.drawable.ic_usb_type_mouse, R.string.usb_type_mouse),
    GAMEPAD(R.drawable.ic_usb_type_gamepad, R.string.usb_type_gamepad),
    INPUT(R.drawable.ic_usb_type_input, R.string.usb_type_input),
    NETWORK(R.drawable.ic_usb_type_network, R.string.usb_type_network),
    STORAGE(R.drawable.ic_usb_type_storage, R.string.usb_type_storage),
    AUDIO(R.drawable.ic_usb_type_audio, R.string.usb_type_audio),
    CAMERA(R.drawable.ic_usb_type_camera, R.string.usb_type_camera),
    PRINTER(R.drawable.ic_usb_type_printer, R.string.usb_type_printer),
    HUB(R.drawable.ic_usb_type_hub, R.string.usb_type_hub),
    GENERIC(R.drawable.ic_usb_type_generic, R.string.usb_type_generic);

    companion object {
        fun from(device: UsbDevice): UsbDeviceType {
            if (UsbDriverService.shouldClaimDevice(device, true)) return GAMEPAD
            // Verified adapter: vendor-specific class, so interface class alone cannot identify it.
            if (device.vendorId == 0x0b95 && device.productId == 0x1790) return NETWORK
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val kinds = interfaces.map { classify(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
                .filter { it != GENERIC }.distinct()
            return when (kinds.size) {
                0 -> classify(device.deviceClass, device.deviceSubclass, device.deviceProtocol)
                1 -> kinds.single()
                else -> GENERIC // Composite devices must not be mislabeled as just one function.
            }
        }

        internal fun classify(deviceClass: Int, subclass: Int, protocol: Int): UsbDeviceType = when {
            deviceClass == 3 && subclass == 1 && protocol == 1 -> KEYBOARD
            deviceClass == 3 && subclass == 1 && protocol == 2 -> MOUSE
            deviceClass == 3 -> INPUT
            deviceClass == 2 && subclass in setOf(6, 13, 14) -> NETWORK
            deviceClass == 1 -> AUDIO
            deviceClass == 7 -> PRINTER
            deviceClass == 8 -> STORAGE
            deviceClass == 9 -> HUB
            deviceClass == 14 -> CAMERA
            else -> GENERIC
        }
    }
}
