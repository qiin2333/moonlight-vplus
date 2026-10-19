package com.limelight.utils.easytier

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.easytier.jni.EasyTierJNI
import com.limelight.HelpActivity
import com.limelight.R
import dalvik.system.BaseDexClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasyTierUnsupportedAbiTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
            Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
                    .setData(Uri.parse("about:blank"))
    )

    @Test
    fun missingNativeLibrariesBlockVpnAndGrantedPermissionDoesNotStartJni() {
        val loader = EasyTierJNI::class.java.classLoader as BaseDexClassLoader
        assumeTrue("Run on an ABI without EasyTier libraries, such as ARMv7 or x86",
                loader.findLibrary("easytier_android_jni") == null)

        var vpnRequests = 0
        lateinit var controller: EasyTierController
        activityRule.scenario.onActivity { activity ->
            controller = EasyTierController(activity,
                    object : EasyTierController.VpnPermissionCallback {
                        override fun requestVpnPermission() { vpnRequests++ }
                    })
            controller.showControlDialog()
        }
        try {
            onView(withText(R.string.easytier_tab_status)).check(doesNotExist())
            activityRule.scenario.onActivity {
                // Reproduce the original crash entry, including an already granted VPN result.
                controller.handleVpnPermissionResult(Activity.RESULT_OK)
                assertEquals(0, vpnRequests)
            }
        } finally {
            activityRule.scenario.onActivity { controller.onDestroy() }
        }
    }
}
