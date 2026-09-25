package com.limelight

internal data class PipInteractiveOverlaySnapshot(
    val virtualControllerVisible: Boolean,
    val crownControllerVisible: Boolean,
    val microphoneButtonVisible: Boolean,
    val floatBallVisible: Boolean
)

internal class PipInteractiveOverlayState {
    private var snapshot: PipInteractiveOverlaySnapshot? = null

    fun enter(current: PipInteractiveOverlaySnapshot): Boolean {
        if (snapshot != null) return false
        snapshot = current
        return true
    }

    fun exitIfResumed(isResumed: Boolean): PipInteractiveOverlaySnapshot? =
        if (isResumed) snapshot?.also { snapshot = null } else null

    fun virtualControllerVisibleForStop(currentVisible: Boolean): Boolean =
        snapshot?.virtualControllerVisible ?: currentVisible

    fun isActive(): Boolean = snapshot != null
}
