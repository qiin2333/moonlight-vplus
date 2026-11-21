#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
script="$script_dir/verify-release-tag.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-tag-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

git init -q --bare "$tmp/origin.git"
git clone -q "$tmp/origin.git" "$tmp/work"
cd "$tmp/work"
git config user.name Test
git config user.email test@example.com
git checkout -qb master
echo first > tracked.txt
git add tracked.txt
git commit -qm first
first=$(git rev-parse HEAD)
git tag v12.15.32-beta.1
git tag -a v12.15.32 -m 'annotated release'
git push -q origin master --tags

bash "$script" v12.15.32-beta.1 >/dev/null
bash "$script" v12.15.32 >/dev/null

# Create another remote commit without changing the locally checked-out tag.
echo second >> tracked.txt
git commit -qam second
second=$(git rev-parse HEAD)
git push -q origin master
git checkout -q --detach "$first"
original_tag=$(git rev-parse refs/tags/v12.15.32)
git tag -a v12.15.32-moved "$second" -m 'moved annotated release'
git push -q origin refs/tags/v12.15.32-moved
git --git-dir="$tmp/origin.git" update-ref refs/tags/v12.15.32 "$(git rev-parse refs/tags/v12.15.32-moved)"
if bash "$script" v12.15.32 >/dev/null 2>&1; then
  fail 'accepted an annotated remote tag moved to a different commit'
fi
git --git-dir="$tmp/origin.git" update-ref refs/tags/v12.15.32 "$original_tag"
git --git-dir="$tmp/origin.git" update-ref refs/tags/v12.15.32-beta.1 "$second"
if bash "$script" v12.15.32-beta.1 >/dev/null 2>&1; then
  fail 'accepted a remote tag moved to a different commit'
fi
git --git-dir="$tmp/origin.git" update-ref -d refs/tags/v12.15.32-beta.1
if bash "$script" v12.15.32-beta.1 >/dev/null 2>&1; then
  fail 'accepted a deleted remote tag'
fi
if bash "$script" v12.15.32 "$tmp/missing.git" >/dev/null 2>&1; then
  fail 'accepted an unavailable remote'
fi
git checkout -q --detach "$second"
if bash "$script" v12.15.32 >/dev/null 2>&1; then
  fail 'accepted a local tag that differs from HEAD'
fi
echo 'verify-release-tag tests passed'
