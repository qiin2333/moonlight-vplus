# Android USB/IP backend

This module exports one Android USB device and carries USB/IP through Moonlight's
reverse TLS tunnel to Sunshine. It builds pinned usbipdcpp and libusb sources into
the app; no root command, second installed server app, Qt, or RUSB component is
required on Android.

The supported target is Android 9+ ARM64. The menu remains discoverable on other
devices and explains why forwarding is unavailable without loading native code.

## Enable forwarding

1. On Windows, install the usbip-win2 transport/driver using Sunshine's USB/IP
   component manager. In Sunshine Web settings → Input, enable experimental USB
   device forwarding, save, and restart Sunshine. Only trust paired clients whose
   USB devices you are willing to attach to Windows.
2. Pair Moonlight with Sunshine and start a stream. Open the stream menu → USB
   forwarding and enable it for this host (off by default, remembered per host).
3. Connect an external device through OTG, explicitly select it, and grant Android
   USB permission. Enabling the switch alone never shares a device.
4. Stop sharing, disable the switch, or leave the stream to release the device.

Connection credentials are obtained over the existing paired, certificate-pinned
HTTPS connection; no build variables or manual tokens are needed. Older hosts,
disabled hosts, and connection failures have visible status and retry controls.
The capability request rejects redirects and has a 5-second total call timeout
and a 4096-byte response limit; a CA-trusted but unpaired certificate is rejected.
The host TCP port defaults to its main port + 7 (normally 47996); setting
`usb_forwarding_port` to 0 selects this automatic mode, while 1024–65535 overrides it.
Clients use the advertised port. Remote networks must also allow/forward that
port. The feature does not configure router forwarding automatically.

## Ownership and transport

`UsbIpBackend` opens only the device selected by the user after Android grants USB
permission. A process-wide owner and a single executor serialize export, release,
detach, and shutdown. Native code duplicates the USB file descriptor and keeps it
alive until all USB/IP callbacks stop.

`UsbReverseTunnel` authenticates Sunshine with the paired leaf certificate, presents
the paired Moonlight client identity, and sends the host-lifetime token and bus ID in
the bounded JSON handshake. It then forwards the raw USB/IP stream with blocking
backpressure. Closing the Activity or stream closes TLS off the UI thread before the
native export is released.

The capability endpoint is HTTPS-only and requires an actual paired certificate,
not a caller-supplied client ID. Tokens are memory-only, marked `no-store`, and
rotate when Sunshine restarts. Reopen the forwarding panel after a host restart
to obtain current credentials. This opt-in is not a per-device trust policy and
does not make arbitrary USB classes safe or supported.

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

## Runtime setup validation (2026-09-09)

Meizu 17 + K380 (`046d:b34d`, bus ID `1-5:0`) streamed to the installed
Sunshine built from `3e142f7d1` plus the runtime-setup changes. The normal USB
panel fetched credentials over paired HTTPS without build-time secrets. This
test caught and fixed a route-construction bug: the legacy HTTP helper encodes
one path segment, so passing the entire API route produced `%2F` and a false 404.

After explicit device selection and Android permission, Windows imported the
device at VHCI port 1 and enumerated 11 matching PnP nodes with status `OK`.
Turning the host-specific switch off removed the port and all matching live
nodes. Re-enabling and explicitly selecting again imported at port 1; exiting
the stream removed the port and nodes again, and Android listed the keyboard
as a local input device. No physical key-report or other USB-class claim is made.
The first permission-grant attempt attached briefly then released; its cause
remains unverified. The subsequent explicit-share/disable and re-share/stream-exit
cycles passed. Qt's new runtime-setup UI flow is a separate pending validation.
The final strict-HTTPS build (no redirects, exact paired certificate, 5-second
call timeout) was retested on the same device: capability retrieval succeeded,
11 matching PnP nodes were `OK`, and explicit Stop removed the port and all nodes.

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
