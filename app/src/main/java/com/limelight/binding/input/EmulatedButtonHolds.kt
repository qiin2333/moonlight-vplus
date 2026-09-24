package com.limelight.binding.input

import java.util.IdentityHashMap

/** Keeps an emulated host input held until every controller releases it. */
internal class EmulatedButtonHolds {
    private val masks = IdentityHashMap<Any, Int>()

    var heldMask: Int = 0
        private set

    /** Returns the flags whose combined pressed state changed. */
    fun update(context: Any, mask: Int): Int {
        if ((masks[context] ?: 0) == mask) return 0

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
