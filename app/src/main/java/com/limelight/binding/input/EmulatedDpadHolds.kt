package com.limelight.binding.input

import java.util.IdentityHashMap

/** Keeps a host key down until every mouse-emulating controller releases it. */
internal class EmulatedDpadHolds {
    private val masks = IdentityHashMap<Any, Int>()

    var heldMask: Int = 0
        private set

    /** Returns the directions whose combined pressed state changed. */
    fun update(context: Any, mask: Int): Int {
        if (mask == 0) {
            masks.remove(context)
        } else {
            masks[context] = mask
        }

        val combinedMask = masks.values.fold(0) { combined, held -> combined or held }
        val changedMask = heldMask xor combinedMask
        heldMask = combinedMask
        return changedMask
    }
}
