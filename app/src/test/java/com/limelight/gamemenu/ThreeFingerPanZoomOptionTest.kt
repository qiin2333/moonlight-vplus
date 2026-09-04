package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeFingerPanZoomOptionTest {
    @Test
    fun rowAndCheckboxShareActionAndRequestMenuRefresh() {
        var count = 0
        val action = Runnable { count++ }
        val option = threeFingerPanZoomOption("Pan/Zoom", true, action)
        val inline = option.inlineControl as GameMenu.InlineControl.Toggle
        assertSame(action, option.runnable)
        assertSame(action, inline.toggleAction)
        assertEquals(GameMenuOptionPresentation.COMPATIBLE_ACTION, option.presentation)
        assertTrue(option.isKeepDialog)
        assertFalse(option.isWithGameFocus)
        option.runnable!!.run()
        assertEquals(1, count)
        inline.toggleAction!!.run()
        assertEquals(2, count)
    }
}
