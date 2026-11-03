package com.limelight.binding.input.touchpad

import android.view.ViewGroup
import com.limelight.Game
import com.limelight.ui.TouchpadZoneOverlayView

/**
 * Owns the screen DS5 pad surface: the zone overlay lifecycle, the persisted zone
 * rect and the host-side DualSense declaration. Centralizes the feature's plumbing
 * so Game and the touch-mode menu only touch a few calls.
 */
class ScreenDs5Touchpad(private val game: Game) {

    /** Attached zone overlay; null while the pad layer is off. */
    var overlay: TouchpadZoneOverlayView? = null
        private set

    private var pendingZoneReveal = false
    private var container: ViewGroup? = null

    private val enabled: Boolean
        get() = game.prefConfig.screenDs5Touchpad

    /** Attach the zone overlay during stream setup when the pad layer is enabled. */
    fun attachIfEnabled(container: ViewGroup) {
        this.container = container
        if (enabled) {
            attach(container)
        }
    }

    /** Activation hint plus a zone reveal on stream start. */
    fun onStreamStarted() {
        overlay?.showActivatedHint()
        revealIfEnabled()
    }

    /**
     * Toggle the pad layer at runtime: persist the preference, attach/detach the
     * overlay, and (un)declare controller 0 on the host. No-op when already active.
     */
    fun setEnabled(enabled: Boolean) {
        if (game.prefConfig.screenDs5Touchpad == enabled) return
        game.prefConfig.screenDs5Touchpad = enabled
        if (enabled) {
            // The area outside the zone is plain multi-touch: normalize the touch
            // mode so runtime toggles match what the menu selects.
            game.prefConfig.enableEnhancedTouch = true
            game.prefConfig.touchscreenTrackpad = false
            game.enableNativeMousePointer(false)
            game.setTouchMode(false)
        }
        game.prefConfig.writePreferences(game)
        game.touchInputHandler.cancelScreenDs5TouchpadGesture()
        if (enabled) {
            container?.let { attach(it) }
            // Show where the pad surface is right after enabling. Also deferred:
            // the menu covering this call hides the outline behind it.
            pendingZoneReveal = true
            overlay?.showZoneReveal()
        } else {
            detach()
        }
        game.controllerHandler.setScreenDs5TouchpadEnabled(enabled)
    }

    /** Toggle the pad layer for hotkeys and quick actions; returns the new state. */
    fun toggle(): Boolean {
        val next = !enabled
        setEnabled(next)
        return next
    }

    fun setSingleTapToClick(enabled: Boolean) {
        if (game.prefConfig.screenDs5SingleTapToClick == enabled) return
        cancelGesture()
        game.prefConfig.screenDs5SingleTapToClick = enabled
        game.prefConfig.writePreferences(game)
        overlay?.setSingleTapToClick(enabled)
        overlay?.showActivatedHint()
    }

    /** Re-declare controller 0 as DualSense after host negotiations settle. */
    fun redeclareOnHost() {
        if (enabled) {
            game.controllerHandler.setScreenDs5TouchpadEnabled(true)
        }
    }

    /** Persist a zone rect (edge-resize lift). */
    fun persistZoneRect(rect: TouchpadZoneGeometry.NormalizedRect) {
        game.prefConfig.screenDs5TouchpadZoneRect =
            TouchpadZoneGeometry.formatZoneRect(rect)
        game.prefConfig.writePreferences(game)
    }

    /** Show the zone outline while the pad layer is enabled. */
    private fun revealIfEnabled() {
        if (enabled) {
            overlay?.showZoneReveal()
        }
    }

    /** Flush a deferred zone reveal when the game menu closes. */
    fun revealPending() {
        if (pendingZoneReveal) {
            pendingZoneReveal = false
            revealIfEnabled()
        }
    }

    private fun attach(container: ViewGroup) {
        if (overlay != null) return
        // Always map against the phone's own stream view: the overlay lives in the
        // phone window, so an external-display activeStreamView would misplace it.
        val view = TouchpadZoneOverlayView(game) {
            game.streamView
        }.apply {
            setSingleTapToClick(game.prefConfig.screenDs5SingleTapToClick)
            setZoneRect(TouchpadZoneGeometry.parseZoneRect(game.prefConfig.screenDs5TouchpadZoneRect))
        }
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay = view
    }

    private fun detach() {
        val view = overlay ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        overlay = null
    }

    /** Drop any in-flight pad gesture and clear pad state on the host. */
    fun cancelGesture() {
        game.touchInputHandler.cancelScreenDs5TouchpadGesture()
    }
}
