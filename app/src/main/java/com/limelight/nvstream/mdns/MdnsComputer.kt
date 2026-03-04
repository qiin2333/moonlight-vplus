package com.limelight.nvstream.mdns

import java.net.Inet6Address
import java.net.InetAddress

class MdnsComputer(
    private val name: String,
    private val localAddr: InetAddress?,
    private val v6Addr: Inet6Address?,
    private val port: Int
) {

    fun getName(): String = name

    fun getLocalAddress(): InetAddress? = localAddr

    fun getIpv6Address(): Inet6Address? = v6Addr

    fun getPort(): Int = port

    override fun hashCode(): Int = name.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is MdnsComputer) {
            if (other.name != name || other.port != port) {
                return false
            }
            if ((other.localAddr != null && localAddr == null) ||
                (other.localAddr == null && localAddr != null) ||
                (other.localAddr != null && other.localAddr != localAddr)
            ) {
                return false
            }
            if ((other.v6Addr != null && v6Addr == null) ||
                (other.v6Addr == null && v6Addr != null) ||
                (other.v6Addr != null && other.v6Addr != v6Addr)
            ) {
                return false
            }
            return true
        }
        return false
    }

    override fun toString(): String = "[$name - $localAddr - $v6Addr]"
}
