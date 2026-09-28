package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrownStoreDateParsingTest {
    @Test
    fun numericOffsetsKeepTimeOfDayForSorting() {
        val earlier = parseStoreUpdatedAt("2026-09-01T08:00:00+02:00")!!.time
        val later = parseStoreUpdatedAt("2026-09-01T09:00:00+02:00")!!.time
        assertEquals(60 * 60 * 1000L, later - earlier)
        assertEquals(earlier, parseStoreUpdatedAt("2026-09-01T06:00:00Z")!!.time)
    }

    @Test
    fun timestampsMustBeFullyConsumed() {
        assertNull(parseStoreUpdatedAt("2026-09-01T08:00:00+02:00extra"))
        assertNull(parseStoreUpdatedAt("2026-09-01suffix"))
        assertNotNull(parseStoreUpdatedAt("1970-01-01"))
    }
}
