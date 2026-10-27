package com.limelight

import org.json.JSONObject

/** Persistent USB identity. Android paths, device IDs and host tokens are deliberately absent. */
internal data class UsbAutoShareIdentity(
    val vendorId: Int, val productId: Int, val signature: String, val serial: String? = null,
) {
    val inputGroup: String get() = "$vendorId:$productId"

    fun sameType(other: UsbAutoShareIdentity): Boolean =
        vendorId == other.vendorId && productId == other.productId && signature == other.signature

    fun matches(other: UsbAutoShareIdentity): Boolean =
        sameType(other) && (serial == null || serial == other.serial)

    fun encode(): String = JSONObject().put("version", 1).put("vendor", vendorId)
        .put("product", productId).put("signature", signature).put("serial", serial).toString()

    companion object {
        fun decode(value: String): UsbAutoShareIdentity? = runCatching {
            val json = JSONObject(value)
            require(json.getInt("version") == 1)
            UsbAutoShareIdentity(json.getInt("vendor"), json.getInt("product"),
                json.getString("signature"), json.optString("serial").takeIf { it.isNotBlank() })
        }.getOrNull()
    }
}

/** Manual Stop sharing lasts for this game, including foreground returns and Activity recreation. */
internal class UsbAutoShareSession(suppressed: Collection<String> = emptyList()) {
    val suppressedGroups = suppressed.toMutableSet()

    fun allows(device: UsbAutoShareIdentity, rules: Collection<UsbAutoShareIdentity>, identicalCount: Int): Boolean =
        identicalCount == 1 && device.inputGroup !in suppressedGroups && rules.any { it.matches(device) }
}
