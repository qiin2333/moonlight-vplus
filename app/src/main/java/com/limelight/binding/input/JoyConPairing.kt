package com.limelight.binding.input

/** First-generation Nintendo Joy-Cons only; do not guess from Bluetooth display names. */
internal enum class JoyConSide { LEFT, RIGHT }

internal fun joyConSide(vendorId: Int, productId: Int): JoyConSide? =
    if (vendorId != 0x057e) null else when (productId) {
        0x2006 -> JoyConSide.LEFT
        0x2007 -> JoyConSide.RIGHT
        else -> null
    }

/** Preserve established pairs; only automatically pair an unambiguous remaining left/right. */
internal class JoyConPairing {
    private val partners = mutableMapOf<Int, Int>()

    fun update(devices: Map<Int, JoyConSide>) {
        partners.entries.removeAll { (id, peer) ->
            id !in devices || peer !in devices || devices[id] == devices[peer]
        }
        val available = devices.filterKeys { it !in partners }
        val left = available.filterValues { it == JoyConSide.LEFT }.keys.singleOrNull()
        val right = available.filterValues { it == JoyConSide.RIGHT }.keys.singleOrNull()
        if (left != null && right != null) {
            partners[left] = right
            partners[right] = left
        }
    }

    fun partner(id: Int): Int? = partners[id]
    val pairCount: Int get() = partners.size / 2
}
