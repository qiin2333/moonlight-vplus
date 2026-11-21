package com.limelight

import android.view.MotionEvent
import com.limelight.nvstream.input.MouseButtonPacket.BUTTON_X1
import com.limelight.nvstream.input.MouseButtonPacket.BUTTON_X2
import org.junit.Assert.assertEquals
import org.junit.Test

class MouseNavigationButtonsTest {
    private val events = mutableListOf<Pair<Byte, Boolean>>()
    private val buttons = MouseNavigationButtons { button, down -> events.add(button to down) }
    private val back = MotionEvent.BUTTON_BACK
    private val forward = MotionEvent.BUTTON_FORWARD

    @Test
    fun heldSideButtonsReleaseIndependentlyAfterSwitchingToSystemPointer() {
        buttons.update(back or forward, allowNewPresses = true)
        buttons.update(back or forward)
        buttons.update(forward)
        buttons.update(forward)
        buttons.update(0)
        assertEquals(listOf(BUTTON_X1 to true, BUTTON_X2 to true, BUTTON_X1 to false, BUTTON_X2 to false), events)
    }

    @Test
    fun desktopPressIsNotForwardedWhenCaptureResumesWhileHeld() {
        buttons.update(back)
        buttons.update(back or forward, allowNewPresses = true)
        buttons.update(0, allowNewPresses = true)
        assertEquals(listOf(BUTTON_X2 to true, BUTTON_X2 to false), events)
    }

    @Test
    fun releasingGrabPairsEachDownOnceAndAllowsANewPressAfterRelease() {
        buttons.update(back, allowNewPresses = true)
        buttons.releaseAll()
        buttons.releaseAll()
        buttons.update(back, allowNewPresses = true)
        buttons.update(0)
        buttons.update(back, allowNewPresses = true)
        buttons.update(0)
        assertEquals(listOf(BUTTON_X1 to true, BUTTON_X1 to false, BUTTON_X1 to true, BUTTON_X1 to false), events)
    }
}
