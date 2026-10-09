package com.limelight.computers

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StunQueryCacheTest {
    @Test fun onlyOneQueryCanRunWithoutQueuingOtherHosts() {
        val cache = StunQueryCache<String>()
        val first = cache.begin("wifi", 0)!!
        assertNull(cache.begin("wifi", 1))
        assertNull(cache.begin("ethernet", 1))
        cache.finish(first, "203.0.113.1", 10)
        assertEquals("203.0.113.1", cache.cached("wifi", 11))
        assertNull(cache.begin("wifi", 11))
    }

    @Test fun failuresAreThrottledButAnotherNetworkCanQuery() {
        val cache = StunQueryCache<String>()
        val first = cache.begin("wifi", 0)!!
        cache.finish(first, null, 10)
        assertNull(cache.begin("wifi", 10 + StunQueryCache.FAILURE_RETRY_MS - 1))
        assertNotNull(cache.begin("ethernet", 11))
    }

    @Test fun failureCanRetryAtItsDeadline() {
        val cache = StunQueryCache<String>()
        cache.finish(cache.begin("wifi", 0)!!, null, 10)
        assertNotNull(cache.begin("wifi", 10 + StunQueryCache.FAILURE_RETRY_MS))
    }

    @Test fun successfulCacheExpiresAndIsNetworkScoped() {
        val cache = StunQueryCache<String>()
        cache.finish(cache.begin("wifi", 0)!!, "203.0.113.1", 10)
        assertNull(cache.cached("ethernet", 11))
        assertEquals("203.0.113.1", cache.cached("wifi", 10 + StunQueryCache.SUCCESS_TTL_MS - 1))
        assertNull(cache.cached("wifi", 10 + StunQueryCache.SUCCESS_TTL_MS))
        assertNotNull(cache.begin("wifi", 10 + StunQueryCache.SUCCESS_TTL_MS))
    }

    @Test fun disablingInvalidatesTheResultButKeepsBlockedWorkerSlot() {
        val cache = StunQueryCache<String>()
        val first = cache.begin("wifi", 0)!!
        cache.invalidate()
        assertFalse(cache.isCurrent(first))
        assertNull(cache.begin("wifi", 1))
        assertFalse(cache.finish(first, "203.0.113.1", 2))
        assertNull(cache.cached("wifi", 3))
        val second = cache.begin("wifi", 3)!!
        assertFalse(cache.finish(first, null, 4))
        assertTrue(cache.isCurrent(second))
    }

    @Test fun disablingClearsSuccessAndFailureCooldown() {
        val cache = StunQueryCache<String>()
        cache.finish(cache.begin("wifi", 0)!!, "203.0.113.1", 1)
        cache.invalidate()
        assertNull(cache.cached("wifi", 2))
        val second = cache.begin("wifi", 2)!!
        cache.finish(second, null, 3)
        cache.invalidate()
        assertNotNull(cache.begin("wifi", 4))
    }

    @Test fun parallelStartsReserveExactlyOneQuery() {
        val cache = StunQueryCache<String>()
        val start = CountDownLatch(1)
        val attempts = ConcurrentLinkedQueue<StunQueryCache.Attempt<String>>()
        val workers = List(16) {
            Thread {
                start.await()
                cache.begin("wifi", 0)?.let(attempts::add)
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(2000) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, attempts.size)
    }

    @Test fun resultPublicationDoesNotWaitForAnObjectMonitor() {
        val cache = StunQueryCache<String>()
        val completed = CountDownLatch(1)
        val worker = Thread {
            val attempt = cache.begin("wifi", 0)!!
            cache.finish(attempt, "203.0.113.1", 1)
            assertEquals("203.0.113.1", cache.cached("wifi", 2))
            completed.countDown()
        }
        try {
            synchronized(cache) {
                worker.start()
                assertTrue("STUN result publication must not require a monitor", completed.await(1, TimeUnit.SECONDS))
            }
        } finally {
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun invalidationRacingWithCompletionCannotRestoreOldResult() {
        repeat(100) {
            val cache = StunQueryCache<String>()
            val attempt = cache.begin("wifi", 0)!!
            val start = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val workers = listOf<() -> Unit>(
                { cache.finish(attempt, "203.0.113.1", 1) },
                { cache.invalidate() }
            ).map { action ->
                Thread {
                    try {
                        start.await()
                        action()
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }.apply { start() }
            }
            start.countDown()
            workers.forEach { it.join(2000) }
            assertTrue(workers.none { it.isAlive })
            assertTrue(failures.isEmpty())
            assertFalse(cache.isCurrent(attempt))
            assertNull(cache.cached("wifi", 2))
            val next = cache.begin("wifi", 2)!!
            assertFalse(cache.finish(attempt, null, 3))
            assertTrue(cache.isCurrent(next))
        }
    }
}
