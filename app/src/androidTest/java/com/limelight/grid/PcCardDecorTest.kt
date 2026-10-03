package com.limelight.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24)
class PcCardDecorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
    private var savedMode: String? = null

    // XML-only resource fields can be removed from R by debug R8; resolve their stable resource names.
    private fun resource(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, context.packageName).also { check(it != 0) { name } }

    @Before fun saveMode() { savedMode = preferences.getString("theme_mode", null) }
    @After fun restoreMode() { preferences.edit().putString("theme_mode", savedMode).commit() }

    private fun configured(night: Boolean, density: Int = context.resources.configuration.densityDpi): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.densityDpi = density
        return context.createConfigurationContext(configuration)
    }

    @Test fun explicitThemeUsesDesiredPaletteBeforePlatformConfigurationArrives() {
        // Exercise both directions repeatedly, including the first cache population.
        for (night in listOf(true, false, true, false)) {
            preferences.edit().putString("theme_mode", if (night) "dark" else "light").commit()
            val stale = configured(!night)
            val expected = configured(night)
            for (bucket in listOf(-1, 0, 6, 11)) {
                val staleCard = PcCardDecor.selector(stale, bucket).current as GradientDrawable
                val currentCard = PcCardDecor.selector(expected, bucket).current as GradientDrawable
                assertArrayEquals(currentCard.colors, staleCard.colors)
                assertEquals(ContextCompat.getColor(expected, resource("pc_item_text_primary", "color")), PcCardDecor.textColor(stale))
                assertEquals(ContextCompat.getColor(expected, resource("pc_item_text_disabled", "color")), PcCardDecor.textColor(stale, true))
            }
            val brandCard = PcCardDecor.selector(stale, -1).current as GradientDrawable
            assertEquals(ContextCompat.getColor(expected, resource("pc_item_surface_default_center", "color")), brandCard.colors!![1])
            val glow = PcCardDecor.glow(stale, -1) as GradientDrawable
            assertEquals(ContextCompat.getColor(expected, resource("pc_item_icon_glow_start", "color")), glow.colors!![0])
        }
    }

    @Test fun cachedDrawablesDoNotShareStateBoundsOrMutableColors() {
        preferences.edit().putString("theme_mode", "light").commit()
        for (bucket in listOf(-1, 0, 11)) {
            val first = PcCardDecor.selector(context, bucket) as StateListDrawable
            val second = PcCardDecor.selector(context, bucket) as StateListDrawable
            assertNotSame(first, second)
            val original = (second.current as GradientDrawable).colors!!.clone()
            first.setBounds(0, 0, 500, 100)
            (first.current as GradientDrawable).colors = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt())
            first.state = intArrayOf(android.R.attr.state_pressed)
            assertTrue(second.bounds.isEmpty)
            assertTrue(second.state.isEmpty())
            assertArrayEquals(original, (second.current as GradientDrawable).colors)
            assertArrayEquals(original, (PcCardDecor.selector(context, bucket).current as GradientDrawable).colors)
        }
    }

    @Test fun cachedGradientsRebuildForDensityAndFollowSystemNightChanges() {
        preferences.edit().putString("theme_mode", "system").commit()
        val lowDensity = PcCardDecor.glow(configured(false, 160), 0) as GradientDrawable
        val highDensity = PcCardDecor.glow(configured(false, 320), 0) as GradientDrawable
        val cachedHighDensity = PcCardDecor.glow(configured(false, 320), 0) as GradientDrawable
        assertEquals(95f, lowDensity.gradientRadius, .01f)
        assertEquals(190f, highDensity.gradientRadius, .01f)
        assertEquals(highDensity.gradientRadius, cachedHighDensity.gradientRadius, .01f)
        val light = PcCardDecor.selector(configured(false), 0).current as GradientDrawable
        val dark = PcCardDecor.selector(configured(true), 0).current as GradientDrawable
        assertFalse(light.colors!!.contentEquals(dark.colors!!))
    }

    @Test fun wallpaperDecorRetainsOriginalGradientGeometryAndTransparency() {
        preferences.edit().putString("theme_mode", "light").commit()
        val originalGlow = PcCardDecor.glow(context, -1) as GradientDrawable
        val coloredGlow = PcCardDecor.glow(context, 0) as GradientDrawable
        assertEquals(originalGlow.gradientRadius, coloredGlow.gradientRadius, .01f)
        val states = listOf(intArrayOf(), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused))
        for (state in states) {
            val original = PcCardDecor.selector(context, -1).apply { this.state = state }.current as GradientDrawable
            val colored = PcCardDecor.selector(context, 0).apply { this.state = state }.current as GradientDrawable
            assertEquals(original.orientation, colored.orientation)
            assertEquals(original.cornerRadius, colored.cornerRadius, .01f)
            assertArrayEquals(original.colors!!.map { it ushr 24 }.toIntArray(), colored.colors!!.map { it ushr 24 }.toIntArray())
            val originalStack = PcCardDecor.multiSelector(context, -1).apply { this.state = state }.current as LayerDrawable
            val coloredStack = PcCardDecor.multiSelector(context, 0).apply { this.state = state }.current as LayerDrawable
            for (i in 0..2) {
                assertEquals((originalStack.getDrawable(i) as GradientDrawable).orientation, (coloredStack.getDrawable(i) as GradientDrawable).orientation)
                assertEquals(originalStack.getLayerInsetBottom(i), coloredStack.getLayerInsetBottom(i))
            }
        }
    }
}
