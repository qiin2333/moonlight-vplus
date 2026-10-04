package com.limelight

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.limelight.gamemenu.TransportPolicyControls
import com.limelight.nvstream.http.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransportPolicyControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val p = TransportFramePolicy("3", "2", "manual", 7000, 4500, 10, 30, 20,
        500, 0, 0, 500, TransportAutomaticControl(false, false, 7000))
    private fun view(applied: Boolean = false, sent: String? = null, failure: String = "none") =
        TransportPolicyView(status = TransportPolicyStatus("1", "18446744073709551615", "2", p,
            if (applied) p else null, true, !applied && failure == "none", false, true,
            listOf(TransportPolicyReceipt(p, applied, sent, failure)), automaticFecAvailable = true), refreshing = false, requestRevision = "3")

    @Test fun unavailableAutomaticFecIsDisabledWhileBitrateRemainsActionable() {
        val current = view().let { it.copy(status = it.status!!.copy(automaticFecAvailable = false)) }
        compose.setContent {
            MaterialTheme { TransportPolicyControls(current, {}, { fail("Unavailable FEC callback") }, {}) }
        }
        compose.onNodeWithTag("transportAutomaticFec").assertIsNotEnabled()
        compose.onNodeWithTag("transportAutomaticBitrate").assertIsEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.transport_auto_fec))
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.transport_auto_bitrate))
            .assertIsEnabled()
    }

    @Test fun fourIndependentModesAndManualActionUseCurrentHostState() {
        val current = mutableStateOf(view())
        val edits = mutableListOf<Pair<Boolean, Boolean>>()
        var manual = 0
        compose.setContent {
            MaterialTheme {
                TransportPolicyControls(current.value, { enabled ->
                    val a = current.value.status!!.accepted.automatic!!
                    edits += enabled to a.fec
                    current.value = current.value.copy(status = current.value.status!!.copy(
                        accepted = p.copy(automatic = a.copy(bitrate = enabled))))
                }, { enabled ->
                    val a = current.value.status!!.accepted.automatic!!
                    edits += a.bitrate to enabled
                    current.value = current.value.copy(status = current.value.status!!.copy(
                        accepted = p.copy(automatic = a.copy(fec = enabled))))
                }, { manual++ })
            }
        }
        compose.onNodeWithTag("transportAutomaticBitrate").assertIsOff().performClick()
        compose.onNodeWithTag("transportAutomaticBitrate").assertIsOn()
        compose.onNodeWithTag("transportAutomaticFec").assertIsOff().performClick()
        compose.onNodeWithTag("transportAutomaticFec").assertIsOn()
        compose.onNodeWithTag("transportAutomaticBitrate").performClick()
        compose.onNodeWithTag("transportAutomaticFec").performClick()
        compose.onNodeWithTag("transportManualControl").performClick()
        compose.runOnIdle {
            assertEquals(listOf(true to false, true to true, false to true, false to false), edits)
            assertEquals(1, manual)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), "transport-controls.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun acceptedApplicationAndFirstSendHaveSeparateLabels() {
        val current = mutableStateOf(view())
        compose.setContent { MaterialTheme { TransportPolicyControls(current.value, {}, {}, {}) } }
        val ctx = compose.activity
        compose.onNodeWithText(ctx.getString(R.string.transport_pending)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.transport_sdk_applied)).assertDoesNotExist()
        compose.runOnIdle { current.value = view(applied = true) }
        compose.onNodeWithText(ctx.getString(R.string.transport_sdk_applied)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.transport_first_sent)).assertDoesNotExist()
        compose.runOnIdle { current.value = view(applied = true, sent = "42") }
        compose.onNodeWithText(ctx.getString(R.string.transport_first_sent)).assertExists()
        compose.runOnIdle { current.value = view(failure = "backend_failure") }
        compose.onNodeWithText(ctx.getString(R.string.transport_failed)).assertExists()
        compose.runOnIdle { current.value = view().copy(requestError = "outcome unknown") }
        compose.onNodeWithText(ctx.getString(R.string.transport_request_failed)).assertExists()
        // A newer automatic candidate cannot inherit an older operation's applied badge.
        val old = view(applied = true, sent = "42")
        compose.runOnIdle {
            current.value = old.copy(status = old.status!!.copy(accepted = p.copy(revision = "4"), pending = true,
                receipts = old.status!!.receipts + TransportPolicyReceipt(p.copy(revision = "4"), false, null, "none")))
        }
        compose.onNodeWithText(ctx.getString(R.string.transport_pending)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.transport_last_operation,
            ctx.getString(R.string.transport_first_sent))).assertExists()
    }
    @Test fun unknownUnavailableStoppedAndBusyStatesDisableWrites() {
        val current = mutableStateOf<TransportPolicyView?>(null)
        compose.setContent { MaterialTheme { TransportPolicyControls(current.value,
            { error("Unavailable must not write") }, { error("Unavailable must not write") },
            { error("Unavailable must not write") }) } }
        val states = listOf(null, view().copy(refreshing = true), view().copy(submitting = true),
            view().copy(error = "authentication failed"),
            view().let { it.copy(status = it.status!!.copy(liveControlAvailable = false)) },
            view().let { it.copy(status = it.status!!.copy(stopped = true)) })
        for (state in states) {
            compose.runOnIdle { current.value = state }
            compose.onNodeWithTag("transportAutomaticBitrate").assertIsNotEnabled()
            compose.onNodeWithTag("transportAutomaticFec").assertIsNotEnabled()
            compose.onNodeWithTag("transportManualControl").assertIsNotEnabled()
        }
    }
}
