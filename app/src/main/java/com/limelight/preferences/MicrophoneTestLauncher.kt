package com.limelight.preferences

import android.content.Context
import android.content.Intent

/** Build-variant hook for the developer-only microphone test screen. */
internal object MicrophoneTestLauncher {
    private const val TEST_ACTIVITY = "com.limelight.preferences.MicrophoneTestActivity"

    fun launch(context: Context): Boolean {
        context.startActivity(Intent().setClassName(context, TEST_ACTIVITY))
        return true
    }
}
