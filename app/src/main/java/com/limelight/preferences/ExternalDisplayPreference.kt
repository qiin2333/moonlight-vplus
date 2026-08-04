package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.AttributeSet
import android.view.Display
import androidx.preference.CheckBoxPreference
import com.limelight.DisplaySelectionFormatter
import com.limelight.DisplaySelectionPreferences
import com.limelight.R
import com.limelight.utils.AppDialogStyler

/**
 * Dual-screen preference that requires the user to select the display used for streaming.
 */
class ExternalDisplayPreference : CheckBoxPreference {
    private val displayManager: DisplayManager
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    constructor(context: Context) : super(context) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        initialize()
    }

    private fun initialize() {
        updateSummary()
    }

    override fun onAttachedToHierarchy(preferenceManager: androidx.preference.PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
    }

    override fun onClick() {
        val displays = connectedDisplays()
        if (displays.size < 2) {
            updateSummary()
            return
        }

        var selectedIndex = findSelectedDisplayIndex(displays)
        val labels = displays.map(DisplaySelectionFormatter::label).toTypedArray()
        val dialog = AlertDialog.Builder(context, R.style.AppDialogStyle)
            .setTitle(R.string.dual_screen_select_primary_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.dual_screen_enable) { _, _ ->
                val selectedDisplay = displays[selectedIndex]
                if (callChangeListener(true)) {
                    DisplaySelectionPreferences.setPrimaryStreamDisplayId(
                        context,
                        selectedDisplay.displayId
                    )
                    isChecked = true
                    updateSummary()
                }
            }
            .setNeutralButton(R.string.dual_screen_disable) { _, _ ->
                if (callChangeListener(false)) {
                    isChecked = false
                    updateSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        AppDialogStyler.applySystemChoiceList(dialog, context)
    }

    private fun connectedDisplays(): List<Display> =
        displayManager.displays.sortedBy { it.displayId }

    private fun findSelectedDisplayIndex(displays: List<Display>): Int {
        val selectedId = DisplaySelectionPreferences.getPrimaryStreamDisplayId(context)
            ?: Display.DEFAULT_DISPLAY
        return displays.indexOfFirst { it.displayId == selectedId }.coerceAtLeast(0)
    }

    private fun updateSummary() {
        try {
            val displays = connectedDisplays()
            if (displays.size < 2) {
                summary = context.getString(R.string.external_display_not_detected)
                isEnabled = false
                isChecked = false
                return
            }

            isEnabled = true
            if (!isChecked) {
                summary = context.getString(
                    R.string.dual_screen_available_summary,
                    displays.size
                )
                return
            }

            val streamDisplay = displays.getOrNull(findSelectedDisplayIndex(displays))
                ?: displays.first()
            val controlDisplay = displays.first { it.displayId != streamDisplay.displayId }
            summary = context.getString(
                R.string.dual_screen_selection_summary,
                DisplaySelectionFormatter.label(streamDisplay),
                DisplaySelectionFormatter.label(controlDisplay)
            )
        } catch (e: Exception) {
            summary = context.getString(
                R.string.external_display_detection_failed,
                e.localizedMessage ?: e.javaClass.simpleName
            )
            isEnabled = false
            isChecked = false
        }
    }
}
