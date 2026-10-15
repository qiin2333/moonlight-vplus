package com.limelight

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.security.MessageDigest

/** Serial access requires USB permission on modern Android. Never mistake an unreadable serial
 * for a serial-less device when deciding whether a saved rule authorizes an export. */
internal fun UsbDevice.autoShareIdentity(manager: UsbManager, readSerial: Boolean = manager.hasPermission(this)): UsbAutoShareIdentity {
    val descriptors = buildString {
        append("$deviceClass:$deviceSubclass:$deviceProtocol:$configurationCount;")
        for (c in 0 until configurationCount) {
            val config = getConfiguration(c)
            append("${config.id}:${config.isSelfPowered}:${config.isRemoteWakeup}:${config.maxPower};")
            for (i in 0 until config.interfaceCount) {
                val intf = config.getInterface(i)
                append("${intf.id}:${intf.alternateSetting}:${intf.interfaceClass}:${intf.interfaceSubclass}:${intf.interfaceProtocol};")
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    append("${ep.address}:${ep.attributes}:${ep.maxPacketSize}:${ep.interval};")
                }
            }
        }
    }
    val signature = MessageDigest.getInstance("SHA-256").digest(descriptors.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val serial = if (readSerial) serialNumber
        ?.takeIf { it.isNotBlank() } else null
    return UsbAutoShareIdentity(vendorId, productId, signature, serial)
}
