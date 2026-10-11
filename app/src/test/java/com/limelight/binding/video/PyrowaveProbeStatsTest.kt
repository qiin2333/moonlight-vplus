package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PyrowaveProbeStatsTest {
    private fun metric(report: Map<String, Any?>, key: String): Map<*, *> = report[key] as Map<*, *>

    @Test fun unknownDisplayFeedbackIsNotReportedAsZeroBacklog() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 0, 0, 0, 0, 1000, "SUCCESS", null)
        val report = stats.snapshot()
        assertNull(report["display_timing_supported"])
        assertNull(metric(report, "unconfirmed_presents_frames")["lifetime_avg"])
        assertEquals(0.0, metric(report, "pending_decode_after_frames")["lifetime_avg"])
    }

    @Test fun growingDecodeQueueAndWaitRemainVisible() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 1, 4, 10_000, 2000, 16_000, "SUCCESS", null)
        stats.record(1_000_000_001, 2, 8, 12, 100_000, 3000, 20_000, "SUCCESS", null)
        val report = stats.snapshot()
        assertEquals(listOf(4.0, 12.0), metric(report, "pending_decode_after_frames")["series"])
        assertEquals(100.0, metric(report, "decode_queue_wait_ms")["lifetime_max"])
        assertEquals(2, (report["timeline"] as List<*>).size)
    }

    @Test fun availableDisplayFeedbackKeepsItsAsynchronousMeaning() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 0, 0, 0, 0, 1000, "SUCCESS", longArrayOf(100, 200, 300, 2, 400, 1, 7))
        val report = stats.snapshot()
        assertEquals(true, report["display_timing_supported"])
        assertEquals(2.0, metric(report, "unconfirmed_presents_frames")["lifetime_avg"])
        assertEquals(0.4, metric(report, "display_extra_wait_ms")["lifetime_avg"])
        assertTrue((report["present_feedback_scope"] as String).contains("not_exact_live_queue_depth"))
    }

    @Test fun incompleteFeedbackAndFrameGapsDoNotFabricateMeasurements() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 0, 0, null, null, 1000, "FRAME_DROPPED", longArrayOf(1))
        stats.record(2, 4, 0, 0, null, null, 1000, "SUCCESS", longArrayOf(0, 0, 0, -1, -1, 0, 0))
        val report = stats.snapshot()
        assertEquals(2L, report["frame_number_gaps"])
        assertNull(metric(report, "decode_queue_wait_ms")["lifetime_avg"])
        assertNull(metric(report, "unconfirmed_presents_frames")["lifetime_avg"])
        assertEquals(false, report["display_timing_supported"])
    }

    @Test fun longSessionCollectionIsBoundedButRetainsLifetimeTotals() {
        val stats = PyrowaveProbeStats(0)
        repeat(605) { index ->
            stats.record(index * 1_000_000_000L, index + 1, index, index, 0, 0, 1000, "SUCCESS", null)
        }
        val report = stats.snapshot()
        assertEquals(605L, report["frames"])
        assertEquals(5L, report["omitted_seconds"])
        assertEquals(600, (report["timeline"] as List<*>).size)
        assertEquals(240, metric(report, "pending_decode_after_frames")["samples"])
        assertEquals(605L, metric(report, "pending_decode_after_frames")["total_samples"])
    }

    @Test fun disabledNativeProbeCannotFabricateZeroWaitsOrFeedbackSupport() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 0, 0, null, null, 1000, "SUCCESS", longArrayOf(-1, -1, -1, -1, -1, -1, -1))
        val report = stats.snapshot()
        assertNull(report["display_timing_supported"])
        assertNull(metric(report, "swapchain_acquire_wait_ms")["lifetime_avg"])
        assertNull(metric(report, "gpu_completion_wait_ms")["lifetime_avg"])
        assertNull(metric(report, "present_call_ms")["lifetime_avg"])
    }

    @Test fun framesWithoutFreshDisplayFeedbackDoNotReuseThePreviousObservation() {
        val stats = PyrowaveProbeStats(0)
        stats.record(1, 1, 0, 0, null, null, 1000, "SUCCESS", longArrayOf(100, 200, 300, 4, 700, 1, 3))
        stats.record(2, 2, 0, 0, null, null, 1000, "SUCCESS", longArrayOf(100, 200, 300, -1, -1, 1, 3))
        val report = stats.snapshot()
        assertEquals(1L, metric(report, "unconfirmed_presents_frames")["total_samples"])
        assertEquals(1L, metric(report, "display_extra_wait_ms")["total_samples"])
        assertEquals(true, report["display_timing_supported"])
        assertEquals(100, report["display_feedback_query_interval_ms"])
    }
}
