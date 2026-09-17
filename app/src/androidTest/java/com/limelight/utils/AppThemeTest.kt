package com.limelight.utils

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppThemeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
    private var mode: String? = null
    private var accent: String? = null
    private var bucket: Int? = null

    // XML-only resource fields can be removed from R by debug R8; resolve their stable resource names.
    private fun resource(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, context.packageName).also { check(it != 0) { name } }

    @Before fun save() {
        mode = prefs.getString("theme_mode", null)
        accent = prefs.getString("accent_mode", null)
        bucket = if (prefs.contains("you_bg_accent_bucket")) prefs.getInt("you_bg_accent_bucket", -1) else null
    }

    @After fun restore() {
        val editor = prefs.edit().putString("theme_mode", mode).putString("accent_mode", accent)
        bucket?.let { editor.putInt("you_bg_accent_bucket", it) } ?: editor.remove("you_bg_accent_bucket")
        editor.commit()
        AppTheme.applyStoredAppTheme(context)
    }

    private fun accentColor(context: Context): Int {
        val value = TypedValue()
        assertTrue(context.theme.resolveAttribute(resource("appAccent", "attr"), value, true))
        return value.data
    }

    @Test fun appearanceAndAccentCanBeCombinedIndependently() {
        for (mode in listOf("system", "light", "dark")) {
            for (accent in listOf("pink", "bg")) {
                AppTheme.save(context, mode, accent)
                assertEquals(mode, AppTheme.getAppThemeMode(context))
                assertEquals(accent, AppTheme.getAccentMode(context))
            }
        }
    }

    @Test fun removingAccentResetsOverlayAndApplyingPageStylePreservesIt() {
        val styled = ContextThemeWrapper(context, resource("AppTheme", "style"))
        prefs.edit().putString("accent_mode", "bg").putInt("you_bg_accent_bucket", 3).commit()
        AppTheme.applyTo(styled)
        val expected = ContextCompat.getColor(styled, resource("you_bg_accent_3", "color"))
        assertEquals(expected, accentColor(styled))
        AppTheme.applyStyle(styled, resource("PreferenceThemeWithShadow", "style"))
        assertEquals(expected, accentColor(styled))
        prefs.edit().putInt("you_bg_accent_bucket", -1).commit()
        AppTheme.applyTo(styled)
        assertEquals(ContextCompat.getColor(styled, resource("ui_shell_accent", "color")), accentColor(styled))
        prefs.edit().putInt("you_bg_accent_bucket", 3).putString("accent_mode", "pink").commit()
        AppTheme.applyTo(styled)
        assertEquals(ContextCompat.getColor(styled, resource("ui_shell_accent", "color")), accentColor(styled))
    }

    @Test fun explicitNightModeRetainsOtherConfigurationDimensions() {
        prefs.edit().putString("theme_mode", "dark").putString("accent_mode", "bg").putInt("you_bg_accent_bucket", 3).commit()
        val activityOverride = AppTheme.nightOverride(context)!!
        assertEquals(Configuration.ORIENTATION_UNDEFINED, activityOverride.orientation)
        assertEquals(0, activityOverride.densityDpi)
        val configuration = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            densityDpi = 320
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val themed = AppTheme.paletteContext(context.createConfigurationContext(configuration))
        val actual = themed.resources.configuration
        assertEquals(Configuration.UI_MODE_NIGHT_YES, actual.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, actual.orientation)
        assertEquals(320, actual.densityDpi)
        assertEquals(ContextCompat.getColor(themed, resource("you_bg_accent_3", "color")), accentColor(themed))
    }
}
