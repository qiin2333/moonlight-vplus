package com.limelight.binding.input

import org.junit.Assert.*
import org.junit.Test

class UsbControllerHandoffTest {
    private val ds5 = UsbControllerHandoff.Identity(0x054c, 0x0ce6)

    @Test fun singlePadStopsKeepingAnEmptyCompatibilitySlot() {
        val state = UsbControllerHandoff()
        val lease = state.acquire("pico", ds5, true)
        assertEquals(0, state.activeMask(false, 0, 0, 0, 0).toInt())
        assertTrue(state.release(lease))
        assertEquals(0, state.activeMask(false, 0, 0, 0, 0).toInt())
        assertEquals(1, state.activeMask(false, 0, 0, 1, 0).toInt())
    }

    @Test fun sharedSlotPreservesAnotherPadAndScreenInput() {
        val state = UsbControllerHandoff().apply { acquire("pico", ds5, true) }
        assertEquals(1, state.activeMask(false, 0, 0, 1, 0).toInt())
        assertEquals(1, state.activeMask(false, 0, 0, 0, 1).toInt())
        assertEquals(0b1010, state.activeMask(true, 0b1000, 0, 0b0010, 0).toInt())
    }

    @Test fun sharingAKeyboardDoesNotWithdrawAnIdleGamepad() {
        val state = UsbControllerHandoff().apply { acquire("keyboard", ds5, false) }
        assertEquals(1, state.activeMask(false, 0, 0, 0, 0).toInt())
    }

    @Test fun identicalDevicesRemainBlockedUntilTheLastLeaseReturns() {
        val state = UsbControllerHandoff()
        val a = state.acquire("a", ds5, true)
        val b = state.acquire("b", ds5, true)
        state.release(a)
        assertTrue(state.blocks(ds5))
        assertFalse(state.blocksPath("a"))
        assertTrue(state.blocksPath("b"))
        state.release(b)
        assertFalse(state.blocks(ds5))
    }

    @Test fun lateReleaseCannotCancelANewShareOfTheSamePath() {
        val state = UsbControllerHandoff()
        val old = state.acquire("pico", ds5, true)
        state.release(old)
        state.acquire("pico", ds5, true)
        assertFalse(state.release(old))
        assertTrue(state.blocksPath("pico"))
    }

    @Test fun aLeaseFromAnotherSessionCannotReleaseThisSession() {
        val old = UsbControllerHandoff().acquire("pico", ds5, true)
        val fresh = UsbControllerHandoff().apply { acquire("pico", ds5, true) }
        assertFalse(fresh.release(old))
        assertTrue(fresh.blocks(ds5))
    }

    @Test fun unpressedInitialPadIsRemovedWithoutChangingAnotherSlot() {
        val slots = InitialControllerSlots().apply { add("pico", 0); add("local", 1) }
        assertEquals(0, slots.remove("pico"))
        assertEquals(2, slots.mask)
        assertEquals(1, slots.slot("local"))
    }

    @Test fun firstInputInDifferentOrderKeepsInitialProvenance() {
        val slots = InitialControllerSlots().apply { add("a", 0); add("b", 1); add("c", 2) }
        slots.consume("b", 0)
        assertNull(slots.slot("b"))
        assertEquals(1, slots.slot("a"))
        slots.remove("a")
        assertEquals(4, slots.mask)
    }

    @Test fun pairedInputsConsumeOneInitialSlot() {
        val slots = InitialControllerSlots().apply { add("left", 0); add("right", 0); add("pico", 1) }
        slots.consume("left", 0)
        assertNull(slots.slot("left"))
        assertNull(slots.slot("right"))
        assertEquals(2, slots.mask)
    }

    @Test fun initialDevicesStillAssignPlayerOneToTheFirstInput() {
        val slots = InitialControllerSlots().apply { add("a", 0); add("b", 1) }
        assertEquals(0, slots.allocationOrder("b").first())
        slots.consume("b", 0)
        assertEquals(1, slots.slot("a"))
        assertNull(slots.slot("b"))
    }

    @Test fun restoredSourceDoesNotTakeAnUnpressedDevicesInitialSlot() {
        val slots = InitialControllerSlots().apply { add("unpressed", 0) }
        assertEquals(1, slots.allocationOrder("restored").first())
        assertEquals(0, slots.allocationOrder("unpressed").first())
    }
}
