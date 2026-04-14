@file:Suppress("DEPRECATION")
package com.limelight

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import com.limelight.utils.UiHelper

/**
 * 外接显示器管理器
 * 负责管理外接显示器的检测、连接、断开和内容显示
 */
class ExternalDisplayManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration,
    private val conn: NvConnection?,
    private val decoderRenderer: MediaCodecDecoderRenderer?,
    private val pcName: String?,
    private val appName: String?
) {
    private var displayManager: DisplayManager? = null
    private var externalDisplay: Display? = null
    private var useExternalDisplay = false
    private var displayListener: DisplayManager.DisplayListener? = null
    private var externalPresentation: ExternalDisplayPresentation? = null

    interface ExternalDisplayCallback {
        fun onExternalDisplayConnected(display: Display)
        fun onExternalDisplayDisconnected()
        fun onStreamViewReady(streamView: StreamView)
    }

    var callback: ExternalDisplayCallback? = null

    fun initialize() {
        displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        setupDisplayListener()
        checkForExternalDisplay()

        if (useExternalDisplay) {
            val window = activity.window
            if (window != null) {
                val layoutParams = window.attributes
                layoutParams.screenBrightness = 0.3f
                window.attributes = layoutParams
            }
            startExternalDisplayPresentation()
        }
    }

    fun cleanup() {
        if (externalPresentation != null) {
            externalPresentation?.dismiss()
            externalPresentation = null
        }

        if (displayListener != null && displayManager != null) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListener = null
        }
    }

    fun getTargetDisplay(): Display {
        if (useExternalDisplay && externalDisplay != null) {
            return externalDisplay!!
        }
        @Suppress("DEPRECATION")
        return activity.windowManager.defaultDisplay
    }

    fun isUsingExternalDisplay(): Boolean = useExternalDisplay && externalDisplay != null

    private fun setupDisplayListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    LimeLog.info("Display added: $displayId")
                    if (prefConfig.useExternalDisplay && displayId != Display.DEFAULT_DISPLAY) {
                        checkForExternalDisplay()
                        if (useExternalDisplay) {
                            startExternalDisplayPresentation()
                        }
                    }
                }

                override fun onDisplayRemoved(displayId: Int) {
                    LimeLog.info("Display removed: $displayId")
                    if (externalDisplay != null && displayId == externalDisplay?.displayId) {
                        if (externalPresentation != null) {
                            externalPresentation?.dismiss()
                            externalPresentation = null
                        }
                        externalDisplay = null
                        useExternalDisplay = false

                        val surfaceView = activity.findViewById<View>(R.id.surfaceView)
                        surfaceView?.visibility = View.VISIBLE
                        Toast.makeText(activity, activity.getString(R.string.toast_external_display_disconnected), Toast.LENGTH_SHORT).show()

                        callback?.onExternalDisplayDisconnected()
                    }
                }

                override fun onDisplayChanged(displayId: Int) {
                    LimeLog.info("Display changed: $displayId")
                }
            }

            displayManager?.registerDisplayListener(displayListener, null)
        }
    }

    private fun checkForExternalDisplay() {
        if (!prefConfig.useExternalDisplay) {
            LimeLog.info("External display disabled by user preference")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val displays = displayManager?.displays

            for (display in (displays ?: emptyArray<Display>())) {
                if (display.displayId != Display.DEFAULT_DISPLAY) {
                    externalDisplay = display
                    useExternalDisplay = true
                    LimeLog.info("Found external display: ${display.name} (ID: ${display.displayId})")

                    callback?.onExternalDisplayConnected(display)
                    break
                }
            }

            if (!useExternalDisplay) {
                LimeLog.info("No external display found, using default display")
            }
        }
    }

    private inner class ExternalDisplayPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

            setContentView(R.layout.activity_game)

            val externalStreamView = findViewById<StreamView>(R.id.surfaceView)
            if (externalStreamView != null) {
                callback?.onStreamViewReady(externalStreamView)
            }
        }

        override fun onDisplayRemoved() {
            super.onDisplayRemoved()
            activity.finish()
        }
    }

    @SuppressLint("ResourceAsColor", "SetTextI18n")
    private fun startExternalDisplayPresentation() {
        if (!(useExternalDisplay && externalDisplay != null && externalPresentation == null)) {
            return
        }

        externalPresentation = ExternalDisplayPresentation(activity, externalDisplay!!)
        externalPresentation?.show()

        val surfaceView = activity.findViewById<View>(R.id.surfaceView)
        surfaceView?.visibility = View.GONE

        if (prefConfig.enablePerfOverlay) {
            val batteryTextView = TextView(activity)
            batteryTextView.gravity = Gravity.CENTER
            batteryTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            batteryTextView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.scene_color_1))

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.CENTER
            batteryTextView.layoutParams = params

            val rootView = activity.findViewById<FrameLayout>(android.R.id.content)
            rootView?.addView(batteryTextView)

            val handler = Handler(Looper.getMainLooper())
            val gravityOptions = intArrayOf(
                Gravity.CENTER,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                Gravity.CENTER_VERTICAL or Gravity.LEFT,
                Gravity.CENTER_VERTICAL or Gravity.RIGHT,
                Gravity.TOP or Gravity.LEFT,
                Gravity.TOP or Gravity.RIGHT,
                Gravity.BOTTOM or Gravity.LEFT,
                Gravity.BOTTOM or Gravity.RIGHT
            )

            val updateBatteryTask = object : Runnable {
                override fun run() {
                    batteryTextView.text = String.format("🔋 %d%%", UiHelper.getBatteryLevel(activity))

                    val randomGravity = gravityOptions[(Math.random() * gravityOptions.size).toInt()]
                    val randomMarginLeft = (Math.random() * 401).toInt() - 200
                    val randomMarginTop = (Math.random() * 401).toInt() - 200
                    val randomMarginRight = (Math.random() * 401).toInt() - 200
                    val randomMarginBottom = (Math.random() * 401).toInt() - 200

                    val p = batteryTextView.layoutParams as FrameLayout.LayoutParams
                    p.gravity = randomGravity
                    p.setMargins(randomMarginLeft, randomMarginTop, randomMarginRight, randomMarginBottom)
                    batteryTextView.layoutParams = p

                    handler.postDelayed(this, 60000)
                }
            }
            updateBatteryTask.run()
        }

        Toast.makeText(activity, activity.getString(R.string.toast_switched_to_external_display), Toast.LENGTH_LONG).show()
    }

    companion object {
        fun hasExternalDisplay(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                if (displayManager != null) {
                    for (display in displayManager.displays) {
                        if (display.displayId != Display.DEFAULT_DISPLAY) {
                            return true
                        }
                    }
                }
            }
            return false
        }
    }
}
