package com.limelight.nvstream.http

import android.content.Context

import com.limelight.utils.NetHelper

import java.net.Inet4Address
import java.net.InetAddress
import java.security.cert.X509Certificate

class ComputerDetails {
    enum class State {
        ONLINE, OFFLINE, UNKNOWN
    }

    class AddressTuple(@JvmField var address: String, @JvmField var port: Int) {
        init {
            require(port > 0) { "Invalid port" }

            // If this was an escaped IPv6 address, remove the brackets
            if (address.startsWith("[") && address.endsWith("]")) {
                address = address.substring(1, address.length - 1)
            }
        }

        override fun hashCode(): Int {
            return address.hashCode() * 31 + port
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AddressTuple) return false
            return port == other.port && address == other.address
        }

        override fun toString(): String {
            return if (address.contains(":")) "[$address]:$port" else "$address:$port"
        }
    }

    // Persistent attributes
    @JvmField var uuid: String? = null
    @JvmField var name: String? = null
    @JvmField var localAddress: AddressTuple? = null
    @JvmField var remoteAddress: AddressTuple? = null
    @JvmField var manualAddress: AddressTuple? = null
    @JvmField var ipv6Address: AddressTuple? = null
    @JvmField var macAddress: String? = null
    @JvmField var serverCert: X509Certificate? = null
    @JvmField var ipv6Disabled = false

    // Transient attributes
    @JvmField var state: State = State.UNKNOWN
    @JvmField var activeAddress: AddressTuple? = null
    @JvmField var availableAddresses: MutableList<AddressTuple> = ArrayList()
    @JvmField var httpsPort = 0
    @JvmField var externalPort = 0
    @JvmField var pairState: PairingManager.PairState? = null
    @JvmField var runningGameId = 0
    @JvmField var rawAppList: String? = null
    @JvmField var nvidiaServer = false
    @JvmField var useVdd = false
    @JvmField var sunshineVersion: String? = null

    constructor()

    constructor(details: ComputerDetails) : this() {
        update(details)
    }

    fun guessExternalPort(): Int {
        if (externalPort != 0) return externalPort
        if (remoteAddress != null) return remoteAddress!!.port
        if (activeAddress != null) return activeAddress!!.port
        if (ipv6Address != null) return ipv6Address!!.port
        if (localAddress != null) return localAddress!!.port
        return NvHTTP.DEFAULT_HTTP_PORT
    }

    fun update(details: ComputerDetails) {
        this.state = details.state
        this.name = details.name
        this.uuid = details.uuid

        if (details.activeAddress != null) {
            this.activeAddress = details.activeAddress
        }
        if (details.localAddress != null && !details.localAddress!!.address.startsWith("127.")) {
            this.localAddress = details.localAddress
        }
        if (details.remoteAddress != null) {
            this.remoteAddress = details.remoteAddress
        } else if (this.remoteAddress != null && details.externalPort != 0) {
            this.remoteAddress!!.port = details.externalPort
        }
        if (details.manualAddress != null) {
            this.manualAddress = details.manualAddress
        }
        if (details.ipv6Address != null && !this.ipv6Disabled) {
            this.ipv6Address = details.ipv6Address
        }
        if (details.macAddress != null && ZERO_MAC != details.macAddress) {
            this.macAddress = details.macAddress
        }
        if (details.serverCert != null) {
            this.serverCert = details.serverCert
        }

        this.externalPort = details.externalPort
        this.httpsPort = details.httpsPort
        this.pairState = details.pairState
        this.runningGameId = details.runningGameId
        this.nvidiaServer = details.nvidiaServer
        this.useVdd = details.useVdd
        this.rawAppList = details.rawAppList
        if (details.sunshineVersion != null) {
            this.sunshineVersion = details.sunshineVersion
        }

        if (details.availableAddresses != null) {
            this.availableAddresses = ArrayList(details.availableAddresses)
        }
    }

    fun addAvailableAddress(address: AddressTuple?) {
        if (address == null) return

        if (ipv6Disabled && isIpv6Address(address)) {
            return
        }

        if (!availableAddresses.contains(address)) {
            availableAddresses.add(address)
        }
    }

    fun getAvailableAddresses(): List<AddressTuple> {
        return availableAddresses
    }

    fun hasMultipleAddresses(): Boolean {
        return availableAddresses.size > 1
    }

    fun getAddressTypeDescription(address: AddressTuple?): String {
        if (address == null) return ""

        if (address == localAddress) return "本地网络"
        if (address == remoteAddress) return "远程网络"
        if (address == manualAddress) return "手动配置"
        if (address == ipv6Address) return "IPv6网络"
        return "其他网络"
    }

    fun getLanIpv4Addresses(): List<AddressTuple> {
        val lanAddresses = ArrayList<AddressTuple>()
        for (address in availableAddresses) {
            if (isLanIpv4Address(address)) {
                lanAddresses.add(address)
            }
        }
        return lanAddresses
    }

    fun hasMultipleLanAddresses(): Boolean {
        return getLanIpv4Addresses().size > 1
    }

    fun selectBestAddress(): AddressTuple? {
        if (availableAddresses.isEmpty()) {
            return selectBestAddressFromFields()
        }

        val lanAddresses = getLanIpv4Addresses()
        if (lanAddresses.isNotEmpty()) {
            return selectFromLanAddresses(lanAddresses)
        }

        if (!ipv6Disabled && ipv6Address != null && availableAddresses.contains(ipv6Address)) {
            return ipv6Address
        }

        if (remoteAddress != null && availableAddresses.contains(remoteAddress)) {
            return remoteAddress
        }

        if (ipv6Disabled) {
            for (address in availableAddresses) {
                if (!isIpv6Address(address)) {
                    return address
                }
            }
        }

        return availableAddresses[0]
    }

    private fun selectBestAddressFromFields(): AddressTuple? {
        if (localAddress != null && isLanIpv4Address(localAddress)) {
            return localAddress
        }
        if (!ipv6Disabled && ipv6Address != null) {
            return ipv6Address
        }
        if (remoteAddress != null) {
            return remoteAddress
        }
        return localAddress
    }

    private fun selectFromLanAddresses(lanAddresses: List<AddressTuple>): AddressTuple {
        if (localAddress != null && lanAddresses.contains(localAddress)) {
            return localAddress!!
        }
        if (manualAddress != null && lanAddresses.contains(manualAddress)) {
            return manualAddress!!
        }
        return lanAddresses[0]
    }

    fun getPairName(context: Context): String {
        val prefs = context.getSharedPreferences("pair_name_map", Context.MODE_PRIVATE)
        return prefs.getString(uuid, "")!!
    }

    fun getSunshineVersionDisplay(): String {
        return if (!sunshineVersion.isNullOrEmpty()) sunshineVersion!! else "Unknown"
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("Name: ").append(name).append("\n")
        str.append("State: ").append(state).append("\n")
        str.append("Active Address: ").append(activeAddress).append("\n")
        str.append("UUID: ").append(uuid).append("\n")
        str.append("Local Address: ").append(localAddress).append("\n")
        str.append("Remote Address: ").append(remoteAddress).append("\n")
        str.append("IPv6 Address: ").append(if (ipv6Disabled) "Disabled" else ipv6Address).append("\n")
        str.append("Manual Address: ").append(manualAddress).append("\n")
        str.append("MAC Address: ").append(macAddress).append("\n")
        str.append("Pair State: ").append(pairState).append("\n")
        str.append("Running Game ID: ").append(runningGameId).append("\n")
        str.append("HTTPS Port: ").append(httpsPort).append("\n")
        str.append("Sunshine Version: ").append(getSunshineVersionDisplay()).append("\n")
        return str.toString()
    }

    companion object {
        private const val ZERO_MAC = "00:00:00:00:00:00"

        @JvmStatic
        fun isLanIpv4Address(address: AddressTuple?): Boolean {
            if (address?.address == null) return false

            return try {
                val inetAddress = InetAddress.getByName(address.address)
                if (inetAddress !is Inet4Address) return false
                NetHelper.isLanAddress(address.address)
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun isIpv6Address(address: AddressTuple?): Boolean {
            return address?.address?.contains(":") == true
        }

        @JvmStatic
        fun isPublicAddress(address: AddressTuple?): Boolean {
            if (address?.address == null) return false
            return !isLanIpv4Address(address) && !isIpv6Address(address)
        }
    }
}
