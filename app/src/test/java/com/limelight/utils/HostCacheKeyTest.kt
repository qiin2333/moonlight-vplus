package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCacheKeyTest {
    @Test
    fun canonicalUuidKeepsExistingPathNameCase() {
        assertEquals(
            "550E8400-E29B-41D4-A716-446655440000",
            HostCacheKey.fromUuid("550E8400-E29B-41D4-A716-446655440000")
        )
    }

    @Test
    fun sunshineIdentifierKeepsExistingPathEvenWithNonRfcBits() {
        assertEquals(
            "01234567-89AB-CDEF-0123-456789ABCDEF",
            HostCacheKey.fromUuid("01234567-89AB-CDEF-0123-456789ABCDEF")
        )
    }

    @Test
    fun legacyIdentifierUsesStableDigest() {
        val expected = "legacy-5bd960e40874e5746bab710cfeb5d7d79a7d0e7f8177ce91c1a966b64e08223a"

        assertEquals(expected, HostCacheKey.fromUuid("legacy-host"))
        assertEquals(expected, HostCacheKey.fromUuid("legacy-host"))
    }

    @Test
    fun lenientJavaUuidSyntaxDoesNotBecomeAStandardUuid() {
        val cacheKey = HostCacheKey.fromUuid("1-1-1-1-1")

        assertTrue(cacheKey?.matches(Regex("legacy-[0-9a-f]{64}")) == true)
    }

    @Test
    fun pathLikeIdentifiersMapToDistinctSafeKeys() {
        val slashKey = HostCacheKey.fromUuid("../probe")
        val backslashKey = HostCacheKey.fromUuid("..\\probe")

        assertTrue(slashKey?.matches(Regex("legacy-[0-9a-f]{64}")) == true)
        assertTrue(backslashKey?.matches(Regex("legacy-[0-9a-f]{64}")) == true)
        assertNotEquals(slashKey, backslashKey)
    }

    @Test
    fun missingIdentifierDoesNotCreateSharedKey() {
        assertNull(HostCacheKey.fromUuid(null))
        assertNull(HostCacheKey.fromUuid(""))
        assertNull(HostCacheKey.fromUuid("   "))
    }
}
