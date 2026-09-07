# Android USB/IP backend

This module exports one Android USB device and carries USB/IP through Moonlight's
reverse TLS tunnel to Sunshine. It builds pinned usbipdcpp and libusb sources into
the app; no root command, second installed server app, Qt, or RUSB component is
required on Android.

The validated target is Android 9+ ARM64. Call `UsbIpBackend.isSupported()` before
showing the feature. Release builds leave the tunnel port and token empty, so the
menu stays hidden until production provisioning is implemented.

## Ownership and transport

`UsbIpBackend` opens only the device selected by the user after Android grants USB
permission. A process-wide owner and a single executor serialize export, release,
detach, and shutdown. Native code duplicates the USB file descriptor and keeps it
alive until all USB/IP callbacks stop.

`UsbReverseTunnel` authenticates Sunshine with the paired leaf certificate, presents
the paired Moonlight client identity, and sends the per-session token and bus ID in
the bounded JSON handshake. It then forwards the raw USB/IP stream with blocking
backpressure. Closing the Activity or stream closes TLS off the UI thread before the
native export is released.

Android loopback is shared by installed apps, so binding the exporter to
`127.0.0.1` alone is insufficient. Before connecting, Moonlight binds its local
socket to an ephemeral source port and authorizes that exact port in native code.
The patched usbipdcpp accept loop consumes the authorization once and rejects every
other connection. The socket reservation prevents another app from claiming the
authorized source port during the handoff.

## Build and test

With Android SDK, NDK 28.2, and JDK 21 installed:

```sh
./gradlew -p usbip-diagnostics assembleDebug \
  :usbip-backend:assembleDebug \
  :usbip-backend:assembleDebugAndroidTest
./gradlew :app:assembleNonRootDebug \
  -PaudioHapticsSdkDir=/path/to/moonlight-audio-haptics
```

The standalone diagnostics app exercises Android permission and native export
lifecycle without starting a tunnel. Instrumented tests cover protocol start/stop,
invalid file descriptors, cleanup, unauthorized loopback rejection, mutual TLS,
certificate pinning, host rejection, handshake bounds, cancellation, and 256 KiB
bidirectional forwarding. Hardware-only tests remain opt-in and skip unless every
required endpoint and bus ID argument is present.

End-to-end validation on 2026-09-07 used an OPPO PKJ110 with a Logitech K380
(`046d:b34d`, Android bus ID `1-6:0`) and Sunshine with usbip-win2 0.9.7.8. Sunshine
attached the device at VHCI hub port 1; Windows enumerated 11 composite/HID nodes
with problem code 0. The device remained attached during the stream. Leaving the
Activity closed the tunnel without `NetworkOnMainThreadException`, removed the VHCI
port, and removed all matching live PnP nodes.

This proves enumeration, control traffic, sustained attachment, and deterministic
teardown through the production Moonlight/Sunshine path. It does not yet establish
support for every USB class or capture independent physical key/mouse input reports.

## Dependency provenance

- FD integration reference: yunsmall/Android-Usbipdcpp at
  `8653028e8197c08456f9495fa71eab3b6e7926a9` (GPL-3.0).
- USB/IP core: yunsmall/usbipdcpp at
  `1355113f030c4c13404030e6e7426bceb6085276` (LGPL-3.0), with a build-time
  connection-filter patch in `patch_usbipdcpp.cmake`.
- libusb 1.0.29 (LGPL-2.1-or-later), Asio 1.30.2 (Boost Software License), and
  spdlog 1.15.3 (MIT, including bundled fmt notices).

Dependencies are source-built from fixed revisions and verified hashes. Release
artifacts must include their notices and corresponding reproducible source/build
instructions.
