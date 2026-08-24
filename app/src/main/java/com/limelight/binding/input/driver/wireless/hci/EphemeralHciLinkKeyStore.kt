package com.limelight.binding.input.driver.wireless.hci

/** API 22 fallback: keeps Link Keys only for the current process and never writes plaintext. */
internal class EphemeralHciLinkKeyStore : HciLinkKeyStore {
    private val keys = HashMap<Long, HciLinkKey>()

    @Synchronized
    override fun load(address: HciBluetoothAddress): HciLinkKey? = keys[address.value]

    @Synchronized
    override fun save(address: HciBluetoothAddress, key: HciLinkKey) {
        keys[address.value] = HciLinkKey(key.value, key.type)
    }

    @Synchronized
    override fun remove(address: HciBluetoothAddress) {
        keys.remove(address.value)
    }
}
