# Android USB/IP backend and reverse tunnel prototype

This is an opt-in development module, not a released USB forwarding feature.
It wraps pinned usbipdcpp + libusb sources through JNI and exports at most one
explicitly authorized USB device. It does not request permissions, start at boot,
or attach itself to Moonlight's existing controller service.

Supported validation target: Android 9+ ARM64. The library manifest remains API 22
compatible; other ABIs/versions must check `UsbIpBackend.isSupported()` before use.
No Qt, RUSB, second installed server app, root command, or hidden FD API is used.

## Build independently of the streaming application's native dependencies

From the repository root, with Android SDK/NDK installed:

```sh
./gradlew -p usbip-diagnostics assembleDebug
./gradlew -p usbip-diagnostics :usbip-backend:connectedDebugAndroidTest
```

The diagnostic app has a different application ID and does not replace Moonlight.
Connect an OTG device, select it, and approve the Android USB permission prompt.
The screen reports its bus ID and ephemeral loopback port. For a development-only
desktop import, use `adb forward tcp:<desktop-port> tcp:<reported-port>` and the
USB/IP importer's configurable TCP port. Loopback alone is NOT authentication;
do not expose this endpoint on a network or ship the diagnostic as a normal feature.
ADB must remain available while the phone is in USB host mode, for example through
an already configured wireless debugging connection.

The native tests exercise standard DEVLIST responses (empty until explicitly bound),
100 start/stop cycles, invalid FD cleanup, duplicate-FD leaks and interrupted clients.
These are not proof of physical USB transfer. Actual OTG input/output, detach during
traffic and coexistence with streaming remain separate acceptance gates.

For a desktop emulator only, pass `-PusbipTestAbi=x86_64` to build/run the native tests.
This does not advertise x86_64 as a supported USB exporting platform. CI first uploads
ARM64 artifacts, then builds and runs the same native tests in an API 34 x86_64 emulator.

### Local validation (2026-09-07)

- NDK 28.2 / API 22 ARM64 native library compiled; diagnostic APK and instrumented
  test APK built using JDK 21. ELF LOAD alignment is 0x4000 (16 KB).
- API 34 x86_64 emulator: `OK (4 tests)`, including 100 protocol start/stop cycles,
  30 failed-wrap cycles with FD-count verification, invalid FD recovery, and stopping
  an incomplete client request.
- After an installation retry, Meizu 17 / Android 13 ARM64 ran all four native tests:
  `OK (4 tests)` in 0.243 seconds, including the protocol and FD lifecycle checks above.
- A subsequent diagnostic APK installation succeeded over the phone's existing
  paired wireless ADB connection. Android enumerated USB Serial `1a86:7523` and
  granted the diagnostic app permission through the normal USB permission dialog.
  The backend exported it as `1-9:0`. A desktop protocol probe through ADB forwarding
  imported it twice and completed 100 GET_DESCRIPTOR control requests per connection
  (200/200), checking the returned VID/PID. Release returned the diagnostic to its
  device list and the old forwarded endpoint returned EOF; the forward was removed.
- This verifies physical USB control transfers through the backend. Serial bulk
  payloads, HID input, Windows VHCI attachment, sustained traffic and the production
  Moonlight/Sunshine TLS tunnel are **not yet verified**. ADB forwarding was only the
  development transport for this test.

## Resource ownership

All backend work uses a single executor; callers must not wait on returned Futures
on the UI thread. Always close the backend when its owner ends, including cancelled
requests. The process-wide native owner prevents two backend instances overlapping.
Export identity is the returned object, not a reusable integer FD or bus ID. Native
duplicates the connection FD and retains it until the server and URB callbacks stop.
Only then is the original UsbDeviceConnection closed. A failed native stop must not
be followed by closing resources that callbacks may still use.

## Reverse TLS transport

`UsbReverseTunnel` is a one-shot Android 9+ transport component. Supply an active
export, the existing paired client certificate/private key, the exact saved host
certificate, host address/port and development token to `start`. Observe the returned
future for **byte forwarding ready**, and `completion()` for termination. Neither
future reports Windows attach success. Call `close()` before releasing the export;
cancelling a Future is not a substitute for closing the tunnel. Future callbacks may
run on I/O threads and must dispatch UI changes appropriately.

