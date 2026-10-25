package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAdjustmentQueueTest {
    @Test
    fun reopenedMenuCanRestoreTheLatestSubmittedTargetWhileTheQueueKeepsRunning() {
        val queue = BitrateAdjustmentQueue()
        val first = queue.submit(30_000, 20_000)!!
        assertEquals(30_000, queue.latestTargetKbps)
        assertNull(queue.submit(40_000, 20_000))
        assertEquals("Reopening restores the queued target, not the older active request", 40_000, queue.latestTargetKbps)

        val next = queue.complete(first, 30_000, userTracking = false)!!.nextRequest!!
        assertEquals(40_000, queue.latestTargetKbps)
        assertNull(queue.submit(50_000, 30_000))
        assertEquals("A selection in the reopened menu replaces the pending target", 50_000, queue.latestTargetKbps)
        val latest = queue.complete(next, 40_000, userTracking = false)!!.nextRequest!!
        queue.complete(latest, 50_000, userTracking = false)
        assertNull(queue.latestTargetKbps)
    }

    @Test
    fun obsoleteConnectionDiscardsPendingTargetsAndRejectsItsLateCallback() {
        val queue = BitrateAdjustmentQueue()
        val old = queue.submit(30_000, 20_000)!!
        queue.submit(40_000, 20_000)
        queue.discard()

        assertFalse(queue.isBusy)
        assertNull(queue.latestTargetKbps)
        assertNull(queue.complete(old, 30_000, userTracking = false))
        val fresh = queue.submit(50_000, 20_000)!!
        assertNull(queue.complete(old, 30_000, userTracking = false))
        assertEquals(50_000, queue.latestTargetKbps)
        assertTrue(queue.complete(fresh, 50_000, userTracking = false)!!.settleSelection)
    }

    @Test
    fun rapidSubmissionsSendOnlyTheLatestTargetAfterTheActiveRequest() {
        val queue = BitrateAdjustmentQueue()
        val first = queue.submit(30_000, 20_000)!!
        assertNull(queue.submit(40_000, 20_000))
        assertNull(queue.submit(50_000, 20_000))

        val intermediate = queue.complete(first, 30_000, userTracking = false)!!
        assertEquals(50_000, intermediate.nextRequest!!.bitrateKbps)
        assertFalse(intermediate.settleSelection)
        assertFalse(intermediate.reportResult)
        assertTrue(queue.isBusy)

        val final = queue.complete(intermediate.nextRequest, 50_000, userTracking = false)!!
        assertNull(final.nextRequest)
        assertTrue(final.settleSelection)
        assertTrue(final.reportResult)
        assertFalse(queue.isBusy)
    }

    @Test
    fun completionDuringANewDragDoesNotReplaceThePreviewOrReportAnOldResult() {
        val queue = BitrateAdjustmentQueue()
        val request = queue.submit(30_000, 20_000)!!
        val completion = queue.complete(request, 30_000, userTracking = true)!!

        assertNull(completion.nextRequest)
        assertFalse(completion.settleSelection)
        assertFalse(completion.reportResult)
        assertEquals(40_000, queue.submit(40_000, 30_000)!!.bitrateKbps)
    }

    @Test
    fun failedRequestStillSendsTheQueuedTargetAndFinalFailureRestoresConfirmedValue() {
        val queue = BitrateAdjustmentQueue()
        val first = queue.submit(30_000, 20_000)!!
        assertNull(queue.submit(40_000, 20_000))

        val retry = queue.complete(first, 20_000, userTracking = false)!!
        assertEquals(40_000, retry.nextRequest!!.bitrateKbps)
        assertFalse(retry.settleSelection)
        assertFalse(retry.reportResult)

        val failedFinal = queue.complete(retry.nextRequest, 20_000, userTracking = false)!!
        assertTrue(failedFinal.settleSelection)
        assertTrue(failedFinal.reportResult)
        assertFalse(queue.isBusy)
    }

    @Test
    fun returningToConfirmedTargetWhileARequestIsRunningRestoresItAfterTheRequest() {
        val queue = BitrateAdjustmentQueue()
        val first = queue.submit(30_000, 20_000)!!
        assertNull(queue.submit(20_000, 20_000))

        val completion = queue.complete(first, 30_000, userTracking = false)!!
        assertEquals(20_000, completion.nextRequest!!.bitrateKbps)
        assertFalse(completion.settleSelection)
    }

    @Test
    fun alreadySatisfiedTargetsAndStaleCallbacksCannotStartOrReplaceRequests() {
        val queue = BitrateAdjustmentQueue()
        assertNull(queue.submit(20_000, 20_000))
        val first = queue.submit(30_000, 20_000)!!
        assertNull(queue.submit(30_000, 20_000))
        val completion = queue.complete(first, 30_000, userTracking = false)!!
        assertNull(completion.nextRequest)
        assertTrue(completion.reportResult)

        val latest = queue.submit(40_000, 30_000)!!
        assertNull(queue.complete(first, 30_000, userTracking = false))
        assertTrue(queue.isBusy)
        assertTrue(queue.complete(latest, 40_000, userTracking = false)!!.settleSelection)
    }
}
