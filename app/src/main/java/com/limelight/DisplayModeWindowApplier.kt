package com.limelight

import android.os.Build
import android.view.Window

/** Applies a display mode selection to the Window rendered on that display. */
internal object DisplayModeWindowApplier {
    fun apply(window: Window, selection: DisplayModeManager.DisplayModeSelection) {
        val layoutParams = window.attributes

        if (selection.preferredModeId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layoutParams.preferredDisplayModeId = selection.preferredModeId
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            layoutParams.preferredRefreshRate = selection.refreshRate
        } else {
            // No exact mode (the setFrameRate() path): clear a pin left by an earlier
            // selection so it cannot override this one. 0 means "no preference".
            layoutParams.preferredDisplayModeId = 0
        }

        window.attributes = layoutParams
    }
}
