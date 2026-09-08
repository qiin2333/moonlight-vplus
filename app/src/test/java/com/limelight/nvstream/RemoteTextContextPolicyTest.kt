package com.limelight.nvstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTextContextPolicyTest {
    private fun context(
        flags: Int,
        source: Int = RemoteTextContext.SOURCE_UIA,
        cause: Int = RemoteTextContext.CAUSE_REMOTE_TOUCH,
    ) = RemoteTextContext(
        flags = flags or RemoteTextContext.FLAG_ANCHOR_POINT,
        revision = 1,
        activationId = 2,
        inputToken = 3,
        source = source,
        cause = cause,
        anchorX = 10,
        anchorY = 20,
        elementLeft = 30,
        elementTop = 40,
        elementRight = 50,
        elementBottom = 60,
        caretLeft = 35,
        caretTop = 45,
        caretRight = 36,
        caretBottom = 55,
        captureWidth = 1920,
        captureHeight = 1080,
    )

    @Test
    fun `input pane requires matched remote touch auto-show transition`() {
        val required = RemoteTextContext.FLAG_INPUT_MATCHED or
            RemoteTextContext.FLAG_PANE_VISIBLE or RemoteTextContext.FLAG_AUTO_SHOW
        assertTrue(RemoteTextContextPolicy.isTrustedActivation(context(required, RemoteTextContext.SOURCE_INPUT_PANE)))
        assertFalse(RemoteTextContextPolicy.isTrustedActivation(context(required, RemoteTextContext.SOURCE_INPUT_PANE, RemoteTextContext.CAUSE_REMOTE_MOUSE)))
        assertFalse(RemoteTextContextPolicy.isTrustedActivation(context(required xor RemoteTextContext.FLAG_AUTO_SHOW, RemoteTextContext.SOURCE_INPUT_PANE)))
    }

    @Test
    fun `uia requires matched active editable remote input`() {
        val required = RemoteTextContext.FLAG_INPUT_MATCHED or RemoteTextContext.FLAG_ACTIVE or
            RemoteTextContext.FLAG_EDITABLE or RemoteTextContext.FLAG_ELEMENT_RECT
        assertTrue(RemoteTextContextPolicy.isTrustedActivation(context(required)))
        assertTrue(RemoteTextContextPolicy.isTrustedActivation(context(required, cause = RemoteTextContext.CAUSE_REMOTE_MOUSE)))
        assertFalse(RemoteTextContextPolicy.isTrustedActivation(context(required xor RemoteTextContext.FLAG_EDITABLE)))
        assertFalse(RemoteTextContextPolicy.isTrustedActivation(context(required, cause = 99)))
        assertFalse(RemoteTextContextPolicy.isTrustedActivation(context(required, source = 99)))
        assertFalse(
            RemoteTextContextPolicy.isTrustedActivation(
                context(required).copy(elementRight = 30),
            ),
        )
    }

    @Test
    fun `revision ordering is unsigned and wrap-safe`() {
        assertTrue(RemoteTextContextPolicy.isNewerRevision(11, 10))
        assertFalse(RemoteTextContextPolicy.isNewerRevision(10, 10))
        assertFalse(RemoteTextContextPolicy.isNewerRevision(9, 10))
        assertTrue(RemoteTextContextPolicy.isNewerRevision(0, 0xffff_ffffL))
        assertFalse(RemoteTextContextPolicy.isNewerRevision(0x8000_0000L, 0))
    }

    @Test
    fun `focus position prefers contained caret then matched anchor never document bottom`() {
        assertEquals(55, RemoteTextContextPolicy.focusY(context(RemoteTextContext.FLAG_CARET_RECT or RemoteTextContext.FLAG_ELEMENT_RECT)))
        assertEquals(20, RemoteTextContextPolicy.focusY(context(RemoteTextContext.FLAG_ELEMENT_RECT or RemoteTextContext.FLAG_INPUT_MATCHED)))
        assertNull(RemoteTextContextPolicy.focusY(context(0)))
        assertEquals(
            20,
            RemoteTextContextPolicy.focusY(
                context(RemoteTextContext.FLAG_CARET_RECT or RemoteTextContext.FLAG_ELEMENT_RECT or RemoteTextContext.FLAG_INPUT_MATCHED)
                    .copy(caretBottom = 45),
            ),
        )
    }

    @Test
    fun `invalid capture and out of capture anchor do not move viewport`() {
        val c = context(RemoteTextContext.FLAG_INPUT_MATCHED)
        assertNull(RemoteTextContextPolicy.focusY(c.copy(captureHeight = 0)))
        assertNull(RemoteTextContextPolicy.focusY(c.copy(anchorY = 1080)))
        assertNull(RemoteTextContextPolicy.focusY(c.copy(anchorX = -1)))
    }

    @Test
    fun `caret outside focused element falls back to matched click`() {
        val c = context(RemoteTextContext.FLAG_INPUT_MATCHED or
            RemoteTextContext.FLAG_CARET_RECT or RemoteTextContext.FLAG_ELEMENT_RECT)
        assertEquals(20, RemoteTextContextPolicy.focusY(c.copy(caretBottom = 1000)))
        assertEquals(20, RemoteTextContextPolicy.focusY(c.copy(caretLeft = -1)))
    }

    @Test
    fun `whole Chrome document avoids clicked point not page bottom`() {
        val c = context(RemoteTextContext.FLAG_INPUT_MATCHED or RemoteTextContext.FLAG_ELEMENT_RECT)
            .copy(elementLeft = 0, elementTop = 242, elementRight = 3840,
                elementBottom = 2064, captureWidth = 3840, captureHeight = 2160,
                anchorX = 800, anchorY = 600)
        assertEquals(600, RemoteTextContextPolicy.focusY(c))
        val mappedY = RemoteTextContextPolicy.focusY(c)!! / 2f
        assertEquals(0f, RemoteTextContextPolicy.viewportOffset(mappedY, 700f, 24f), 0f)
    }

    @Test
    fun `docked keyboard movement is repeatable and returns to base when dismissed`() {
        val baseFocus = 1000f
        val visibleBottom = RemoteTextContextPolicy.imeVisibleBottom(0f, 1080, 400)!!
        repeat(20) {
            // Always map from the user pan and zoom, excluding the previous IME offset.
            val offset = RemoteTextContextPolicy.viewportOffset(baseFocus, visibleBottom, 24f)
            assertEquals(-344f, offset, 0f)
            assertEquals(656f, baseFocus + offset, 0f)
        }
        assertNull(RemoteTextContextPolicy.imeVisibleBottom(0f, 1080, 0))
    }

    @Test
    fun `viewport offset only moves content upward`() {
        assertEquals(0f, RemoteTextContextPolicy.viewportOffset(500f, 900f, 24f), 0f)
        assertEquals(-124f, RemoteTextContextPolicy.viewportOffset(1000f, 900f, 24f), 0f)
    }

    @Test
    fun `deactivation must match the active trusted activation`() {
        val activeId = 42L
        val deactivation = context(RemoteTextContext.FLAG_INPUT_MATCHED).copy(
            activationId = activeId,
            source = RemoteTextContext.SOURCE_UIA,
            cause = RemoteTextContext.CAUSE_REMOTE_TOUCH,
        )
        assertTrue(RemoteTextContextPolicy.isTrustedDeactivation(deactivation, activeId))
        assertFalse(RemoteTextContextPolicy.isTrustedDeactivation(deactivation, 41L))
        assertFalse(
            RemoteTextContextPolicy.isTrustedDeactivation(
                deactivation.copy(
                    flags = RemoteTextContext.FLAG_ACTIVE or
                        RemoteTextContext.FLAG_EDITABLE or
                        RemoteTextContext.FLAG_INPUT_MATCHED,
                ),
                activeId,
            ),
        )
    }

    @Test
    fun `IME inset boundary uses screen coordinates and rejects floating IME`() {
        assertEquals(700f, RemoteTextContextPolicy.imeVisibleBottom(100f, 1000, 400)!!, 0f)
        assertNull(RemoteTextContextPolicy.imeVisibleBottom(100f, 1000, 0))
    }

    @Test
    fun `legacy visible frame accounts for nonzero root origin`() {
        assertEquals(850f, RemoteTextContextPolicy.legacyVisibleBottom(100f, 1000, 850)!!, 0f)
        assertNull(RemoteTextContextPolicy.legacyVisibleBottom(100f, 1000, 1000))
    }
}