The transport uses TLS 1.2 mutual authentication, exact leaf-certificate pinning,
a 15-second startup deadline, a 4096-byte handshake bound and fixed 64 KiB buffers
in each direction with blocking socket backpressure. It consumes only through the
handshake newline, preserving coalesced binary data. EOF, cancellation and errors
close both sockets. Tokens and certificates are not persisted by the component.

Instrumented tests cover mutual TLS and 256 KiB bidirectional payload integrity,
wrong-pin rejection before opening the backend, host rejection, handshake overflow
and cancellation during stalled TLS. Test certificates/keys are synthetic fixtures
under `src/androidTest/assets` and must never be trusted by a normal installation.

The optional `sunshineProductionServiceInterop` test accepts instrumentation argument
`sunshineProbePort`, reachable on Android loopback (for example using `adb reverse`).
Run Sunshine's `reverse_tunnel_probe` with the matching test server/client PEMs,
token `interop-test-only`, helper `synthetic`, and mode `ok`. It exchanges binary
import/reply traffic with the real host tunnel service; its synthetic helper does
not validate Windows VHCI or a physical USB device. Without the argument this test
is skipped. Sunshine must accept Android's colon-containing bus IDs (`1-9:0`).

Local validation on Meizu 17 / Android 13: `OK (9 tests)` in 2.372 seconds with the
external probe enabled. Sunshine logged `IMPORT_EXCHANGED`, attachment of `1-9:0`,
then `DETACHED`. The probe used the production host tunnel service and a synthetic
importer over wireless ADB reverse forwarding. The host regression suite also passed
after allowing colon-containing IDs. Full Moonlight session/UI wiring, real VHCI
import through this TLS component, network failure recovery and sustained physical
USB traffic remain pending.

### Physical TLS / Windows VHCI run (2026-09-07)

The opt-in `physicalExportThroughSunshine` instrumented test connects the same TLS
component to a real diagnostic export for 45 seconds. Select only that test and
provide `sunshineProbePort`, `physicalExportPort`, and `physicalBusId`; configure the
Sunshine probe with the real usbip-win2 executable instead of `synthetic`.
The test only asserts that the tunnel stays open. Desktop driver state and payload
checks must be collected separately; passing it alone is not functional device proof.

- CH340 `1a86:7523`, `1-9:0`: real Windows usbip-win2 0.9.7.7 import succeeded at
  hub port 1 through Android TLS and the production Sunshine service. Windows
  enumerated the matching USB device; the tunnel stayed open for 45 seconds.
  PnP reported code 28 (CH340 driver missing). No serial payload test was possible.
  After teardown `usbip port` was empty; Sunshine's detach command reported that
  the device was already disconnected. This is not evidence that explicit detach
  itself succeeded.
- WCH's official CH341SER archive was downloaded from its WCH-IC GitHub release;
  catalog signature verified as Microsoft Windows Hardware Compatibility Publisher.
  Installation was not attempted because the current process is not administrator.
- Keyboard `046d:b34d`, `1-10:0`: first import caused keyboard configuration changes
  to recreate the diagnostic Activity and close the export. The diagnostic manifest
  now handles keyboard/keyboardHidden/navigation changes without recreation.
  Two subsequent Windows attach attempts timed out before forwarding; the export
  remained visible, but successful HID import/input and the complete fix are not
  yet verified. No keyboard support claim follows from this run.
- Test forwards were removed, the diagnostic export released, and no imported
  port remained. The transport still used wireless ADB as its TCP carrier and test
  credentials, not the user's paired Moonlight streaming session.

### After host reboot and driver upgrade (2026-09-07)

usbip-win2 0.9.7.8 no longer reported ABI mismatch after reboot, but reported
multiple VHCI interfaces: the upgrade had left `ROOT\USB\0000` and `0001` enabled.
The older `0000` was disabled (reversible; not uninstalled), keeping `0001` enabled.
`usbip port` then returned successfully. Use the upgraded tool and matching DLLs
from the registered installation directory, not the older portable 0.9.7.7 bundle.

