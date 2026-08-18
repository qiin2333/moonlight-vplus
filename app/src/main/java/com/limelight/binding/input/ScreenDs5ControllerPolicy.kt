package com.limelight.binding.input

/** Pure policy for replacing controller 0 when its host-visible profile changes. */
internal object ScreenDs5ControllerPolicy {
    private const val PRIMARY_CONTROLLER_MASK = 1

    fun shouldRemovePrimaryController(activeMask: Short, hasSentMetadata: Boolean): Boolean =
        hasSentMetadata || (activeMask.toInt() and PRIMARY_CONTROLLER_MASK) != 0

    fun withoutPrimaryController(activeMask: Short): Short =
        (activeMask.toInt() and PRIMARY_CONTROLLER_MASK.inv()).toShort()
}
