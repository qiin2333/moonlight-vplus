package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAdjustmentQueueTest {
    @Test
    fun rapidTargetsAreCoalescedEvenWhenTheActiveRequestFails() {
        for ((confirmed, target) in listOf(30_000 to 50_000, null to 20_000)) {
            val queue = BitrateAdjustmentQueue()
            val first = queue.submit(30_000)!!
            assertNull(queue.submit(40_000))
            assertNull(queue.submit(target))
            assertEquals(target, queue.latestTargetKbps)

            val intermediate = queue.complete(first, confirmed, userTracking = false)!!
            assertEquals(target, intermediate.nextRequest!!.bitrateKbps)
            assertFalse(intermediate.settleSelection)
            assertFalse(intermediate.reportResult)

            val final = queue.complete(intermediate.nextRequest, target, userTracking = false)!!
            assertTrue(final.settleSelection)
            assertTrue(final.reportResult)
            assertFalse(queue.isBusy)
        }
    }

    @Test
    fun completionDuringANewDragDoesNotReplaceThePreviewOrReportAnOldResult() {
        val queue = BitrateAdjustmentQueue()
        val request = queue.submit(30_000)!!
        val completion = queue.complete(request, 30_000, userTracking = true)!!

        assertFalse(completion.settleSelection)
        assertFalse(completion.reportResult)
    }

    @Test
    fun discardedCallbacksCannotCompleteANewRequestWithTheSameTarget() {
        val queue = BitrateAdjustmentQueue()
        val old = queue.submit(30_000)!!
        queue.submit(40_000)
        queue.discard()
        val fresh = queue.submit(30_000)!!
        assertNull(queue.complete(old, 30_000, userTracking = false))
        assertTrue(queue.complete(fresh, 30_000, userTracking = false)!!.settleSelection)
    }

    @Test
    fun explicitApplyAlwaysStartsEvenIfTheCachedValueMatchesButConfirmedPendingTargetsAreDeduplicated() {
        val queue = BitrateAdjustmentQueue()
        val first = queue.submit(20_000)!!
        assertNull(queue.submit(20_000))
        val completion = queue.complete(first, 20_000, userTracking = false)!!
        assertNull(completion.nextRequest)

        // An explicit apply must send again: ABR may have changed the host while dragging.
        assertTrue(queue.submit(20_000) != null)
        assertNull(queue.complete(first, 20_000, userTracking = false))
    }
}
