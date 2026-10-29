# Authored IR v2 enhancement fixtures

These five synthetic, stereo haptic PCM fixtures were analyzed by Sunshine's
`analyze_authored_ir_extended` tool using moonlight-audio-haptics' authored event engine.
They contain generated rain, 50 onsets/second, sustained 250/300 Hz carriers, and
a continuous carrier mixed with isolated impacts. They contain no recorded game
audio. The rain fixture uses the SDK's transient/body separation fix: 144 distinct
events and no continuous layer. Repeated wire history is deliberate.

The [common-c IR contract](../../../../main/jni/moonlight-core/moonlight-common-c/docs/DS5-HAPTICS-IR.md)
owns field definitions and validation. The client follows the v2 callback API;
build it together with the matching common-c headers and library. Wire fallback
to old hosts does not promise drop-in shared-library binary compatibility.

Each `.irextbin` concatenates enhanced IR payloads. Each starts with an original
72-byte v2 base (version 2, base length 72), then a 32-byte enhancement header
and 0-8 events of 20 bytes each. The enhancement length at offset 74 covers the
header and events; the whole record is `72 + enhancementLength` bytes. The final
record carries base END. There is no outer file header. The v2 base is decoded
into the same data object used by the legacy path; only new continuous/event
information belongs to the enhancement.

Regenerate from 48 kHz interleaved stereo signed 16-bit little-endian PCM:

```text
analyze_authored_ir_extended input.pcm output.irextbin
```

`AuthoredIrMockIntegrationTest` exercises these host-generated files through the
production coordinator, timeline, policies, mixer and phone dispatcher. Cases
cover clean rain, continuous/mixed output, packet loss/duplication/reordering,
stalls, missing END, capability fallback and ordinary-rumble restoration.
Source-clock boundaries and retired epochs are checked in `AuthoredIrTimelineTest`.
The five packaged files run without external test data.

The debug-only `AuthoredIrTestActivity` replays these files on a device. Pass
`--ez native true` to exercise the optional SoundPool backend; it waits for the
native bank to load before starting the source clock. Counters measure accepted
events and renderer/framework invocations, not completed physical actuations.
