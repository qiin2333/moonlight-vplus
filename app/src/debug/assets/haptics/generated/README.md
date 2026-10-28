# Authored IR v3 fixtures

These five synthetic, stereo haptic PCM fixtures were analyzed by Sunshine's
`analyze_authored_ir_v3` tool using moonlight-audio-haptics' authored event engine.
They contain generated rain, 50 onsets/second, sustained 250/300 Hz carriers, and
a continuous carrier mixed with isolated impacts. They contain no recorded game
audio. The rain fixture contains 176 distinct events; repeated wire history is
deliberate.

Each `.irv3bin` concatenates little-endian v3 payloads. Each payload starts with
its 64-byte header, including its own byte length, followed by 0-8 event records
of 32 bytes each. The final payload carries END. There is no outer file header.

Regenerate from 48 kHz interleaved stereo signed 16-bit little-endian PCM:

```text
analyze_authored_ir_v3 input.pcm output.irv3bin
```

`AuthoredV3FixtureTest` checks every distinct host event through the production
client timeline. `AuthoredV3MockIntegrationTest` also exercises the production
coordinator and output worker under packet loss, duplication, reordering,
stalls, source resets, lease expiry, and ordinary-rumble ownership changes.
Pass `-PauthoredIrFixtureDir=/path/to/corpus` to run the larger local corpus;
the five packaged files run by default without external test data.

The debug-only `AuthoredV3TestActivity` replays these files on a device. Its
counters measure framework calls, not physical actuator motion or fidelity.
