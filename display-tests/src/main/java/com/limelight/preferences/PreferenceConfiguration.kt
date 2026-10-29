package com.limelight.preferences
import android.content.Context
import android.graphics.Point
import android.view.Display

/** Controlled preference boundary; Game methods are generated from production. */
class PreferenceConfiguration {
    var useExternalDisplay = true
    var enablePerfOverlay = false
    var enableNativeMousePointer = false
    var width = 1920
    var height = 1080
    var reverseResolution = false
    var isNativeResolution = false
    var isCustomResolution = false
    var fps = 60
    var bitrate = 20000
    var screenCombinationMode = -1

    companion object {
        var latest = PreferenceConfiguration()

        @Suppress("DEPRECATION", "UNUSED_PARAMETER")
        fun readPreferences(context: Context, display: Display): PreferenceConfiguration {
            return PreferenceConfiguration().apply {
                isNativeResolution = latest.isNativeResolution
                width = latest.width
                height = latest.height
                if (isNativeResolution) {
                    val size = Point()
                    display.getRealSize(size)
                    width = size.x
                    height = size.y
                }
            }
        }
    }
}
