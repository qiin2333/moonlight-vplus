package com.limelight.binding.input.virtual_controller

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.ui.FloatingButtonNormalizedPosition
import com.limelight.utils.ConfigurationSyncManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OscSettingsButtonBackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(
        OscSettingsButtonStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val profiles = context.getSharedPreferences(
        VirtualControllerConfigurationLoader.OSC_PREFERENCE,
        Context.MODE_PRIVATE
    )

    @After
    fun clear() {
        preferences.edit().clear().commit()
        profiles.edit().clear().commit()
    }

    @Test
    fun positionSizeAndDragSettingRoundTripThroughConfigurationBackup() {
        preferences.edit()
            .putBoolean("drag_enabled", true)
            .putFloat("x", 0.73f)
            .putFloat("y", 0.21f)
            .putFloat("size_scale", 1.65f)
            .commit()
        profiles.edit().putString("xbox.full.6", "{\"LEFT\":12,\"TOP\":18}").commit()

        val manager = ConfigurationSyncManager(context)
        val packageText = manager.exportSyncPackage()
        preferences.edit().clear().commit()

        assertEquals(0, manager.importSyncPackage(packageText).crownProfilesFailed)
        val restored = OscSettingsButtonStore(context)
        assertTrue(restored.dragEnabled)
        assertEquals(FloatingButtonNormalizedPosition(0.73f, 0.21f), restored.position())
        assertEquals(1.65f, restored.sizeScale, 0.0001f)
        assertEquals("{\"LEFT\":12,\"TOP\":18}", profiles.getString("xbox.full.6", null))
    }

    @Test
    fun oldBackupWithoutGearSectionLeavesCurrentGearSettingsUntouched() {
        preferences.edit().putBoolean("drag_enabled", true).putFloat("size_scale", 1.4f).commit()
        val manager = ConfigurationSyncManager(context)
        val root = JSONObject(manager.exportSyncPackage())
        root.getJSONObject("sections").remove("oscSettingsButton")
        val packageText = root.toString()
        preferences.edit().putBoolean("drag_enabled", false).commit()

        // The actual historical shape omits the section, so it must not clear local-only state.
        assertEquals(0, manager.importSyncPackage(packageText).crownProfilesFailed)
        assertFalse(OscSettingsButtonStore(context).dragEnabled)
        assertEquals(1.4f, OscSettingsButtonStore(context).sizeScale, 0.0001f)
    }
}
