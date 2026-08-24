package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.AbstractController
import com.limelight.binding.input.driver.ControllerDriverListener
import com.limelight.binding.input.driver.wireless.hci.EphemeralHciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.HciAclLink
import com.limelight.binding.input.driver.wireless.hci.HciAdapterBootstrapListener
import com.limelight.binding.input.driver.wireless.hci.HciAdapterCapabilities
import com.limelight.binding.input.driver.wireless.hci.HciBluetoothAddress
import com.limelight.binding.input.driver.wireless.hci.HciConnectionListener
import com.limelight.binding.input.driver.wireless.hci.HciConnectionRequest
import com.limelight.binding.input.driver.wireless.hci.HciEventPacket
import com.limelight.binding.input.driver.wireless.hci.HciLinkKey
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveredDevice
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveryListener
import com.limelight.binding.input.driver.wireless.hci.HciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.HciSecurityListener
import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DualSenseWirelessBridgeManagerTest {
    @Test
    fun sequencesDiscoverySecurityHidControllerAndNeutralTeardown() {
        val host = FakeHost()
        val controllerListener = RecordingControllerListener()
        val bridgeListener = RecordingBridgeListener()
        lateinit var adapterListener: HciAdapterBootstrapListener
        val manager = DualSenseWirelessBridgeManager(
            controllerListener,
            EphemeralHciLinkKeyStore(),
            hostFactory = { listener -> adapterListener = listener; host },
            listener = bridgeListener,
            hidOpenDelayMs = 0
        )

        assertTrue(manager.start())
        assertEquals(DualSenseWirelessBridgeState.STARTING_ADAPTER, manager.state)
        adapterListener.onAdapterReady(capabilities())
        assertEquals(DualSenseWirelessBridgeState.READY, manager.state)

        assertTrue(manager.startDiscovery())
        val ignored = device(1, "Wireless Keyboard")
        val dualSense = device(2, "DualSense Wireless Controller")
        host.discoveryListener!!.onDeviceFound(ignored)
        host.discoveryListener!!.onDeviceFound(dualSense)
        host.discoveryListener!!.onDiscoveryComplete(listOf(ignored, dualSense))
        assertEquals(listOf(dualSense), manager.discoveredDevices())
        assertEquals(DualSenseWirelessBridgeState.READY, manager.state)

        assertTrue(manager.connect(dualSense.address.value))
        assertEquals(DualSenseWirelessBridgeState.CONNECTING, manager.state)
        host.connectionListener!!.onConnected(
            HciAclLink(dualSense.address, connectionHandle = 0x42, encrypted = false)
        )
        assertEquals(DualSenseWirelessBridgeState.SECURING, manager.state)
        host.securityListener!!.onLinkEncrypted(
            HciAclLink(dualSense.address, connectionHandle = 0x42, encrypted = true)
        )
        assertEquals(DualSenseWirelessBridgeState.OPENING_HID, manager.state)

        val parsed = DualSenseBluetoothInputCodec().decode(
            DualSenseBluetoothInputCodecTest.report(1)
        )
        host.hidpListener!!.onInput(parsed.state!!, parsed)
        assertTrue(bridgeListener.active.await(2, TimeUnit.SECONDS))
        assertEquals(DualSenseWirelessBridgeState.ACTIVE, manager.state)
        assertEquals(1, controllerListener.added.size)

        controllerListener.added.single().rumble(0x5500, 0x3300)
        assertTrue(host.stateOutputsSent.await(1, TimeUnit.SECONDS))
        manager.close(adapterPresent = true)

        assertEquals(DualSenseWirelessBridgeState.DETACHED, manager.state)
        assertTrue(host.closed)
        assertTrue(host.flushed)
        assertEquals(1, controllerListener.removed.size)
        val reports = synchronized(host.outputReports) { host.outputReports.toList() }
        assertTrue(reports.any {
            (it[5].toInt() and 0xff) == 0x33 && (it[6].toInt() and 0xff) == 0x55
        })
        val neutral = reports.last()
        assertEquals(0, neutral[5].toInt() and 0xff)
        assertEquals(0, neutral[6].toInt() and 0xff)
        assertEquals(0x05, neutral[13].toInt() and 0xff)
        assertEquals(0x05, neutral[24].toInt() and 0xff)
    }

    @Test
    fun rejectsNonDualSenseAndFailsClosedWhenHidCannotOpen() {
        val host = FakeHost().apply { acceptHid = false }
        val bridgeListener = RecordingBridgeListener()
        lateinit var adapterListener: HciAdapterBootstrapListener
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            EphemeralHciLinkKeyStore(),
            hostFactory = { listener -> adapterListener = listener; host },
            listener = bridgeListener,
            hidOpenDelayMs = 0
        )

        assertTrue(manager.start())
        adapterListener.onAdapterReady(capabilities())
        assertTrue(manager.startDiscovery())
        val keyboard = device(1, "Wireless Keyboard")
        val dualSense = device(2, "DualSense Wireless Controller")
        host.discoveryListener!!.onDiscoveryComplete(listOf(keyboard, dualSense))

        assertFalse(manager.connect(keyboard.address.value))
        assertTrue(manager.connect(dualSense.address.value))
        host.connectionListener!!.onConnected(
            HciAclLink(dualSense.address, connectionHandle = 0x42, encrypted = true)
        )

        assertEquals(DualSenseWirelessBridgeState.FAILED, manager.state)
        assertTrue(host.closed)
        assertEquals(DualSenseWirelessBridgeFailureStage.HID, bridgeListener.failures.single().stage)
    }

    @Test
    fun defersHidOpenWithoutBlockingTheEncryptedLinkCallback() {
        val host = FakeHost()
        lateinit var adapterListener: HciAdapterBootstrapListener
        var scheduledOpen: (() -> Unit)? = null
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            EphemeralHciLinkKeyStore(),
            hostFactory = { listener -> adapterListener = listener; host },
            listener = RecordingBridgeListener(),
            hidOpenDelayMs = 25,
            hidOpenScheduler = { _, callback ->
                scheduledOpen = callback
                PendingHidOpen { scheduledOpen = null }
            }
        )
        val dualSense = device(2, "DualSense Wireless Controller")

        assertTrue(manager.start())
        adapterListener.onAdapterReady(capabilities())
        assertTrue(manager.startDiscovery())
        host.discoveryListener!!.onDiscoveryComplete(listOf(dualSense))
        assertTrue(manager.connect(dualSense.address.value))
        host.connectionListener!!.onConnected(
            HciAclLink(dualSense.address, connectionHandle = 0x42, encrypted = true)
        )

        assertEquals(DualSenseWirelessBridgeState.CONNECTING, manager.state)
        assertEquals(1L, host.hidOpened.count)
        scheduledOpen!!()
        assertTrue(host.hidOpened.await(1, TimeUnit.SECONDS))
        assertEquals(DualSenseWirelessBridgeState.OPENING_HID, manager.state)
        manager.close(adapterPresent = true)
    }

    @Test
    fun canRestartAfterCloseAndScheduleHidOpenAgain() {
        val hosts = ArrayList<FakeHost>()
        val adapterListeners = ArrayList<HciAdapterBootstrapListener>()
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            EphemeralHciLinkKeyStore(),
            hostFactory = { listener ->
                adapterListeners += listener
                FakeHost().also(hosts::add)
            },
            listener = RecordingBridgeListener(),
            hidOpenDelayMs = 10
        )

        assertTrue(manager.start())
        manager.close(adapterPresent = true)
        assertTrue(manager.start())
        adapterListeners.last().onAdapterReady(capabilities())
        assertTrue(manager.startDiscovery())
        val controller = device(2, "DualSense Wireless Controller")
        hosts.last().discoveryListener!!.onDiscoveryComplete(listOf(controller))
        assertTrue(manager.connect(controller.address.value))
        hosts.last().connectionListener!!.onConnected(
            HciAclLink(controller.address, connectionHandle = 0x42, encrypted = true)
        )

        assertTrue(hosts.last().hidOpened.await(1, TimeUnit.SECONDS))
        manager.close(adapterPresent = true)
    }

    @Test
    fun acceptsControllerInitiatedReconnectOnlyForAStoredLinkKey() {
        val host = FakeHost()
        val keyStore = EphemeralHciLinkKeyStore()
        val pairedAddress = HciBluetoothAddress(0x112233445566)
        keyStore.save(pairedAddress, HciLinkKey(ByteArray(16) { 0x5a }, 0x04))
        lateinit var adapterListener: HciAdapterBootstrapListener
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            keyStore,
            hostFactory = { listener -> adapterListener = listener; host },
            listener = RecordingBridgeListener(),
            hidOpenDelayMs = 0
        )

        assertTrue(manager.start())
        adapterListener.onAdapterReady(capabilities())
        adapterListener.onUnhandledEvent(connectionRequest(HciBluetoothAddress(1)))
        assertEquals(0, host.incomingRequests.size)
        assertEquals(DualSenseWirelessBridgeState.READY, manager.state)

        adapterListener.onUnhandledEvent(connectionRequest(pairedAddress))
        assertEquals(listOf(pairedAddress), host.incomingRequests.map { it.address })
        assertEquals(DualSenseWirelessBridgeState.CONNECTING, manager.state)
    }

    @Test
    fun pairedReconnectCancelsDiscoveryBeforeAcceptingTheConnection() {
        val host = FakeHost()
        val keyStore = EphemeralHciLinkKeyStore()
        val pairedAddress = HciBluetoothAddress(0x112233445566)
        keyStore.save(pairedAddress, HciLinkKey(ByteArray(16) { 0x5a }, 0x04))
        lateinit var adapterListener: HciAdapterBootstrapListener
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            keyStore,
            hostFactory = { listener -> adapterListener = listener; host },
            listener = RecordingBridgeListener(),
            hidOpenDelayMs = 0
        )

        assertTrue(manager.start())
        adapterListener.onAdapterReady(capabilities())
        assertTrue(manager.startDiscovery())
        adapterListener.onUnhandledEvent(connectionRequest(pairedAddress))

        assertEquals(1, host.discoveryCancelCalls)
        assertTrue(host.incomingRequests.isEmpty())
        host.discoveryListener!!.onDiscoveryCancelled()
        assertEquals(listOf(pairedAddress), host.incomingRequests.map { it.address })
        assertEquals(DualSenseWirelessBridgeState.CONNECTING, manager.state)
    }

    @Test
    fun remoteInitiatedLinkOpensPassiveHidPathWithoutTheOutgoingDelay() {
        val host = FakeHost()
        val keyStore = EphemeralHciLinkKeyStore()
        val pairedAddress = HciBluetoothAddress(0x112233445566)
        keyStore.save(pairedAddress, HciLinkKey(ByteArray(16) { 0x5a }, 0x04))
        lateinit var adapterListener: HciAdapterBootstrapListener
        val manager = DualSenseWirelessBridgeManager(
            RecordingControllerListener(),
            keyStore,
            hostFactory = { listener -> adapterListener = listener; host },
            listener = RecordingBridgeListener(),
            hidOpenDelayMs = 10_000L
        )

        assertTrue(manager.start())
        adapterListener.onAdapterReady(capabilities())
        adapterListener.onUnhandledEvent(connectionRequest(pairedAddress))
        host.connectionListener!!.onConnected(
            HciAclLink(
                pairedAddress,
                connectionHandle = 0x42,
                encrypted = true,
                initiatedByRemote = true
            )
        )

        assertTrue(host.hidOpened.await(100, TimeUnit.MILLISECONDS))
        assertEquals(DualSenseWirelessBridgeState.OPENING_HID, manager.state)
        manager.close(adapterPresent = true)
    }

    private class FakeHost : DualSenseWirelessBridgeHost {
        var discoveryListener: HciDiscoveryListener? = null
        var connectionListener: HciConnectionListener? = null
        var securityListener: HciSecurityListener? = null
        var hidpListener: DualSenseHidpListener? = null
        var acceptHid = true
        var closed = false
        var flushed = false
        val outputReports = mutableListOf<ByteArray>()
        // The controller sends a one-shot lightbar setup before the queued rumble state.
        val stateOutputsSent = CountDownLatch(2)
        val hidOpened = CountDownLatch(1)
        val incomingRequests = mutableListOf<HciConnectionRequest>()
        var discoveryCancelCalls = 0

        override fun start(): Boolean = true
        override fun startDiscovery(listener: HciDiscoveryListener): Boolean {
            discoveryListener = listener
            return true
        }
        override fun cancelDiscovery(): Boolean {
            discoveryCancelCalls++
            return true
        }
        override fun connect(
            device: HciDiscoveredDevice,
            listener: HciConnectionListener
        ): Boolean {
            connectionListener = listener
            return true
        }
        override fun acceptIncomingConnection(
            request: HciConnectionRequest,
            listener: HciConnectionListener
        ): Boolean {
            incomingRequests += request
            connectionListener = listener
            return true
        }
        override fun disconnect(): Boolean = true
        override fun secureConnection(
            keyStore: HciLinkKeyStore,
            listener: HciSecurityListener
        ): Boolean {
            securityListener = listener
            return true
        }
        override fun openDualSenseHidp(listener: DualSenseHidpListener): Boolean {
            hidpListener = listener
            hidOpened.countDown()
            return acceptHid
        }
        override fun closeDualSenseHidp(): Boolean = true
        override fun sendDualSenseOutputReport(report: ByteArray): Boolean {
            synchronized(outputReports) { outputReports += report }
            stateOutputsSent.countDown()
            return true
        }
        override fun flushAcl(timeoutMs: Long): Boolean {
            flushed = true
            return true
        }
        override fun close() {
            closed = true
        }
    }

    private class RecordingBridgeListener : DualSenseWirelessBridgeListener {
        val states = mutableListOf<DualSenseWirelessBridgeState>()
        val failures = mutableListOf<DualSenseWirelessBridgeFailure>()
        val active = CountDownLatch(1)
        override fun onStateChanged(state: DualSenseWirelessBridgeState) {
            states += state
            if (state == DualSenseWirelessBridgeState.ACTIVE) active.countDown()
        }
        override fun onFailure(failure: DualSenseWirelessBridgeFailure) {
            failures += failure
        }
    }

    private class RecordingControllerListener : ControllerDriverListener {
        val added = mutableListOf<AbstractController>()
        val removed = mutableListOf<AbstractController>()
        override fun reportControllerState(
            controllerId: Int,
            buttonFlags: Int,
            leftStickX: Float,
            leftStickY: Float,
            rightStickX: Float,
            rightStickY: Float,
            leftTrigger: Float,
            rightTrigger: Float
        ) = Unit
        override fun deviceRemoved(controller: AbstractController) {
            removed += controller
        }
        override fun deviceAdded(controller: AbstractController) {
            added += controller
        }
        override fun reportControllerMotion(
            controllerId: Int,
            motionType: Byte,
            x: Float,
            y: Float,
            z: Float
        ) = Unit
        override fun onDualSenseNativeHapticsSinkAvailable(
            controllerId: Int,
            sink: DualSenseNativeHapticsSink
        ) = Unit
    }

    companion object {
        private fun capabilities() = HciAdapterCapabilities(
            aclDataPacketLength = 1021,
            aclPacketCredits = 8,
            address = HciBluetoothAddress(0x010203040506),
            localVersion = null
        )

        private fun device(address: Long, name: String) = HciDiscoveredDevice(
            address = HciBluetoothAddress(address),
            pageScanRepetitionMode = 1,
            classOfDevice = 0x2508,
            clockOffset = 0,
            name = name
        )

        private fun connectionRequest(address: HciBluetoothAddress) = HciEventPacket(
            0x04,
            address.toLittleEndianByteArray() + byteArrayOf(
                0x08, 0x25, 0x00,
                0x01
            )
        )
    }
}
