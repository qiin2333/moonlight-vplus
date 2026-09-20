package com.limelight.binding.input.haptics

enum class HapticOutput { WAVEFORM_STREAM }
enum class HapticAvailability {
    NEEDS_VALIDATION, NEEDS_PERMISSION, NEEDS_ASSOCIATION, BUSY,
    INITIALIZING, READY, FAILED, DISCONNECTED, UNSUPPORTED_PATH
}
enum class HapticEvidence { PLATFORM_API, VENDOR_SDK, EXPERIMENTAL_PROTOCOL, VALIDATED_PROTOCOL }
data class WaveformFormat(val sampleRateHz: Int)
data class ControllerHapticsCapability(
    val backendId: String,
    val output: HapticOutput,
    val availability: HapticAvailability,
    val evidence: HapticEvidence,
    val format: WaveformFormat? = null,
    val reason: String? = null
)

enum class HapticTransport { USB, BLUETOOTH }
data class HapticUsbEndpoint(val address: Int, val type: Int, val packetSize: Int) {
    val isOutput: Boolean get() = address and 0x80 == 0
}
data class HapticUsbInterface(
    val id: Int, val alternate: Int, val deviceClass: Int, val subclass: Int,
    val endpoints: List<HapticUsbEndpoint>
)
data class HapticDeviceIdentity(
    val instance: String, val name: String, val vendorId: Int, val productId: Int,
    val transport: HapticTransport, val interfaces: List<HapticUsbInterface> = emptyList()
)

enum class HapticBackendOwnership { OUTPUT_COMPANION, INPUT_DRIVER }
data class HapticCandidate(
    val capability: ControllerHapticsCapability,
    val ownership: HapticBackendOwnership,
    val minimumApi: Int,
    val interfaceId: Int? = null,
    val endpointAddress: Int? = null,
    val layoutMatches: Boolean = false
)

/** Passive matching only. Implementations must never perform I/O or request permission. */
interface HapticProtocolProfile {
    val id: String
    fun matchesIdentity(device: HapticDeviceIdentity): Boolean
    fun probe(device: HapticDeviceIdentity): HapticCandidate?
}

/** Protocols register here; discovery, routing and UI do not know their brands. */
class HapticBackendRegistry(private val profiles: List<HapticProtocolProfile> = listOf(
    KishiUsbHapticProfile, DualSenseUsbHapticProfile
)) {
    init { require(profiles.map { it.id }.distinct().size == profiles.size) }
    fun discover(device: HapticDeviceIdentity): List<HapticCandidate> = profiles.mapNotNull { it.probe(device) }
}

object KishiUsbHapticProfile : HapticProtocolProfile {
    override val id = "razer-kishi-usb-pcm"
    override fun matchesIdentity(device: HapticDeviceIdentity) = device.transport == HapticTransport.USB &&
        device.vendorId == 0x1532 && device.productId in setOf(0x0719, 0x071a, 0x0721, 0x0724)
    override fun probe(device: HapticDeviceIdentity): HapticCandidate? {
        if (!matchesIdentity(device)) return null
        // Interface number is a protocol constraint, never a position in the descriptor list.
        val iface = device.interfaces.singleOrNull {
            it.id == 3 && it.alternate == 0 && it.deviceClass == 3 && it.endpoints.size == 1 &&
                it.endpoints[0].let { ep -> ep.isOutput && ep.type == 3 && ep.packetSize == 64 }
        }
        return HapticCandidate(capability(HapticAvailability.NEEDS_VALIDATION),
            HapticBackendOwnership.OUTPUT_COMPANION, 26, iface?.id,
            iface?.endpoints?.single()?.address, iface != null)
    }
    fun capability(state: HapticAvailability, reason: String? = null) = ControllerHapticsCapability(
        id, HapticOutput.WAVEFORM_STREAM, state, HapticEvidence.EXPERIMENTAL_PROTOCOL,
        WaveformFormat(4000), reason
    )
}

