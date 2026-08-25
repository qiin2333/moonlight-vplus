package com.limelight.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchPolicyTest {
    @Test
    fun matchingChildCannotRevealHiddenParentCategory() {
        assertFalse(
            SettingsSearchPolicy.shouldShowChild(
                categoryEligible = false,
                childEligible = true,
                categoryMatches = false,
                childMatches = true,
            ),
        )
    }

    @Test
    fun eligibleMatchingChildRemainsVisible() {
        assertTrue(
            SettingsSearchPolicy.shouldShowChild(
                categoryEligible = true,
                childEligible = true,
                categoryMatches = false,
                childMatches = true,
            ),
        )
    }
}
