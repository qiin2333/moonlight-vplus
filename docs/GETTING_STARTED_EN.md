# First connection with Foundation Sunshine

This guide helps you connect your Android device to a computer running [Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine). Below, **host** means that computer. Get a stream working while both devices are on the same network, then try other features. See the [compatibility guide](COMPATIBILITY_EN.md) for requirements.

## 1. Prepare host and client

1. Install the appropriate build from [Foundation Sunshine releases](https://github.com/AlkaidLab/foundation-sunshine/releases), finish its first-time setup, and confirm it is running.
2. Install the Android client from [Moonlight V+ releases](https://github.com/qiin2333/moonlight-vplus/releases/latest). Put both devices on the same LAN; connect the host by Ethernet when possible.
3. Test the ordinary LAN route before adding a VPN or other virtual-network path.

## 2. Add and pair the host

Wait for your computer to appear on the Moonlight V+ home screen and select it. When the app shows a PIN (pairing code), enter that code in the Foundation Sunshine web interface on your computer. If the web interface offers a pairing QR code, you can use the app's QR scan action instead.

If discovery fails, check that both devices are on the same network, the host is running, and the client has not moved to mobile data or an isolated guest network. Then try adding the host by its LAN address. If it can be added but streaming still fails, check the host firewall and network path. See the [Q&A](../FAQ_EN.md) for specific cases.

## 3. Verify the first stream

For the first test, use 1080p resolution, 60 FPS, H.264 or Auto encoding, HDR off, and a 10–20 Mbps bitrate. Once streaming works, raise picture quality one setting at a time. Then try HEVC / AV1, HDR, and other features separately so you can tell which setting caused a problem.

For a black screen or decoder error, restore those settings and reconnect. If the picture stutters, lower the bitrate first. You can also open performance information to check for lost packets or sudden latency spikes. On Android TV devices, test H.264 before other codecs.

## Enable the microphone

Enable it under microphone settings and choose an initial state. Grant Android recording permission when prompted. Use the in-stream microphone button to switch state. The host must support forwarding and negotiate a microphone channel; see the [compatibility matrix](COMPATIBILITY_EN.md) for version evidence. If the button appears but audio is absent, check permission, initial state, host microphone settings, and the input device selected by the host application.

## Remote access

Only after LAN streaming works, add EasyTier, Tailscale, or another remote network. If discovery fails over the virtual network, try adding the host by its virtual IP. For EasyTier, check network name, secret, peers, and virtual addresses on both sides. Redact pairing certificates, secrets, and public host addresses in public issues.
