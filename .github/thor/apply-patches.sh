#!/usr/bin/env bash
# Applies the Thor fork's patches to the moonlight-common-c submodule, in name order.
# CI runs this in every job right after checkout. Safe to run twice: a patch that is
# already applied is skipped. A patch that no longer applies fails the build.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
common_c="$root/app/src/main/jni/moonlight-core/moonlight-common-c"

shopt -s nullglob
patches=("$root"/patches/common-c/*.patch)
if [ ${#patches[@]} -eq 0 ]; then
  echo "No common-c patches to apply"
  exit 0
fi

for patch in "${patches[@]}"; do
  name=$(basename "$patch")
  if git -C "$common_c" apply --reverse --check "$patch" 2>/dev/null; then
    echo "already applied: $name"
  elif git -C "$common_c" apply "$patch"; then
    echo "applied: $name"
  else
    echo "::error::$name no longer applies to moonlight-common-c; rebase it onto the new submodule commit"
    exit 1
  fi
done
