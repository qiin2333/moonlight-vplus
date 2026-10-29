@file:Suppress("DEPRECATION")

package com.limelight

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameDisplayRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun external(): Pair<VirtualDisplay, ImageReader> {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener(
            { it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper())
        )
        val display = checkNotNull(displays.createVirtualDisplay(
            "Initialization order", 320, 240, 160, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        ))
        return display to reader
    }

    private fun nativeSize(): Point = Point().also {
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
    }

    private fun awaitStarts(scenario: ActivityScenario<Game>, count: Int) {
        var actual = 0
        for (attempt in 0..60) {
            scenario.onActivity { actual = it.startedConnections.size }
            if (actual >= count) break
            Thread.sleep(100)
        }
        assertEquals("Natural Surface callbacks must start the prepared connection", count, actual)
    }

    @Test
    fun rejectedDisplayIsResolvedBeforeConfigurationOnCreateAndOnStart() {
        val original = PreferenceConfiguration.latest
        for (native in listOf(false, true)) for (resume in listOf(false, true)) {
            val size = nativeSize()
            Game.initialNativeResolution = native
            PreferenceConfiguration.latest = PreferenceConfiguration().apply { isNativeResolution = native }
            var output = if (!resume || native) external() else null
            Game.rejectOnInflation = if (resume) null else output?.first
            try {
                ActivityScenario.launch<Game>(Intent(context, Game::class.java)).use { scenario ->
                    if (resume) {
                        awaitStarts(scenario, 1)
                        scenario.moveToState(Lifecycle.State.CREATED)
                        if (output == null) output = external()
                        Game.rejectOnInflation = output?.first
                        scenario.moveToState(Lifecycle.State.RESUMED)
                    }
                    val sessions = if (resume) 2 else 1
                    awaitStarts(scenario, sessions)
                    scenario.onActivity { game ->
                        assertNull("Display-removal injection must have run", Game.rejectOnInflation)
                        assertEquals(sessions, game.configureCalls)
                        val config = game.startedConnections.last().config
                        assertEquals(Game.Contract(
                            Display.DEFAULT_DISPLAY, false, 60,
                            if (native) size.x else 1920, if (native) size.y else 1080
                        ), config)
                        assertEquals(Display.DEFAULT_DISPLAY, checkNotNull(game.conn).display.displayId)
                        assertNull(game.externalStreamView)
                        assertTrue(game.inputCaptureProvider.isCapturingEnabled())
                        assertFalse(game.isFinishing)
                    }
                }
            } finally {
                Game.rejectOnInflation = null
                Game.initialNativeResolution = false
                PreferenceConfiguration.latest = original
                output?.first?.release()
                output?.second?.close()
            }
        }
    }

    @Test
    fun acceptedPresentationBindsAfterConfigurationAndStartsFromNaturalSurfaceCallback() {
        val (display, reader) = external()
        try {
            ActivityScenario.launch<Game>(Intent(context, Game::class.java)).use { scenario ->
                awaitStarts(scenario, 1)
                scenario.onActivity { game ->
                    assertEquals(1, game.configureCalls)
                    assertEquals(Game.Contract(display.display.displayId, true, 240),
                        game.startedConnections.single().config)
                    assertSame(game.externalDisplayManager?.getStreamView(), game.externalStreamView)
                    assertTrue(game.events.indexOf("configure:${display.display.displayId}") <
                        game.events.indexOf("bind"))
                    assertEquals(0.3f, game.window.attributes.screenBrightness, 0f)
                }
            }
        } finally {
            display.release()
            reader.close()
        }
    }

    @Test
    fun localOutputReusesProviderAndRestoresGrabWithoutCapturingNativePointerMode() {
        ActivityScenario.launch<Game>(Intent(context, Game::class.java)).use { scenario ->
            lateinit var original: InputCaptureProvider
            scenario.onActivity { original = it.inputCaptureProvider }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { game ->
                assertSame(original, game.inputCaptureProvider)
                for ((grab, native, expected) in listOf(
                    Triple(true, false, true), Triple(false, false, false), Triple(true, true, false)
                )) {
                    game.grabbedInput = grab
                    game.prefConfig.enableNativeMousePointer = native
                    val old = game.inputCaptureProvider
                    old.enableCapture()
                    game.disconnect()
                    assertSame(old, game.inputCaptureProvider)
                    assertEquals(expected, game.inputCaptureProvider.isCapturingEnabled())
                    assertEquals(grab, game.grabbedInput)
                }
            }
        }
    }

    @Test
    fun nativeSizeIgnoresGlobalPresetAndOtherSessionModesStayCommitted() {
        val original = PreferenceConfiguration.latest
        val size = nativeSize()
        try {
            for (mode in listOf("native", "native-reversed", "fixed", "custom", "committed-fixed")) {
                ActivityScenario.launch<Game>(Intent(context, Game::class.java)).use { scenario ->
                    scenario.onActivity { game ->
                        game.prefConfig.apply {
                            width = 1920
                            height = 1080
                            isNativeResolution = mode.startsWith("native") || mode == "committed-fixed"
                            reverseResolution = mode == "native-reversed"
                            isCustomResolution = mode == "custom"
                            fps = 120
                            bitrate = 40000
                            screenCombinationMode = 4
                        }
                        if (mode == "committed-fixed") {
                            DisplayReconnectSettings(false, false, 1920, 1080, 120, 40000, 4)
                                .applyTo(game.prefConfig)
                        }
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    PreferenceConfiguration.latest = PreferenceConfiguration().apply {
                        width = 2880
                        height = 1800
                        isNativeResolution = false
                    }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.onActivity { game ->
                        assertEquals(mode, 2, game.configureCalls)
                        val expectedWidth = when (mode) {
                            "native" -> size.x
                            "native-reversed" -> size.y
                            else -> 1920
                        }
                        val expectedHeight = when (mode) {
                            "native" -> size.y
                            "native-reversed" -> size.x
                            else -> 1080
                        }
                        assertEquals(mode, expectedWidth, game.prefConfig.width)
                        assertEquals(mode, expectedHeight, game.prefConfig.height)
                        assertEquals(mode, mode.startsWith("native"), game.prefConfig.isNativeResolution)
                        assertEquals(mode, mode == "custom", game.prefConfig.isCustomResolution)
                        assertEquals(120, game.prefConfig.fps)
                        assertEquals(40000, game.prefConfig.bitrate)
                        assertEquals(4, game.prefConfig.screenCombinationMode)
                        assertEquals(expectedWidth, checkNotNull(game.conn).config.width)
                        assertEquals(expectedHeight, checkNotNull(game.conn).config.height)
                    }
                }
            }
        } finally {
            PreferenceConfiguration.latest = original
        }
    }

    @Test
    fun providerReplacementDestroysOldResourcesBeforeConstruction() {
        try {
            ActivityScenario.launch<Game>(Intent(context, Game::class.java)).use { scenario ->
                scenario.onActivity { game ->
                    var destroyed = 0
                    val old = object : InputCaptureProvider() {
                        override fun destroy() {
                            assertFalse(isCapturingEnabled())
                            destroyed++
                        }
                    }
                    old.enableCapture()
                    game.inputCaptureProvider = old
                    var constructed = false
                    InputCaptureManager.beforeExternalCreate = {
                        assertEquals(1, destroyed)
                        constructed = true
                    }
                    game.connect(game.currentTargetDisplay)
                    assertTrue(constructed)
                    assertEquals(1, destroyed)
                    val replacement = game.inputCaptureProvider
                    assertNotSame(old, replacement)
                    game.disconnect()
                    assertSame(replacement, game.inputCaptureProvider)
                }
            }
        } finally {
            InputCaptureManager.beforeExternalCreate = null
        }
    }
}
