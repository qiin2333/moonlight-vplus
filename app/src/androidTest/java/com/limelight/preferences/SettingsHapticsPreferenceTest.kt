package com.limelight.preferences

import android.hardware.usb.UsbManager
import android.content.Context
import android.os.SystemClock
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SettingsHapticsPreferenceTest {
    @Test fun settingsButtonUsesServiceAndSelectedStrength() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiSettingsTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.single { it.vendorId == 0x1532 && it.productId == 0x0727 }
        assertTrue("Grant USB access first", manager.hasPermission(device))
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = SensaStrengthPreferences.enabled(context)
        val hadStrength = prefs.contains(SensaStrengthPreferences.KEY)
        val strength = prefs.getInt(SensaStrengthPreferences.KEY, 100)
        prefs.edit().putBoolean(SensaStrengthPreferences.ENABLED_KEY, true)
            .putInt(SensaStrengthPreferences.KEY, 0).commit()
        try {
            ActivityScenario.launch(StreamSettings::class.java).use { scenario ->
                lateinit var button: Preference
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()
                    val toggle = checkNotNull(fragment.findPreference<Preference>(SensaStrengthPreferences.ENABLED_KEY))
                    button = checkNotNull(fragment.findPreference("test_experimental_haptics"))
                    val slider = checkNotNull(fragment.findPreference<SeekBarPreference>(SensaStrengthPreferences.KEY))
                    assertTrue(toggle.order < button.order && button.order < slider.order)
                    assertEquals(0, slider.currentValue)
                    assertEquals(0.0, SensaStrengthPreferences.read(activity), 0.0)
                    assertTrue(button.isEnabled)
                    button.performClick()
                }
                val deadline = SystemClock.elapsedRealtime() + 10000
                var finished = false
                while (!finished && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { finished = button.isEnabled && button.summary == it.getString(R.string.haptics_test_finished) }
                    if (!finished) SystemClock.sleep(100)
                }
                scenario.onActivity { assertTrue("Settings test did not complete: ${button.summary}", finished) }
            }
        } finally {
            prefs.edit().putBoolean(SensaStrengthPreferences.ENABLED_KEY, enabled).apply {
                if (hadStrength) putInt(SensaStrengthPreferences.KEY, strength) else remove(SensaStrengthPreferences.KEY)
            }.commit()
        }
    }
}
