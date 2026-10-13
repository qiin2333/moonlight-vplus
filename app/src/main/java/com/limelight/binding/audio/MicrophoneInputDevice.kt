package com.limelight.binding.audio

import java.security.MessageDigest

internal const val DEFAULT_MICROPHONE_INPUT = "default"

internal data class MicrophoneInputDevice(
    val key: String,
    val id: Int,
    val type: Int,
    val name: String,
    val persistent: Boolean
)

internal data class MicrophoneInputDescriptor(
    val id: Int,
    val type: Int,
    val name: String,
    val address: String
)

/** IDs identify a current attachment, not a device across reconnects. Ambiguous names stay local. */
internal fun microphoneInputChoices(inputs: List<MicrophoneInputDescriptor>): List<MicrophoneInputDevice> {
    fun fingerprint(input: MicrophoneInputDescriptor): String? {
        val identity = input.address.takeIf(String::isNotBlank)
            ?.let { "address:$it" }
            ?: input.name.takeIf(String::isNotBlank)?.let { "name:$it" }
            ?: return null
        val bytes = MessageDigest.getInstance("SHA-256").digest("${input.type}:$identity".toByteArray(Charsets.UTF_8))
        return "device:" + bytes.joinToString("") { "%02x".format(it) }
    }
    val fingerprints = inputs.map(::fingerprint)
    val counts = fingerprints.filterNotNull().groupingBy { it }.eachCount()
    return inputs.mapIndexed { index, input ->
        val fingerprint = fingerprints[index]?.takeIf { counts[it] == 1 }
        MicrophoneInputDevice(
            key = fingerprint ?: "session:${input.type}:${input.id}",
            id = input.id,
            type = input.type,
            name = input.name,
            persistent = fingerprint != null
        )
    }
}

internal fun resolvedMicrophoneInputKey(selected: String, choices: List<MicrophoneInputDevice>): String {
    choices.firstOrNull { it.key == selected }?.let { return it.key }
    // A second identical microphone disappearing may make the remaining identity unambiguous.
    // Session IDs never reach disk, so this only follows an attachment in the current manager.
    choices.firstOrNull { selected == "session:${it.type}:${it.id}" }?.let { return it.key }
    return DEFAULT_MICROPHONE_INPUT
}

internal fun persistedMicrophoneInputKey(choice: MicrophoneInputDevice?): String =
    choice?.takeIf { it.persistent }?.key ?: DEFAULT_MICROPHONE_INPUT

internal fun selectMicrophoneInput(
    key: String,
    choices: List<MicrophoneInputDevice>,
    capturing: Boolean,
    requestRoute: () -> Boolean,
    save: (MicrophoneInputDevice?) -> Unit
): Boolean {
    val choice = choices.firstOrNull { it.key == key }
    if (key != DEFAULT_MICROPHONE_INPUT && choice == null) return false
    if (capturing && !requestRoute()) return false
    save(choice)
    return true
}
