package com.limelight.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionPresetPolicyTest {
    @Test
    fun only360pAnd480pAreLowBandwidthPresets() {
        assertTrue(PreferenceConfiguration.isLowResolutionPreset(PreferenceConfiguration.RES_360P))
        assertTrue(PreferenceConfiguration.isLowResolutionPreset(PreferenceConfiguration.RES_480P))
        assertTrue(PreferenceConfiguration.isLowResolutionPreset("360x640"))
        assertTrue(PreferenceConfiguration.isLowResolutionPreset("480x854"))
        assertFalse(PreferenceConfiguration.isLowResolutionPreset(PreferenceConfiguration.RES_720P))
        assertFalse(PreferenceConfiguration.isLowResolutionPreset(PreferenceConfiguration.RES_NATIVE))
        assertFalse(PreferenceConfiguration.isLowResolutionPreset(null))
    }
}
