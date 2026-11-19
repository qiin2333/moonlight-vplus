#!/usr/bin/env bash
# Fails if a fix this fork depends on is missing from the tree. Run after
# apply-patches.sh. It's a text check, a tripwire, not a test of behaviour.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
failed=0

require() {
  local file=$1 pattern=$2 what=$3
  if grep -Eq -- "$pattern" "$root/$file" 2>/dev/null; then
    echo "ok: $what"
  else
    echo "::error file=$file::missing: $what"
    failed=1
  fi
}

require app/src/main/jni/moonlight-core/moonlight-common-c/src/ControlStream.c \
  'ENET_PEER_PACKET_THROTTLE_ACCELERATION, 0\);' \
  "ENet throttle never drops gyro packets (patches/common-c/0001)"
require app/src/main/java/com/limelight/binding/input/ControllerGyroManager.kt \
  'TYPE_GYROSCOPE_UNCALIBRATED' \
  "device gyro reads the raw (uncalibrated) sensor"
require app/src/main/java/com/limelight/binding/input/ControllerGyroManager.kt \
  'GyroCalibration\.apply' \
  "gyro calibration applied at the source"
require app/src/main/java/com/limelight/SecondScreenTrackpad.kt \
  'class SecondScreenTrackpad' \
  "bottom-screen touchpad"
require app/src/main/java/com/limelight/utils/UpdateMetadataClient.kt \
  'repos/Zach200910/moonlight-vplus-aynthor-gyro/releases/latest' \
  "update check reads this fork's releases"

exit $failed
