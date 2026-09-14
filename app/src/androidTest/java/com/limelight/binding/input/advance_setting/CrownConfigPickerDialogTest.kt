package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.ListView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import com.limelight.binding.input.advance_setting.config.PageConfigController
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownConfigPickerDialogTest {
    @get:Rule val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )
    private lateinit var dialog: CrownConfigPickerDialog
    private val selections = mutableListOf<Int>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun show() {
        activityRule.scenario.onActivity { activity ->
            dialog = CrownConfigPickerDialog(activity, (0..39).map { "Config $it" },
                readAxes = { emptyList<Pair<Float, Float>>() to 0f },
                onSelected = selections::add)
            dialog.show()
        }
        instrumentation.waitForIdleSync()
        awaitWindowFocus(true)
    }

    private fun awaitWindowFocus(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000L
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            activityRule.scenario.onActivity { focused = dialog.window!!.decorView.hasWindowFocus() }
            if (focused == expected) return
            SystemClock.sleep(20)
        }
        fail("Dialog did not reach expected window focus: $expected")
    }

    private fun key(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        dialog.dispatchKeyEvent(KeyEvent(now, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0))
    }

    @After fun close() {
        activityRule.scenario.onActivity { if (::dialog.isInitialized) dialog.dismiss() }
    }

    @Test fun dpadAndConfirmSelectOnceWithoutTouch() {
        show()
        activityRule.scenario.onActivity {
            assertTrue(dialog.currentFocus is ListView)
            key(KeyEvent.KEYCODE_DPAD_DOWN, true)
            key(KeyEvent.KEYCODE_DPAD_DOWN, false)
            key(KeyEvent.KEYCODE_BUTTON_A, true)
            key(KeyEvent.KEYCODE_BUTTON_A, true)
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            assertEquals(listOf(1), selections)
            assertFalse(dialog.isShowing)
        }
    }

    @Test fun backCancelsWithoutChangingBinding() {
        show()
        activityRule.scenario.onActivity {
            key(KeyEvent.KEYCODE_BUTTON_B, true)
            assertTrue(dialog.isShowing)
            key(KeyEvent.KEYCODE_BUTTON_B, false)
            assertFalse(dialog.isShowing)
            assertTrue(selections.isEmpty())
        }
    }

    @Test fun leftStickMovesFocusAndDisconnectStopsRepeat() {
        show()
        activityRule.scenario.onActivity {
            dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
            assertEquals(1, (dialog.currentFocus as ListView).selectedItemPosition)
            dialog.releaseSource(42)
        }
        SystemClock.sleep(500)
        activityRule.scenario.onActivity {
            assertEquals(1, (dialog.currentFocus as ListView).selectedItemPosition)
        }
    }

    @Test fun rightStickScrollsWithoutSelectingOrChangingFocus() {
        show()
        activityRule.scenario.onActivity {
            dialog.dispatchAxes(42, listOf(0f to 0f), 1f)
        }
        SystemClock.sleep(800)
        activityRule.scenario.onActivity {
            dialog.dispatchAxes(42, listOf(0f to 0f), 0f)
            val list = dialog.currentFocus as ListView
            assertTrue(list.firstVisiblePosition > 0)
            assertEquals(0, list.selectedItemPosition)
            assertTrue(selections.isEmpty())
        }
    }

    @Test fun confirmWithoutPressDoesNothing() {
        show()
        activityRule.scenario.onActivity {
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            assertTrue(dialog.isShowing)
            assertTrue(selections.isEmpty())
        }
    }

    @Test fun movingSelectionWhileConfirmIsHeldCancelsConfirmation() {
        show()
        activityRule.scenario.onActivity {
            key(KeyEvent.KEYCODE_BUTTON_A, true)
            key(KeyEvent.KEYCODE_DPAD_DOWN, true)
            key(KeyEvent.KEYCODE_DPAD_DOWN, false)
            key(KeyEvent.KEYCODE_BUTTON_A, true) // Duplicate DOWN must retain its original target.
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            assertTrue(selections.isEmpty())
            assertTrue(dialog.isShowing)
            key(KeyEvent.KEYCODE_BUTTON_A, true)
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            assertEquals(listOf(1), selections)
        }
    }

    @Test fun steadyOtherControllerCannotTakeBackNavigation() {
        show()
        activityRule.scenario.onActivity {
            dialog.dispatchAxes(41, listOf(0f to 1f), 0f)
            dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
            dialog.dispatchAxes(41, listOf(0f to 1f), 0f)
            dialog.dispatchAxes(42, listOf(0f to 0f), 0f)
            dialog.dispatchAxes(41, listOf(0f to 1f), 0f)
            assertEquals(2, (dialog.currentFocus as ListView).selectedItemPosition)
        }
        SystemClock.sleep(600)
        activityRule.scenario.onActivity {
            assertEquals(2, (dialog.currentFocus as ListView).selectedItemPosition)
        }
    }

    private fun coverAndReturn() {
        lateinit var cover: android.app.AlertDialog
        activityRule.scenario.onActivity { activity ->
            cover = android.app.AlertDialog.Builder(activity).setMessage("Overlay").show()
        }
        try {
            awaitWindowFocus(false)
        } finally {
            activityRule.scenario.onActivity { cover.dismiss() }
        }
        awaitWindowFocus(true)
    }

    @Test fun confirmRepeatAfterFocusReturnCannotArmNewClick() {
        show()
        activityRule.scenario.onActivity { key(KeyEvent.KEYCODE_BUTTON_A, true) }
        coverAndReturn()
        activityRule.scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            dialog.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1))
            key(KeyEvent.KEYCODE_BUTTON_A, false)
            assertTrue(selections.isEmpty())
            assertTrue(dialog.isShowing)
        }
    }

    @Test fun heldStickMustReturnToNeutralAfterFocusReturn() {
        show()
        activityRule.scenario.onActivity { dialog.dispatchAxes(42, listOf(0f to 1f), 0f) }
        coverAndReturn()
        activityRule.scenario.onActivity {
            dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
            assertEquals(1, (dialog.currentFocus as ListView).selectedItemPosition)
            dialog.dispatchAxes(42, listOf(0f to 0f), 0f)
            dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
            assertEquals(2, (dialog.currentFocus as ListView).selectedItemPosition)
        }
    }

    @Test fun coveredDialogStopsRepeatingAndCannotConfirmInBackground() {
        show()
        lateinit var coveringDialog: android.app.AlertDialog
        activityRule.scenario.onActivity { activity ->
            dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
            coveringDialog = android.app.AlertDialog.Builder(activity).setMessage("Overlay").show()
        }
        instrumentation.waitForIdleSync()
        awaitWindowFocus(false)
        SystemClock.sleep(500)
        try {
            activityRule.scenario.onActivity {
                assertEquals(1, (dialog.currentFocus as ListView).selectedItemPosition)
                dialog.dispatchAxes(42, listOf(0f to 1f), 0f)
                key(KeyEvent.KEYCODE_BUTTON_A, true)
                key(KeyEvent.KEYCODE_BUTTON_A, false)
                assertTrue(selections.isEmpty())
                assertTrue(dialog.isShowing)
            }
        } finally {
            activityRule.scenario.onActivity { coveringDialog.dismiss() }
        }
    }

    @Test fun deletedAndRenamedBindingsAreResolvedFromCurrentDatabase() {
        activityRule.scenario.onActivity { activity ->
            val root = FrameLayout(activity).apply {
                id = R.id.advance_setting_view
                addView(FrameLayout(activity).apply { id = R.id.super_pages_box })
            }
            val manager = ControllerManager(root, activity)
            val database = manager.superConfigDatabaseHelper!!
            val picker = manager.pageDeviceController!!
            val target = 9539000001L
            val sameName = target + 1
            val action = DirectConfigAction.encode(target)
            try {
                database.insertConfig(ContentValues().apply {
                    put(PageConfigController.COLUMN_LONG_CONFIG_ID, target)
                    put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "Original")
                })
                assertTrue(picker.getKeyNameByValue(action).contains("Original"))
                database.updateConfig(target, ContentValues().apply {
                    put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "Renamed")
                })
                assertTrue(picker.getKeyNameByValue(action).contains("Renamed"))
                database.deleteConfig(target)
                database.insertConfig(ContentValues().apply {
                    put(PageConfigController.COLUMN_LONG_CONFIG_ID, sameName)
                    put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "Renamed")
                })
                assertEquals(activity.getString(R.string.crown_direct_config_unavailable),
                    picker.getKeyNameByValue(action))
                // The execution entry must return without touching the current config or loading Views.
                manager.pageConfigController!!.switchDirectlyToConfig(target)
            } finally {
                database.deleteConfig(target)
                database.deleteConfig(sameName)
            }
        }
    }

    @Test fun targetListExcludesSelfAndRechecksDeletionWithoutChangingBinding() {
        activityRule.scenario.onActivity { activity ->
            val root = FrameLayout(activity).apply {
                id = R.id.advance_setting_view
                addView(FrameLayout(activity).apply { id = R.id.super_pages_box })
            }
            val manager = ControllerManager(root, activity)
            val database = manager.superConfigDatabaseHelper!!
            val config = manager.pageConfigController!!
            val source = config.currentConfigId
            val target = 9539000010L
            val createdSource = source !in database.queryAllConfigIds()
            try {
                if (createdSource) database.insertConfig(ContentValues().apply {
                    put(PageConfigController.COLUMN_LONG_CONFIG_ID, source)
                    put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "Current")
                })
                database.insertConfig(ContentValues().apply {
                    put(PageConfigController.COLUMN_LONG_CONFIG_ID, target)
                    put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "Other")
                })
                assertFalse(source in config.directSwitchTargetIds)
                assertTrue(target in config.directSwitchTargetIds)
                assertEquals(activity.getString(R.string.crown_direct_config_self),
                    manager.pageDeviceController!!.getKeyNameByValue(DirectConfigAction.encode(source)))
                database.deleteConfig(target)
                assertFalse(target in config.directSwitchTargetIds)
                config.switchDirectlyToConfig(source)
                assertEquals(source, config.currentConfigId)
            } finally {
                database.deleteConfig(target)
                if (createdSource) database.deleteConfig(source)
            }
        }
    }
}
