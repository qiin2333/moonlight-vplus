package com.limelight.binding.input.virtual_controller

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.limelight.utils.AppActionSheet
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualControllerOptionsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var dialog: VirtualControllerOptionsDialog? = null

    @After fun dismissDialog() {
        compose.runOnUiThread { dialog?.dismiss() }
    }

    @Test fun usbNeutralDuringFocusLossReleasesTheAxisGate() {
        compose.runOnUiThread {
            val activity = compose.activity
            dialog = VirtualControllerOptionsDialog(activity,
                listOf(AppActionSheet.Action(0, "First"), AppActionSheet.Action(1, "Second")),
                readAxes = { emptyList<Pair<Float, Float>>() to 0f },
                initialOpacity = 90,
                initialSizeScale = 1f,
                onToggleChanged = { _, _ -> },
                onOpacityChanged = {},
                onSizeScaleChanged = {},
                onAction = {}).also { it.showMenu() }
        }
        onView(isRoot()).inRoot(isDialog()).check(matches(isDisplayed()))
        compose.onNodeWithText("First").assertIsFocused()
        compose.runOnUiThread { requireNotNull(dialog).dispatchAxes(-7, listOf(0f to 1f), 0f) }
        compose.onNodeWithText("Second").assertIsFocused()
        compose.runOnUiThread {
            val menu = requireNotNull(dialog)
            menu.onWindowFocusChanged(false)
            menu.dispatchAxes(-7, listOf(0f to 0f), 0f)
            menu.onWindowFocusChanged(true)
            menu.dispatchAxes(-7, listOf(0f to -1f), 0f)
            menu.releaseSource(-7)
        }
        compose.onNodeWithText("First").assertIsFocused()
    }
}
