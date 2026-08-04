@file:Suppress("DEPRECATION")
package com.limelight

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.limelight.preferences.BackgroundSource
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import java.io.File

/**
 * 外接显示器管理器
 * 负责管理外接显示器的检测、连接、断开和内容显示
 */
class ExternalDisplayManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
    private val targetDisplayResolver: TargetDisplayResolver
) {
    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var displayListener: DisplayManager.DisplayListener? = null
    private var externalPresentation: ExternalDisplayPresentation? = null
    private var dualScreenPresentation: DualScreenControlPresentation? = null
    private var idleBackgroundPresentation: IdleBackgroundPresentation? = null
    private var dualScreenControlsEnabled = true
    private var displayModeSelection: DisplayModeManager.DisplayModeSelection? = null
    private var backgroundPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    interface ExternalDisplayCallback {
        fun onExternalDisplayConnected(display: Display)
        fun onExternalDisplayDisconnected()
        fun onStreamViewReady(streamView: StreamView)
        fun onDualScreenControlPanelReady(
            rootView: View,
            streamDisplay: Display,
            controlDisplay: Display
        )
        fun onDualScreenDisconnected()
    }

    var callback: ExternalDisplayCallback? = null

    fun initialize(initialDisplayMode: DisplayModeManager.DisplayModeSelection? = null) {
        displayModeSelection = initialDisplayMode
        registerIdleBackgroundPreferenceListener()
        setupDisplayListener()
        reconcileDisplays()
    }

    fun cleanup() {
        dismissExternalPresentation()
        dismissDualScreenPresentation()
        dismissIdleBackgroundPresentation()
        unregisterIdleBackgroundPreferenceListener()

        displayListener?.let {
            displayManager.unregisterDisplayListener(it)
            displayListener = null
        }
    }

    fun getTargetDisplay(): Display {
        return targetDisplayResolver.currentDisplay()
    }

    fun isUsingExternalDisplay(): Boolean = targetDisplayResolver.isExternalDisplaySelected()

    fun setDualScreenControlsEnabled(enabled: Boolean) {
        dualScreenControlsEnabled = enabled
        if (!enabled) {
            val hadControlPresentation = dualScreenPresentation != null
            dismissDualScreenPresentation()
            if (hadControlPresentation) {
                callback?.onDualScreenDisconnected()
            }
            return
        }

        reconcileDisplays()
    }

    fun canRestoreDualScreenControls(): Boolean {
        if (dualScreenControlsEnabled || !prefConfig.useExternalDisplay) return false
        val streamDisplay = targetDisplayResolver.currentDisplay()
        return streamDisplay.displayId == Display.DEFAULT_DISPLAY &&
            targetDisplayResolver.controlDisplayFor(streamDisplay.displayId) != null
    }

    /**
     * Stores the mode selected for a specific display and applies it to the Presentation when
     * that display is rendered. The display id prevents a stale mode id from being used after a
     * hotplug event changes the target display.
     */
    fun updateDisplayMode(selection: DisplayModeManager.DisplayModeSelection) {
        displayModeSelection = selection

        if (externalPresentation?.isForDisplay(selection.displayId) == true) {
            externalPresentation?.window?.let { DisplayModeWindowApplier.apply(it, selection) }
        }
    }

    private fun setupDisplayListener() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                LimeLog.info("Display added: $displayId")
                if (prefConfig.useExternalDisplay && displayId != Display.DEFAULT_DISPLAY) {
                    reconcileDisplays()
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                LimeLog.info("Display removed: $displayId")
                val wasIdleBackground =
                    idleBackgroundPresentation?.isForDisplay(displayId) == true
                if (wasIdleBackground) {
                    dismissIdleBackgroundPresentation()
                }

                val wasDualScreen = dualScreenPresentation?.isForDisplay(displayId) == true
                if (wasDualScreen) {
                    dismissDualScreenPresentation()
                    callback?.onDualScreenDisconnected()
                }

                val wasTargetDisplay = displayId != Display.DEFAULT_DISPLAY &&
                    targetDisplayResolver.onDisplayRemoved(displayId)
                if (wasTargetDisplay) {
                    dismissExternalPresentation()

                    if (callback != null) {
                        val surfaceView = activity.findViewById<View>(R.id.surfaceView)
                        surfaceView?.visibility = View.VISIBLE
                        Toast.makeText(activity, activity.getString(R.string.toast_external_display_disconnected), Toast.LENGTH_SHORT).show()

                        callback?.onExternalDisplayDisconnected()
                    }
                }

                if (wasIdleBackground || wasDualScreen || wasTargetDisplay) {
                    reconcileDisplays()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                LimeLog.info("Display changed: $displayId")
            }
        }

        displayListener = listener
        displayManager.registerDisplayListener(listener, null)
    }

    private fun dismissExternalPresentation() {
        externalPresentation?.dismiss()
        externalPresentation = null
    }

    private fun dismissDualScreenPresentation() {
        dualScreenPresentation?.dismiss()
        dualScreenPresentation = null
    }

    private fun dismissIdleBackgroundPresentation() {
        idleBackgroundPresentation?.clearBackground()
        idleBackgroundPresentation?.dismiss()
        idleBackgroundPresentation = null
    }

    private fun reconcileDisplays() {
        if (!prefConfig.useExternalDisplay) {
            LimeLog.info("Dual-screen and external display support disabled by user preference")
            targetDisplayResolver.resolve(false)
            dismissExternalPresentation()
            dismissDualScreenPresentation()
            dismissIdleBackgroundPresentation()
            return
        }

        val streamDisplay = targetDisplayResolver.resolve(true)
        if (callback == null) {
            val activityDisplayId = activity.windowManager.defaultDisplay.displayId
            val idleDisplay = displayManager.displays
                .sortedBy { it.displayId }
                .firstOrNull { it.displayId != activityDisplayId }
            if (idleDisplay == null) {
                LimeLog.info("No secondary display found for settings background")
                dismissIdleBackgroundPresentation()
                return
            }

            LimeLog.info(
                "Showing settings background on ${idleDisplay.name} " +
                    "(ID: ${idleDisplay.displayId})"
            )
            startIdleBackgroundPresentation(idleDisplay)
            return
        }

        dismissIdleBackgroundPresentation()
        val controlDisplay = targetDisplayResolver.controlDisplayFor(streamDisplay.displayId)
        if (controlDisplay == null) {
            LimeLog.info("No secondary display found, using default display")
            dismissExternalPresentation()
            dismissDualScreenPresentation()
            return
        }

        if (streamDisplay.displayId != Display.DEFAULT_DISPLAY) {
            LimeLog.info(
                "Using selected stream display: ${streamDisplay.name} " +
                    "(ID: ${streamDisplay.displayId}); controls on display ${controlDisplay.displayId}"
            )
            startExternalDisplayPresentation(streamDisplay)
            return
        }

        LimeLog.info(
            "Using selected stream display: ${streamDisplay.name} " +
                "(ID: ${streamDisplay.displayId}); controls on display ${controlDisplay.displayId}"
        )
        if (!dualScreenControlsEnabled) {
            LimeLog.info("Dual-screen controls hidden for the current streaming session")
            dismissDualScreenPresentation()
            return
        }
        startDualScreenPresentation(controlDisplay, streamDisplay)
    }

    private inner class ExternalDisplayPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {
        private val presentationDisplayId = display.displayId

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

            val selection = displayModeSelection
            if (selection != null && selection.displayId == presentationDisplayId) {
                window?.let { DisplayModeWindowApplier.apply(it, selection) }
            }

            setContentView(R.layout.activity_game)

            val externalStreamView = findViewById<StreamView>(R.id.surfaceView)
            if (externalStreamView != null) {
                callback?.onStreamViewReady(externalStreamView)
            }
        }

        override fun onDisplayRemoved() {
            super.onDisplayRemoved()
            // DisplayManager.DisplayListener restores the main StreamView and hides the
            // lower-screen dashboard. Finishing here races that fallback path.
        }

        fun isForDisplay(displayId: Int): Boolean = presentationDisplayId == displayId
    }

    private inner class DualScreenControlPresentation(
        outerContext: Context,
        private val controlDisplay: Display,
        private val streamDisplay: Display
    ) : Presentation(outerContext, controlDisplay) {
        private val presentationDisplayId = controlDisplay.displayId

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

            setContentView(R.layout.dual_screen_control_panel)
            findViewById<View>(R.id.dualScreenControlPanel)?.let { panelRoot ->
                callback?.onDualScreenControlPanelReady(
                    panelRoot,
                    streamDisplay,
                    controlDisplay
                )
            }
        }

        fun isForDisplay(displayId: Int): Boolean = presentationDisplayId == displayId
    }

    private inner class IdleBackgroundPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {
        private val presentationDisplayId = display.displayId

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.setBackgroundDrawableResource(R.color.advance_setting_background)
            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

            setContentView(R.layout.dual_screen_idle_background)
            refreshBackground()
        }

        fun refreshBackground() {
            val imageView = findViewById<ImageView>(R.id.dualScreenIdleBackgroundImage) ?: return
            Glide.with(activity).clear(imageView)
            imageView.setImageDrawable(null)

            val target = BackgroundSource.current(activity).resolveTarget(
                activity,
                resources.configuration.orientation
            ) ?: return
            val model: Any = if (target.startsWith("http")) target else File(target)
            Glide.with(activity)
                .load(model)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(imageView)
        }

        fun clearBackground() {
            findViewById<ImageView>(R.id.dualScreenIdleBackgroundImage)?.let { imageView ->
                Glide.with(activity).clear(imageView)
                imageView.setImageDrawable(null)
            }
        }

        fun isForDisplay(displayId: Int): Boolean = presentationDisplayId == displayId
    }

    private fun startExternalDisplayPresentation(display: Display) {
        if (!isUsingExternalDisplay() || externalPresentation?.isForDisplay(display.displayId) == true) {
            return
        }

        if (dualScreenPresentation != null) {
            dismissDualScreenPresentation()
            callback?.onDualScreenDisconnected()
        }

        callback?.onExternalDisplayConnected(display)
        externalPresentation = ExternalDisplayPresentation(activity, display)
        externalPresentation?.show()

        val surfaceView = activity.findViewById<View>(R.id.surfaceView)
        surfaceView?.visibility = View.GONE

        Toast.makeText(activity, activity.getString(R.string.toast_switched_to_external_display), Toast.LENGTH_LONG).show()
    }

    private fun startIdleBackgroundPresentation(display: Display) {
        if (idleBackgroundPresentation?.isForDisplay(display.displayId) == true) {
            idleBackgroundPresentation?.refreshBackground()
            return
        }

        dismissExternalPresentation()
        dismissDualScreenPresentation()
        dismissIdleBackgroundPresentation()
        idleBackgroundPresentation = IdleBackgroundPresentation(activity, display)
        idleBackgroundPresentation?.show()
    }

    private fun startDualScreenPresentation(controlDisplay: Display, streamDisplay: Display) {
        if (externalPresentation != null ||
            dualScreenPresentation?.isForDisplay(controlDisplay.displayId) == true
        ) {
            return
        }

        dualScreenPresentation = DualScreenControlPresentation(
            activity,
            controlDisplay,
            streamDisplay
        )
        dualScreenPresentation?.show()
        Toast.makeText(
            activity,
            activity.getString(R.string.toast_dual_screen_controls_ready),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun registerIdleBackgroundPreferenceListener() {
        if (callback != null || backgroundPrefsListener != null) return

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BackgroundSource.KEY_SOURCE ||
                key == BackgroundSource.KEY_API_URL ||
                key == BackgroundSource.KEY_LOCAL_PATH
            ) {
                activity.runOnUiThread {
                    idleBackgroundPresentation?.refreshBackground()
                }
            }
        }
        backgroundPrefsListener = listener
        PreferenceManager.getDefaultSharedPreferences(activity)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    private fun unregisterIdleBackgroundPreferenceListener() {
        backgroundPrefsListener?.let { listener ->
            PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(listener)
        }
        backgroundPrefsListener = null
    }

    companion object {
        fun hasExternalDisplay(context: Context): Boolean {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (displayManager != null) {
                for (display in displayManager.displays) {
                    if (display.displayId != Display.DEFAULT_DISPLAY) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
