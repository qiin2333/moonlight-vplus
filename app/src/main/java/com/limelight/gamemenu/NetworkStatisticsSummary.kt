package com.limelight.gamemenu

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.nvstream.http.TransportPolicyView
import kotlinx.coroutines.delay
import java.util.Locale

/** Local expiry continues while a slow/failed HTTP poll has not returned. */
@Composable
internal fun NetworkStatisticsSummary(view: TransportPolicyView?) {
    var nowNs by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(view) {
        while (true) { nowNs = System.nanoTime(); delay(100) }
    }
    val statistics = remember(view, nowNs) { view?.networkStatistics() }
    val loss = statistics?.rawLossPercent?.let { String.format(Locale.getDefault(), "%.2f%%", it) }
        ?: stringResource(when (statistics?.reason) {
            "not_negotiated" -> R.string.transport_stats_no_feedback
            "no_samples" -> R.string.transport_stats_no_samples
            "feedback_stale" -> R.string.transport_stats_expired
            "history_truncated" -> R.string.transport_stats_truncated
            "coverage_incomplete" -> R.string.transport_stats_incomplete
            else -> R.string.transport_stats_unavailable
        })
    Text(stringResource(R.string.transport_stats_raw_loss, loss), fontSize = 10.sp)
    statistics?.windowDurationMs?.let { duration ->
        Text(stringResource(R.string.transport_stats_window, duration,
            statistics.receivedPackets + statistics.missingPackets + statistics.unknownPackets), fontSize = 10.sp)
    }
}
