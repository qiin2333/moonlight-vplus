package com.limelight

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetDisplayResolverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displays = mutableListOf<VirtualDisplay>()

    @Before
    fun requireNoAttachedPresentationDisplay() {
        assumeTrue(displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isEmpty())
    }

    @After
    fun releaseDisplays() {
        displays.forEach { it.release() }
    }

    @Test
    fun nonPresentationDisplayIsNotSelected() {
        val secondary = createDisplay(presentation = false).display
        assertTrue(displayManager.displays.any { it.displayId == secondary.displayId })
        assertEquals(0, secondary.flags and Display.FLAG_PRESENTATION)

        val resolver = TargetDisplayResolver(context)
        assertEquals(Display.DEFAULT_DISPLAY, resolver.resolve(true).displayId)
        assertFalse(resolver.isExternalDisplaySelected())
    }

    @Test
    fun presentationDisplayIsSelectedReusedAndClearedOnRemoval() {
        createDisplay(presentation = false)
        val external = createDisplay(presentation = true)
        val displayId = external.display.displayId
        val resolver = TargetDisplayResolver(context)

        assertEquals(displayId, resolver.resolve(true).displayId)
        assertEquals(displayId, resolver.currentDisplay().displayId)
        assertTrue(resolver.isExternalDisplaySelected())

        val other = createDisplay(presentation = true)
        assertEquals(displayId, resolver.resolve(true).displayId)
        other.release()
        displays.remove(other)
        assertEquals(Display.DEFAULT_DISPLAY, resolver.resolve(false).displayId)
        assertFalse(resolver.isExternalDisplaySelected())
        assertEquals(displayId, resolver.resolve(true).displayId)

        external.release()
        displays.remove(external)

        assertEquals(Display.DEFAULT_DISPLAY, resolver.currentDisplay().displayId)
        assertFalse(resolver.isExternalDisplaySelected())
        // Preserve the removal notification even if currentDisplay() already fell back.
        assertTrue(resolver.onDisplayRemoved(displayId))
        assertEquals(Display.DEFAULT_DISPLAY, resolver.resolve(true).displayId)
    }

    private fun createDisplay(presentation: Boolean): VirtualDisplay {
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            if (presentation) DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION else 0
        return checkNotNull(displayManager.createVirtualDisplay(
            "TargetDisplayResolverTest-${displays.size}", 320, 240, 160, null, flags
        )).also { displays.add(it) }
    }
}
