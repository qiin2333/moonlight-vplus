package com.limelight

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Resolves the display selected by the user for a streaming session.
 *
 * Display selection is intentionally kept separate from Presentation creation. The stream
 * configuration is built before [ExternalDisplayManager] creates its Presentation, so using
 * the Presentation manager as the source of truth would make early Native/HDR/refresh-rate
 * decisions fall back to the default display.
 */
class TargetDisplayResolver(context: Context) {
    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var targetDisplayId = Display.DEFAULT_DISPLAY

    fun resolve(useExternalDisplay: Boolean): Display {
        val availableDisplays = displayManager.displays.sortedBy { it.displayId }
        val selectedDisplayId = DisplaySelectionPolicy.resolveStreamDisplayId(
            useExternalDisplay,
            DisplaySelectionPreferences.getPrimaryStreamDisplayId(appContext),
            availableDisplays.map { it.displayId }
        )

        targetDisplayId = selectedDisplayId
        return displayManager.getDisplay(selectedDisplayId) ?: getDefaultDisplay()
    }

    fun currentDisplay(): Display {
        return displayManager.getDisplay(targetDisplayId) ?: getDefaultDisplay()
    }

    fun isExternalDisplaySelected(): Boolean {
        return targetDisplayId != Display.DEFAULT_DISPLAY &&
            displayManager.getDisplay(targetDisplayId) != null
    }

    /** Returns the first connected display other than the selected stream display. */
    fun controlDisplayFor(streamDisplayId: Int): Display? {
        val displays = displayManager.displays.sortedBy { it.displayId }
        val controlDisplayId = DisplaySelectionPolicy.resolveControlDisplayId(
            streamDisplayId,
            displays.map { it.displayId }
        ) ?: return null
        return displayManager.getDisplay(controlDisplayId)
    }

    /** Clears the active target when it is removed while retaining the user's preference. */
    fun onDisplayRemoved(displayId: Int): Boolean {
        if (targetDisplayId != displayId) {
            return false
        }

        targetDisplayId = Display.DEFAULT_DISPLAY
        return true
    }

    private fun getDefaultDisplay(): Display {
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display is unavailable")
    }
}

object DisplaySelectionPreferences {
    private const val PRIMARY_STREAM_DISPLAY_ID_PREF = "primary_stream_display_id"

    fun getPrimaryStreamDisplayId(context: Context): Int? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return if (preferences.contains(PRIMARY_STREAM_DISPLAY_ID_PREF)) {
            preferences.getInt(PRIMARY_STREAM_DISPLAY_ID_PREF, Display.DEFAULT_DISPLAY)
        } else {
            null
        }
    }

    fun setPrimaryStreamDisplayId(context: Context, displayId: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putInt(PRIMARY_STREAM_DISPLAY_ID_PREF, displayId)
        }
    }
}

internal object DisplaySelectionPolicy {
    fun resolveStreamDisplayId(
        enabled: Boolean,
        preferredDisplayId: Int?,
        availableDisplayIds: List<Int>
    ): Int {
        if (!enabled) return Display.DEFAULT_DISPLAY

        if (preferredDisplayId != null && preferredDisplayId in availableDisplayIds) {
            return preferredDisplayId
        }

        return if (Display.DEFAULT_DISPLAY in availableDisplayIds) {
            Display.DEFAULT_DISPLAY
        } else {
            availableDisplayIds.firstOrNull() ?: Display.DEFAULT_DISPLAY
        }
    }

    fun resolveControlDisplayId(
        streamDisplayId: Int,
        availableDisplayIds: List<Int>
    ): Int? = availableDisplayIds.firstOrNull { it != streamDisplayId }
}

object DisplaySelectionFormatter {
    @Suppress("DEPRECATION")
    fun label(display: Display): String {
        val realSize = Point()
        display.getRealSize(realSize)
        return label(display.name, display.displayId, realSize.x, realSize.y)
    }

    internal fun label(name: String?, displayId: Int, width: Int, height: Int): String {
        val displayName = name?.trim().takeUnless { it.isNullOrEmpty() } ?: "display$displayId"
        return "$displayName (${width}×${height})"
    }
}
