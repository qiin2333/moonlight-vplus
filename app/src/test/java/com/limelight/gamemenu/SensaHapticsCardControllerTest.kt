package com.limelight.gamemenu

import com.limelight.preferences.SensaStrengthPreferences
import org.junit.Assert.*
import org.junit.Test

class SensaHapticsCardControllerTest {
    private class Access : SensaHapticsCardAccess {
        var state = SensaHapticsCardState(appliedEnabled = false)
        var reads = 0
        val writes = mutableListOf<String>()
        override fun readState(): SensaHapticsCardState { reads++; return state }
        override fun setEnabled(enabled: Boolean) {
            writes += "enabled:$enabled"
            state = state.copy(requestedEnabled = enabled)
        }
        override fun setMode(mode: String) { writes += "mode:$mode"; state = state.copy(mode = mode) }
        override fun setStrength(value: Float) { writes += "strength:$value"; state = state.copy(strength = value) }
        override fun setFrequency(value: Float) { writes += "frequency:$value"; state = state.copy(frequency = value) }
    }

    private class Scheduler : SensaHapticsCardScheduler {
        val pending = linkedMapOf<Runnable, Long>()
        override fun postDelayed(action: Runnable, delayMs: Long) {
            check(action !in pending) { "Duplicate poll" }
            pending[action] = delayMs
        }
        override fun remove(action: Runnable) { pending.remove(action) }
        fun tick() {
            val action = pending.keys.single()
            pending.remove(action)
            action.run()
        }
    }

    @Test fun hiddenCardsNeverScheduleAndShowingRefreshesChanges() {
        val access = Access()
        val scheduler = Scheduler()
        val controller = SensaHapticsCardController(access, scheduler)
        val updates = mutableListOf<SensaHapticsCardState>()
        controller.start(visible = false, onStateChanged = updates::add)
        assertTrue(scheduler.pending.isEmpty())
        access.state = access.state.copy(strength = 35f)
        controller.setVisible(true)
        assertEquals(35f, updates.last().strength)
        assertEquals(1000L, scheduler.pending.values.single())
        controller.setVisible(false)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun enableAndDisableReconcileServiceThenSlowDownPolling() {
        val access = Access()
        val scheduler = Scheduler()
        val controller = SensaHapticsCardController(access, scheduler)
        val updates = mutableListOf<SensaHapticsCardState>()
        controller.start(onStateChanged = updates::add)
        controller.setEnabled(true)
        assertEquals(listOf("enabled:true"), access.writes)
        assertTrue(updates.last().pending)
        assertEquals(100L, scheduler.pending.values.single())
        access.state = access.state.copy(appliedEnabled = true)
        scheduler.tick()
        assertFalse(updates.last().pending)
        assertEquals(1000L, scheduler.pending.values.single())
        controller.setEnabled(false)
        assertTrue(updates.last().pending)
        access.state = access.state.copy(appliedEnabled = false)
        scheduler.tick()
        assertFalse(updates.last().pending)
    }

    @Test fun disposeStopsQueuedWorkAndReopenReadsFreshState() {
        val access = Access()
        val scheduler = Scheduler()
        val controller = SensaHapticsCardController(access, scheduler)
        val oldUpdates = mutableListOf<SensaHapticsCardState>()
        controller.start(onStateChanged = oldUpdates::add)
        val oldPoll = scheduler.pending.keys.single()
        controller.dispose()
        val reads = access.reads
        oldPoll.run()
        assertEquals(reads, access.reads)
        assertTrue(scheduler.pending.isEmpty())
        access.state = access.state.copy(frequency = 180f)
        val updates = mutableListOf<SensaHapticsCardState>()
        controller.start(onStateChanged = updates::add)
        assertEquals(180f, updates.last().frequency)
        assertEquals(1, oldUpdates.size)
        assertEquals(1, scheduler.pending.size)
    }

    @Test fun tuningWritesRefreshTheSnapshotWithoutDuplicatingPolls() {
        val access = Access()
        val scheduler = Scheduler()
        val controller = SensaHapticsCardController(access, scheduler)
        controller.start { }
        controller.setMode(SensaStrengthPreferences.RUMBLE_ONLY)
        controller.setStrength(45f)
        controller.setFrequency(220f)
        assertEquals(listOf("mode:rumble_only", "strength:45.0", "frequency:220.0"), access.writes)
        assertEquals(45f, controller.snapshot().strength)
        assertEquals(220f, controller.snapshot().frequency)
        assertEquals(SensaStrengthPreferences.RUMBLE_ONLY, controller.snapshot().mode)
        assertEquals(1, scheduler.pending.size)
        controller.start { }
        assertEquals(1, scheduler.pending.size)
    }
}
