package com.limelight

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OrientationManagerMultiWindowTest {
    @Suppress("DEPRECATION")
    @Test
    fun portraitHostIsRecheckedWhenConnectionStartsAfterItsResolutionNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // No network request is made: only the rotation transport is replaced.
                val crypto = object : LimelightCryptoProvider {
                    override fun getClientCertificate(): X509Certificate = error("unused")
                    override fun getClientPrivateKey(): PrivateKey = error("unused")
                    override fun getPemEncodedClientCertificate(): ByteArray = error("unused")
                    override fun encodeBase64String(data: ByteArray): String = error("unused")
                }
                val connection = NvConnection(
                    activity.applicationContext, ComputerDetails.AddressTuple("127.0.0.1", 47989),
                    47984, "test", "test", StreamConfiguration.Builder().build(), crypto,
                    null, activity.windowManager.defaultDisplay, null, false
                )
                val rotations = mutableListOf<Int>()
                val requestRotation: (NvConnection, Int, NvConnection.DisplayRotationCallback) -> Unit =
                    { _, angle, _ -> rotations.add(angle) }

                val resolutionFirst = OrientationManager(
                    activity, 1920, 1080, true, false, forceLandscapeMultiWindow = true,
                    inMultiWindow = { true }, requestHostRotation = requestRotation, displayProvider = { null }
                )
                resolutionFirst.connection = connection
                resolutionFirst.syncOrientationOnFirstFrame(1080, 1920)
                assertEquals(0, rotations.size)
                resolutionFirst.onConnectionEstablished()
                resolutionFirst.onConnectionEstablished()
                assertEquals(listOf(0), rotations)
                resolutionFirst.cleanup()

                rotations.clear()
                val connectionFirst = OrientationManager(
                    activity, 1920, 1080, true, false, forceLandscapeMultiWindow = true,
                    inMultiWindow = { true }, requestHostRotation = requestRotation, displayProvider = { null }
                )
                connectionFirst.connection = connection
                connectionFirst.onConnectionEstablished()
                assertEquals(0, rotations.size)
                connectionFirst.syncOrientationOnFirstFrame(1080, 1920)
                assertEquals(listOf(0), rotations)
                connectionFirst.cleanup()

                for ((enabled, multiWindow) in listOf(false to true, true to false)) {
                    rotations.clear()
                    val manager = OrientationManager(
                        activity, 1920, 1080, true, false, forceLandscapeMultiWindow = enabled,
                        inMultiWindow = { multiWindow }, requestHostRotation = requestRotation,
                        displayProvider = { null }
                    )
                    manager.connection = connection
                    manager.syncOrientationOnFirstFrame(1080, 1920)
                    manager.onConnectionEstablished()
                    assertEquals(0, rotations.size)
                    manager.cleanup()
                }

                rotations.clear()
                val invalid = OrientationManager(
                    activity, 1920, 1080, true, false, forceLandscapeMultiWindow = true,
                    inMultiWindow = { true }, requestHostRotation = requestRotation, displayProvider = { null }
                )
                invalid.syncOrientationOnFirstFrame(1080, 1920)
                invalid.onConnectionEstablished()
                assertFalse(invalid.connected)
                invalid.cleanup()
                invalid.connection = connection
                invalid.onConnectionEstablished()
                assertFalse(invalid.connected)
                assertEquals(0, rotations.size)
            }
        }
    }

    @Test
    fun forceLandscapeSurvivesServerRotationAndRestoresAutoRotateOnExit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
            var inMultiWindow = false
            lateinit var manager: OrientationManager
            scenario.onActivity { activity ->
                manager = OrientationManager(
                    activity, 1920, 1080, true, false,
                    forceLandscapeMultiWindow = true,
                    inMultiWindow = { inMultiWindow },
                    displayProvider = { null }
                )
                manager.setPreferredOrientation()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)

                // A server rotation may already have a delayed FULL_USER restore queued.
                manager.onServerResolutionChanged(false)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, activity.requestedOrientation)
                inMultiWindow = true
                manager.onWindowModeChanged()
                manager.onServerResolutionChanged(false)
                manager.onConfigurationChanged()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, activity.requestedOrientation)
            }
            Thread.sleep(1200)
            scenario.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, activity.requestedOrientation)
                inMultiWindow = false
                manager.onWindowModeChanged()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
                manager.cleanup()
            }
        }
    }

    @Test
    fun disabledOptionPreservesAutomaticRotationInMultiWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val manager = OrientationManager(
                    activity, 1920, 1080, true, false,
                    forceLandscapeMultiWindow = false,
                    inMultiWindow = { true },
                    displayProvider = { null }
                )
                manager.setPreferredOrientation()
                manager.onWindowModeChanged()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
                manager.cleanup()
            }
        }
    }

    @Test
    fun enteringMultiWindowCancelsUnsentRotationAndInvalidatesItsCachedDirection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
            var inMultiWindow = false
            val sent = AtomicBoolean(false)
            lateinit var manager: OrientationManager
            scenario.onActivity { activity ->
                manager = OrientationManager(
                    activity, 1920, 1080, true, false,
                    forceLandscapeMultiWindow = true,
                    inMultiWindow = { inMultiWindow },
                    displayProvider = { null }
                )
                val lastRotation = OrientationManager::class.java.getDeclaredField("lastRotation")
                    .apply { isAccessible = true }
                val pending = OrientationManager::class.java.getDeclaredField("pendingRotationRunnable")
                    .apply { isAccessible = true }
                val handler = OrientationManager::class.java.getDeclaredField("handler")
                    .apply { isAccessible = true }.get(manager) as Handler
                val runnable = Runnable { sent.set(true) }
                lastRotation.setInt(manager, 0)
                pending.set(manager, runnable)
                handler.postDelayed(runnable, 250)
                inMultiWindow = true
                manager.onWindowModeChanged()
                assertEquals(-1, lastRotation.getInt(manager))
                inMultiWindow = false
                manager.onWindowModeChanged()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
            }
            Thread.sleep(400)
            assertFalse(sent.get())
            scenario.onActivity { manager.cleanup() }
        }
    }
}
