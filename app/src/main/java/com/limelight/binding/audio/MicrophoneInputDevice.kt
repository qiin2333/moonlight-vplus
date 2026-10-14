package com.limelight.binding.audio

import java.security.MessageDigest

internal const val DEFAULT_MICROPHONE_INPUT = "default"

internal data class MicrophoneInputDevice(
    val key: String,
    val id: Int,
    val type: Int,
    val name: String,
    val persistentKey: String?
) {
    val persistent: Boolean get() = persistentKey != null
}

internal data class MicrophoneInputDescriptor(
    val id: Int,
    val type: Int,
    val name: String,
    val address: String
)

// Only known microphone endpoints. isSource also includes non-recording/vendor audio ports.
internal fun isSelectableMicrophoneInput(type: Int): Boolean = when (type) {
    3, 7, 11, 12, 15, 22, 26 -> true
    else -> false
}

internal fun selectableMicrophoneInputs(devices: List<MicrophoneInputDevice>): List<MicrophoneInputDevice> =
    devices.filter { isSelectableMicrophoneInput(it.type) }.sortedWith(
        compareBy<MicrophoneInputDevice> {
            when (it.type) {
                3, 11, 12, 22 -> 0
                15 -> 1
                7, 26 -> 3
                else -> 2
            }
        }.thenBy { it.id }
    )

internal fun microphoneInputLabels(
    devices: List<MicrophoneInputDevice>,
    nameOf: (MicrophoneInputDevice) -> String,
    numberedName: (String, Int) -> String
): Map<String, String> {
    val names = devices.associate { it.key to nameOf(it) }
    val counts = names.values.groupingBy { it }.eachCount()
    val occurrences = mutableMapOf<String, Int>()
    return devices.sortedBy { it.id }.associate { device ->
        val name = names.getValue(device.key)
        val number = (occurrences[name] ?: 0) + 1
        occurrences[name] = number
        device.key to if (counts.getValue(name) > 1) numberedName(name, number) else name
    }
}

/** Attachment keys never depend on other devices; only unambiguous identities may be persisted. */
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
        MicrophoneInputDevice(
            key = "session:${input.type}:${input.id}",
            id = input.id,
            type = input.type,
            name = input.name,
            persistentKey = fingerprints[index]?.takeIf { counts[it] == 1 }
        )
    }
}

internal fun resolvedMicrophoneInputKey(selected: String, choices: List<MicrophoneInputDevice>): String {
    choices.firstOrNull { it.key == selected }?.let { return it.key }
    choices.firstOrNull { it.persistentKey == selected }?.let { return it.key }
    return DEFAULT_MICROPHONE_INPUT
}

internal fun persistedMicrophoneInputKey(choice: MicrophoneInputDevice?): String =
    choice?.persistentKey ?: DEFAULT_MICROPHONE_INPUT

/** Bind a saved identity once per manager. A missing attachment must not adopt a namesake. */
internal class MicrophoneInputSelection(savedKey: String) {
    private var savedKey = savedKey
    private var attachmentKey: String? = null

    val selectedKey: String
        @Synchronized get() = attachmentKey ?: savedKey

    @Synchronized
    fun resolve(choices: List<MicrophoneInputDevice>): String {
        val resolved = resolvedMicrophoneInputKey(attachmentKey ?: savedKey, choices)
        if (attachmentKey == null && resolved != DEFAULT_MICROPHONE_INPUT) attachmentKey = resolved
        return resolved
    }

    @Synchronized
    fun select(choice: MicrophoneInputDevice?): String {
        attachmentKey = choice?.key
        savedKey = persistedMicrophoneInputKey(choice)
        return savedKey
    }
}

internal fun selectMicrophoneInput(
    key: String,
    choices: List<MicrophoneInputDevice>,
    capturing: Boolean,
    requestRoute: ((Boolean) -> Unit) -> Boolean,
    save: (MicrophoneInputDevice?) -> Unit,
    canComplete: () -> Boolean = { true },
    onComplete: (Boolean) -> Unit = {}
): Boolean {
    val choice = choices.firstOrNull { it.key == key && isSelectableMicrophoneInput(it.type) }
    if (key != DEFAULT_MICROPHONE_INPUT && choice == null) return false
    if (!capturing) {
        save(choice)
        return true
    }
    return requestRoute { success ->
        if (canComplete()) {
            if (success) save(choice)
            onComplete(success)
        }
    }
}
