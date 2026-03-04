package com.limelight.nvstream.mdns

interface MdnsDiscoveryListener {
    fun notifyComputerAdded(computer: MdnsComputer)
    fun notifyDiscoveryFailure(e: Exception)
}
