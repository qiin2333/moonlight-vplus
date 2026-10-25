package com.limelight.nvstream.http

import org.junit.Assert.*
import org.junit.Test

class ComputerDetailsStunAddressTest {
    @Test fun discoveredAddressCanBeRefreshed() {
        val details = ComputerDetails()
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.1", 47989))
        assertTrue(details.remoteAddressFromStun)
        assertTrue(details.canRefreshRemoteAddressWithStun)
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.2", 50000))
        assertEquals(ComputerDetails.AddressTuple("203.0.113.2", 50000), details.remoteAddress)
    }

    @Test fun unmarkedAddressIsNeverOverwrittenByStun() {
        val existing = ComputerDetails.AddressTuple("203.0.113.1", 50000)
        val details = ComputerDetails().apply { remoteAddress = existing }
        assertFalse(details.canRefreshRemoteAddressWithStun)
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.2", 47989))
        assertSame(existing, details.remoteAddress)
        assertFalse(details.remoteAddressFromStun)
    }

    @Test fun copyPreservesDiscoveredOrigin() {
        val details = ComputerDetails()
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.1", 47989))
        val copy = ComputerDetails(details)
        assertEquals(details.remoteAddress, copy.remoteAddress)
        assertTrue(copy.remoteAddressFromStun)
        assertTrue(copy.canRefreshRemoteAddressWithStun)
    }

    @Test fun hostResponseReplacesDiscoveredOriginEvenAtSameAddress() {
        val details = ComputerDetails()
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.1", 47989))
        details.update(ComputerDetails().apply {
            remoteAddress = ComputerDetails.AddressTuple("203.0.113.1", 47989)
        })
        assertFalse(details.remoteAddressFromStun)
        assertFalse(details.canRefreshRemoteAddressWithStun)
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.2", 47989))
        assertEquals("203.0.113.1", details.remoteAddress?.address)
    }

    @Test fun absentHostAddressKeepsDiscoveredOriginAndUpdatesPort() {
        val details = ComputerDetails()
        details.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.1", 47989))
        details.update(ComputerDetails().apply { externalPort = 50000 })
        assertEquals(ComputerDetails.AddressTuple("203.0.113.1", 50000), details.remoteAddress)
        assertTrue(details.remoteAddressFromStun)
        assertTrue(details.canRefreshRemoteAddressWithStun)
    }

    @Test fun discoveredSnapshotCannotReplaceHostAddress() {
        val existing = ComputerDetails.AddressTuple("203.0.113.1", 47989)
        val details = ComputerDetails().apply { remoteAddress = existing }
        val stale = ComputerDetails().apply {
            updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.2", 47989))
        }
        details.update(stale)
        assertSame(existing, details.remoteAddress)
        assertFalse(details.remoteAddressFromStun)
    }

}
