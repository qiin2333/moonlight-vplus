package com.limelight.nvstream.mdns

import com.limelight.LimeLog

import java.net.Inet4Address
import java.net.Inet6Address

abstract class MdnsDiscoveryAgent(protected var listener: MdnsDiscoveryListener) {

    protected val computers = HashSet<MdnsComputer>()

    abstract fun startDiscovery(discoveryIntervalMs: Int)

    abstract fun stopDiscovery()

    protected fun reportNewComputer(name: String, port: Int, v4Addrs: Array<Inet4Address>, v6Addrs: Array<Inet6Address>) {
        LimeLog.info("mDNS: $name has ${v4Addrs.size} IPv4 addresses")
        LimeLog.info("mDNS: $name has ${v6Addrs.size} IPv6 addresses")

        val v6GlobalAddr = getBestIpv6Address(v6Addrs)

        // Add a computer object for each IPv4 address reported by the PC
        for (v4Addr in v4Addrs) {
            synchronized(computers) {
                val computer = MdnsComputer(name, v4Addr, v6GlobalAddr, port)
                if (computers.add(computer)) {
                    listener.notifyComputerAdded(computer)
                }
            }
        }

        // If there were no IPv4 addresses, use IPv6 for registration
        if (v4Addrs.isEmpty()) {
            val v6LocalAddr = getLocalAddress(v6Addrs)
            if (v6LocalAddr != null || v6GlobalAddr != null) {
                val computer = MdnsComputer(name, v6LocalAddr, v6GlobalAddr, port)
                if (computers.add(computer)) {
                    listener.notifyComputerAdded(computer)
                }
            }
        }
    }

    fun getComputerSet(): List<MdnsComputer> {
        synchronized(computers) {
            return ArrayList(computers)
        }
    }

    companion object {
        private fun getLocalAddress(addresses: Array<Inet6Address>): Inet6Address? {
            for (addr in addresses) {
                if (addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    return addr
                }
                // fc00::/7 - ULAs
                if ((addr.address[0].toInt() and 0xfe) == 0xfc) {
                    return addr
                }
            }
            return null
        }

        private fun getLinkLocalAddress(addresses: Array<Inet6Address>): Inet6Address? {
            for (addr in addresses) {
                if (addr.isLinkLocalAddress) {
                    LimeLog.info("Found link-local address: ${addr.hostAddress}")
                    return addr
                }
            }
            return null
        }

        private fun getBestIpv6Address(addresses: Array<Inet6Address>): Inet6Address? {
            val linkLocalAddr = getLinkLocalAddress(addresses)

            for (tries in 0 until 2) {
                for (addr in addresses) {
                    if (addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isLoopbackAddress) {
                        LimeLog.info("Ignoring non-global address: ${addr.hostAddress}")
                        continue
                    }

                    val addrBytes = addr.address

                    // 2002::/16
                    if (addrBytes[0] == 0x20.toByte() && addrBytes[1] == 0x02.toByte()) {
                        LimeLog.info("Ignoring 6to4 address: ${addr.hostAddress}")
                        continue
                    }
                    // 2001::/32
                    if (addrBytes[0] == 0x20.toByte() && addrBytes[1] == 0x01.toByte() &&
                        addrBytes[2] == 0x00.toByte() && addrBytes[3] == 0x00.toByte()
                    ) {
                        LimeLog.info("Ignoring Teredo address: ${addr.hostAddress}")
                        continue
                    }
                    // fc00::/7
                    if ((addrBytes[0].toInt() and 0xfe) == 0xfc) {
                        LimeLog.info("Ignoring ULA: ${addr.hostAddress}")
                        continue
                    }

                    // Compare the final 64-bit interface identifier
                    if (linkLocalAddr != null && tries == 0) {
                        var matched = true
                        for (i in 8 until 16) {
                            if (linkLocalAddr.address[i] != addr.address[i]) {
                                matched = false
                                break
                            }
                        }
                        if (!matched) {
                            LimeLog.info("Ignoring non-matching global address: ${addr.hostAddress}")
                            continue
                        }
                    }

                    return addr
                }
            }

            return null
        }
    }
}
