package com.limelight.binding.input.touchpad

import android.content.ContentValues
import android.view.ViewGroup
import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.preferences.TouchModePreferenceState
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
    private var pendingHostUpdate: Runnable? = null
    private var activationFallback: TouchModePreferenceState? = null

    private val enabled: Boolean
        get() = game.prefConfig.screenDs5Touchpad

    /** Attach the zone overlay during stream setup when the pad layer is enabled. */
    fun attachIfEnabled(container: ViewGroup) {
        this.container = container
        if (enabled) {
            rememberTouchMode()
            attach(container)
        }
    }

    /** Activation hint plus a zone reveal on stream start. */
    fun onStreamStarted() {
        if (enabled) rememberTouchMode()
        overlay?.showActivatedHint()
        revealIfEnabled()
    }

    /**
     * Toggle the pad layer at runtime: persist the preference, attach/detach the
     * overlay, and (un)declare controller 0 on the host. Re-selecting the same mode
     * retries a pending host update without rebuilding the surface.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && game.screenDs5TouchpadHostSupport == Game.ScreenDs5HostSupport.UNSUPPORTED) return
        if (game.prefConfig.screenDs5Touchpad == enabled) {
            if (pendingHostUpdate != null) updateHost()
            return
        }
        if (enabled) rememberTouchMode() else activationFallback = null
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
        syncTouchModeConfig()
        game.touchInputHandler.cancelScreenDs5TouchpadGesture()
        if (enabled) {
            container?.let { attach(it) }
            // Show where the pad surface is right after enabling. Also deferred:
            // the menu covering this call hides the outline behind it.
            pendingZoneReveal = true
            overlay?.showZoneReveal()
        } else {
            pendingZoneReveal = false
            detach()
        }
        updateHost()
    }

    /** Toggle the pad layer for hotkeys and quick actions; returns the new state. */
    fun toggle(): Boolean {
        setEnabled(!enabled)
        return enabled
    }

    private fun rememberTouchMode() {
        if (activationFallback == null) {
            activationFallback = TouchModePreferenceState(
                game.prefConfig.enableEnhancedTouch,
                game.prefConfig.touchscreenTrackpad,
                game.prefConfig.enableNativeMousePointer,
                screenDs5Touchpad = false,
            )
        }
    }

    /** A rejected probe restores the ordinary touch mode that activation replaced. */
    fun onHostSupportKnown(supported: Boolean) {
        val fallback = activationFallback
        activationFallback = null
        if (supported || !enabled) return
        if (fallback != null) {
            game.setEnhancedTouch(fallback.enhancedTouch)
            game.setTouchMode(fallback.touchscreenTrackpad)
            game.enableNativeMousePointer(fallback.nativeMousePointer)
        }
        setEnabled(false)
    }

    private fun syncTouchModeConfig() {
        val manager = game.controllerManager ?: return
        val configId = manager.pageConfigController?.currentConfigId ?: return
        val values = ContentValues().apply {
            put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, game.prefConfig.touchscreenTrackpad.toString())
            put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, game.prefConfig.enableEnhancedTouch.toString())
        }
        manager.superConfigDatabaseHelper?.updateConfig(configId, values)
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
        if (enabled || pendingHostUpdate != null) updateHost()
    }

    private fun updateHost() {
        cancelPendingHostUpdate()
        val controller = game.controllerHandler
        var attempts = 0
        val update = object : Runnable {
            override fun run() {
                if (pendingHostUpdate !== this || game.controllerHandler !== controller ||
                    !game.connected || game.isFinishing || game.isDestroyed
                ) return
                val result = controller.setScreenDs5TouchpadEnabled(enabled)
                if (result == 0) {
                    pendingHostUpdate = null
                    return
                }
                attempts++
                LimeLog.warning("Screen DS5 touchpad controller update failed: enabled=$enabled, result=$result, attempt=$attempts")
                if (attempts < 3) game.streamView.postDelayed(this, 150L)
                // Keep the latest intent pending after exhaustion, for an explicit
                // retry or the next host-ready callback; never spin indefinitely.
            }
        }
        pendingHostUpdate = update
        update.run()
    }

    /** Cancel the old request before another mode change or stream teardown. */
    fun cancelPendingHostUpdate() {
        pendingHostUpdate?.let { game.streamView.removeCallbacks(it) }
        pendingHostUpdate = null
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
