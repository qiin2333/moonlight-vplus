package com.limelight.preferences

import android.content.Context
import android.preference.Preference
import android.util.AttributeSet
import com.limelight.utils.HelpLauncher

class WebLauncherPreference : Preference {
    private lateinit var url: String

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) { initialize(attrs) }

    constructor(context: Context, attrs: AttributeSet) :
        super(context, attrs) { initialize(attrs) }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) { initialize(attrs) }

    private fun initialize(attrs: AttributeSet) {
        url = attrs.getAttributeValue(null, "url")
            ?: throw IllegalStateException("WebLauncherPreference must have 'url' attribute!")
    }

    override fun onClick() {
        HelpLauncher.launchUrl(context, url)
    }
}
