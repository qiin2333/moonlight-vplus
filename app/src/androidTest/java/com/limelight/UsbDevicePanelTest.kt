package com.limelight

import android.graphics.Bitmap
import android.app.Dialog
import androidx.activity.ComponentActivity
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.utils.AppActionSheet
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsbDevicePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun controllerBackClosesSharedShell() {
        lateinit var dialog: Dialog
        compose.runOnIdle {
            dialog = AppActionSheet.showCustom(compose.activity) {
                androidx.compose.material3.Text("USB panel")
            }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(dialog.isShowing) }
    }

    @Test fun touchAndControllerOperateWithoutDismissing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = mutableStateOf(true)
        val selected = mutableStateOf<String?>(null)
        var dismissals = 0
        var refreshes = 0
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                UsbDevicePanel(listOf(UsbPanelDevice("usb/test", "AX88179A", UsbDeviceType.NETWORK)),
                    selected.value, false, R.string.usb_forward_choose, "7840", enabled.value, enabled.value,
                    { enabled.value = it }, {}, { selected.value = it }, { selected.value = null },
                    { refreshes++ }, { dismissals++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.usb_forward_share)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("usb/test", selected.value); assertEquals(0, dismissals) }
        compose.onNodeWithText(context.getString(R.string.usb_forward_release)).performClick()
        compose.runOnIdle { assertNull(selected.value) }
        compose.onNodeWithText(context.getString(R.string.usb_forward_refresh)).performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        // Request keyboard mode through a real D-pad event, then navigate to the main switch.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitForIdle()
        compose.onNode(isToggleable()).performScrollTo().requestFocus()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(enabled.value) }
        compose.onNodeWithText(context.getString(R.string.usb_forward_share)).assertIsNotEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(enabled.value) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.usb_forward_refresh)).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, refreshes) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), "usb-panel.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.usb_forward_close)).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
    @Test fun defaultOffRemainsDiscoverableAndRequiresExplicitOptIn() {
        var enabled by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), null, false, R.string.usb_forward_disabled, "Test host",
                enabled, false, { enabled = it; changes++ }, {},
                { error("Enabling must not share a device") }, {}, {}, {})
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.usb_forward_toggle_title)
        compose.onNodeWithText(label).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(true, enabled); assertEquals(1, changes) }
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(false, enabled); assertEquals(2, changes) }
    }

    @Test fun userCanDisableWhileCapabilityCheckIsRunning() {
        var enabled = true
        compose.setContent {
            UsbDevicePanel(emptyList(), null, true, R.string.usb_forward_checking, "Test host",
                true, false, { enabled = it }, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.usb_forward_toggle_title))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, enabled) }
    }

    @Test fun unavailableHostOffersExplicitRetryWithoutSharing() {
        var retries = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), null, false, R.string.usb_forward_host_disabled, "Test host",
                true, false, {}, { retries++ }, { error("Unavailable host must not share") }, {}, {}, {})
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.usb_forward_host_disabled))
            .assertExists()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.usb_forward_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
