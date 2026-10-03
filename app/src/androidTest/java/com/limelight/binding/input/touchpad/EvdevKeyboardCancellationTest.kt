package com.limelight.binding.input.touchpad

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.KeyboardInputHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.capture.InputCaptureProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvdevKeyboardCancellationTest {
    private val chord = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_C)

    @Test fun forcedReleasesCancelAPendingShortcutWithoutChangingCapture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val game = Game()
            val keyboard = KeyboardInputHandler(game).apply { keyboardTranslator = KeyboardTranslator() }
            game.cursorVisible = true
            val changes = mutableListOf<String>()
            game.inputCaptureProvider = object : InputCaptureProvider() {
                override fun hideCursor() { changes += "hide" }
                override fun showCursor() { changes += "show" }
            }
            chord.forEach { keyboard.keyboardEvent(true, it.toShort()) }
            chord.forEach { keyboard.cancelKeyboardEvent(it.toShort()) }
            assertTrue(changes.isEmpty())
            assertTrue(game.cursorVisible)
            // No stale modifier/shortcut state is left behind for the next chord.
            chord.forEach { keyboard.keyboardEvent(true, it.toShort()) }
            chord.forEach { keyboard.keyboardEvent(false, it.toShort()) }
            assertEquals(listOf("hide"), changes)
            assertFalse(game.cursorVisible)
        }
    }

    @Test fun releaseDuringACursorShortcutCannotExecuteTheShortcutAgain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val game = Game()
            val keyboard = KeyboardInputHandler(game).apply { keyboardTranslator = KeyboardTranslator() }
            game.cursorVisible = true
            var hides = 0
            game.inputCaptureProvider = object : InputCaptureProvider() {
                override fun hideCursor() {
                    hides++
                    // Emulate Evdev releasing another held key during handoff.
                    keyboard.cancelKeyboardEvent(KeyEvent.KEYCODE_W.toShort())
                }
            }
            keyboard.keyboardEvent(true, KeyEvent.KEYCODE_W.toShort())
            chord.forEach { keyboard.keyboardEvent(true, it.toShort()) }
            chord.take(3).forEach { keyboard.keyboardEvent(false, it.toShort()) }
            assertEquals(1, hides)
            assertFalse(game.cursorVisible)
            keyboard.keyboardEvent(false, KeyEvent.KEYCODE_C.toShort())
            assertEquals(1, hides)
        }
    }
}
