package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDriverExitCoordinatorTest {
    @Test
    fun releasesUsbBeforeFinishingActivity() {
        val events = mutableListOf<String>()

        UsbDriverExitCoordinator.exit(
            isFinishing = false,
            releaseUsb = { events += "release" },
            finishActivity = { events += "finish" }
        )

        assertEquals(listOf("release", "finish"), events)
    }

    @Test
    fun ignoresDuplicateExitWhileActivityIsFinishing() {
        val events = mutableListOf<String>()

        UsbDriverExitCoordinator.exit(
            isFinishing = true,
            releaseUsb = { events += "release" },
            finishActivity = { events += "finish" }
        )

        assertEquals(emptyList<String>(), events)
    }
}
