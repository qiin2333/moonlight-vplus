package com.limelight.utils

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.preferences.StreamSettings
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Iperf3ProtocolSelectionTest {
    @Test
    fun protocolSelectionDefaultsToTcpAndOnlyUdpExposesBandwidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bandwidth = context.resources.getIdentifier("edit_text_udp_bandwidth", "id", context.packageName)
        ActivityScenario.launch(StreamSettings::class.java).use { scenario ->
            scenario.onActivity { Iperf3Tester(it, null).show() }
            onView(withText("TCP")).check(matches(isChecked()))
            onView(withId(bandwidth)).check(matches(not(isDisplayed())))
            onView(withText("UDP")).perform(click()).check(matches(isChecked()))
            onView(withText("TCP")).check(matches(not(isChecked())))
            onView(withId(bandwidth)).check(matches(isDisplayed()))
            onView(withText("TCP")).perform(click()).check(matches(isChecked()))
            onView(withId(bandwidth)).check(matches(not(isDisplayed())))
            pressBack()
        }
    }
}
