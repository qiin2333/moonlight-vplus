# Android USB/IP backend — phase 1

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

Before app integration: add per-device ownership coordination with UsbDriverService,
normal Android input filtering, the existing permission-prompt coordinator, service
death handling, a private/authenticated backend transport, paired TLS forwarding,
Sunshine per-session credentials and attach status. No claim of support for cameras,
audio devices or all composite devices is made by this phase.

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
