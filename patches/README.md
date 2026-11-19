# Patches

App code lives in source. Patches are only for code this repo doesn't own.

## `common-c/`: applied to the `moonlight-common-c` submodule

`.github/thor/apply-patches.sh` applies these in name order, in every CI job. If one stops applying after a submodule bump, the build fails and the patch has to be rebased.

| Patch | What it does |
| --- | --- |
| `0001-enet-gyro-throttle.patch` | Stops ENet's congestion throttle from discarding unreliable gyro/accel packets (the stick-moving gyro freeze). |

## `archive/`: not applied, kept for reference

| Patch | Status |
| --- | --- |
| `ds4-bottom-trackpad.patch` | Touchpad v8. Now in source (`SecondScreenTrackpad.kt`, `GyroCalibration.kt`). |
| `gyro-raw-sensor.patch` | Uncalibrated gyro. Folded into touchpad v8, now in source. |
| `gyro-rate-thread.patch` | 200 Hz sampling + dedicated sensor thread. Untested on the Thor; never shipped. |
| `gyro-priority-app.patch` | Sensor thread at URGENT_AUDIO priority. Needs `gyro-rate-thread.patch` first. Untested; never shipped. |
| `gyro-priority-core.patch` | common-c input threads at nice −19. Untested; never shipped. |
