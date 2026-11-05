package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSourceInitializationTest {
    @Test
    fun referencingASourceBeforeItsRegistryStillResolvesEverySource() {
        val defaultSource = BackgroundSource.Auto
        assertEquals(defaultSource, BackgroundSource.fromPrefValue(null))
        assertEquals(defaultSource, BackgroundSource.fromPrefValue("unknown-source"))
        listOf(BackgroundSource.Auto, BackgroundSource.Pipw, BackgroundSource.Picsum,
            BackgroundSource.Api, BackgroundSource.Local, BackgroundSource.None).forEach {
            assertEquals(it, BackgroundSource.fromPrefValue(it.prefValue))
        }
    }
}
