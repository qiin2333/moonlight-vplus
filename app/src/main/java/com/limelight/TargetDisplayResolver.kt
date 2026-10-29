package com.limelight

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Resolves the display used by a streaming session.
 *
 * Display selection is intentionally kept separate from Presentation creation. The stream
 * configuration is built before [ExternalDisplayManager] creates its Presentation, so using
 * the Presentation manager as the source of truth would make early Native/HDR/refresh-rate
 * decisions fall back to the default display.
 */
class TargetDisplayResolver(context: Context) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var targetDisplayId = Display.DEFAULT_DISPLAY

    fun resolve(useExternalDisplay: Boolean): Display {
        if (!useExternalDisplay) {
            targetDisplayId = Display.DEFAULT_DISPLAY
            return getDefaultDisplay()
        }

        val displays = presentationDisplays()
        val externalDisplay = displays.firstOrNull { it.displayId == targetDisplayId }
            ?: displays.firstOrNull()

        if (externalDisplay != null) {
            targetDisplayId = externalDisplay.displayId
            return externalDisplay
        }

        targetDisplayId = Display.DEFAULT_DISPLAY
        return getDefaultDisplay()
    }

    fun currentDisplay(): Display {
        if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            return getDefaultDisplay()
        }

        return presentationDisplays().firstOrNull { it.displayId == targetDisplayId }
            ?: getDefaultDisplay()
    }

    fun isExternalDisplaySelected(): Boolean {
        return currentDisplay().displayId != Display.DEFAULT_DISPLAY
    }

    /** Clears the selected target when it is removed and reports whether it was selected. */
    fun onDisplayRemoved(displayId: Int): Boolean {
        if (targetDisplayId != displayId) {
            return false
        }

        targetDisplayId = Display.DEFAULT_DISPLAY
        return true
    }

    // Secondary internal/virtual displays are not necessarily Presentation targets.
    private fun presentationDisplays(): List<Display> =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }

    private fun getDefaultDisplay(): Display {
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display is unavailable")
    }
}
