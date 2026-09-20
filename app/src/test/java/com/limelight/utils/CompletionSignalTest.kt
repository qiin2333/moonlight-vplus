package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class CompletionSignalTest {
    @Test fun lateListenerRunsInlineAfterSuccess() {
        val signal = CompletionSignal()
        signal.complete()
        var calls = 0
        signal.whenComplete { error ->
            assertNull(error)
            calls++
        }
        assertEquals(1, calls)
        assertTrue(signal.isDone)
        assertFalse(signal.isFailed)
    }

    @Test fun listenerRegisteredBeforeCompletionRunsOnCompletingThread() {
        val signal = CompletionSignal()
        val fired = CountDownLatch(1)
        var listenerThread: Thread? = null
        signal.whenComplete { error ->
            listenerThread = Thread.currentThread()
            assertNull(error)
            fired.countDown()
        }
        thread(name = "completer") { signal.complete() }
        assertTrue(fired.await(2, TimeUnit.SECONDS))
        assertFalse("listener must not run on the registering thread",
            listenerThread === Thread.currentThread())
    }

    @Test fun failurePropagatesToAwaitAndLateListeners() {
        val signal = CompletionSignal()
        val failure = IllegalStateException("stop failed")
        signal.completeExceptionally(failure)
        assertTrue(signal.isDone)
        assertTrue(signal.isFailed)
        val thrown = assertThrows(IllegalStateException::class.java) { signal.await() }
        assertEquals(failure, thrown)
        var observed: Throwable? = null
        signal.whenComplete { observed = it }
        assertEquals(failure, observed)
    }

    @Test fun awaitTimesOutWhilePendingAndSucceedsAfterCompletion() {
        val signal = CompletionSignal()
        assertThrows(TimeoutException::class.java) { signal.await(1) }
        assertFalse(signal.isDone)
        signal.complete()
        signal.await(1_000)
    }

    @Test fun doubleCompletionIsIgnored() {
        val signal = CompletionSignal()
        signal.complete()
        signal.completeExceptionally(IllegalStateException("late failure"))
        assertFalse(signal.isFailed)
        signal.await()
    }

    @Test fun allOfEmptyCompletesImmediately() {
        val all = CompletionSignal.allOf(emptyList())
        assertTrue(all.isDone)
        assertFalse(all.isFailed)
    }

    @Test fun allOfWaitsForEverySignalAndReportsFirstFailure() {
        val first = CompletionSignal()
        val second = CompletionSignal()
        val all = CompletionSignal.allOf(listOf(first, second))
        assertFalse(all.isDone)
        first.complete()
        assertFalse(all.isDone)
        val failure = IllegalStateException("second failed")
        second.completeExceptionally(failure)
        assertTrue(all.isDone)
        assertTrue(all.isFailed)
        assertEquals(failure, assertThrows(IllegalStateException::class.java) { all.await() })
    }

    @Test fun allOfSucceedsWhenEverySignalSucceeds() {
        val signals = List(3) { CompletionSignal() }
        val all = CompletionSignal.allOf(signals)
        signals.forEach { it.complete() }
        all.await(1_000)
        assertFalse(all.isFailed)
    }
}
