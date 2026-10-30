# Authored IR acceptance fixtures

These four synthetic stereo PCM scenes were analyzed by Sunshine's
`analyze_authored_ir_extended` tool using moonlight-audio-haptics. No recorded
game audio or raw PCM is packaged.

| Fixture | Regression / handheld purpose |
| --- | --- |
| `rain-haptics` | Irregular graded points: 144 events and no continuous layer. |
| `pulse_density_50` | 100 events in two seconds; density, network faults and backpressure. |
| `sustain_250hz_0.12` | Continuous output, lease expiry and missing END. |
| `mixed_clean_body_0.12_separate_0` | Body/point allocation and single-phone fallback. |

The redundant 300 Hz sustain fixture was removed: client allocation and output
use the same continuous branch. These files are shared by the debug replay page
and `AuthoredIrMockIntegrationTest`, which exercises the production coordinator,
timeline, mixer and phone dispatcher. Transport faults and clocks are simulated;
software counts do not prove physical actuator fidelity.

The rain file includes the local SDK transient/body separation fix. This client
PR does not contain that SDK change. Fixture playback is not online Sunshine
streaming acceptance.

The [common-c IR contract](../main/jni/moonlight-core/moonlight-common-c/docs/DS5-HAPTICS-IR.md)
owns wire layout and validation. Each file concatenates enhanced v2 payloads;
the debug decoder only supports fixture replay. Live traffic uses common-c.
Regenerate from 48 kHz stereo signed 16-bit little-endian PCM:

```text
analyze_authored_ir_extended input.pcm output.irextbin
```

`AuthoredIrTestActivity` accepts `--es asset <name>.irextbin --ez native true`.
The optional native backend is SoundPool or RichTap; both render sustained
backgrounds and graded points. Use `--ez measure true` only for flat-device
latency capture. Sensor data and event timestamps stay in
app-private files. Replay and measurement code are debug-only; sampling reuses
the high-rate sensor permission already present in the main manifest for controller motion.
Temporary probe apps, raw captures and benchmark reports are not committed.
