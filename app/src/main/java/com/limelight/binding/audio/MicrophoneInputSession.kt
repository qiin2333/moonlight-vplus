package com.limelight.binding.audio

internal sealed class MicrophoneInputResolution {
    data object Default : MicrophoneInputResolution()
    data class Available(val device: MicrophoneInputDevice) : MicrophoneInputResolution()
    data object Unavailable : MicrophoneInputResolution()
}

internal fun resolveMicrophoneInput(
    selected: String, choices: List<MicrophoneInputDevice>
): MicrophoneInputResolution {
    if (selected == DEFAULT_MICROPHONE_INPUT) return MicrophoneInputResolution.Default
    val resolved = resolvedMicrophoneInputKey(selected, choices)
    return choices.firstOrNull { it.key == resolved }?.let(MicrophoneInputResolution::Available)
        ?: MicrophoneInputResolution.Unavailable
}

internal data class MicrophoneObservedInput(val id: Int?, val type: Int?, val silenced: Boolean = false)

internal fun reconcileMicrophoneInput(
    routed: MicrophoneObservedInput, recording: MicrophoneObservedInput?
): MicrophoneObservedInput {
    val silenced = routed.silenced || recording?.silenced == true
    if (routed.id != null && recording?.id != null &&
        (routed.id != recording.id || routed.type != recording.type)) {
        return MicrophoneObservedInput(null, null, silenced)
    }
    return (recording?.takeIf { it.id != null } ?: routed).copy(silenced = silenced)
}

/** Platform routing evidence, not proof of which physical sensor produced the PCM. */
internal data class MicrophoneActiveInput(
    val choice: MicrophoneInputDevice?, val observed: MicrophoneObservedInput
)

internal sealed class MicrophoneInputResult(val active: MicrophoneActiveInput?) {
    class Applied(input: MicrophoneActiveInput) : MicrophoneInputResult(input)
    class Restored(input: MicrophoneActiveInput) : MicrophoneInputResult(input)
    data object Stopped : MicrophoneInputResult(null)
}

internal class MicrophoneInputContract(
    val choice: MicrophoneInputDevice?,
    private val expectedCommunicationInputId: () -> Int? = { null },
    private val routeReady: () -> Boolean = { true }
) {
    val expectedId: Int? get() = choice?.takeUnless { it.communication }?.id

    fun matches(input: MicrophoneObservedInput): Boolean = when {
        choice?.communication == true -> !input.silenced && input.id != null &&
            input.type == choice.type && isBluetoothMicrophone(choice.type) &&
            (!requiresBluetoothRecordingBinding(choice.type) || input.id == expectedCommunicationInputId()) &&
            routeReady()
        choice != null -> input.id == choice.id && input.type == choice.type
        else -> true
    }
}

/** Publish the effective selection before any consumer can resume sending audio. */
internal fun completeMicrophoneInput(
    result: MicrophoneInputResult,
    save: (MicrophoneInputDevice?) -> Unit,
    onComplete: (MicrophoneInputResult) -> Unit
) {
    result.active?.let { save(it.choice) }
    onComplete(result)
}
