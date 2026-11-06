package com.limelight.binding.input.haptics

/** Calibration applies only to the tested system TICK path and firmware, not arbitrary PCM. */
internal object DeviceDetailProfile {
    fun tickIntervalMs(manufacturer: String, model: String, sdk: Int, display: String): Long =
        if (manufacturer.equals("meizu", ignoreCase = true) &&
            model.equals("meizu 17", ignoreCase = true) && sdk == 33 &&
            display == "Flyme 10.5.0.0A") 40L else 90L
}
