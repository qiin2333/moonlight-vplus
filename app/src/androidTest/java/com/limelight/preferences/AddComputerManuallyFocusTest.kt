package com.limelight.preferences

import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddComputerManuallyFocusTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(AddComputerManually::class.java)

    @Test
    fun connectingButtonRemainsAValidExplicitFocusTarget() {
        activityRule.scenario.onActivity { activity ->
            val host = activity.findViewById<EditText>(R.id.hostTextView)
            val port = activity.findViewById<EditText>(R.id.portTextView)
            val connect = activity.findViewById<Button>(R.id.addPcButton)

            activity.setAddingState(true)
            assertTrue(connect.isEnabled)
            assertTrue(connect.isFocusable)
            assertTrue(connect.isFocusableInTouchMode)

            assertTrue(host.requestFocus())
            val hostDownTarget = host.focusSearch(View.FOCUS_DOWN)
            assertSame(connect, hostDownTarget)
            assertTrue(hostDownTarget!!.requestFocus())

            assertTrue(port.requestFocus())
            val portRightTarget = port.focusSearch(View.FOCUS_RIGHT)
            assertSame(connect, portRightTarget)
            assertTrue(portRightTarget!!.requestFocus())
        }
    }
}
