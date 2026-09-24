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

    fun exit(): PipInteractiveOverlaySnapshot? = snapshot.also { snapshot = null }

    fun virtualControllerVisibleOrNull(): Boolean? = snapshot?.virtualControllerVisible

    fun virtualControllerVisibleForStop(currentVisible: Boolean): Boolean =
        snapshot?.virtualControllerVisible ?: currentVisible

    fun isActive(): Boolean = snapshot != null
}
