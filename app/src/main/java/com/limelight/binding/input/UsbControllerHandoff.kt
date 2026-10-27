package com.limelight.binding.input

/** Session-local ownership. Tokens prevent an old release from unblocking a new share. */
internal class UsbControllerHandoff {
    data class Identity(val vendorId: Int, val productId: Int)
    class Lease internal constructor(
        val path: String, val identity: Identity
    )

    private val leases = mutableSetOf<Lease>()
    private var compatibilitySlotWithdrawn = false

    @Synchronized fun acquire(path: String, identity: Identity, gamepad: Boolean): Lease =
        Lease(path, identity).also {
            leases.add(it)
            if (gamepad) compatibilitySlotWithdrawn = true
        }

    @Synchronized fun release(lease: Lease): Boolean = leases.remove(lease)
    @Synchronized fun blocks(identity: Identity): Boolean = leases.any { it.identity == identity }
    @Synchronized fun blocksPath(path: String?): Boolean = path != null && leases.any { it.path == path }

    @Synchronized fun activeMask(multi: Boolean, reserved: Int, initial: Int, local: Int, screen: Int): Short =
        (if (multi) reserved or initial or local or screen
        // Releasing a lease after unplug must not recreate an empty compatibility pad.
        // Freshly enumerated/assigned input or an enabled screen source reactivates it.
        else if (compatibilitySlotWithdrawn) (if (initial != 0 || local != 0) 1 else 0) or screen
        else 1).toShort()
}

/** Initial slots retain their device provenance even before the first input report. */
internal class InitialControllerSlots {
    private val slots = linkedMapOf<String, Int>()
    @Synchronized fun add(source: String, slot: Int) { if (slot in 0..15) slots[source] = slot }
    @Synchronized fun slot(source: String): Int? = slots[source]
    @Synchronized fun remove(source: String): Int? = slots.remove(source)
    @get:Synchronized
    val mask: Int get() = slots.values.fold(0) { mask, slot -> mask or (1 shl slot) }

    @Synchronized fun allocationOrder(source: String?): List<Int> {
        val numbers = (0..15).toList()
        // Keep first-input assignment for initial devices. A restored/new source
        // must avoid slots still reserved by unpressed initial devices.
        if (source in slots) return numbers
        val initialMask = mask
        return numbers.sortedBy { if (initialMask and (1 shl it) == 0) 0 else 1 }
    }

    /** The first-used pad can claim a different slot from its enumeration order. */
    @Synchronized fun consume(source: String, assignedSlot: Int) {
        val initialSlot = slots[source] ?: return
        val group = slots.filterValues { it == initialSlot }.keys.toSet()
        slots.entries.filter { it.value == assignedSlot && it.key !in group }
            .forEach { it.setValue(initialSlot) }
        group.forEach(slots::remove)
    }
}
