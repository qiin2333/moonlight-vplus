package com.limelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PreferenceConfiguration construction loads streaming JNI and requires Android. */
@RunWith(AndroidJUnit4::class)
class DisplayReconnectSettingsTest {
    @Test
    fun committedChoicesWinOverOriginalLastSettingsAndScreenModeOverrides() {
        val config = PreferenceConfiguration().apply {
            width = 2560
            height = 1440
            fps = 120
            bitrate = 40000
            screenCombinationMode = 4
            isNativeResolution = true
        }
        val committed = DisplayReconnectSettings.capture(config)

        // onCreate still applies the launch Intent's settings for the app.
        config.apply {
            width = 1920
            height = 1080
            fps = 60
            bitrate = 20000
            screenCombinationMode = 2
            isNativeResolution = false
            isCustomResolution = true
            enableAdaptiveBitrate = true
            abrMode = "quality"
        }
        committed.applyTo(config)

        assertEquals(2560, config.width)
        assertEquals(1440, config.height)
        assertEquals(120, config.fps)
        assertEquals(40000, config.bitrate)
        assertEquals(4, config.screenCombinationMode)
        assertTrue(config.isNativeResolution)
        assertFalse(config.isCustomResolution)
        assertTrue(config.enableAdaptiveBitrate)
        assertEquals("quality", config.abrMode)
    }

    @Test
    fun fixedResolutionSemanticsSurviveReconnectAlongsideAnUnchangedPerAppSetting() {
        val committed = DisplayReconnectSettings.capture(PreferenceConfiguration().apply {
            width = 1920
            height = 1080
            fps = 60
            screenCombinationMode = -1
        })
        val reloaded = PreferenceConfiguration().apply {
            isNativeResolution = true
            isCustomResolution = true
            screenCombinationMode = 3
            enableAdaptiveBitrate = true
            abrMode = "lowLatency"
        }

        committed.applyTo(reloaded)

        assertFalse(reloaded.isNativeResolution)
        assertFalse(reloaded.isCustomResolution)
        assertEquals(-1, reloaded.screenCombinationMode)
        assertTrue(reloaded.enableAdaptiveBitrate)
        assertEquals("lowLatency", reloaded.abrMode)
    }
}
