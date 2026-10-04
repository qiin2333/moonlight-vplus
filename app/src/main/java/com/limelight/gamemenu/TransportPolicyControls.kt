package com.limelight.gamemenu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.nvstream.http.TransportPolicyView

/** The game card and device UI checks consume the same host-confirmed state. */
@Composable
internal fun TransportPolicyControls(
    view: TransportPolicyView?,
    onAutomaticBitrate: (Boolean) -> Unit,
    onAutomaticFec: (Boolean) -> Unit,
    onManual: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        val policy = view?.status?.accepted
        val confirmed = view?.status?.confirmed
        Text(stringResource(R.string.transport_total_limit), fontSize = 10.sp)
        if (policy != null) {
            Text(stringResource(if (policy.source == "googcc") R.string.transport_host_automatic
                else R.string.transport_host_manual), fontSize = 10.sp)
        }
        if (confirmed != null) {
            Text(stringResource(R.string.transport_applied_values, confirmed.totalKbps,
                confirmed.encoderKbps, confirmed.fecBase, confirmed.fecKey, confirmed.fecRecovery), fontSize = 10.sp)
        }
        val receipt = view?.status?.receipts?.find { it.policy.revision == policy?.revision }
        val label = when {
            view == null -> R.string.transport_unavailable
            view.submitting -> R.string.transport_requesting
            view.requestError != null -> R.string.transport_request_failed
            view.refreshing || view.error != null -> R.string.transport_status_unknown
            view.status?.liveControlAvailable != true -> R.string.transport_unavailable
            receipt?.failure != null && receipt.failure != "none" -> R.string.transport_failed
            receipt == null -> R.string.transport_receipt_missing
            receipt?.firstSentFrame != null -> R.string.transport_first_sent
            receipt?.encoderApplied == true -> R.string.transport_sdk_applied
            view.status?.pending == true -> R.string.transport_pending
            else -> R.string.transport_ready
        }
        Text(stringResource(label), fontSize = 10.sp)
        if (view?.requestRevision != null && !view.refreshing && view.error == null) {
            val requested = view.requestReceipt
            val operation = when {
                requested == null -> R.string.transport_receipt_missing
                requested.failure != "none" -> R.string.transport_failed
                requested.firstSentFrame != null -> R.string.transport_first_sent
                requested.encoderApplied -> R.string.transport_sdk_applied
                else -> R.string.transport_pending
            }
            Text(stringResource(R.string.transport_last_operation, stringResource(operation)), fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transport_auto_bitrate), modifier = Modifier.weight(1f), fontSize = 11.sp)
            Switch(checked = policy?.automatic?.bitrate == true, onCheckedChange = onAutomaticBitrate,
                enabled = view?.canSubmit == true, modifier = Modifier.testTag("transportAutomaticBitrate"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transport_auto_fec), modifier = Modifier.weight(1f), fontSize = 11.sp)
            Switch(checked = policy?.automatic?.fec == true, onCheckedChange = onAutomaticFec,
                enabled = view?.canSubmit == true && view.status?.automaticFecAvailable == true,
                modifier = Modifier.testTag("transportAutomaticFec"))
        }
        TextButton(onClick = onManual, enabled = view?.canSubmit == true,
            modifier = Modifier.testTag("transportManualControl")) {
            Text(stringResource(R.string.transport_manual))
        }
    }
}
