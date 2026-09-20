package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class SensaStartupBudgetTest {
    @Test fun coversMaximumHandshakeAndTightensForObservedMetadata() {
        assertTrue(SensaStartupBudget.maximumMs > 86 * 150L)
        // The tested XL reports 879 bytes: 18 chunks, mode read/write and silence.
        assertTrue(SensaStartupBudget.remainingAfterSize(879) >= 21 * 150L)
        assertTrue(SensaStartupBudget.remainingAfterSize(879) < SensaStartupBudget.maximumMs)
        assertEquals(150L, SensaStartupBudget.remainingAfterSize(51) -
            SensaStartupBudget.remainingAfterSize(50))
    }
}
