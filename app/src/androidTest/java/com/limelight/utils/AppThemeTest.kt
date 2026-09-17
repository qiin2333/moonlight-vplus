package com.limelight.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.preference.ListPreference
import androidx.preference.PreferenceGroup
import com.limelight.preferences.StreamSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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

    @Test fun missingAccentPreferencePreservesBrandAndAppearance() {
        prefs.edit().remove("accent_mode").putString("theme_mode", "dark").commit()
        assertEquals("pink", AppTheme.getAccentMode(context))
        assertEquals("dark", AppTheme.getAppThemeMode(context))
    }

    @Test fun transparentPixelsCannotOutvoteVisibleContent() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPremultiplied(false)
            bitmap.eraseColor(Color.argb(0, 230, 0, 0))
            assertEquals(-1, BgAccent.extractHueBucket(bitmap))
            bitmap.eraseColor(Color.argb(1, 230, 0, 0))
            bitmap.setPixel(0, 0, Color.rgb(0, 230, 0))
            assertEquals(4, BgAccent.extractHueBucket(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun accentObserverRefreshesOnlyChangesAndCatchesUpAfterStop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            prefs.edit().putString("accent_mode", "bg").putInt("you_bg_accent_bucket", 1).commit()
            val styled = ContextThemeWrapper(context, resource("AppTheme", "style"))
            AppTheme.applyTo(styled)
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            var refreshes = 0
            AppTheme.observeAccent(styled, owner) {
                refreshes++
                assertEquals(AppTheme.activeBucket(styled).let {
                    ContextCompat.getColor(styled, resource("you_bg_accent_$it", "color"))
                }, accentColor(styled))
            }
            try {
                owner.registry.currentState = Lifecycle.State.STARTED
                prefs.edit().putInt("you_bg_accent_bucket", 2).commit()
                assertEquals(1, refreshes)
                prefs.edit().putInt("you_bg_accent_bucket", 2).commit()
                assertEquals(1, refreshes)
                owner.registry.currentState = Lifecycle.State.CREATED
                prefs.edit().putInt("you_bg_accent_bucket", 3).commit()
                assertEquals(1, refreshes)
                owner.registry.currentState = Lifecycle.State.STARTED
                assertEquals(2, refreshes)
            } finally {
                owner.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    @Test fun openSettingsUpdatesSummaryWithoutRecreatingThePage() {
        prefs.edit().putString("accent_mode", "bg").putInt("you_bg_accent_bucket", 1).commit()
        ActivityScenario.launch<StreamSettings>(Intent(context, StreamSettings::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(resource("preference_container", "id"))
                    as androidx.preference.PreferenceFragmentCompat
                fun firstList(group: PreferenceGroup): ListPreference? {
                    for (i in 0 until group.preferenceCount) {
                        val child = group.getPreference(i)
                        if (child is ListPreference && child.summary is Spanned) return child
                        if (child is PreferenceGroup) firstList(child)?.let { return it }
                    }
                    return null
                }
                val pref = firstList(fragment.preferenceScreen)!!
                fun markerColor(): Int {
                    val summary = pref.summary as Spanned
                    return summary.getSpans(0, 1, ForegroundColorSpan::class.java).single().foregroundColor
                }
                val before = markerColor()
                prefs.edit().putInt("you_bg_accent_bucket", 8).commit()
                assertFalse(activity.isFinishing)
                assertFalse(activity.isChangingConfigurations)
                assertEquals(accentColor(activity), markerColor())
                assertNotEquals(before, markerColor())
                assertSame(fragment, activity.supportFragmentManager.findFragmentById(resource("preference_container", "id")))
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