object DualSenseUsbHapticProfile : HapticProtocolProfile {
    override val id = "dualsense-usb-uac"
    override fun matchesIdentity(device: HapticDeviceIdentity) = device.transport == HapticTransport.USB &&
        device.vendorId == 0x054c && device.productId in setOf(0x0ce6, 0x0df2)
    override fun probe(device: HapticDeviceIdentity): HapticCandidate? {
        if (!matchesIdentity(device)) return null
        val endpoints = device.interfaces.filter { it.deviceClass == 1 && it.subclass == 2 }
            .flatMap { iface -> iface.endpoints.filter { it.isOutput && it.type == 1 }.map { iface to it } }
        val endpoint = endpoints.singleOrNull()
        // The existing input driver owns UAC setup. Descriptor discovery is not proof of ISO support.
        return HapticCandidate(ControllerHapticsCapability(id, HapticOutput.WAVEFORM_STREAM,
            HapticAvailability.NEEDS_VALIDATION, HapticEvidence.EXPERIMENTAL_PROTOCOL,
            WaveformFormat(48000)), HapticBackendOwnership.INPUT_DRIVER, 22,
            endpoint?.first?.id, endpoint?.second?.address, endpoint != null)
    }
}

/** Authorization/validation policy is independent of device matching and transport creation. */
object HapticActivationPolicy {
    fun evaluate(candidate: HapticCandidate, api: Int, allowExperimental: Boolean,
                 hasPermission: Boolean, uniqueDevice: Boolean, reserved: Boolean): HapticAvailability = when {
        reserved -> HapticAvailability.BUSY
        api < candidate.minimumApi || !candidate.layoutMatches -> HapticAvailability.UNSUPPORTED_PATH
        candidate.capability.evidence == HapticEvidence.EXPERIMENTAL_PROTOCOL && !allowExperimental ->
            HapticAvailability.NEEDS_VALIDATION
        !uniqueDevice -> HapticAvailability.NEEDS_ASSOCIATION
        !hasPermission -> HapticAvailability.NEEDS_PERMISSION
        else -> HapticAvailability.INITIALIZING
    }
}

data class HapticRouteSnapshot(
    val id: Int, val device: HapticDeviceIdentity, val capability: ControllerHapticsCapability
)

/** One connection token per physical path/backend. Late callbacks cannot resurrect an old route. */
class HapticRouteCatalog(private val allocateId: () -> Int) {
    private val routes = linkedMapOf<Pair<String, String>, HapticRouteSnapshot>()
    @Synchronized fun discover(device: HapticDeviceIdentity, capability: ControllerHapticsCapability): HapticRouteSnapshot {
        val key = device.instance to capability.backendId
        val existing = routes[key]
        if (existing?.device == device) return existing
        return HapticRouteSnapshot(allocateId(), device, capability).also { routes[key] = it }
    }
    @Synchronized fun update(id: Int, capability: ControllerHapticsCapability): HapticRouteSnapshot? {
        val entry = routes.entries.firstOrNull { it.value.id == id } ?: return null
        if (entry.key.second != capability.backendId) return null
        return entry.value.copy(capability = capability).also { routes[entry.key] = it }
    }
    @Synchronized fun renew(id: Int): HapticRouteSnapshot? {
        val entry = routes.entries.firstOrNull { it.value.id == id } ?: return null
        return entry.value.copy(id = allocateId(), capability = entry.value.capability.copy(
            availability = HapticAvailability.INITIALIZING)).also { routes[entry.key] = it }
    }
    @Synchronized fun remove(instance: String): List<HapticRouteSnapshot> {
        val removed = routes.values.filter { it.device.instance == instance }
        routes.entries.removeAll { it.key.first == instance }
        return removed
    }
    @Synchronized fun snapshots(): List<HapticRouteSnapshot> = routes.values.toList()
    @Synchronized fun clear(): List<HapticRouteSnapshot> = routes.values.toList().also { routes.clear() }
}

/** Read-only UI projection, separate from identity and protocol capability. */
data class HapticRouteView(
    val route: HapticRouteSnapshot, val player: Int?, val canTest: Boolean, val testing: Boolean
)
