package com.limelight.preferences

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.preference.ListPreference
import android.provider.Settings
import android.util.AttributeSet

class LanguagePreference : ListPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // Launch the Android native app locale settings page
                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent, null)
                return
            } catch (_: ActivityNotFoundException) {
                // App locale settings should be present on all Android 13 devices,
                // but if not, we'll launch the old language chooser.
            }
        }

        // If we don't have native app locale settings, launch the normal dialog
        super.onClick()
    }
}
