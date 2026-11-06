package com.limelight.unlock

import com.limelight.binding.input.KeyboardTranslator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUnlockKeySequenceTest {
    @Test
    fun acceptsDigitPinsWithinWindowsLimits() {
        assertTrue(HostUnlockPin.isValid("1234".toCharArray()))
        assertTrue(HostUnlockPin.isValid("0".repeat(HostUnlockPin.MAX_LENGTH).toCharArray()))
    }

    @Test
    fun rejectsShortLongOrNonDigitPins() {
        assertFalse(HostUnlockPin.isValid("123".toCharArray()))
        assertFalse(HostUnlockPin.isValid("1".repeat(HostUnlockPin.MAX_LENGTH + 1).toCharArray()))
        assertFalse(HostUnlockPin.isValid("12a4".toCharArray()))
        assertFalse(HostUnlockPin.isValid("12 34".toCharArray()))
    }

    @Test
    fun buildsWakeDigitsAndEnter() {
        val keys = HostUnlockKeySequence.build("0937".toCharArray())

        assertArrayEquals(
            shortArrayOf(
                KeyboardTranslator.VK_LCONTROL.toShort(),
                KeyboardTranslator.VK_0.toShort(),
                (KeyboardTranslator.VK_0 + 9).toShort(),
                (KeyboardTranslator.VK_0 + 3).toShort(),
                (KeyboardTranslator.VK_0 + 7).toShort(),
                0x0d
            ),
            keys
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToBuildInvalidPin() {
        HostUnlockKeySequence.build("12ab".toCharArray())
    }
}
