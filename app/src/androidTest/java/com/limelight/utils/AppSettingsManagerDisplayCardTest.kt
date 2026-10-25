package com.limelight.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the public persistence and launch APIs with isolated SharedPreferences files. */
@RunWith(AndroidJUnit4::class)
class AppSettingsManagerDisplayCardTest {
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            super.getSharedPreferences("app_settings_display_card_test_$name", mode)
    }
    private val globalPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val lastSettingsPreferences = context.getSharedPreferences("app_last_settings", Context.MODE_PRIVATE)
    private val manager = AppSettingsManager(context)
    private val app = NvApp("Display card test", APP_ID, false)

    @Before
    @After
    fun clearIsolatedPreferences() {
        assertTrue(globalPreferences.edit().clear().commit())
        assertTrue(lastSettingsPreferences.edit().clear().commit())
    }

    @Test
    fun visibleDisplayCardSurvivesSaveLoadAndOverridesHiddenGlobalSetting() {
        assertSavedDisplayCardRoundTrip(visible = true)
    }

    @Test
    fun hiddenDisplayCardSurvivesSaveLoadAndOverridesVisibleGlobalSetting() {
        assertSavedDisplayCardRoundTrip(visible = false)
    }

    @Test
    fun legacyJsonWithoutDisplayCardUsesCurrentGlobalVisibility() {
        manager.saveAppLastSettings(COMPUTER_UUID, app, settings(visible = true))
        val record = JSONObject(lastSettingsPreferences.getString(RECORD_KEY, null)!!)
        record.remove("showDisplaySettingsCard")
        record.remove("showWaveformHapticsCard")
        assertTrue(lastSettingsPreferences.edit().putString(RECORD_KEY, record.toString()).commit())

        for (visible in listOf(false, true)) {
            setGlobalDisplayCardVisibility(visible)
            setGlobalWaveformCardVisibility(!visible)
            val loaded = manager.getAppLastSettings(COMPUTER_UUID, app)
            assertNotNull(loaded)
            assertEquals(visible, loaded!!.showDisplaySettingsCard)
            assertEquals(!visible, loaded.showWaveformHapticsCard)
            assertSummaryVisibility(visible)

            val intent = Intent()
            AppSettingsManager.addLastSettingsToIntent(intent, loaded)
            val session = settings(visible = !visible)
            assertTrue(manager.applyLastSettingsFromIntent(intent, session))
            assertEquals(visible, session.showDisplaySettingsCard)
        }
    }

    @Test
    fun legacyIntentWithoutDisplayCardPreservesCurrentSessionVisibility() {
        for (visible in listOf(false, true)) {
            val intent = Intent()
            AppSettingsManager.addLastSettingsToIntent(intent, settings(visible = !visible))
            intent.removeExtra("LastSettingsShowDisplaySettingsCard")

            val session = settings(visible)
            assertTrue(manager.applyLastSettingsFromIntent(intent, session))
            assertEquals(visible, session.showDisplaySettingsCard)
        }
    }

    @Test
    fun waveformCardVisibilitySurvivesSaveLoadAndLastSettingsIntent() {
        for (visible in listOf(false, true)) {
            manager.saveAppLastSettings(
                COMPUTER_UUID,
                app,
                settings(visible = true, waveformVisible = visible)
            )
            setGlobalWaveformCardVisibility(!visible)

            val loaded = manager.getAppLastSettings(COMPUTER_UUID, app)
            assertEquals(visible, loaded?.showWaveformHapticsCard)
            assertEquals(
                visible,
                manager.getSettingsSummary(COMPUTER_UUID, app)
                    .contains(context.getString(R.string.waveform_card_title))
            )

            val intent = Intent()
            AppSettingsManager.addLastSettingsToIntent(intent, loaded)
            val session = settings(visible = false, waveformVisible = !visible)
            assertTrue(manager.applyLastSettingsFromIntent(intent, session))
            assertEquals(visible, session.showWaveformHapticsCard)
        }
    }

    private fun assertSavedDisplayCardRoundTrip(visible: Boolean) {
        manager.saveAppLastSettings(COMPUTER_UUID, app, settings(visible))
        setGlobalDisplayCardVisibility(!visible)

        val loaded = manager.getAppLastSettings(COMPUTER_UUID, app)
        assertNotNull(loaded)
        assertEquals(visible, loaded!!.showDisplaySettingsCard)
        assertSummaryVisibility(visible)

        val intent = Intent()
        AppSettingsManager.addLastSettingsToIntent(intent, loaded)
        val session = settings(visible = !visible)
        assertTrue(manager.applyLastSettingsFromIntent(intent, session))
        assertEquals(visible, session.showDisplaySettingsCard)
        assertEquals(!visible, globalPreferences.getBoolean(
            PreferenceConfiguration.SHOW_DISPLAY_SETTINGS_CARD_PREF_STRING,
            visible
        ))
    }

    private fun assertSummaryVisibility(visible: Boolean) {
        val displayCardLabel = context.getString(R.string.game_menu_display_settings)
        assertEquals(visible, manager.getSettingsSummary(COMPUTER_UUID, app).contains(displayCardLabel))
    }

    private fun setGlobalDisplayCardVisibility(visible: Boolean) {
        assertTrue(globalPreferences.edit().putBoolean(
            PreferenceConfiguration.SHOW_DISPLAY_SETTINGS_CARD_PREF_STRING,
            visible
        ).commit())
    }

    private fun setGlobalWaveformCardVisibility(visible: Boolean) {
        assertTrue(globalPreferences.edit().putBoolean(
            PreferenceConfiguration.SHOW_WAVEFORM_HAPTICS_CARD_PREF_STRING,
            visible
        ).commit())
    }

    private fun settings(visible: Boolean, waveformVisible: Boolean = true) = PreferenceConfiguration().apply {
        width = 1920
        height = 1080
        fps = 60
        bitrate = 20_000
        resolutionScale = 100
        showDisplaySettingsCard = visible
        showWaveformHapticsCard = waveformVisible
    }

    private companion object {
        const val APP_ID = 736
        const val COMPUTER_UUID = "display-card-test"
        const val RECORD_KEY = "${COMPUTER_UUID}_$APP_ID"
    }
}
