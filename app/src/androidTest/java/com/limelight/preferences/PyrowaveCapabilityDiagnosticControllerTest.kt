package com.limelight.preferences

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PyrowaveCapabilityDiagnosticControllerTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<CapabilityDiagnosticActivity>

    @Before
    fun openPyrowaveReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(
            Intent(context, CapabilityDiagnosticActivity::class.java)
                .putExtra(CapabilityDiagnosticActivity.EXTRA_PYROWAVE_REPORT, true)
        )
    }

    @After
    fun closeReport() {
        scenario.close()
    }

    @Test
    fun initialFocusIsReportAndResultDoesNotStealActionFocus() {
        compose.onNodeWithTag(CapabilityDiagnosticTags.REPORT).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForResult()
        compose.onNodeWithTag(CapabilityDiagnosticTags.COPY).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag(CapabilityDiagnosticTags.BACK).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(CapabilityDiagnosticTags.REPORT).assertIsFocused()
    }

    @Test
    @Suppress("DEPRECATION")
    fun rtlToolbarNavigationFollowsPhysicalDirections() {
        lateinit var original: Configuration
        scenario.onActivity { activity ->
            original = Configuration(activity.resources.configuration)
            val configuration = Configuration(activity.resources.configuration).apply {
                setLayoutDirection(Locale("he"))
            }
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            activity.window.decorView.dispatchConfigurationChanged(configuration)
        }
        try {
            compose.waitForIdle()
            assertTrue(compose.onNodeWithTag(CapabilityDiagnosticTags.COPY).fetchSemanticsNode().boundsInRoot.left <
                compose.onNodeWithTag(CapabilityDiagnosticTags.BACK).fetchSemanticsNode().boundsInRoot.left)
            sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag(CapabilityDiagnosticTags.COPY).assertIsFocused()
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag(CapabilityDiagnosticTags.BACK).assertIsFocused()
            sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag(CapabilityDiagnosticTags.COPY).assertIsFocused()
        } finally {
            scenario.onActivity { activity ->
                activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
                activity.window.decorView.dispatchConfigurationChanged(original)
            }
        }
    }

    @Test
    fun gamepadConfirmCopiesThePyrowaveReport() {
        waitForResult()
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity).toString()
            assertTrue(text.contains("PyroWave"))
            assertTrue(text.contains(activity.getString(R.string.pyrowave_diag_scope)))
        }
    }

    @Test
    fun gamepadBackFinishesOnlyTheReport() {
        var activity: CapabilityDiagnosticActivity? = null
        scenario.onActivity { activity = it }
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        compose.waitUntil(5_000) { activity?.isFinishing == true || activity?.isDestroyed == true }
    }

    private fun waitForResult() {
        var scope = ""
        scenario.onActivity { scope = it.getString(R.string.pyrowave_diag_scope) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(scope).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun sendKey(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

}
