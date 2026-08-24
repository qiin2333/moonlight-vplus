package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciUsbInterfaceMatcherTest {
    @Test
    fun matchesStandardBluetoothInterfaceByLayout() {
        val layout = HciUsbInterfaceMatcher.match(standardInterface())

        assertNotNull(layout)
        assertEquals(2, layout!!.interfaceId)
        assertEquals(0x81, layout.eventIn.address)
        assertEquals(0x82, layout.aclIn.address)
        assertEquals(0x02, layout.aclOut.address)
    }

    @Test
    fun ignoresVidPidAndAllowsUnrelatedExtraEndpoints() {
        val descriptor = standardInterface().copy(
            endpoints = standardInterface().endpoints + HciUsbEndpointDescriptor(
                address = 0x83,
                direction = HciUsbEndpointDirection.IN,
                transferType = HciUsbTransferType.ISOCHRONOUS,
                maxPacketSize = 49,
                interval = 1
            )
        )

        assertNotNull(HciUsbInterfaceMatcher.match(descriptor))
        assertEquals(HciUsbAdapterProfile.GENERIC, HciUsbAdapterProfileResolver.resolve(0x1234, 0xabcd))
        assertEquals(HciUsbAdapterProfile.CSR, HciUsbAdapterProfileResolver.resolve(0x0a12, 0x0001))
    }

    @Test
    fun resolvesCommandRecipientOrderAndAdapterQuirks() {
        val singleFunction = HciUsbAdapterBehaviorResolver.resolve(
            HciUsbAdapterProfile.GENERIC,
            compositeDevice = false
        )
        assertEquals(listOf(HciUsbCommandRecipient.DEVICE), singleFunction.commandRecipients)
        assertEquals(false, singleFunction.eventInputUsesEndpointPacketSize)
        assertEquals(4, singleFunction.aclInputRequestCount)

        val composite = HciUsbAdapterBehaviorResolver.resolve(
            HciUsbAdapterProfile.GENERIC,
            compositeDevice = true
        )
        assertEquals(
            listOf(HciUsbCommandRecipient.INTERFACE, HciUsbCommandRecipient.DEVICE),
            composite.commandRecipients
        )

        val csr = HciUsbAdapterBehaviorResolver.resolve(
            HciUsbAdapterProfile.CSR,
            compositeDevice = false
        )
        assertTrue(csr.reassembleEventTransfers)
        assertTrue(csr.eventInputUsesEndpointPacketSize)
        assertEquals(
            listOf(HciUsbCommandRecipient.INTERFACE, HciUsbCommandRecipient.DEVICE),
            csr.commandRecipients
        )
    }

    @Test
    fun rejectsWrongInterfaceIdentity() {
        val standard = standardInterface()

        assertNull(HciUsbInterfaceMatcher.match(standard.copy(interfaceClass = 0x03)))
        assertNull(HciUsbInterfaceMatcher.match(standard.copy(interfaceSubclass = 0x00)))
        assertNull(HciUsbInterfaceMatcher.match(standard.copy(interfaceProtocol = 0x00)))
    }

    @Test
    fun requiresEventAndBothAclDirections() {
        val standard = standardInterface()

        assertNull(HciUsbInterfaceMatcher.match(standard.copy(
            endpoints = standard.endpoints.filterNot { it.address == 0x81 }
        )))
        assertNull(HciUsbInterfaceMatcher.match(standard.copy(
            endpoints = standard.endpoints.filterNot { it.address == 0x82 }
        )))
        assertNull(HciUsbInterfaceMatcher.match(standard.copy(
            endpoints = standard.endpoints.filterNot { it.address == 0x02 }
        )))
    }

    @Test
    fun doesNotAcceptEndpointWithWrongTransferType() {
        val standard = standardInterface()
        val wrongEvent = standard.endpoints.map {
            if (it.address == 0x81) it.copy(transferType = HciUsbTransferType.BULK) else it
        }

        assertNull(HciUsbInterfaceMatcher.match(standard.copy(endpoints = wrongEvent)))
    }

    private fun standardInterface(): HciUsbInterfaceDescriptor {
        return HciUsbInterfaceDescriptor(
            interfaceId = 2,
            alternateSetting = 0,
            interfaceClass = HciUsbInterfaceMatcher.WIRELESS_CONTROLLER_CLASS,
            interfaceSubclass = HciUsbInterfaceMatcher.BLUETOOTH_SUBCLASS,
            interfaceProtocol = HciUsbInterfaceMatcher.BLUETOOTH_PROTOCOL,
            endpoints = listOf(
                HciUsbEndpointDescriptor(
                    address = 0x81,
                    direction = HciUsbEndpointDirection.IN,
                    transferType = HciUsbTransferType.INTERRUPT,
                    maxPacketSize = 16,
                    interval = 1
                ),
                HciUsbEndpointDescriptor(
                    address = 0x82,
                    direction = HciUsbEndpointDirection.IN,
                    transferType = HciUsbTransferType.BULK,
                    maxPacketSize = 64,
                    interval = 0
                ),
                HciUsbEndpointDescriptor(
                    address = 0x02,
                    direction = HciUsbEndpointDirection.OUT,
                    transferType = HciUsbTransferType.BULK,
                    maxPacketSize = 64,
                    interval = 0
                )
            )
        )
    }
}
