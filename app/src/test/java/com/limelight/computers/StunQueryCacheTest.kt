package com.limelight.computers

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
}
