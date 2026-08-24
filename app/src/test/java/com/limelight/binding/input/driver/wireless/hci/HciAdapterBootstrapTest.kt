package com.limelight.binding.input.driver.wireless.hci

import com.limelight.binding.input.driver.DualSenseInputState
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseBluetoothInputResult
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseHidpListener
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseBluetoothInputCodecTest
import com.limelight.binding.input.driver.wireless.hidp.HidpFailure
import com.limelight.binding.input.driver.wireless.l2cap.L2capPacket
import com.limelight.binding.input.driver.wireless.l2cap.L2capPacketCodec
import com.limelight.binding.input.driver.wireless.l2cap.L2capSignalingCodec
import com.limelight.binding.input.driver.wireless.l2cap.L2capSignalingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HciAdapterBootstrapTest {
    @Test
    fun initializesConfiguresNegotiatedAclLimitAndHandsOffPackets() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val forwarded = CountDownLatch(2)
        var readyCapabilities: HciAdapterCapabilities? = null
        val bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    readyCapabilities = capabilities
                    ready.countDown()
                }

                override fun onUnhandledEvent(packet: HciEventPacket) {
                    forwarded.countDown()
                }

                override fun onAcl(packet: HciAclPacket) {
                    forwarded.countDown()
                }

                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )

        assertTrue(bootstrap.start())
        assertEquals(HciOpcodes.RESET, transport.commands.last().opcode)
        transport.emit(commandComplete(HciOpcodes.RESET, byteArrayOf(0x00)))
        transport.emit(commandComplete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00)))
        transport.emit(commandComplete(
            HciOpcodes.READ_LOCAL_VERSION,
            byteArrayOf(
                0x00,
                0x09,
                0x34, 0x12,
                0x09,
                0x4c, 0x00,
                0x78, 0x56
            )
        ))
        transport.emit(commandComplete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(
                0x00,
                0xfb.toByte(), 0x03,
                0x00,
                0x08, 0x00,
                0x00, 0x00
            )
        ))
        transport.emit(commandComplete(HciOpcodes.WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(0x00)))
        transport.emit(commandComplete(HciOpcodes.WRITE_SCAN_ENABLE, byteArrayOf(0x00)))
        transport.emit(commandComplete(
            HciOpcodes.READ_BD_ADDR,
            byteArrayOf(0x00, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        ))

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertEquals(HciAdapterBootstrapState.READY, bootstrap.state)
        assertEquals(1019, transport.configuredAclLength)
        assertEquals(8, transport.configuredAclCredits)
        assertEquals("01:02:03:04:05:06", readyCapabilities!!.address.toString())

        transport.emit(HciEventPacket(0xff, byteArrayOf(0x01)))
        transport.emitAcl(HciAclPacket(1, 2, 0, byteArrayOf(0x02)))
        assertTrue(forwarded.await(2, TimeUnit.SECONDS))

        bootstrap.close()
        assertEquals(HciAdapterBootstrapState.CLOSED, bootstrap.state)
    }

    @Test
    fun timeoutFailsBootstrapAndClosesTransport() {
        val transport = FakeTransport()
        val failed = CountDownLatch(1)
        var receivedFailure: HciAdapterBootstrapFailure? = null
        val bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) = Unit
                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) {
                    receivedFailure = failure
                    failed.countDown()
                }
            },
            commandTimeoutMs = 20L
        )

        assertTrue(bootstrap.start())
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(HciAdapterBootstrapState.FAILED, bootstrap.state)
        assertEquals(HciAdapterBootstrapErrorCode.INITIALIZATION_FAILED, receivedFailure!!.code)
        assertEquals(
            HciAdapterInitializationErrorCode.TIMEOUT,
            receivedFailure!!.initializationFailure!!.code
        )
        assertTrue(transport.closed)

        bootstrap.close()
    }

    @Test
    fun routesDiscoveryEventsAndKeepsCommandsInsideDiscoverySession() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val discoveryComplete = CountDownLatch(1)
        var discoveryDevices: List<HciDiscoveredDevice>? = null
        val bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    ready.countDown()
                }

                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )

        assertTrue(bootstrap.start())
        initializeAdapter(transport)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(bootstrap.startDiscovery(object : HciDiscoveryListener {
            override fun onDeviceFound(device: HciDiscoveredDevice) = Unit
            override fun onDiscoveryComplete(devices: List<HciDiscoveredDevice>) {
                discoveryDevices = devices
                discoveryComplete.countDown()
            }

            override fun onDiscoveryCancelled() = Unit
            override fun onDiscoveryFailure(failure: HciDiscoveryFailure) = Unit
        }))
        assertEquals(HciOpcodes.INQUIRY, transport.commands.last().opcode)

        transport.emit(commandStatus(HciOpcodes.INQUIRY))
        transport.emit(HciEventPacket(0x01, byteArrayOf(0x00)))
        assertTrue(discoveryComplete.await(2, TimeUnit.SECONDS))
        assertTrue(discoveryDevices!!.isEmpty())

        // The completed operation releases the session for the next host command.
        assertTrue(bootstrap.submitCommand(HciCommandPacket(0x0c01)) {})
        bootstrap.close()
    }

    @Test
    fun ownsConnectionOperationUntilTheAclLinkDisconnects() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val address = HciBluetoothAddress(0x112233445566)
        val bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    ready.countDown()
                }

                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )

        assertTrue(bootstrap.start())
        initializeAdapter(transport)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(bootstrap.connect(
            HciDiscoveredDevice(address, 0x01, 0x002508, 0x1234),
            object : HciConnectionListener {
                override fun onConnected(link: HciAclLink) {
                    connected.countDown()
                }

                override fun onDisconnected(link: HciAclLink, reason: Int) {
                    disconnected.countDown()
                }

                override fun onConnectionCancelled() = Unit
                override fun onConnectionFailure(failure: HciConnectionFailure) = Unit
            }
        ))
        assertEquals(HciOpcodes.CREATE_CONNECTION, transport.commands.last().opcode)
        assertFalse(bootstrap.submitCommand(HciCommandPacket(0x0c01)) {})

        transport.emit(commandStatus(HciOpcodes.CREATE_CONNECTION))
        transport.emit(connectionComplete(address, 0x0042))
        assertTrue(connected.await(2, TimeUnit.SECONDS))
        assertEquals(0x0042, bootstrap.connectedLink()!!.connectionHandle)

        assertTrue(bootstrap.disconnect())
        assertEquals(HciOpcodes.DISCONNECT, transport.commands.last().opcode)
        transport.emit(commandStatus(HciOpcodes.DISCONNECT))
        transport.emit(HciEventPacket(0x05, byteArrayOf(0x00, 0x42, 0x00, 0x16)))
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertNull(bootstrap.connectedLink())
        bootstrap.close()
    }

    @Test
    fun cancelledDiscoveryCanHandCommandOwnershipToIncomingConnection() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val address = HciBluetoothAddress(0x112233445566)
        val request = HciConnectionRequest(address, 0x002508, 0x01)
        var accepted = false
        val connectionListener = object : HciConnectionListener {
            override fun onConnected(link: HciAclLink) = Unit
            override fun onDisconnected(link: HciAclLink, reason: Int) = Unit
            override fun onConnectionCancelled() = Unit
            override fun onConnectionFailure(failure: HciConnectionFailure) = Unit
        }
        lateinit var bootstrap: HciAdapterBootstrap
        bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    ready.countDown()
                }
                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )

        assertTrue(bootstrap.start())
        initializeAdapter(transport)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(bootstrap.startDiscovery(object : HciDiscoveryListener {
            override fun onDeviceFound(device: HciDiscoveredDevice) = Unit
            override fun onDiscoveryComplete(devices: List<HciDiscoveredDevice>) = Unit
            override fun onDiscoveryCancelled() {
                accepted = bootstrap.acceptIncomingConnection(request, connectionListener)
            }
            override fun onDiscoveryFailure(failure: HciDiscoveryFailure) = Unit
        }))
        transport.emit(commandStatus(HciOpcodes.INQUIRY))
        assertTrue(bootstrap.cancelDiscovery())
        assertEquals(HciOpcodes.INQUIRY_CANCEL, transport.commands.last().opcode)

        transport.emit(commandComplete(HciOpcodes.INQUIRY_CANCEL, byteArrayOf(0x00)))
        assertTrue(accepted)
        assertEquals(HciOpcodes.ACCEPT_CONNECTION_REQUEST, transport.commands.last().opcode)
        bootstrap.close()
    }

    @Test
    fun incomingEncryptedLinkArmsPassiveHidBeforeTheFirstRemoteSignal() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val address = HciBluetoothAddress(0x112233445566)
        lateinit var bootstrap: HciAdapterBootstrap
        var hidOpened = false
        bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    ready.countDown()
                }
                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )
        val hidListener = object : DualSenseHidpListener {
            override fun onInput(
                state: DualSenseInputState,
                metadata: DualSenseBluetoothInputResult
            ) = Unit
            override fun onClosed() = Unit
            override fun onFailure(failure: HidpFailure) = Unit
        }

        assertTrue(bootstrap.start())
        initializeAdapter(transport)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(bootstrap.acceptIncomingConnection(
            HciConnectionRequest(address, 0x002508, 0x01),
            object : HciConnectionListener {
                override fun onConnected(link: HciAclLink) {
                    hidOpened = bootstrap.openDualSenseHidp(hidListener)
                }
                override fun onDisconnected(link: HciAclLink, reason: Int) = Unit
                override fun onConnectionCancelled() = Unit
                override fun onConnectionFailure(failure: HciConnectionFailure) = Unit
            }
        ))
        transport.emit(commandStatus(HciOpcodes.ACCEPT_CONNECTION_REQUEST))
        transport.emit(connectionComplete(address, 0x0042, encrypted = true))

        assertTrue(hidOpened)
        assertTrue(transport.aclPackets.isEmpty())
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(0x02, 0x30, le16(0x0011) + le16(0x0070))
        ))
        assertEquals(2, transport.aclPackets.size)
        val response = L2capSignalingCodec.decode(
            l2capPayload(transport.aclPackets.first())
        )!!.single()
        assertEquals(0x03, response.code)
        assertEquals(0x30, response.identifier)
        assertEquals(
            (le16(0x0040) + le16(0x0070) + le16(0) + le16(0)).toList(),
            response.data.toList()
        )
        bootstrap.close()
    }

    @Test
    fun routesSecurityEventsAndMarksTheConnectionEncrypted() {
        val transport = FakeTransport()
        val ready = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val encrypted = CountDownLatch(1)
        var openedHidFromEncryptionCallback = false
        var input: DualSenseInputState? = null
        val address = HciBluetoothAddress(0x112233445566)
        val storedKey = HciLinkKey(ByteArray(16) { 0x5a }, 0x04)
        val bootstrap = HciAdapterBootstrap(
            transport,
            object : HciAdapterBootstrapListener {
                override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
                    ready.countDown()
                }

                override fun onUnhandledEvent(packet: HciEventPacket) = Unit
                override fun onAcl(packet: HciAclPacket) = Unit
                override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) = Unit
            }
        )

        bootstrap.start()
        initializeAdapter(transport)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        bootstrap.connect(
            HciDiscoveredDevice(address, 0x01, 0x002508, 0x1234),
            object : HciConnectionListener {
                override fun onConnected(link: HciAclLink) = connected.countDown()
                override fun onDisconnected(link: HciAclLink, reason: Int) = Unit
                override fun onConnectionCancelled() = Unit
                override fun onConnectionFailure(failure: HciConnectionFailure) = Unit
            }
        )
        transport.emit(commandStatus(HciOpcodes.CREATE_CONNECTION))
        transport.emit(connectionComplete(address, 0x0042))
        assertTrue(connected.await(2, TimeUnit.SECONDS))

        assertTrue(bootstrap.secureConnection(
            object : HciLinkKeyStore {
                override fun load(address: HciBluetoothAddress): HciLinkKey = storedKey
                override fun save(address: HciBluetoothAddress, key: HciLinkKey) = Unit
                override fun remove(address: HciBluetoothAddress) = Unit
            },
            object : HciSecurityListener {
                override fun onLinkEncrypted(link: HciAclLink) {
                    openedHidFromEncryptionCallback = bootstrap.openDualSenseHidp(
                        object : DualSenseHidpListener {
                            override fun onInput(
                                state: DualSenseInputState,
                                metadata: DualSenseBluetoothInputResult
                            ) {
                                input = state
                            }

                            override fun onClosed() = Unit
                            override fun onFailure(failure: HidpFailure) = Unit
                        }
                    )
                    encrypted.countDown()
                }
                override fun onSecurityFailure(failure: HciSecurityFailure) = Unit
            }
        ))
        transport.emit(commandStatus(HciOpcodes.AUTHENTICATION_REQUESTED))
        transport.emit(HciEventPacket(0x17, address.toLittleEndianByteArray()))
        assertEquals(HciOpcodes.LINK_KEY_REQUEST_REPLY, transport.commands.last().opcode)
        transport.emit(commandComplete(
            HciOpcodes.LINK_KEY_REQUEST_REPLY,
            byteArrayOf(0x00) + address.toLittleEndianByteArray()
        ))
        transport.emit(HciEventPacket(0x06, byteArrayOf(0x00, 0x42, 0x00)))
        assertEquals(HciOpcodes.SET_CONNECTION_ENCRYPTION, transport.commands.last().opcode)
        transport.emit(commandStatus(HciOpcodes.SET_CONNECTION_ENCRYPTION))
        transport.emit(HciEventPacket(0x08, byteArrayOf(0x00, 0x42, 0x00, 0x01)))

        assertTrue(encrypted.await(2, TimeUnit.SECONDS))
        assertTrue(bootstrap.connectedLink()!!.encrypted)
        assertTrue(openedHidFromEncryptionCallback)

        assertEquals(1, transport.aclPackets.size)
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(
                0x03,
                1,
                le16(0x0070) + le16(0x0040) + le16(0) + le16(0)
            )
        ))
        assertEquals(2, transport.aclPackets.size)
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(0x04, 0x40, le16(0x0040) + le16(0))
        ))
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(0x05, 2, le16(0x0040) + le16(0) + le16(0))
        ))
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(
                0x03,
                3,
                le16(0x0071) + le16(0x0041) + le16(0) + le16(0)
            )
        ))
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(0x04, 0x41, le16(0x0041) + le16(0))
        ))
        transport.emitAcl(signalingAcl(
            0x0042,
            L2capSignalingCommand(0x05, 4, le16(0x0041) + le16(0) + le16(0))
        ))
        assertEquals(byteArrayOf(0x71).toList(), l2capPayload(transport.aclPackets.last()).toList())

        transport.emitAcl(dataAcl(0x0042, 0x0040, byteArrayOf(0x00)))
        assertEquals(
            byteArrayOf(0x43, 0x05).toList(),
            l2capPayload(transport.aclPackets.last()).toList()
        )
        transport.emitAcl(dataAcl(
            0x0042,
            0x0040,
            byteArrayOf(0xA3.toByte(), 0x05, 0x00)
        ))
        transport.emitAcl(dataAcl(
            0x0042,
            0x0041,
            byteArrayOf(0xA1.toByte()) + DualSenseBluetoothInputCodecTest.report(1)
        ))
        assertEquals(1, input!!.sequence)
        assertEquals(HciOpcodes.WRITE_LINK_POLICY_SETTINGS, transport.commands.last().opcode)
        assertEquals(
            byteArrayOf(0x42, 0x00, 0x00, 0x00).toList(),
            transport.commands.last().parameters.toList()
        )
        bootstrap.close()
    }

    private class FakeTransport : HciTransport {
        override var state = HciTransportState.CLOSED
            private set
        override val profile = HciUsbAdapterProfile.GENERIC
        private var packetListener: HciPacketListener? = null
        val commands = ArrayList<HciCommandPacket>()
        val aclPackets = ArrayList<HciAclPacket>()
        var configuredAclLength: Int? = null
        var configuredAclCredits: Int? = null
        var closed = false

        override fun setListener(listener: HciPacketListener?) {
            packetListener = listener
        }

        override fun open(): Boolean {
            state = HciTransportState.OPEN
            return true
        }

        override fun configureAclOutput(maxPayloadLength: Int, packetCredits: Int): Boolean {
            configuredAclLength = maxPayloadLength
            configuredAclCredits = packetCredits
            return true
        }

        override fun sendCommand(packet: HciCommandPacket): Boolean {
            commands.add(packet)
            return true
        }

        override fun sendAcl(packet: HciAclPacket): Boolean {
            aclPackets.add(packet)
            return true
        }

        override fun close() {
            closed = true
            state = HciTransportState.CLOSED
        }

        fun emit(packet: HciEventPacket) {
            packetListener?.onEvent(packet)
        }

        fun emitAcl(packet: HciAclPacket) {
            packetListener?.onAcl(packet)
        }
    }

    private fun commandComplete(opcode: Int, returnParameters: ByteArray): HciEventPacket {
        return HciEventPacket(
            0x0e,
            byteArrayOf(0x01, opcode.toByte(), (opcode ushr 8).toByte()) + returnParameters
        )
    }

    private fun commandStatus(opcode: Int, status: Int = 0): HciEventPacket {
        return HciEventPacket(
            0x0f,
            byteArrayOf(status.toByte(), 0x01, opcode.toByte(), (opcode ushr 8).toByte())
        )
    }

    private fun connectionComplete(
        address: HciBluetoothAddress,
        handle: Int,
        encrypted: Boolean = false
    ): HciEventPacket {
        return HciEventPacket(
            0x03,
            byteArrayOf(0x00, handle.toByte(), (handle ushr 8).toByte()) +
                address.toLittleEndianByteArray() +
                byteArrayOf(0x01, (if (encrypted) 0x01 else 0x00).toByte())
        )
    }

    private fun signalingAcl(handle: Int, command: L2capSignalingCommand): HciAclPacket {
        return L2capPacketCodec.encode(
            L2capPacket(
                handle,
                L2capPacketCodec.SIGNALING_CID,
                L2capSignalingCodec.encode(command)
            )
        )
    }

    private fun dataAcl(handle: Int, cid: Int, payload: ByteArray): HciAclPacket =
        L2capPacketCodec.encode(L2capPacket(handle, cid, payload))

    private fun l2capPayload(packet: HciAclPacket): ByteArray {
        val length = (packet.payload[0].toInt() and 0xFF) or
            ((packet.payload[1].toInt() and 0xFF) shl 8)
        return packet.payload.copyOfRange(4, 4 + length)
    }

    private fun le16(value: Int): ByteArray {
        return byteArrayOf(value.toByte(), (value ushr 8).toByte())
    }

    private fun initializeAdapter(transport: FakeTransport) {
        transport.emit(commandComplete(HciOpcodes.RESET, byteArrayOf(0x00)))
        transport.emit(commandComplete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00)))
        transport.emit(commandComplete(
            HciOpcodes.READ_LOCAL_VERSION,
            byteArrayOf(
                0x00,
                0x09,
                0x34, 0x12,
                0x09,
                0x4c, 0x00,
                0x78, 0x56
            )
        ))
        transport.emit(commandComplete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(
                0x00,
                0xfb.toByte(), 0x03,
                0x00,
                0x08, 0x00,
                0x00, 0x00
            )
        ))
        transport.emit(commandComplete(HciOpcodes.WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(0x00)))
        transport.emit(commandComplete(HciOpcodes.WRITE_SCAN_ENABLE, byteArrayOf(0x00)))
        transport.emit(commandComplete(
            HciOpcodes.READ_BD_ADDR,
            byteArrayOf(0x00, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        ))
    }
}
