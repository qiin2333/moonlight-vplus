package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfOverlayHealthMigrationTest {
    @Test fun migrationPreservesMetricsAndHealthChoice() {
        val metrics = setOf("resolution", "clock")
        assertEquals(metrics + "health", PerfOverlayDisplayItemsPreference.migrateHealthSelection(metrics, true))
        assertEquals(metrics, PerfOverlayDisplayItemsPreference.migrateHealthSelection(metrics + "health", false))
        assertEquals(emptySet<String>(), PerfOverlayDisplayItemsPreference.migrateHealthSelection(emptySet(), false))
    }
}
