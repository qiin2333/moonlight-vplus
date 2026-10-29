package com.limelight.utils

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsManagerResolutionTest {
    private val manager = AppSettingsManager(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun savedDimensionsReplaceInheritedResolutionMode() {
        for (custom in listOf(false, true)) {
            val saved = PreferenceConfiguration().apply {
                width = if (custom) 2222 else 1920
                height = if (custom) 1111 else 1080
            }
            val intent = Intent()
            AppSettingsManager.addLastSettingsToIntent(intent, saved)
            val session = PreferenceConfiguration().apply {
                isNativeResolution = true
                isCustomResolution = !custom
            }

            assertTrue(manager.applyLastSettingsFromIntent(intent, session))

            assertEquals(saved.width, session.width)
            assertEquals(saved.height, session.height)
            assertFalse(session.isNativeResolution)
            assertEquals(custom, session.isCustomResolution)
        }
    }

    @Test
    fun intentWithoutDimensionsPreservesSessionResolutionMode() {
        val intent = Intent()
        AppSettingsManager.addLastSettingsToIntent(intent, PreferenceConfiguration())
        intent.removeExtra("LastSettingsWidth")
        intent.removeExtra("LastSettingsHeight")
        val session = PreferenceConfiguration().apply {
            width = 2560
            height = 1440
            isNativeResolution = true
        }

        assertTrue(manager.applyLastSettingsFromIntent(intent, session))

        assertEquals(2560, session.width)
        assertEquals(1440, session.height)
        assertTrue(session.isNativeResolution)
        assertFalse(session.isCustomResolution)
    }
}
