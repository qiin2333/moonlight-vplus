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

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun panelDevice(path: String, name: String, type: UsbDeviceType, sharing: Boolean = false) =
        UsbPanelDevice(path, name, type,
            status = if (sharing) R.string.usb_forward_sharing else R.string.usb_forward_local,
            sharing = sharing)

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
        val enabled = mutableStateOf(true)
        val selected = mutableStateOf<String?>(null)
        var dismissals = 0
        var refreshes = 0
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                UsbDevicePanel(listOf(panelDevice("usb/test", "AX88179A", UsbDeviceType.NETWORK,
                    selected.value != null)),
                    false, R.string.usb_forward_choose, "7840", enabled.value, enabled.value,
                    { enabled.value = it }, {}, { selected.value = it }, { selected.value = null },
                    { refreshes++ }, { dismissals++ })
            }
        }
        compose.onNodeWithText(label(R.string.usb_forward_share)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("usb/test", selected.value); assertEquals(0, dismissals) }
        compose.onNodeWithText(label(R.string.usb_forward_release)).performClick()
        compose.runOnIdle { assertNull(selected.value) }
        compose.onNodeWithText(label(R.string.usb_forward_refresh)).performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        // Request keyboard mode through a real D-pad event, then navigate to the main switch.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitForIdle()
        compose.onNode(isToggleable()).performScrollTo().requestFocus()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(enabled.value) }
        compose.onNodeWithText(label(R.string.usb_forward_share)).assertIsNotEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(enabled.value) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.usb_forward_refresh)).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, refreshes) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "usb-panel.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription(label(R.string.usb_forward_close)).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    /** Sharing several devices at once is the point of this panel: a device that is
     * already shared must not block the others. */
    @Test fun otherDevicesStayAvailableWhileOneIsShared() {
        var shared: String? = null
        var released: String? = null
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                UsbDevicePanel(listOf(
                    panelDevice("usb/gamepad", "DualSense", UsbDeviceType.GAMEPAD, sharing = true),
                    panelDevice("usb/net", "AX88179A", UsbDeviceType.NETWORK)),
                    false, R.string.usb_forward_choose, "7840", true, true,
                    {}, {}, { shared = it }, { released = it }, {}, {})
            }
        }
        compose.onNodeWithText(label(R.string.usb_forward_share)).performScrollTo()
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("usb/net", shared) }
        compose.onNodeWithText(label(R.string.usb_forward_release)).performClick()
        compose.runOnIdle { assertEquals("usb/gamepad", released) }
    }

    @Test fun onlyTheReleasingDeviceLocksItsRow() {
        // A tunnel that is still being set up can be stopped from its own row...
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                UsbDevicePanel(listOf(
                    UsbPanelDevice("usb/a", "DualSense", UsbDeviceType.GAMEPAD,
                        R.string.usb_forward_connecting, sharing = true, busy = true)),
                    false, R.string.usb_forward_choose, "7840", true, true,
                    {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(label(R.string.usb_forward_release)).assertIsEnabled()
    }

    @Test fun releasingDeviceCannotBeStoppedTwice() {
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                UsbDevicePanel(listOf(
                    UsbPanelDevice("usb/a", "DualSense", UsbDeviceType.GAMEPAD,
                        R.string.usb_forward_releasing, sharing = true, busy = true, locked = true)),
                    false, R.string.usb_forward_choose, "7840", true, true,
                    {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(label(R.string.usb_forward_release)).assertIsNotEnabled()
    }

    @Test fun defaultOffRemainsDiscoverableAndRequiresExplicitOptIn() {
        var enabled by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), false, R.string.usb_forward_disabled, "Test host",
                enabled, false, { enabled = it; changes++ }, {},
                { error("Enabling must not share a device") }, {}, {}, {})
        }
        compose.onNodeWithText(label(R.string.usb_forward_toggle_title)).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText(label(R.string.usb_forward_toggle_title)).performClick()
        compose.runOnIdle { assertEquals(true, enabled); assertEquals(1, changes) }
        compose.onNodeWithText(label(R.string.usb_forward_toggle_title)).performClick()
        compose.runOnIdle { assertEquals(false, enabled); assertEquals(2, changes) }
    }

    @Test fun userCanDisableWhileCapabilityCheckIsRunning() {
        var enabled = true
        compose.setContent {
            UsbDevicePanel(emptyList(), true, R.string.usb_forward_checking, "Test host",
                true, false, { enabled = it }, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText(label(R.string.usb_forward_toggle_title))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, enabled) }
    }

    @Test fun unavailableHostOffersExplicitRetryWithoutSharing() {
        var retries = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), false, R.string.usb_forward_host_disabled, "Test host",
                true, false, {}, { retries++ }, { error("Unavailable host must not share") }, {}, {}, {})
        }
        compose.onNodeWithText(label(R.string.usb_forward_host_disabled)).assertExists()
        compose.onNodeWithText(label(R.string.usb_forward_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