CH340 `1a86:7523` was exported as `1-28:0` and imported through Android TLS into
Windows hub port 1. Windows bound `oem107.inf` / WCH driver 3.9.2024.9 and exposed
`USB-SERIAL CH340 (COM4)` with PnP problem code 0. The first 45-second instrumented
run passed; its disconnect removed the imported port and live COM device. A second
45-second run also passed, reused hub port 1 and restored COM4 with status OK.

The user clarified that the phone has a USB hub with a mouse attached. CH340 is a
separately enumerated serial interface, not an identification of the hub or mouse.
No COM port was opened and no serial payload was sent. These PnP and lifecycle
results must not be described as a serial send/receive test.

The composite HID `046d:b34d`, exported as `1-29:0`, also imported successfully
through Android TLS and the real Sunshine host service using usbip-win2 0.9.7.8.
Windows enumerated its mouse, keyboard and other HID interfaces with status OK;
the first 45-second test passed and disconnect removed the imported port and all
matching live PnP devices. A subsequent import restored all 11 matching PnP nodes
with problem code 0. The diagnostic export survived the HID configuration changes.
That retry ended after 38.7 seconds when the shared probe reached its fixed
120-second lifetime. A fresh probe then completed another full 45-second run;
teardown again left no imported ports or matching live PnP devices. Test ADB
forwards were removed and the diagnostic export released afterward.
Physical mouse movement/click reports have not been independently captured, so
this establishes device initialization, not an end-to-end mouse-input test.

Before app integration: add per-device ownership coordination with UsbDriverService,
normal Android input filtering, the existing permission-prompt coordinator, service
death handling, a private/authenticated backend transport, paired TLS lifecycle wiring,
Sunshine per-session credentials and attach status. No claim of support for cameras,
audio devices or all composite devices is made by this phase.

## Android stream integration in progress (2026-09-07)

The main app now depends on this module and exposes USB devices in the existing
Game menu. `UsbForwardingController` uses the current host address, paired server
certificate and app client identity with the same `forward`/`ready` protocol as
the PC client. No additional Sunshine status operation is required or retained.
Debug builds accept `usbTunnelPort` / `usbTunnelToken` Gradle properties, falling
back to the PC-compatible `MOONLIGHT_USB_TUNNEL_PORT` / `MOONLIGHT_USB_TUNNEL_TOKEN`
environment variables. Release builds leave these fields empty. Never distribute
a debug APK containing a real host's shared token.

USB permissions use Game's existing permission-prompt notifications. Export and
cleanup run off the UI thread, with operation generations invalidating late
callbacks. Release closes TLS before draining the native export; foreground exit,
device removal, stream termination and Activity destruction invoke cleanup.
Android input paths, including pointer capture and external-display routing,
filter the selected USB device's VID/PID while it is reserved for forwarding.
Identical VID/PID devices and custom-driver/HCI devices are temporarily rejected;
per-device custom-driver handoff remains unfinished, not a completed feature.

Validation: `:app:compileNonRootDebugKotlin` passed after restoring the matching
framegen sources. The original Sunshine TLS regression suite passed after removing
the proposed status extension. This is compile validation only: installable APK,
normal paired streaming, physical mouse reports, held-key handoff, disconnect and
reconnect behavior still require main-app device validation before this integration
can be called a closed loop.

## Dependency provenance

- FD integration reference: yunsmall/Android-Usbipdcpp at
  `8653028e8197c08456f9495fa71eab3b6e7926a9` (GPL-3.0).
- USB/IP core: yunsmall/usbipdcpp at
  `1355113f030c4c13404030e6e7426bceb6085276` (LGPL-3.0).
- libusb 1.0.29 (LGPL-2.1-or-later), Asio 1.30.2 (Boost Software License),
  spdlog 1.15.3 (MIT, with its bundled fmt notices).

Dependencies are source-built, not taken from third-party binary APKs. Their source
and license files are retained in the CMake FetchContent cache. Before distributing
the AAR/APK, include dependency notices and corresponding reproducible source/build
instructions; retain upstream licenses when redistributing sources.
