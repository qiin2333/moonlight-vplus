package com.limelight.binding.audio

import java.security.MessageDigest

internal fun isBluetoothMicrophone(type: Int): Boolean = type == 7 || type == 26

/** Communication output identities must never be confused with AudioRecord input IDs. */
internal fun communicationMicrophoneChoices(outputs: List<MicrophoneInputDescriptor>): List<MicrophoneInputDevice> {
    val headsets = outputs.filter { isBluetoothMicrophone(it.type) }
    val addresses = headsets.groupingBy { it.type to it.address }.eachCount()
    return headsets.map { output ->
        val stable = output.address.takeIf { it.isNotBlank() && addresses[output.type to it] == 1 }
        val fingerprint = stable?.let {
            MessageDigest.getInstance("SHA-256").digest("communication:${output.type}:$it".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        MicrophoneInputDevice("communication-session:${output.type}:${output.id}", output.id,
            output.type, output.name, fingerprint?.let { "communication:$it" }, true, output.address)
    }
}

internal fun matchingBluetoothInput(target: MicrophoneInputDevice, inputs: List<MicrophoneInputDevice>): MicrophoneInputDevice? {
    val candidates = inputs.filter { !it.communication && it.type == target.type }
    if (target.address.isNotBlank()) {
        val exact = candidates.filter { it.address.equals(target.address, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        // An explicit different address is evidence against a match, not an absent identity.
        if (candidates.any { it.address.isNotBlank() }) return null
    }
    return candidates.singleOrNull()
}

internal fun validMicrophonePreference(key: String?): String = key?.takeIf {
    it == DEFAULT_MICROPHONE_INPUT || it.matches(Regex("(?:device|communication):[a-f0-9]{64}"))
} ?: DEFAULT_MICROPHONE_INPUT
