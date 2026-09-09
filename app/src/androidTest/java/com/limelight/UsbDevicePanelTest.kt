package com.limelight

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UsbDevicePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultOffRemainsDiscoverableAndRequiresExplicitOptIn() {
        var enabled by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), null, false, R.string.usb_forward_disabled, "Test host",
                enabled, false, { enabled = it; changes++ }, {},
                { error("Enabling must not share a device") }, {})
        }
        val label = compose.activity.getString(R.string.usb_forward_enable)
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
                true, false, { enabled = it }, {}, {}, {})
        }
        compose.onNodeWithText(compose.activity.getString(R.string.usb_forward_enable))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, enabled) }
    }

    @Test fun unavailableHostOffersExplicitRetryWithoutSharing() {
        var retries = 0
        compose.setContent {
            UsbDevicePanel(emptyList(), null, false, R.string.usb_forward_host_disabled, "Test host",
                true, false, {}, { retries++ }, { error("Unavailable host must not share") }, {})
        }
        compose.onNodeWithText(compose.activity.getString(R.string.usb_forward_host_disabled))
            .assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.usb_forward_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
