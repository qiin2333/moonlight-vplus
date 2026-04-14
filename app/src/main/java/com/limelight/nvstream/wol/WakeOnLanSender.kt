package com.limelight.nvstream.wol

import com.limelight.LimeLog
import com.limelight.nvstream.http.ComputerDetails

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Scanner

object WakeOnLanSender {
    // These ports will always be tried as-is.
    private val STATIC_PORTS_TO_TRY = intArrayOf(
        9,     // Standard WOL port (privileged port)
        47009, // Port opened by Moonlight Internet Hosting Tool for WoL (non-privileged port)
    )

    // These ports will be offset by the base port number (47989) to support alternate ports.
    private val DYNAMIC_PORTS_TO_TRY = intArrayOf(
        47998, 47999, 48000, 48002, 48010, // Ports opened by GFE
    )

    @Throws(IOException::class)
    private fun sendPacketsForAddress(address: InetAddress, httpPort: Int, sock: DatagramSocket, payload: ByteArray) {
        var lastException: IOException? = null
        var sentWolPacket = false

        // Try the static ports
        for (port in STATIC_PORTS_TO_TRY) {
            try {
                val dp = DatagramPacket(payload, payload.size)
                dp.address = address
                dp.port = port
                sock.send(dp)
                sentWolPacket = true
            } catch (e: IOException) {
                e.printStackTrace()
                lastException = e
            }
        }

        // Try the dynamic ports
        for (port in DYNAMIC_PORTS_TO_TRY) {
            try {
                val dp = DatagramPacket(payload, payload.size)
                dp.address = address
                dp.port = (port - 47989) + httpPort
                sock.send(dp)
                sentWolPacket = true
            } catch (e: IOException) {
                e.printStackTrace()
                lastException = e
            }
        }

        if (!sentWolPacket) {
            throw lastException!!
        }
    }

    @Throws(IOException::class)
    fun sendWolPacket(computer: ComputerDetails) {
        val payload = createWolPayload(computer)
        var lastException: IOException? = null
        var sentWolPacket = false

        DatagramSocket(0).use { sock ->
            // Try all resolved remote and local addresses and broadcast addresses.
            for (address in arrayOf(
                computer.localAddress, computer.remoteAddress,
                computer.manualAddress, computer.ipv6Address,
            )) {
                if (address == null) continue

                try {
                    sendPacketsForAddress(InetAddress.getByName("255.255.255.255"), address.port, sock, payload)
                    sentWolPacket = true
                } catch (e: IOException) {
                    e.printStackTrace()
                    lastException = e
                }

                try {
                    for (resolvedAddress in InetAddress.getAllByName(address.address)) {
                        try {
                            sendPacketsForAddress(resolvedAddress, address.port, sock, payload)
                            sentWolPacket = true
                        } catch (e: IOException) {
                            e.printStackTrace()
                            lastException = e
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    lastException = e
                }
            }
        }

        if (!sentWolPacket && lastException != null) {
            throw lastException!!
        }
    }

    private fun macStringToBytes(macAddress: String): ByteArray {
        val macBytes = ByteArray(6)
        Scanner(macAddress).useDelimiter(":").use { scan ->
            for (i in macBytes.indices) {
                if (!scan.hasNext()) break
                try {
                    macBytes[i] = scan.next().toInt(16).toByte()
                } catch (e: NumberFormatException) {
                    LimeLog.warning("Malformed MAC address: $macAddress (index: $i)")
                    break
                }
            }
        }
        return macBytes
    }

    private fun createWolPayload(computer: ComputerDetails): ByteArray {
        val payload = ByteArray(102)
        val macAddress = macStringToBytes(computer.macAddress!!)
        var i = 0

        // 6 bytes of FF
        while (i < 6) {
            payload[i] = 0xFF.toByte()
            i++
        }

        // 16 repetitions of the MAC address
        for (j in 0 until 16) {
            System.arraycopy(macAddress, 0, payload, i, macAddress.size)
            i += macAddress.size
        }

        return payload
    }
}
