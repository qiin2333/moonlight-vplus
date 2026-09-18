# Automatic controller waveform haptics

## User behavior

Controller discovery runs automatically whenever a USB host is available, including
when Moonlight's ordinary USB input driver is disabled. There is no brand enable
switch and no need to choose a controller model.

Each recognized connection appears in the stream menu audio-haptics card with its
own state: needs validation, needs USB permission, needs a unique player association,
busy, initializing, ready, failed, or unavailable. Unknown devices retain ordinary
vibration; this does not assert that their hardware lacks waveform support.

**Allow experimental haptic protocols** is an optional validation-policy setting,
default off. It permits trying recognized but unverified protocols, across brands.
It does not enable discovery, bypass descriptor checks, infer support from device
names, or promote a protocol to validated. Reconnect the stream after changing it.
A validated profile automatically proceeds to activation, requesting USB permission
when necessary. The current Kishi and DualSense UAC profiles remain experimental;
no new profile has been declared hardware-validated by this implementation.

## Responsibilities

```mermaid
flowchart TD
    A[Connection identity and USB descriptors] --> B[Protocol profile registry]
    B --> C[Candidate capabilities and evidence]
    C --> D[Backend selection and activation policy]
    D --> E[Connection-scoped route catalog]
    E --> F[Unique player association]
    F --> G[Waveform coordinator]
    G --> H[Transport-specific sink]
    E --> I[Per-device UI status]
```

- `HapticBackendRegistry` performs passive matching. Profiles cannot open hardware
  during discovery. Built-in profiles recognize Kishi USB and Sony DualSense USB;
  Bluetooth does not inherit USB capabilities.
- `HapticBackendSelector` prefers platform evidence, vendor SDK evidence, validated
  protocols, then experimental protocols. Equal competing claims are not resolved
  by registration order. Only the selected companion backend may open a device.
- `HapticActivationPolicy` separately checks protocol evidence, API level, descriptor
  layout, unique device association, permission and external ownership. A descriptor
  match can reach `INITIALIZING`, never `READY` by itself.
- `HapticRouteCatalog` keys routes by connection instance and backend and allocates
  fresh tokens on channel restart, detach/reconnect or identity/layout changes. Late callbacks for
  old tokens are discarded. Two identical models have separate state.
- `UsbWaveformBackends` contains Android transport factories. This is where device
  implementations are registered; the USB service and input handler do not switch
  on brands or cast to Kishi classes.
- `UsbWaveformController` owns an output-only companion's USB lifetime. It never
  registers a second gamepad or allocates a player. It participates in existing USB
  forwarding and stop-completion handling. Combined input/UAC devices remain owned
  by their existing input driver; discovery alone never takes that driver over.
- `ControllerHandler` associates a companion with an existing Android input instance
  and player. Unknown or ambiguous associations cannot receive waveform output.
  Losing an association asks the transport owner to close and re-probe with a fresh
  token; no queued samples carry over to another player. Removing an identical USB
  device triggers discovery again for the remaining devices.
  The host's authored-content request is based on discovered capabilities and remains
  independent of local USB permission and output readiness.
- `WaveformHapticsSink` is transport-neutral. Optional `WaveformPlaybackControl` and
  `WaveformChannelTest` interfaces expose ownership and testing without device casts.
  The existing DualSense sink interface remains a compatible subtype.
- The coordinator handles startup and stale connection generations. Ordinary motor
  output yields while a sink's playback control owns those motors, then resumes
  after that sink disables waveform playback. Trigger feedback is separate.
- The menu renders one status/test row per discovered route. Testing selects a route
  ID, not a brand or an arbitrary first device. Tests are cancelled when the card
  controller is disposed.

## Host negotiation and fallback

The current Sunshine host selects controller emulation through `gamepad=ds5` on
both `/launch` and `/resume`. `LI_CCAP_PREFER_DS5` alone is not sufficient for that
host version. The client now exposes Automatic, Follow host, Xbox 360, DS4 and DS5.
Explicit selection wins. Automatic requests DS5 when the screen touchpad is enabled
or a descriptor/API/validation-eligible USB waveform controller is present before
launch. Host selection is session-wide, including other players. Host policy may
reject the override or fall back if its DS5 component is unavailable. A controller
plugged in after launch cannot change that query: reconnect or select DS5 beforehand.
Legacy arrival metadata retains the PS type/DS5 preference for compatible older hosts;
metadata replacement releases the correct player slot, including players beyond zero.

PCM is a separate session-wide SDP capability. It is enabled only when an eligible waveform controller is present at launch,
controller output is enabled, and the explicit host choice is neither Xbox nor DS4.
Enabling experimental protocols alone never changes session PCM negotiation.
Eligibility is a passive descriptor/API/evidence check, not proof of permission or
working output. Connecting the first eligible controller after launch requires
reconnecting to negotiate PCM.
The JNI callback is selected on a connection-local copy, so a later legacy session
cannot inherit it. The existing common-c parser, control packet and JNI frame are
reused. No new wire protocol or per-player PCM capability is invented; the current
host chooses raw PCM versus its legacy reducer using the session feature flags.

