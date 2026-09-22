package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class HapticBackendRegistryTest {
    private val output = HapticUsbEndpoint(2, 3, 64)
    private fun kishi(instance: String = "usb/1") = HapticDeviceIdentity(instance, "Controller", 0x1532, 0x724,
        HapticTransport.USB, listOf(HapticUsbInterface(3, 0, 3, 0, listOf(output))))
    private fun dualSense() = HapticDeviceIdentity("usb/2", "Controller", 0x054c, 0x0ce6,
        HapticTransport.USB, listOf(HapticUsbInterface(1, 1, 1, 2, listOf(HapticUsbEndpoint(1, 1, 392)))))
    private val registry = HapticBackendRegistry()
    private fun candidate() = registry.discover(kishi()).single()
    private fun state(candidate: HapticCandidate = candidate(), experimental: Boolean = false,
                      permission: Boolean = true, unique: Boolean = true, reserved: Boolean = false, api: Int = 35) =
        HapticActivationPolicy.evaluate(candidate, api, experimental, permission, unique, reserved)

    @Test fun multipleProtocolsAreDiscoveredWithoutAnEnableFlag() {
        assertEquals(KishiUsbHapticProfile.id, registry.discover(kishi()).single().capability.backendId)
        assertEquals(DualSenseUsbHapticProfile.id, registry.discover(dualSense()).single().capability.backendId)
    }

    @Test fun namesAudioAndOutputEndpointsCannotInventSupport() {
        assertTrue(registry.discover(kishi().copy(vendorId = 0x9999, name = "Razer Kishi DualSense PCM")).isEmpty())
        assertTrue(registry.discover(dualSense().copy(productId = 0xffff)).isEmpty())
        assertTrue(registry.discover(kishi().copy(productId = 0x37)).isEmpty())
    }

    @Test fun connectionModeIsPartOfTheMatch() {
        assertTrue(registry.discover(kishi().copy(transport = HapticTransport.BLUETOOTH)).isEmpty())
        assertTrue(registry.discover(dualSense().copy(transport = HapticTransport.BLUETOOTH)).isEmpty())
    }

    @Test fun interfaceNumberIsNotAnEnumerationIndex() {
        val iface = kishi().interfaces.single()
        val device = kishi().copy(interfaces = listOf(HapticUsbInterface(8, 0, 255, 0, emptyList()), iface))
        assertTrue(registry.discover(device).single().layoutMatches)
        assertFalse(registry.discover(kishi().copy(interfaces = listOf(iface.copy(id = 8)))).single().layoutMatches)
    }

    @Test fun inputAndAmbiguousInterfacesAreRejected() {
        val iface = kishi().interfaces.single()
        val withInput = iface.copy(endpoints = listOf(output, HapticUsbEndpoint(0x81, 3, 64)))
        assertFalse(registry.discover(kishi().copy(interfaces = listOf(withInput))).single().layoutMatches)
        assertFalse(registry.discover(kishi().copy(interfaces = listOf(iface, iface))).single().layoutMatches)
    }

    @Test fun proXlUsesItsDedicatedInterfaceAndRequestsPermissionAfterOptIn() {
        val device = kishi().copy(productId = 0x0727, interfaces = listOf(
            kishi().interfaces.single(),
            HapticUsbInterface(4, 0, 3, 0, listOf(
                HapticUsbEndpoint(0x84, 3, 64), HapticUsbEndpoint(0x04, 3, 64)))
        ))
        val result = registry.discover(device).single()
        assertTrue(result.layoutMatches)
        assertEquals(4, result.interfaceId)
        assertTrue(KishiSensaHapticProfile.allowsKernelDriverDetach(device))
        assertFalse(KishiSensaHapticProfile.allowsKernelDriverDetach(device.copy(vendorId = 0x9999)))
        assertFalse(KishiSensaHapticProfile.allowsKernelDriverDetach(device.copy(transport = HapticTransport.BLUETOOTH)))
        assertEquals(0x04, result.endpointAddress)
        assertEquals(0x04, registry.discover(device.copy(interfaces = listOf(
            device.interfaces.last().copy(endpoints = device.interfaces.last().endpoints.reversed())
        ))).single().endpointAddress)
        assertEquals(HapticAvailability.NEEDS_VALIDATION, state(result, permission = false))
        assertEquals(HapticAvailability.NEEDS_PERMISSION, state(result, experimental = true, permission = false))
        assertEquals(HapticAvailability.INITIALIZING, state(result, experimental = true))
        assertTrue(registry.discover(device.copy(transport = HapticTransport.BLUETOOTH)).isEmpty())
    }

    @Test fun proXlRejectsOtherInterfacesAndMalformedEndpoints() {
        val endpoint = HapticUsbEndpoint(0x04, 3, 64)
        val input = endpoint.copy(address = 0x84)
        val iface = HapticUsbInterface(4, 0, 3, 0, listOf(input, endpoint))
        val invalidLayouts = listOf(
            listOf(iface.copy(id = 3)), listOf(iface.copy(alternate = 1)),
            listOf(iface.copy(deviceClass = 1)), listOf(iface, iface),
            listOf(iface.copy(endpoints = emptyList())),
            listOf(iface.copy(endpoints = listOf(endpoint))),
            listOf(iface.copy(endpoints = listOf(input))),
            listOf(iface.copy(endpoints = listOf(endpoint, endpoint))),
            listOf(iface.copy(endpoints = listOf(input, endpoint, input))),
            listOf(iface.copy(endpoints = listOf(input.copy(address = 0x85), endpoint))),
            listOf(iface.copy(endpoints = listOf(input.copy(type = 2), endpoint))),
            listOf(iface.copy(endpoints = listOf(input.copy(packetSize = 32), endpoint)))
        ) + listOf(endpoint.copy(address = 0x84), endpoint.copy(address = 2),
            endpoint.copy(type = 2), endpoint.copy(packetSize = 32)).map {
            listOf(iface.copy(endpoints = listOf(input, it)))
        }
        for (interfaces in invalidLayouts) {
            assertFalse(KishiSensaHapticProfile.allowsKernelDriverDetach(kishi().copy(productId = 0x0727, interfaces = interfaces)))
            val result = registry.discover(kishi().copy(productId = 0x0727, interfaces = interfaces)).single()
            assertFalse(result.layoutMatches)
            assertEquals(HapticAvailability.UNSUPPORTED_PATH, state(result, experimental = true))
        }
    }

    @Test fun olderKishiProductsRetainInterfaceThree() {
        for (productId in listOf(0x0719, 0x071a, 0x0721, 0x0724)) {
            val device = kishi().copy(productId = productId)
            assertFalse(KishiSensaHapticProfile.allowsKernelDriverDetach(device))
            val result = registry.discover(device).single()
            assertTrue(result.layoutMatches)
            assertEquals(3, result.interfaceId)
            assertEquals(output.address, result.endpointAddress)
            assertFalse(registry.discover(device.copy(interfaces = listOf(
                device.interfaces.single().copy(id = 4)
            ))).single().layoutMatches)
        }
    }

    @Test fun experimentalIdentityNeverBecomesReadyFromDescriptors() {
        assertEquals(HapticAvailability.NEEDS_VALIDATION, state())
        assertEquals(HapticAvailability.INITIALIZING, state(experimental = true))
        assertEquals(HapticEvidence.EXPERIMENTAL_PROTOCOL, candidate().capability.evidence)
    }

    @Test fun validatedProtocolActivatesAutomaticallyWithoutExperimentalOptIn() {
        val validated = candidate().copy(capability = candidate().capability.copy(evidence = HapticEvidence.VALIDATED_PROTOCOL))
        assertEquals(HapticAvailability.INITIALIZING, state(validated))
        assertEquals(HapticAvailability.NEEDS_PERMISSION, state(validated, permission = false))
    }

    @Test fun associationPermissionOccupancyAndPlatformRemainDistinct() {
        assertEquals(HapticAvailability.NEEDS_PERMISSION, state(experimental = true, permission = false))
        assertEquals(HapticAvailability.NEEDS_ASSOCIATION, state(experimental = true, unique = false))
        assertEquals(HapticAvailability.BUSY, state(reserved = true))
        assertEquals(HapticAvailability.UNSUPPORTED_PATH, state(api = 25))
        assertEquals(HapticAvailability.UNSUPPORTED_PATH, state(candidate().copy(layoutMatches = false), experimental = true))
    }

    @Test fun newProfilesDoNotRequireChangingTheRouter() {
        val profile = object : HapticProtocolProfile {
            override val id = "vendor-sdk"
            override fun matchesIdentity(device: HapticDeviceIdentity) = device.vendorId == 42
            override fun probe(device: HapticDeviceIdentity) = if (matchesIdentity(device)) candidate().copy(
                capability = candidate().capability.copy(backendId = id, evidence = HapticEvidence.VENDOR_SDK)) else null
        }
        val result = HapticBackendRegistry(listOf(profile)).discover(kishi().copy(vendorId = 42)).single()
        assertEquals("vendor-sdk", result.capability.backendId)
        assertEquals(HapticAvailability.INITIALIZING, state(result))
    }

    @Test fun sameModelsKeepIndependentConnectionState() {
        var id = 0
        val catalog = HapticRouteCatalog { id++ }
        val first = catalog.discover(kishi("usb/1"), candidate().capability)
        val second = catalog.discover(kishi("usb/2"), candidate().capability)
        catalog.update(first.id, first.capability.copy(availability = HapticAvailability.READY))
        assertEquals(HapticAvailability.NEEDS_VALIDATION, catalog.snapshots().single { it.id == second.id }.capability.availability)
        assertNotEquals(first.id, second.id)
    }

    @Test fun reconnectRejectsOldCompletionsAndPermissionState() {
        var id = 0
        val catalog = HapticRouteCatalog { id++ }
        val old = catalog.discover(kishi(), candidate().capability)
        catalog.update(old.id, old.capability.copy(availability = HapticAvailability.READY))
        catalog.remove(kishi().instance)
        val current = catalog.discover(kishi(), candidate().capability)
        assertNull(catalog.update(old.id, old.capability.copy(availability = HapticAvailability.FAILED)))
        assertEquals(HapticAvailability.NEEDS_VALIDATION, catalog.snapshots().single().capability.availability)
        assertNotEquals(old.id, current.id)
    }

    @Test fun repeatedDiscoveryDoesNotOverwriteRuntimeReadiness() {
        val catalog = HapticRouteCatalog { 1 }
        val first = catalog.discover(kishi(), candidate().capability)
        catalog.update(first.id, first.capability.copy(availability = HapticAvailability.READY))
        assertEquals(HapticAvailability.READY, catalog.discover(kishi(), candidate().capability).capability.availability)
    }

    @Test fun modeChangeAtTheSamePathInvalidatesTheOldToken() {
        var id = 0
        val catalog = HapticRouteCatalog { id++ }
        val first = catalog.discover(kishi(), candidate().capability)
        val changed = catalog.discover(kishi().copy(interfaces = emptyList()), candidate().capability)
        assertNotEquals(first.id, changed.id)
        assertNull(catalog.update(first.id, first.capability.copy(availability = HapticAvailability.READY)))
    }

    @Test fun restartingAChannelRejectsCallbacksFromItsPreviousOwner() {
        var id = 0
        val catalog = HapticRouteCatalog { id++ }
        val first = catalog.discover(kishi(), candidate().capability)
        val next = catalog.renew(first.id)!!
        assertNotEquals(first.id, next.id)
        assertEquals(HapticAvailability.INITIALIZING, next.capability.availability)
        assertNull(catalog.update(first.id, first.capability.copy(availability = HapticAvailability.READY)))
        assertNotNull(catalog.update(next.id, next.capability.copy(availability = HapticAvailability.READY)))
    }
}
