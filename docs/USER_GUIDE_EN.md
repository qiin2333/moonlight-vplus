# Moonlight V+ feature guide

If this is your first time, follow the [connection guide](GETTING_STARTED_EN.md) to stream while your Android device and computer are on the same network. Then use the [V+ feature guide](VPLUS_FEATURES_EN.md), [backups and migration](BACKUP_AND_MIGRATION_EN.md), or [Q&A](../FAQ_EN.md) for your next step.

## Host and V+ features

We recommend [Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine) on your computer as the companion to Moonlight V+. Ordinary Sunshine can also provide basic streaming. V+ features such as microphone forwarding and computer display control need Foundation Sunshine support. See the [compatibility guide](COMPATIBILITY_EN.md) for conditions.

## Microphone

Enable the microphone in the streaming settings and choose its initial state. Grant the Android microphone permission when prompted. The in-stream microphone button can change its state. Audio cannot be forwarded without a compatible host, permission, and a negotiated microphone channel.

## Remote networks and EasyTier

First, make sure streaming works while your Android device and computer are on the same network. To connect from elsewhere, join both devices to the same EasyTier network and check its status in the app. If it says connected but the computer does not appear, manually add the address EasyTier assigned to the computer. Check the network name and secret; see the [Q&A](../FAQ_EN.md#q-easytier-does-not-connect-when-network-secret-is-empty) for the empty-secret issue.

## Controllers, touch, and audio vibration

Enable the desired on-screen controller, touch, or motion settings in the app. Mapping, sensors, and advanced haptics depend on the connection type, firmware, Android version, and host support. Verify basic button input before testing motion or haptics. Some features require Bluetooth, USB, or sensor permissions. See the [first-generation Joy-Con notes](joycon-support.md) for their current scope.

Audio vibration can target the device, controller, or both. If it does not work, confirm that the output device can vibrate and check the audio vibration settings. Audio passthrough and the device audio path may affect the result.

## Backups and profile sharing

Use Backup and Sync to restore settings on this device, and keep the backup password safe. On a new device, pair your computers again. To share a control layout, export only its Crown profile. See [Backups and migration](BACKUP_AND_MIGRATION_EN.md) for the steps.

## Reporting a problem

Include client and host versions, Android device, resolution, frame rate, codec, HDR, bitrate, and network path. Review screenshots and logs before posting; redact host addresses, pairing data, certificates, and other private information from public issues.
