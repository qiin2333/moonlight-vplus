# Foundation Sunshine compatibility matrix

[Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine) is the recommended host for Moonlight V+. Standard Sunshine also supports ordinary streaming. This table focuses on V+ features that need host cooperation.

**To get started:** Install a recent Foundation Sunshine release, follow the [setup guide](GETTING_STARTED_EN.md) to start an ordinary stream, then enable the feature you need. If it fails, check computer settings, Android permissions, and both versions.

“Unverified” means we have not confirmed **which version first supported the feature**. It does not mean current versions lack it. A version number alone also cannot guarantee that a feature works on every device.

| Feature | Host | Earliest-version evidence | Other conditions |
| --- | --- | --- | --- |
| Standard video and input streaming | Foundation Sunshine or standard Sunshine | No V+ specific minimum | Pairing, network access, and device support for the chosen codec |
| Microphone forwarding | Foundation Sunshine | **2025.0720+: stated by the project's earlier README and in-app text**; earliest release-build verification is still pending | Android recording permission and a microphone channel negotiated with the host; see [setup](GETTING_STARTED_EN.md#enable-the-microphone) |
| Host display control | Foundation Sunshine | Unverified | The host must expose the relevant display capability |
| Live bitrate adjustment | Foundation Sunshine | Unverified | The host must support the control interface |
| Super menu commands | Foundation Sunshine | Unverified | The host must have the relevant commands configured |
| App desktop enhancements and host auto-optimization | Foundation Sunshine | Unverified | Availability depends on host configuration and release |

## For maintainers: establishing a minimum version

1. Identify the first release with the feature in [Foundation Sunshine releases](https://github.com/AlkaidLab/foundation-sunshine/releases) or its implementation history.
2. Test that release and an earlier one with the same Moonlight V+ version. Record host OS, settings, and result.
3. Add the host and client versions, release date, and evidence link here; then update the README, in-app text, and store descriptions together.

If a feature fails on a newer host, check its configuration and negotiated capability first. Report both versions, systems, steps, and redacted logs in an [Issue](https://github.com/qiin2333/moonlight-vplus/issues).
