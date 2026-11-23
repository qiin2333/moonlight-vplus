package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PyrowaveFrameAccountingTest {
    @Test fun separatesLocalDiscardsFromMissingFrames() {
        assertEquals(0, PyrowaveFrameAccounting.missingFrames(10, 14, 3))
        assertEquals(2, PyrowaveFrameAccounting.missingFrames(10, 16, 3))
        assertEquals(0, PyrowaveFrameAccounting.missingFrames(10, 11, 3))
    }

    @Test fun handlesFirstFrameAndNonIncreasingNumbers() {
        assertEquals(0, PyrowaveFrameAccounting.missingFrames(0, 8, 2))
        assertEquals(0, PyrowaveFrameAccounting.missingFrames(10, 9, 0))
        assertEquals(2, PyrowaveFrameAccounting.missingFrames(10, 13, -1))
    }
}
