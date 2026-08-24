package com.limelight

internal data class PipInteractiveOverlaySnapshot(
    val virtualControllerVisible: Boolean,
    val crownControllerVisible: Boolean,
    val microphoneButtonVisible: Boolean
)

internal class PipInteractiveOverlayState {
    private var snapshot: PipInteractiveOverlaySnapshot? = null

    fun enter(current: PipInteractiveOverlaySnapshot): Boolean {
        if (snapshot != null) return false
        snapshot = current
        return true
    }

    fun exit(): PipInteractiveOverlaySnapshot? = snapshot.also { snapshot = null }

    fun isActive(): Boolean = snapshot != null
}
