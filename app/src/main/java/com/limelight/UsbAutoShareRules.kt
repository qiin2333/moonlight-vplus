package com.limelight

/** Each computer UUID has its own opt-in list; legacy host enable flags grant no device rules. */
internal class UsbAutoShareRules(
    hostId: String,
    private val read: (String) -> Set<String>,
    private val write: (String, Set<String>) -> Unit,
) {
    private val key = "auto_devices_v1_$hostId"

    fun load(): Set<UsbAutoShareIdentity> = read(key).mapNotNull(UsbAutoShareIdentity::decode).toSet()
    fun save(rules: Set<UsbAutoShareIdentity>) = write(key, rules.map { it.encode() }.toSet())
}
