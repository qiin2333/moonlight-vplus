# Explore Moonlight V+ features

New to streaming? Follow the [Foundation Sunshine setup guide](GETTING_STARTED_EN.md) to pair your computer and start a first stream. Below, **host** means the computer running Foundation Sunshine. **Stream menu** means the controls you open after entering a game or the desktop.

| I want to… | Start here |
| --- | --- |
| Place game controls on the touch screen | Settings → Crown → Manage Crown profiles |
| Use a control layout shared by someone else | Settings → Crown → Crown Market |
| Adjust quality or run a computer action while playing | Stream menu → Adjust Bitrate / Super Commands |
| Show the stream on an external screen | Settings → Send stream to external display |
| Use my Android microphone for voice chat | Stream settings → Microphone |
| Feel vibration from game audio | Settings → Audio → Audio vibration output |
| Reach my computer away from home | In-app EasyTier panel |
| Try frame generation | Settings → Frame generation → Check device support |

## Crown: save and switch touch controls

A Crown profile saves on-screen buttons, quick actions, touch behavior, and vibration settings. Create a layout under Manage Crown profiles, test it in a game, then adjust it. During a stream, open Crown to switch layouts, edit one, or temporarily hide the buttons.

Crown Market has layouts shared by other users. Check which game and device a layout suits before importing it. To share yours, use the Crown export or share action. Publishing to the community requires GitHub and a submission for maintainer review. Never send someone a [full backup](BACKUP_AND_MIGRATION_EN.md), which contains computer pairing information.

## Control Foundation Sunshine during a stream

If your connection becomes unstable, open Adjust Bitrate in the stream menu and try a lower bitrate. Super Commands run computer actions you configured in Foundation Sunshine beforehand. If the list is empty, check the configuration on your computer.

Display control and microphone forwarding also need host support. If an option appears but does not work, check Foundation Sunshine settings and both versions. The [compatibility guide](COMPATIBILITY_EN.md) lists known requirements.

## External screens, microphone, and vibration

- **External screen:** Connect the screen and turn on Send stream to external display. Check ordinary video and audio first, then try HDR or a higher refresh rate. If your computer has multiple displays, you can separately choose which computer display to stream.
- **Microphone:** Grant Android recording permission the first time and enable the microphone channel on the computer. See the [setup guide](GETTING_STARTED_EN.md#enable-the-microphone) for steps.
- **Audio-simulated vibration:** Turn changes in audio into vibration on your device or controller. This is separate from vibration sent by the game itself. Scene mode lets you choose an effect.

## Reach your computer away from home

First, make sure streaming works while both devices are on the same network. Then configure EasyTier. If your computer does not appear after the virtual network connects, manually add the address EasyTier assigned to it. See [remote access](GETTING_STARTED_EN.md#remote-access) for troubleshooting.

## Frame generation: advanced

Frame generation requires developer features to be enabled. Open Check device support to see whether your device meets requirements such as Android 10, a 64-bit ARM processor, and Vulkan 1.1. For first use, import `Lossless.dll` from your own Lossless Scaling installation.

For 120 Hz output, the app advises setting the stream to 60 FPS first. Weak-network frame fill is a separate option for dropped frames. If the image looks wrong, turn off frame generation and check that ordinary streaming works again.
