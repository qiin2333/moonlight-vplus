package com.limelight.binding.input

/** Physical identity is independent of the user's pairing/vertical-layout preference. */
internal class JoyConDeviceState private constructor(
    val side: JoyConSide,
    val combineEnabled: Boolean
) {
    val dpad: ControllerDpadState? = if (side == JoyConSide.LEFT) ControllerDpadState() else null
    fun preferMotionSource(hasActivePeer: Boolean): Boolean =
        combineEnabled && side == JoyConSide.RIGHT && hasActivePeer

    fun remapKey(keyCode: Int, scanCode: Int): Int {
        // The left scan-code workaround is needed even when pairing is disabled.
        val mappingSide = side.takeIf { it == JoyConSide.LEFT || combineEnabled }
        return JoyConMapping.key(mappingSide, keyCode, scanCode)
    }

    companion object {
        fun create(vendorId: Int, productId: Int, combineEnabled: Boolean): JoyConDeviceState? =
            joyConSide(vendorId, productId)?.let { JoyConDeviceState(it, combineEnabled) }
    }
}
