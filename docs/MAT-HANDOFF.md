# Experimental MAT Android handoff — 2026-10-22

Companion repositories: AlkaidLab/foundation-sunshine branch
`codex/atmos-mat-handoff`, qiin2333/moonlight-common-c branch
`codex/atmos-mat-transport` (PR base **mic**). Use the common-c gitlink committed
here; do not run `git submodule update --remote`.

2026-10-22 upstream refresh: the shared dependency now includes mic
`2aa14ffeb877f9093993d9c9cf3c3440e2d2220d`, pinned here as
`cdd4aaecd7919878a0472e6ca18a65461a85984a`. The host PR merges master
`c211d711` and uses its PyroWave/Granite gitlinks. The new RTSP parser retains
upstream case-folded coalescing/node ownership while preserving MAT duplicate
ACK rejection. Fresh no-device test results and transfer evidence are recorded
separately in the companion host's `MAT-UPSTREAM-RESULT.txt` and handoff.

## Implemented

Codec 4 is still dump-only: an app-private unique file is published only after
native count/SHA/EOS validation. Codec 5 is explicitly negotiated finite replay:
common-c -> dedicated 61,440-byte JNI bridge -> `MatPlaybackSession` -> bounded
`MatCarrierPlayer` -> independent `MatAudioTrackSink`. No AC3 16 KB callback,
Opus decoder, PCM processing, haptics, extra byte swap or second MAT wrapper.

Opt-in: enable audio passthrough, select **MAT IEC61937 — finite replay / HDMI
(experimental)** and **disable the MAT dump checkbox**. Dump retains precedence.
MAT is never selected automatically; unsupported tuples/routes fail explicitly.

The sink requires API 24+, IEC61937, 192 kHz, 8 channels, MODE_STREAM and a
suitable HDMI route. Short writes, queue pressure, EOS drain, cancellation and
playback-head wrap/watchdog are handled independently from ordinary audio.
An API capability result is not proof that a TV internal app can reach its eARC
MAT sink. DD+ Atmos support alone does not establish MAT compatibility.

## Tested without hardware

Actual production JNI + Java player/session with fake final sink: all 178 cycles
of the 3.56 s fixture, SHA256
`79cd69e33adcd1bcffe95673564b6fc727a7aaed7370745d43ec269925a492f6`.
Three full real-Sunshine RTSP/RTP modes, existing dump path, 15 bad negotiation
cases, JNI/player/lifecycle regression; arm64 NDK MAT module compilation/link;
full app Kotlin and Java compilation. **No full Moonlight APK/native application
link, installation, AudioTrack/HAL/eARC playback or actual Atmos recognition yet.**

The standalone `com.limelight.matdiagnostic` A/B APK is not a network client.
Its source-only debug replay activity is included, but recording/APK/signing keys
are deliberately excluded from Git. The fixture must be transferred privately.

## Continue on another computer

Read `docs/ATMOS-MAT-HANDOFF.md` in the companion Sunshine PR first. Its
`spatial-object-spike/mat-android-session/verify.ps1` runs cross-repo acceptance
when this repo is cloned at `spatial-object-spike/moonlight-vplus` under Sunshine.
Tool paths/build caches must be adapted; this is not a clean-machine installer.
For app compilation, set local JAVA_HOME and ANDROID_HOME, then run:

```powershell
./gradlew.bat --no-daemon -PaudioHapticsSdkDir=<Sunshine>/third-party/moonlight-audio-haptics :app:compileNonRootDebugKotlin :app:compileNonRootDebugJavaWithJavac
```

Use `--offline` only after dependencies are cached. Next: full APK/native link,
real target TV/box -> HDMI/eARC -> MAT-capable soundbar acceptance, then live
capture contract, loss/route recovery and measured latency. Phone-local MAT
decoding and endpoint-independent live host integration are not established.
