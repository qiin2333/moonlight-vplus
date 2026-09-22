package com.limelight.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensaCardPreferencesTest {
    // Exercise the real preference reader without changing the user's settings.
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("sensa_card_test_$name", mode)
    }
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun waveformVisibilityIgnoresSensaVisibilityAndPersistsItsOwnChoice() {
        prefs.edit().putBoolean("checkbox_show_haptic_vibration_card", false).commit()
        val config = PreferenceConfiguration.readPreferences(context)
        assertTrue(config.showWaveformHapticsCard)
        assertFalse(config.showHapticVibrationCard)
        config.writePreferences(context)
        assertTrue(PreferenceConfiguration.readPreferences(context).showWaveformHapticsCard)
        config.showWaveformHapticsCard = false
        config.showHapticVibrationCard = true
        config.writePreferences(context)
        val restored = PreferenceConfiguration.readPreferences(context)
        assertFalse(restored.showWaveformHapticsCard)
        assertTrue(restored.showHapticVibrationCard)
    }

    @Test fun sensaWritesAreBoundedAndInvalidModesPreserveSelection() {
        SensaStrengthPreferences.setStrength(context, -5)
        assertEquals(0.0, SensaStrengthPreferences.read(context), 0.0)
        SensaStrengthPreferences.setStrength(context, 120)
        assertEquals(1.0, SensaStrengthPreferences.read(context), 0.0)
        SensaStrengthPreferences.setFrequency(context, 0)
        assertEquals(30.0, SensaStrengthPreferences.frequency(context), 0.0)
        SensaStrengthPreferences.setFrequency(context, 450)
        assertEquals(400.0, SensaStrengthPreferences.frequency(context), 0.0)
        SensaStrengthPreferences.setMode(context, SensaStrengthPreferences.RUMBLE_ONLY)
        SensaStrengthPreferences.setMode(context, "invalid")
        assertEquals(SensaStrengthPreferences.RUMBLE_ONLY, SensaStrengthPreferences.mode(context))
    }
}
