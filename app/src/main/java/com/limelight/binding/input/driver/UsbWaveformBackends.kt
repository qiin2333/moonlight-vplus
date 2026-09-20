package com.limelight.binding.input.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.limelight.binding.input.haptics.*

/** Android transport adapters. Matching and activation decisions live in the pure registry. */
internal object UsbWaveformBackends {
    private val factories: Map<String, (android.content.Context, UsbManager, UsbDevice, HapticCandidate,
        (ControllerHapticsCapability) -> Unit) -> WaveformHapticsSink?> = mapOf(
        KishiUsbHapticProfile.id to { _, manager, device, candidate, status ->
            val iface = (0 until device.interfaceCount).map(device::getInterface).singleOrNull {
                it.id == candidate.interfaceId && it.alternateSetting == 0
            }
            val endpoint = iface?.let { intf -> (0 until intf.endpointCount).map(intf::getEndpoint)
                .singleOrNull { it.address == candidate.endpointAddress } }
            if (iface != null && endpoint != null) KishiUsbHapticsSink(manager, device, iface, endpoint, status)
            else null
        },
        KishiSensaHapticProfile.id to { context, manager, device, candidate, status ->
            val iface = (0 until device.interfaceCount).map(device::getInterface).singleOrNull {
                it.id == candidate.interfaceId && it.alternateSetting == 0
            }
            if (iface != null && KishiSensaHapticProfile.probe(identity(device))?.layoutMatches == true) {
                KishiSensaHapticsSink(manager, device, iface,
                    (0 until iface.endpointCount).map(iface::getEndpoint).single { it.address == 4 },
                    { com.limelight.preferences.SensaStrengthPreferences.read(context) },
                    { com.limelight.preferences.SensaStrengthPreferences.frequency(context) },
                    { com.limelight.preferences.SensaStrengthPreferences.conversionEnabled(context) },
                    { com.limelight.preferences.SensaStrengthPreferences.pcmEnabled(context) }, status)
            } else null
        }
    )

    /** Passive pre-launch hint. Permission and a working sink are still required for output. */
    fun hasEligibleController(manager: UsbManager?, allowExperimental: Boolean,
                              includeRumbleConversion: Boolean = true, sensaEnabled: Boolean = false): Boolean =
        manager?.deviceList?.values?.any { device ->
            HapticBackendRegistry().discover(identity(device)).any {
                it.layoutMatches && android.os.Build.VERSION.SDK_INT >= it.minimumApi &&
                    (includeRumbleConversion || it.capability.backendId != KishiSensaHapticProfile.id) &&
                    (if (it.capability.backendId == KishiSensaHapticProfile.id) sensaEnabled
                    else it.capability.evidence != HapticEvidence.EXPERIMENTAL_PROTOCOL || allowExperimental)
            }
        } == true

    fun identity(device: UsbDevice) = HapticDeviceIdentity(
        device.deviceName, device.productName ?: "USB controller", device.vendorId, device.productId,
        HapticTransport.USB, (0 until device.interfaceCount).map { index ->
            val iface = device.getInterface(index)
            HapticUsbInterface(iface.id, iface.alternateSetting, iface.interfaceClass, iface.interfaceSubclass,
                (0 until iface.endpointCount).map { epIndex ->
                    val ep = iface.getEndpoint(epIndex)
                    HapticUsbEndpoint(ep.address, ep.type, ep.maxPacketSize)
                })
        }
    )

    fun create(context: android.content.Context, manager: UsbManager, device: UsbDevice, candidate: HapticCandidate,
               status: (ControllerHapticsCapability) -> Unit): WaveformHapticsSink? =
        factories[candidate.capability.backendId]?.invoke(context, manager, device, candidate, status)
}