Because that choice disables host-side PCM-to-rumble fallback for the entire session,
Android now supplies its own conservative energy fallback for players without a
working waveform sink, including output failures and unplugging. It folds both
actuator lanes to equal motor amplitudes at 0.25 gain; this is deliberately a lossy
approximation, not the host SDK's perceptual reconstruction. The fallback is an
independent AUTHORED mixer source, preserves ordinary HOST rumble, respects the
controller/device routing preference, expires after 50 ms without fresh packets,
and has at most one queued main-thread dispatch plus one latest value per player.
Malformed, duplicate and reordered frames are rejected; sequence wrap and explicit
stream restart are supported. Direct output clears only the authored fallback.

This change is client-only: common-c stays at `31a2a4589ea926988a08ca508bb317fbfbe2a177`;
there are no new feature bits, messages or host requirements. The host's existing
session-wide routing and process-global gamepad preference behavior are unchanged.
Consequently mixed-player PCM fallback is an approximation, and concurrent-session
type isolation cannot be guaranteed by this client change.

## Current transport adapters

Kishi uses VID 0x1532 and candidate PIDs 0x0719, 0x071a, 0x0721, 0x0724. Shared PID
0x0037 and name-only guesses are excluded. Its experimental profile requires interface
ID 3 (not enumeration index), alternate 0, HID class, one 64-byte Interrupt OUT endpoint
and no input endpoints. The backend uses `claimInterface(..., false)`; a busy kernel
interface is not forcibly detached. API 26+ is required for bounded completion waits.

The Kishi encoder preserves stereo channel separation and resampling state across
blocks. It converts S16LE to 4 kHz, using a 63-tap anti-alias filter for downsampling,
conservative gain, clamping and 64-byte packet encoding. It queues at most ten 3 ms
packets, drops locally stale packets, flushes discontinuities/end-of-stream, and checks
USB transfer completion length. The budget measures local software age, not total
latency. Host presentation timestamps are not synchronized to the Kishi device clock.
The Kishi backend also disables its waveform motor mode after 30 ms without a played
packet, emitting silence during short underruns. This transport-local idle budget is
independent of the 50 ms ordinary-rumble fallback expiry; the fallback timeout is not
a minimum hold time for raw waveform devices. Extending idle mode would not restore
missing waveform samples.

Kishi supports a cancellable, low-amplitude test: 400 ms left followed by 400 ms right.
Host PCM is ignored during the test. Shutdown logs sent, dropped and silence packets.

The DualSense profile recognizes Sony USB identities and UAC streaming OUT descriptors.
Its existing input driver owns interface setup. The Java isochronous transport remains
unverified; experimental activation is subject to the same validation policy. This
change does not repair Android isochronous transport support or establish Bluetooth PCM
capability. Merely finding a USB sound card does not establish motor channel mapping.

The Kishi initialization and packet-format reference is the public
[PeaSyo implementation](https://github.com/Geocld/PeaSyo/blob/master/android/app/src/main/java/com/peasyo/input/RazerKishiController.java),
reviewed on 2026-09-18. Initialization success is transport evidence, not proof of XL
compatibility, mechanical waveform fidelity, channel polarity or firmware compatibility.

## Extending support

Add a passive `HapticProtocolProfile` and its transport factory (or integrate an existing
input-driver-owned sink). Provide the actual format, ownership mode, minimum platform
version, descriptor constraints and evidence. Upper-level discovery, policy, player
routing and UI need no new brand condition. Hardware validation is recorded in the
profile; runtime permission, handles and readiness are never persisted as capability.

System/vendor capability providers may use the same candidate model, but no fictional
universal Android PCM query is implemented here. Arbitrary unknown protocols, Nexus
AIDL, rumble-to-PCM synthesis, a manual identical-device association selector and new
Bluetooth transports are outside this implementation.

## Validation

Unit tests exercise both built-in profiles, rejection of name-only/unknown/incorrect-mode
matches, descriptor constraints, evidence ordering, competing backends, validated-profile
automatic activation, permissions, occupation, independent identical-device state,
reconnect/layout-change tokens and late callback rejection. Encoder tests cover channel
isolation, chunk-invariant resampling, reset, packet layout/checksum, malformed data and
anti-alias attenuation. PCM fallback tests cover malformed payloads, independent player
sequences, wrap/restart, terminal zero, expiry, source isolation and explicit host-selection
precedence. JNI is compiled through the Android native build. Existing haptic mixing/lifecycle/forwarding tests remain in use.

Hardware acceptance before changing profile evidence:

1. Record the exact model, mode, firmware and descriptors.
2. Verify left/right test output, polarity, cancellation and stop behavior.
3. Check that buttons and sticks still arrive exactly once during output.
4. Verify authored host PCM separately from the built-in test.
5. Exercise denied permission, a busy interface, unplug during startup/playback and
   stream exit; confirm ordinary fallback and no stuck vibration.
6. Connect identical devices and change modes/player assignment; verify no cross-player
   waveform output and fresh readiness after reconnect.
7. Forward an active USB device; ensure forwarding waits for local handle release.
8. Measure sustained latency, underruns, thermal load and artifacts across supported
   Android builds and firmware. Successful writes alone do not complete validation.
