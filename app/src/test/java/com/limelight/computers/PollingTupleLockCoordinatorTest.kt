package com.limelight.computers

import com.limelight.nvstream.http.ComputerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PollingTupleLockCoordinatorTest {
    @Test
    fun replacementWithSameUuidReceivesUpdateInsteadOfDetachedTuple() {
        val uuid = "same-host"
        val oldTuple = PollingTuple(computer(uuid), null)
        val replacement = PollingTuple(computer(uuid), null)
        val tuples = LinkedList<PollingTuple>().apply { add(oldTuple) }
        val coordinator = PollingTupleLockCoordinator(tuples)
        val updateFinished = CountDownLatch(1)

        val worker = Thread {
            coordinator.withCurrent(uuid) { tuple ->
                tuple.computer.state = ComputerDetails.State.UNKNOWN
            }
            updateFinished.countDown()
        }

        synchronized(oldTuple.networkLock) {
            worker.start()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadlineNanos) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, worker.state)
            synchronized(tuples) {
                tuples.clear()
                tuples.add(replacement)
            }
        }

        assertTrue(updateFinished.await(1, TimeUnit.SECONDS))
        assertEquals(ComputerDetails.State.ONLINE, oldTuple.computer.state)
        assertEquals(ComputerDetails.State.UNKNOWN, replacement.computer.state)
    }

    @Test
    fun missingUuidDoesNotRunAction() {
        val tuples = LinkedList<PollingTuple>()
        val coordinator = PollingTupleLockCoordinator(tuples)
        var called = false

        assertFalse(coordinator.withCurrent("missing") { called = true })
        assertFalse(called)
    }

    private fun computer(uuid: String) = ComputerDetails().apply {
        this.uuid = uuid
        state = ComputerDetails.State.ONLINE
    }
}
