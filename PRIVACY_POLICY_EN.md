# Moonlight V+ privacy notice

This notice covers the Moonlight V+ Android client. Firebase availability varies by build. A self-built APK without `app/google-services.json` normally cannot connect to Firebase. Host streaming, analytics, and crash diagnostics use separate data paths.

## Streaming and optional device features

- The app connects to a host chosen by the user to carry video, audio, and input. Host names, pairing data, settings, and caches are stored on the device. When you export a configuration or share a file, you choose where to send it.
- If you enable microphone forwarding and grant recording permission, microphone audio is sent to the connected host. The feature is off by default and can be controlled in settings and during a stream. The app does not upload microphone audio as a Firebase analytics event.
- Camera access supports QR pairing. Bluetooth, USB, sensor, notification, and file permissions support their respective optional features. Available permissions vary by Android version and device.

## Analytics

Release builds with working Firebase configuration use Firebase Analytics. The app logs launch, session, and stream start/end events, durations, and parameters such as computer name, app name, decoder, resolution, and average latency. It also sets properties including first-open date and whether a first stream was completed. Computer and app names can be chosen by users, so these data cannot be guaranteed fully anonymous. The Firebase SDK may also collect automatic events and device-related data; see [Firebase's privacy and security information](https://firebase.google.com/support/privacy).

The in-app “Participate in analytics” preference is on by default. Turning it off stops the app's own `AnalyticsManager` from sending those custom events and user properties. The current preference does not call Firebase's global collection-disable API, so it **does not establish that automatic Firebase collection or Crashlytics reporting has stopped**. Debug builds do not run the app's own analytics calls. We will update this notice if the actual control behavior changes.

## Crash diagnostics

Builds with working Firebase configuration include Firebase Crashlytics, which may automatically send crash diagnostics to Google. It is separate from the analytics preference.

The app also stores the most recent uncaught-exception report locally. It includes time, app version, device and Android details, thread name, and stack trace. On the next launch, you can review and choose to share, copy, or ignore it. Generating this local file does not automatically send it to the developer through the share flow. If you share it, the app you choose handles delivery. The app attempts to delete the local report after sharing or ignoring it.

## Storage, control, and contact

Pairing data, configuration, and caches remain on the device for their respective features. Uninstalling normally removes app-private storage but does not itself remove data already sent to Firebase. Firebase retention depends on project settings and service rules; the repository does not establish a fixed period.

For questions or deletion requests about uploaded data, contact the maintainers through [project Issues](https://github.com/qiin2333/moonlight-vplus/issues). Do not post pairing certificates, host addresses, or full crash logs in a public issue. What can be located and deleted depends on available identifiers and Firebase capabilities.

Last updated: September 2026. This notice should be updated when app behavior changes.
