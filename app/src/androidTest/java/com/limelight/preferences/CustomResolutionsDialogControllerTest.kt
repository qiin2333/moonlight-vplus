package com.limelight.preferences

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import android.view.KeyEvent
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomResolutionsDialogControllerTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(StreamSettings::class.java)

    @Before
    fun clearStoredResolutionsBeforeTest() {
        clearStoredResolutions()
    }

    @After
    fun clearStoredResolutionsAfterTest() {
        clearStoredResolutions()
    }

    @Test
    fun compactLandscapeKeepsFormVisibleAndSupportsControllerNavigation() {
        rotateToLandscape(activityRule.scenario)
        showCustomResolutionsDialog(activityRule.scenario)

        onView(withId(R.id.custom_resolution_list)).check(matches(withEffectiveVisibility(GONE)))
        onView(withId(R.id.add_resolution_button)).check(matches(isCompletelyDisplayed()))
        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(withId(R.id.custom_resolution_height_field)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(withId(R.id.add_resolution_button)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        onView(withId(R.id.add_resolution_button)).check(doesNotExist())
    }

    @Test
    fun gamepadBackLeavesFieldEditingBeforeClosingDialog() {
        rotateToLandscape(activityRule.scenario)
        showCustomResolutionsDialog(activityRule.scenario)

        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        onView(withId(R.id.add_resolution_button)).check(matches(isDisplayed()))
        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        onView(withId(R.id.add_resolution_button)).check(doesNotExist())
    }

    @Test
    fun remoteConfirmAndBackFollowTheSameEditingContract() {
        rotateToLandscape(activityRule.scenario)
        showCustomResolutionsDialog(activityRule.scenario)

        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        sendKey(KeyEvent.KEYCODE_BACK)
        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BACK)
        onView(withId(R.id.add_resolution_button)).check(doesNotExist())
    }

    @Test
    fun controllerCanReachDeleteActionAndFocusReturnsAfterDeletion() {
        val resolution = "1280x720"
        activityRule.scenario.onActivity { activity ->
            activity.getSharedPreferences(
                CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE,
                Context.MODE_PRIVATE
            ).edit()
                .putStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, setOf(resolution))
                .commit()
        }
        rotateToLandscape(activityRule.scenario)
        showCustomResolutionsDialog(activityRule.scenario)

        sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        onView(withContentDescription(resolution)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        onView(withContentDescription(deleteDescription(resolution))).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        onView(withContentDescription(deleteDescription(resolution))).check(doesNotExist())
        onView(withId(R.id.custom_resolution_width_field)).check(matches(hasFocus()))

        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        onView(withId(R.id.add_resolution_button)).check(doesNotExist())
    }

    private fun rotateToLandscape(scenario: ActivityScenario<StreamSettings>) {
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        waitUntil {
            var landscape = false
            scenario.onActivity {
                landscape = it.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            }
            landscape
        }
    }

    private fun showCustomResolutionsDialog(scenario: ActivityScenario<StreamSettings>) {
        val tag = "CustomResolutionsPreferenceTest"
        waitUntil {
            var transactionStarted = false
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.preference_container) as? StreamSettings.SettingsFragment
                val preference = fragment
                    ?.findPreference<Preference>(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY)
                if (fragment != null && preference is CustomResolutionsPreference) {
                    val dialog = CustomResolutionsPreferenceDialogFragment.newInstance(preference.key)
                    @Suppress("DEPRECATION")
                    dialog.setTargetFragment(fragment, 0)
                    dialog.show(activity.supportFragmentManager, tag)
                    transactionStarted = true
                }
            }
            transactionStarted
        }
        waitUntil {
            var ready = false
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag(tag)
                    as? CustomResolutionsPreferenceDialogFragment
                ready = dialog?.dialog?.let { windowDialog ->
                    windowDialog.isShowing &&
                        windowDialog.window?.decorView?.hasWindowFocus() == true
                } == true
            }
            ready
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun deleteDescription(resolution: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return "${context.getString(R.string.dialog_button_delete)} $resolution"
    }

    private fun clearStoredResolutions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(
            CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Condition was not met before timeout")
    }
}
