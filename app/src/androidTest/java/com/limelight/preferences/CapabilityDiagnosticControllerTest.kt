package com.limelight.preferences

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityDiagnosticControllerTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<CapabilityDiagnosticActivity>()

    @Test
    fun reportOwnsInitialFocusAndDpadScrolls() {
        val report = composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.REPORT)
        val list = composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.LIST)
        report.assertIsFocused()
        val initialPosition = list.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            list.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value() > initialPosition
        }
        report.assertIsFocused()
    }

    @Test
    fun horizontalNavigationReachesTopBarActions() {
        val report = composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.REPORT)
        report.assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.COPY).assertIsFocused()

        sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.BACK).assertIsFocused()
    }

    @Test
    fun gamepadConfirmCopiesReport() {
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithTag(CapabilityDiagnosticTags.COPY).assertIsFocused()

        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        var copiedText = ""
        composeTestRule.runOnIdle {
            val clipboard = composeTestRule.activity
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            copiedText = clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(composeTestRule.activity)
                ?.toString()
                .orEmpty()
        }
        assertFalse(copiedText.isBlank())
    }

    @Test
    fun gamepadBackClosesPage() {
        val activity = composeTestRule.activity
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            activity.isFinishing || activity.isDestroyed
        }
    }

    @Test
    fun landscapeContentAvoidsSystemBarsAndDisplayCutout() {
        composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }

        var expectedRightInset = 0
        var expectedBottomInset = 0
        composeTestRule.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(composeTestRule.activity.window.decorView)
                ?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
            expectedRightInset = insets?.right ?: 0
            expectedBottomInset = insets?.bottom ?: 0
        }

        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val contentBounds = composeTestRule
            .onNodeWithTag(CapabilityDiagnosticTags.REPORT)
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(rootBounds.right - expectedRightInset, contentBounds.right, 1f)
        assertEquals(rootBounds.bottom - expectedBottomInset, contentBounds.bottom, 1f)
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        composeTestRule.waitForIdle()
    }
}
