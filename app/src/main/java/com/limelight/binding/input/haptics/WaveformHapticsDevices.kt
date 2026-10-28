package com.limelight.binding.input.haptics

import android.content.Context
import android.hardware.usb.UsbManager
import com.limelight.binding.input.driver.UsbWaveformBackends
import com.limelight.binding.input.driver.wireless.dualsense.AndroidBluetoothHidHostTransport
import com.limelight.binding.input.driver.wireless.dualsense.DirectDualSenseBluetoothOutput

/** Shared pre-launch hint for host-gamepad selection and session PCM negotiation. */
internal object WaveformHapticsDevices {
    fun hasEligibleController(context: Context, allowExperimental: Boolean, directBluetooth: Boolean): Boolean =
        UsbWaveformBackends.hasEligibleController(
            context.getSystemService(Context.USB_SERVICE) as? UsbManager, allowExperimental) ||
            (allowExperimental && directBluetooth && AndroidBluetoothHidHostTransport.isAvailable(context) &&
                DirectDualSenseBluetoothOutput.hasWaveformCandidate())
}
